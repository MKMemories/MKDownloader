# Auto-héberger MKDownloader (PC, Mac ou Raspberry Pi) — 100 % gratuit, sans carte

En 2026, plus aucun cloud n'offre d'hébergement Docker gratuit sans carte bancaire (Render, Koyeb, Fly, Railway, Oracle exigent une carte ; Hugging Face a rendu les Spaces Docker payants). La solution pérenne : une machine chez vous. C'est aussi la **meilleure** configuration : pas de mise en veille, pas de limite de RAM, et une IP résidentielle que YouTube ne bloque pas.

N'importe quelle machine suffit : un PC (même ancien), un Mac, un Raspberry Pi 4/5.

## 1. Lancer l'application

Avec [Docker](https://docs.docker.com/get-docker/) installé (Docker Desktop sur Windows/Mac) :

```bash
git clone https://github.com/MKMemories/MKDownloader.git
cd MKDownloader
docker compose up -d --build
```

→ **http://localhost:8000** sur la machine, ou `http://<ip-locale-de-la-machine>:8000` depuis n'importe quel appareil du Wi-Fi maison (trouvez l'IP avec `ip a` / `ipconfig`).

Sans Docker : `pip install -r requirements.txt` (+ ffmpeg installé) puis `uvicorn server.main:app --host 0.0.0.0 --port 8000`.

## 2. Y accéder depuis l'extérieur (téléphone en 4G/5G, etc.)

### Option A — Tailscale : privé, gratuit, recommandé pour un usage personnel

[Tailscale](https://tailscale.com) crée un VPN privé entre vos appareils (gratuit jusqu'à 100 appareils, sans carte bancaire) :

1. Installez Tailscale sur la machine qui héberge **et** sur votre téléphone (application Android/iOS), connectez les deux au même compte.
2. Depuis le téléphone, où que vous soyez : `http://<nom-de-la-machine>:8000`.

Rien n'est exposé sur Internet public : inutile de définir `APP_PASSWORD`, personne d'autre ne peut y accéder.

### Option B — Tunnel Cloudflare : URL publique à partager

Pour une URL publique HTTPS (sans ouvrir de port sur la box, sans compte, sans carte) :

```bash
# installez cloudflared : https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/downloads/
cloudflared tunnel --url http://localhost:8000
```

Une URL `https://xxxx.trycloudflare.com` s'affiche — accessible du monde entier tant que la commande tourne. **Définissez impérativement `APP_PASSWORD`** dans `docker-compose.yml` avant d'exposer publiquement. (L'URL change à chaque relance ; pour une URL stable, créez un tunnel nommé avec un compte Cloudflare gratuit et un domaine.)

## 3. Démarrage automatique

Le `docker-compose.yml` contient `restart: unless-stopped` : l'application redémarre toute seule avec la machine. Pour un Raspberry Pi dédié, c'est tout ce qu'il faut.

## 4. Maintenance

```bash
docker compose restart        # met yt-dlp à jour au redémarrage (YTDLP_AUTO_UPDATE=1)
git pull && docker compose up -d --build   # récupère les évolutions du repo
```
