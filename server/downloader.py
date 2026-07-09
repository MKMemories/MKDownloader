"""Cœur du téléchargeur : analyse des liens, jobs yt-dlp, progression, nettoyage."""

import ipaddress
import os
import re
import shutil
import socket
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Optional
from urllib.parse import urlparse

import yt_dlp

_PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOWNLOAD_DIR = os.environ.get("DOWNLOAD_DIR", os.path.join(_PROJECT_ROOT, "data"))
COOKIES_FILE = os.environ.get("COOKIES_FILE", os.path.join(_PROJECT_ROOT, "cookies.txt"))
# Durée de conservation des fichiers téléchargés avant purge automatique.
RETENTION_MINUTES = int(os.environ.get("RETENTION_MINUTES", "180"))
MAX_CONCURRENT_JOBS = int(os.environ.get("MAX_CONCURRENT_JOBS", "3"))

# "max" garantit la meilleure qualité absolue (4K/8K, AV1/VP9) quitte à produire
# un MKV ; les presets mp4 privilégient la compatibilité (lecteurs, iPhone, TV).
QUALITY_PRESETS: dict[str, dict[str, Any]] = {
    "max": {
        "label": "Qualité maximale (jusqu'à 8K)",
        "format": "bestvideo*+bestaudio/best",
        "merge": "mkv",
    },
    "mp4": {
        "label": "Meilleure qualité MP4",
        "format": (
            "bestvideo*[vcodec^=avc1]+bestaudio[acodec^=mp4a]"
            "/bestvideo*[ext=mp4]+bestaudio[ext=m4a]"
            "/best[ext=mp4]/bestvideo*+bestaudio/best"
        ),
        "merge": "mp4",
    },
    "1080p": {
        "label": "Full HD 1080p (MP4)",
        "format": "bestvideo*[height<=1080]+bestaudio/best[height<=1080]/best",
        "merge": "mp4",
    },
    "720p": {
        "label": "HD 720p (MP4)",
        "format": "bestvideo*[height<=720]+bestaudio/best[height<=720]/best",
        "merge": "mp4",
    },
    "audio": {
        "label": "Audio seul (MP3)",
        "format": "bestaudio/best",
        "audio_only": True,
    },
}

_ANSI_RE = re.compile(r"\x1b\[[0-9;]*m")


def _human_size(num: Optional[float]) -> Optional[str]:
    if not num:
        return None
    for unit in ("o", "Ko", "Mo", "Go", "To"):
        if num < 1024:
            return f"{num:.1f} {unit}"
        num /= 1024
    return f"{num:.1f} Po"


def _clean_error(message: str) -> str:
    message = _ANSI_RE.sub("", message)
    message = re.sub(r"^ERROR:\s*", "", message).strip()
    lowered = message.lower()
    if "login required" in lowered or "cookies" in lowered or "rate-limit" in lowered:
        return (
            "Cette vidéo demande une connexion (contenu privé ou limité). "
            "Ajoutez un fichier cookies.txt — voir le README, section « Contenus privés »."
        )
    if "unsupported url" in lowered:
        return "Ce lien n'est pas pris en charge. Collez l'URL directe de la vidéo."
    return message[:400]


def _reject_private_hosts(url: str) -> None:
    """Garde-fou anti-SSRF : refuse les URL visant le réseau local."""
    parsed = urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        raise ValueError("URL invalide : collez un lien http(s) complet.")
    host = parsed.hostname
    try:
        infos = socket.getaddrinfo(host, None)
    except socket.gaierror:
        raise ValueError("Nom de domaine introuvable.")
    for info in infos:
        addr = ipaddress.ip_address(info[4][0])
        if addr.is_private or addr.is_loopback or addr.is_link_local or addr.is_reserved:
            raise ValueError("URL refusée (adresse réseau interne).")


def _base_opts() -> dict[str, Any]:
    opts: dict[str, Any] = {
        "quiet": True,
        "noprogress": True,
        "no_warnings": True,
        "noplaylist": True,
        "retries": 5,
        "fragment_retries": 5,
        "concurrent_fragment_downloads": 4,
    }
    if COOKIES_FILE and os.path.exists(COOKIES_FILE):
        opts["cookiefile"] = COOKIES_FILE
    return opts


def probe(url: str) -> dict[str, Any]:
    """Analyse un lien sans télécharger : titre, miniature, résolutions disponibles."""
    _reject_private_hosts(url)
    with yt_dlp.YoutubeDL(_base_opts()) as ydl:
        info = ydl.extract_info(url, download=False)
    if info is None:
        raise ValueError("Impossible d'analyser ce lien.")
    if info.get("_type") == "playlist":
        entries = [e for e in (info.get("entries") or []) if e]
        if not entries:
            raise ValueError("Aucune vidéo trouvée derrière ce lien.")
        info = entries[0]
    heights = sorted(
        {f["height"] for f in info.get("formats", []) if f.get("height")},
        reverse=True,
    )
    return {
        "title": info.get("title"),
        "uploader": info.get("uploader") or info.get("channel"),
        "duration": info.get("duration"),
        "thumbnail": info.get("thumbnail"),
        "platform": info.get("extractor_key"),
        "url": info.get("webpage_url") or url,
        "resolutions": heights[:10],
        "best_height": heights[0] if heights else None,
    }


