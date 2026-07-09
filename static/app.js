/* MKDownloader — logique front : analyse, choix de qualité, suivi de progression. */

const $ = (id) => document.getElementById(id);

const state = {
  key: localStorage.getItem("mkdl-key") || "",
  videoUrl: null,
  quality: "max",
  pollTimer: null,
};

const QUALITY_FALLBACK = [
  { id: "max", label: "Qualité maximale (jusqu'à 8K)" },
  { id: "mp4", label: "Meilleure qualité MP4" },
  { id: "1080p", label: "Full HD 1080p (MP4)" },
  { id: "720p", label: "HD 720p (MP4)" },
  { id: "audio", label: "Audio seul (MP3)" },
];

/* ---------- Helpers réseau ---------- */

async function api(path, options = {}) {
  const headers = { "Content-Type": "application/json", ...(options.headers || {}) };
  if (state.key) headers["X-App-Key"] = state.key;
  const res = await fetch(path, { ...options, headers });
  if (res.status === 401) {
    const granted = await askPassword();
    if (granted) return api(path, options);
    throw new Error("Mot de passe requis.");
  }
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.detail || `Erreur ${res.status}`);
  return body;
}

function askPassword() {
  return new Promise((resolve) => {
    const dialog = $("password-dialog");
    const form = $("password-form");
    const input = $("password-input");
    input.value = "";
    dialog.showModal();
    form.onsubmit = () => {
      state.key = input.value;
      localStorage.setItem("mkdl-key", state.key);
      resolve(true);
    };
    dialog.oncancel = () => resolve(false);
  });
}

/* ---------- Affichage ---------- */

function showError(message) {
  const box = $("error-box");
  box.textContent = message;
  box.hidden = !message;
}

function setBusy(button, busy, busyLabel) {
  button.disabled = busy;
  const label = button.querySelector(".btn-label");
  if (busy) {
    button.dataset.label = label.textContent;
    label.textContent = busyLabel;
  } else if (button.dataset.label) {
    label.textContent = button.dataset.label;
  }
}

function formatDuration(seconds) {
  if (!seconds) return "";
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.round(seconds % 60);
  return h ? `${h} h ${String(m).padStart(2, "0")} min` : `${m} min ${String(s).padStart(2, "0")}`;
}

function renderQualities(list, bestHeight) {
  const grid = $("quality-grid");
  grid.innerHTML = "";
  for (const q of list) {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "quality-option" + (q.id === state.quality ? " selected" : "");
    let label = q.label;
    if (q.id === "max" && bestHeight) label = `Maximum — ${bestHeight}p disponible`;
    btn.textContent = label;
    btn.onclick = () => {
      state.quality = q.id;
      grid.querySelectorAll(".quality-option").forEach((b) => b.classList.remove("selected"));
      btn.classList.add("selected");
    };
    grid.appendChild(btn);
  }
}

function resetDownloadUi() {
  clearInterval(state.pollTimer);
  $("progress-block").hidden = true;
  $("save-btn").hidden = true;
  $("done-hint").hidden = true;
  $("download-btn").hidden = false;
  $("play-btn").hidden = false;
  const player = $("player");
  player.pause();
  player.removeAttribute("src");
  player.load();
  player.hidden = true;
  $("progress-fill").style.width = "0%";
  $("progress-fill").classList.remove("indeterminate");
}

/* ---------- Analyse ---------- */

$("url-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const url = $("url-input").value.trim();
  if (!url) return;
  showError("");
  resetDownloadUi();
  $("video-card").hidden = true;
  const btn = $("analyze-btn");
  setBusy(btn, true, "Analyse…");
  try {
    const [info, qualities] = await Promise.all([
      api("/api/info", { method: "POST", body: JSON.stringify({ url }) }),
      api("/api/qualities").catch(() => QUALITY_FALLBACK),
    ]);
    state.videoUrl = info.url || url;
    $("thumb").src = info.thumbnail || "";
    $("thumb").hidden = !info.thumbnail;
    $("platform-badge").textContent = info.platform || "Vidéo";
    $("video-title").textContent = info.title || "Vidéo sans titre";
    const parts = [];
    if (info.uploader) parts.push(info.uploader);
    if (info.duration) parts.push(formatDuration(info.duration));
    if (info.best_height) parts.push(`jusqu'à ${info.best_height}p`);
    $("video-sub").textContent = parts.join(" · ");
    renderQualities(Array.isArray(qualities) ? qualities : QUALITY_FALLBACK, info.best_height);
    $("video-card").hidden = false;
    $("video-card").scrollIntoView({ behavior: "smooth", block: "nearest" });
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(btn, false);
  }
});

