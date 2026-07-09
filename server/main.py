"""API FastAPI de MKDownloader + service des fichiers statiques."""

import os

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from .downloader import QUALITY_PRESETS, manager, probe

# Si APP_PASSWORD est défini, toutes les requêtes API doivent le fournir
# (en-tête X-App-Key ou paramètre ?key=). Indispensable avant d'exposer
# l'application sur Internet.
APP_PASSWORD = os.environ.get("APP_PASSWORD", "")

app = FastAPI(title="MKDownloader", docs_url=None, redoc_url=None)


def require_key(
    x_app_key: str = Header(default=""),
    key: str = Query(default=""),
) -> None:
    if APP_PASSWORD and x_app_key != APP_PASSWORD and key != APP_PASSWORD:
        raise HTTPException(status_code=401, detail="Mot de passe requis")


class UrlPayload(BaseModel):
    url: str = Field(min_length=8, max_length=2000)


class DownloadPayload(UrlPayload):
    quality: str = "max"


@app.get("/api/health")
def health() -> dict:
    return {"status": "ok", "protected": bool(APP_PASSWORD)}


@app.get("/api/qualities", dependencies=[Depends(require_key)])
def qualities() -> list[dict]:
    return [{"id": key, "label": preset["label"]} for key, preset in QUALITY_PRESETS.items()]


@app.post("/api/info", dependencies=[Depends(require_key)])
async def info(payload: UrlPayload) -> dict:
    try:
        return await run_in_threadpool(probe, payload.url)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:  # noqa: BLE001 — yt-dlp lève des types variés
        from .downloader import _clean_error

        raise HTTPException(status_code=422, detail=_clean_error(str(exc)))


@app.post("/api/download", dependencies=[Depends(require_key)])
def download(payload: DownloadPayload) -> dict:
    try:
        job = manager.start(payload.url, payload.quality)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except RuntimeError as exc:
        raise HTTPException(status_code=429, detail=str(exc))
    return job.to_dict()


@app.get("/api/jobs/{job_id}", dependencies=[Depends(require_key)])
def job_status(job_id: str) -> dict:
    job = manager.get(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Téléchargement introuvable ou expiré")
    return job.to_dict()


@app.get("/api/jobs/{job_id}/file", dependencies=[Depends(require_key)])
def job_file(job_id: str) -> FileResponse:
    job = manager.get(job_id)
    if job is None or job.status != "finished" or not job.filepath:
        raise HTTPException(status_code=404, detail="Fichier indisponible")
    if not os.path.exists(job.filepath):
        raise HTTPException(status_code=410, detail="Fichier expiré (purgé)")
    return FileResponse(
        job.filepath,
        filename=job.filename or "video",
        media_type="application/octet-stream",
    )


_STATIC_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")
app.mount("/", StaticFiles(directory=_STATIC_DIR, html=True), name="static")
