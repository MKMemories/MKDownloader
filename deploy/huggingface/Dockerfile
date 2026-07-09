# Dockerfile prêt à coller dans un Hugging Face Space (SDK : Docker).
# Il clone ce repo GitHub (qui doit être PUBLIC), installe tout et démarre
# l'application sur le port 7860 attendu par Hugging Face.
FROM python:3.12-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg git \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -u 1000 app

RUN git clone --depth 1 https://github.com/MKMemories/MKDownloader /app \
    && chown -R app:app /app
WORKDIR /app

RUN pip install --no-cache-dir -r requirements.txt

USER app
ENV DOWNLOAD_DIR=/tmp/downloads
EXPOSE 7860

CMD ["python", "-m", "uvicorn", "server.main:app", "--host", "0.0.0.0", "--port", "7860"]