/* ---------- Téléchargement + suivi ---------- */

$("download-btn").addEventListener("click", async () => {
  showError("");
  resetDownloadUi();
  const btn = $("download-btn");
  setBusy(btn, true, "Lancement…");
  try {
    const job = await api("/api/download", {
      method: "POST",
      body: JSON.stringify({ url: state.videoUrl, quality: state.quality }),
    });
    btn.hidden = true;
    $("play-btn").hidden = true;
    $("progress-block").hidden = false;
    trackJob(job.id, "download");
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(btn, false);
  }
});

/* ---------- Lecture dans le navigateur ---------- */

$("play-btn").addEventListener("click", async () => {
  showError("");
  resetDownloadUi();
  const btn = $("play-btn");
  setBusy(btn, true, "Préparation…");
  try {
    const job = await api("/api/download", {
      method: "POST",
      body: JSON.stringify({ url: state.videoUrl, quality: "stream" }),
    });
    btn.hidden = true;
    $("download-btn").hidden = true;
    $("progress-block").hidden = false;
    trackJob(job.id, "play");
  } catch (err) {
    showError(err.message);
  } finally {
    setBusy(btn, false);
  }
});

function trackJob(jobId, mode) {
  const fill = $("progress-fill");
  const statusEl = $("progress-status");
  const detailEl = $("progress-detail");

  state.pollTimer = setInterval(async () => {
    let job;
    try {
      job = await api(`/api/jobs/${jobId}`);
    } catch (err) {
      clearInterval(state.pollTimer);
      showError(err.message);
      $("download-btn").hidden = false;
      $("progress-block").hidden = true;
      return;
    }

    if (job.status === "downloading") {
      fill.classList.remove("indeterminate");
      fill.style.width = `${job.progress}%`;
      statusEl.textContent = `Téléchargement… ${job.progress}%`;
      const bits = [];
      if (job.speed) bits.push(job.speed);
      if (job.eta) bits.push(`${job.eta}s restantes`);
      detailEl.textContent = bits.join(" · ");
    } else if (job.status === "processing") {
      fill.classList.add("indeterminate");
      statusEl.textContent = "Fusion vidéo + audio (ffmpeg)…";
      detailEl.textContent = "";
    } else if (job.status === "finished") {
      clearInterval(state.pollTimer);
      fill.classList.remove("indeterminate");
      fill.style.width = "100%";
      statusEl.textContent = "Terminé ✔";
      detailEl.textContent = job.filesize || "";
      if (mode === "play") {
        const keyQ = state.key ? `&key=${encodeURIComponent(state.key)}` : "";
        const player = $("player");
        player.src = `/api/jobs/${jobId}/file?inline=1${keyQ}`;
        player.hidden = false;
        $("progress-block").hidden = true;
        player.scrollIntoView({ behavior: "smooth", block: "nearest" });
        player.play().catch(() => {});
      } else {
        const save = $("save-btn");
        const key = state.key ? `?key=${encodeURIComponent(state.key)}` : "";
        save.href = `/api/jobs/${jobId}/file${key}`;
        save.setAttribute("download", job.filename || "video");
        save.hidden = false;
        $("done-hint").hidden = false;
      }
    } else if (job.status === "error") {
      clearInterval(state.pollTimer);
      showError(job.error || "Le téléchargement a échoué.");
      $("progress-block").hidden = true;
      $("download-btn").hidden = false;
    }
  }, 800);
}

/* ---------- Confort ---------- */

$("paste-btn").addEventListener("click", async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (text) {
      $("url-input").value = text.trim();
      $("url-form").requestSubmit();
    }
  } catch {
    $("url-input").focus();
  }
});

/* ---------- PWA : enregistrement du service worker ---------- */

if ("serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  });
}