@dataclass
class Job:
    id: str
    url: str
    quality: str
    status: str = "queued"  # queued | downloading | processing | finished | error
    progress: float = 0.0
    speed: Optional[str] = None
    eta: Optional[int] = None
    title: Optional[str] = None
    filename: Optional[str] = None
    filepath: Optional[str] = None
    filesize: Optional[str] = None
    error: Optional[str] = None
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "status": self.status,
            "progress": round(self.progress, 1),
            "speed": self.speed,
            "eta": self.eta,
            "title": self.title,
            "filename": self.filename,
            "filesize": self.filesize,
            "error": self.error,
        }


class JobManager:
    def __init__(self) -> None:
        self._jobs: dict[str, Job] = {}
        self._lock = threading.Lock()
        os.makedirs(DOWNLOAD_DIR, exist_ok=True)
        janitor = threading.Thread(target=self._janitor, daemon=True)
        janitor.start()

    def start(self, url: str, quality: str) -> Job:
        if quality not in QUALITY_PRESETS:
            raise ValueError(f"Qualité inconnue : {quality}")
        _reject_private_hosts(url)
        with self._lock:
            active = sum(
                1 for j in self._jobs.values() if j.status in ("queued", "downloading", "processing")
            )
            if active >= MAX_CONCURRENT_JOBS:
                raise RuntimeError(
                    f"{MAX_CONCURRENT_JOBS} téléchargements déjà en cours — réessayez dans un instant."
                )
            job = Job(id=uuid.uuid4().hex[:12], url=url, quality=quality)
            self._jobs[job.id] = job
        threading.Thread(target=self._run, args=(job,), daemon=True).start()
        return job

    def get(self, job_id: str) -> Optional[Job]:
        with self._lock:
            return self._jobs.get(job_id)

    def _run(self, job: Job) -> None:
        outdir = os.path.join(DOWNLOAD_DIR, job.id)
        os.makedirs(outdir, exist_ok=True)
        preset = QUALITY_PRESETS[job.quality]
        opts = _base_opts()
        opts.update(
            {
                "outtmpl": os.path.join(outdir, "%(title).170B [%(id)s].%(ext)s"),
                "format": preset["format"],
                "progress_hooks": [lambda d: self._on_progress(job, d)],
                "postprocessor_hooks": [lambda d: self._on_postprocess(job, d)],
            }
        )
        if preset.get("merge"):
            opts["merge_output_format"] = preset["merge"]
        if preset.get("audio_only"):
            opts["postprocessors"] = [
                {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": "0"}
            ]
        try:
            job.status = "downloading"
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = ydl.extract_info(job.url, download=True)
            if info:
                job.title = info.get("title") or job.title
            files = [
                os.path.join(outdir, f)
                for f in os.listdir(outdir)
                if not f.endswith((".part", ".ytdl", ".temp"))
            ]
            if not files:
                raise RuntimeError("Le téléchargement n'a produit aucun fichier.")
            path = max(files, key=os.path.getsize)
            job.filepath = path
            job.filename = os.path.basename(path)
            job.filesize = _human_size(os.path.getsize(path))
            job.progress = 100.0
            job.status = "finished"
        except Exception as exc:  # noqa: BLE001 — l'erreur est restituée à l'utilisateur
            job.status = "error"
            job.error = _clean_error(str(exc))

    def _on_progress(self, job: Job, d: dict[str, Any]) -> None:
        if d.get("status") == "downloading":
            job.status = "downloading"
            total = d.get("total_bytes") or d.get("total_bytes_estimate")
            downloaded = d.get("downloaded_bytes") or 0
            if total:
                job.progress = min(downloaded / total * 100, 100.0)
            speed = d.get("speed")
            job.speed = f"{_human_size(speed)}/s" if speed else None
            job.eta = d.get("eta")
        elif d.get("status") == "finished":
            # Un flux (vidéo ou audio) est terminé ; la fusion ffmpeg peut suivre.
            job.status = "processing"
            job.speed = None
            job.eta = None

    def _on_postprocess(self, job: Job, d: dict[str, Any]) -> None:
        if d.get("status") == "started":
            job.status = "processing"

    def _janitor(self) -> None:
        while True:
            time.sleep(300)
            cutoff = time.time() - RETENTION_MINUTES * 60
            with self._lock:
                expired = [j for j in self._jobs.values() if j.created_at < cutoff]
                for job in expired:
                    self._jobs.pop(job.id, None)
            for job in expired:
                shutil.rmtree(os.path.join(DOWNLOAD_DIR, job.id), ignore_errors=True)
            # Purge aussi les dossiers orphelins (redémarrage du serveur, etc.).
            try:
                for name in os.listdir(DOWNLOAD_DIR):
                    path = os.path.join(DOWNLOAD_DIR, name)
                    if os.path.isdir(path) and os.path.getmtime(path) < cutoff:
                        shutil.rmtree(path, ignore_errors=True)
            except OSError:
                pass


manager = JobManager()
