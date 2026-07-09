#!/bin/sh
set -e

# Les extracteurs (YouTube, Facebook, Instagram…) évoluent sans arrêt :
# YTDLP_AUTO_UPDATE=1 met yt-dlp à jour à chaque démarrage du conteneur.
if [ "${YTDLP_AUTO_UPDATE:-1}" = "1" ]; then
  pip install --quiet --upgrade yt-dlp || echo "Mise à jour yt-dlp impossible (hors ligne ?), on continue."
fi

exec uvicorn server.main:app --host 0.0.0.0 --port "${PORT:-8000}"
