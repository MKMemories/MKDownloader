"""API HTTP de MKDownloader (Starlette) + service des fichiers statiques.

Starlette plutôt que FastAPI : aucune dépendance compilée (pydantic-core
exige Rust), donc l'application s'installe partout — y compris sur un
téléphone Android via Termux.
"""

import mimetypes
import os

from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.responses import FileResponse, JSONResponse
from starlette.routing import Mount, Route
from starlette.staticfiles import StaticFiles

from .downloader import QUALITY_PRESETS, _clean_error, manager, probe

# Si APP_PASSWORD est défini, toutes les requêtes API doivent le fournir
# (en-tête X-App-Key ou paramètre ?key=). Indispensable avant d'exposer
# l'application sur Internet.
APP_PASSWORD = os.environ.get("APP_PASSWORD", "")

# Types MIME corrects pour la PWA (installable sur iPhone via « Ajouter à
# l'écran d'accueil ») et le service worker.
mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("text/javascript", ".js")


def _authorized(request) -> bool:
    if not APP_PASSWORD:
        return True
    return (
        request.headers.get("x-app-key", "") == APP_PASSWORD
        or request.query_params.get("key", "") == APP_PASSWORD
    )


def _deny() -> JSONResponse:
    return JSONResponse({"detail": "Mot de passe requis"}, status_code=401)


async def _json_body(request) -> dict:
    try:
        data = await request.json()
    except Exception:  # noqa: BLE001 — corps absent ou JSON invalide
        return {}
    return data if isinstance(data, dict) else {}


def _extract_url(data: dict):
    url = str(data.get("url", "")).strip()
    return url if 8 <= len(url) <= 2000 else None


async def health(request) -> JSONResponse:
    return JSONResponse({"status": "ok", "protected": bool(APP_PASSWORD)})


async def qualities(request) -> JSONResponse:
    if not _authorized(request):
        return _deny()
    return JSONResponse(
        [
            {"id": key, "label": preset["label"]}
            for key, preset in QUALITY_PRESETS.items()
            if not preset.get("hidden")
        ]
    )


async def info(request) -> JSONResponse:
    if not _authorized(request):
        return _deny()
    url = _extract_url(await _json_body(request))
    if url is None:
        return JSONResponse(
            {"detail": "URL invalide : collez un lien http(s) complet."}, status_code=400
        )
    try:
        result = await run_in_threadpool(probe, url)
    except ValueError as exc:
        return JSONResponse({"detail": str(exc)}, status_code=400)
    except Exception as exc:  # noqa: BLE001 — yt-dlp lève des types variés
        return JSONResponse({"detail": _clean_error(str(exc))}, status_code=422)
    return JSONResponse(result)


async def download(request) -> JSONResponse:
    if not _authorized(request):
        return _deny()
    data = await _json_body(request)
    url = _extract_url(data)
    if url is None:
        return JSONResponse(
            {"detail": "URL invalide : collez un lien http(s) complet."}, status_code=400
        )
    quality = str(data.get("quality", "max"))
    try:
        job = manager.start(url, quality)
    except ValueError as exc:
        return JSONResponse({"detail": str(exc)}, status_code=400)
    except RuntimeError as exc:
        return JSONResponse({"detail": str(exc)}, status_code=429)
    return JSONResponse(job.to_dict())


async def job_status(request) -> JSONResponse:
    if not _authorized(request):
        return _deny()
    job = manager.get(request.path_params["job_id"])
    if job is None:
        return JSONResponse({"detail": "Téléchargement introuvable ou expiré"}, status_code=404)
    return JSONResponse(job.to_dict())


async def job_file(request):
    if not _authorized(request):
        return _deny()
    job = manager.get(request.path_params["job_id"])
    if job is None or job.status != "finished" or not job.filepath:
        return JSONResponse({"detail": "Fichier indisponible"}, status_code=404)
    if not os.path.exists(job.filepath):
        return JSONResponse({"detail": "Fichier expiré (purgé)"}, status_code=410)
    # ?inline=1 : lecture dans le navigateur (bon type MIME + disposition inline,
    # FileResponse gère les requêtes Range → seek vidéo sur iPhone/Safari).
    if request.query_params.get("inline") == "1":
        media = mimetypes.guess_type(job.filename or "")[0] or "application/octet-stream"
        return FileResponse(job.filepath, media_type=media, content_disposition_type="inline")
    return FileResponse(
        job.filepath,
        filename=job.filename or "video",
        media_type="application/octet-stream",
    )


_STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")

app = Starlette(
    routes=[
        Route("/api/health", health),
        Route("/api/qualities", qualities),
        Route("/api/info", info, methods=["POST"]),
        Route("/api/download", download, methods=["POST"]),
        Route("/api/jobs/{job_id}", job_status),
        Route("/api/jobs/{job_id}/file", job_file),
        Mount("/", StaticFiles(directory=_STATIC_DIR, html=True), name="static"),
    ]
)
