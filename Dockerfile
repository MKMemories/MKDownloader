FROM python:3.12-slim

# ffmpeg est indispensable : fusion des flux vidéo+audio HD/4K et extraction MP3.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY server ./server
COPY static ./static
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

ENV DOWNLOAD_DIR=/data
VOLUME /data
EXPOSE 8000

CMD ["./entrypoint.sh"]
