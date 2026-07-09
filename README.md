# MKDownloader ⬇

Application web **auto-hébergée** de téléchargement vidéo en **qualité maximale** (HD, 4K, 8K quand disponible). Sans limite, sans publicité, 100 % gratuite.

Optimisée pour **Facebook**, **YouTube** et **Instagram**, et compatible avec les 1 000+ sites pris en charge par [yt-dlp](https://github.com/yt-dlp/yt-dlp) (TikTok, X/Twitter, Vimeo, Dailymotion…).

## Fonctionnalités

- 🎯 **Qualité maximale réelle** : les plateformes séparent les flux vidéo et audio en haute qualité — l'application télécharge les deux meilleurs flux et les fusionne avec ffmpeg (c'est pour ça que les « téléchargeurs en un clic » plafonnent souvent à 720p, pas celui-ci).
- 📊 **Analyse du lien** avant téléchargement : titre, miniature, durée, résolution maximale disponible.
- ⚙️ **Presets de qualité** : maximum absolu (jusqu'à 8K, MKV), meilleur MP4 compatible, 1080p, 720p, ou extraction audio MP3.
- 📈 **Progression en direct** : pourcentage, vitesse, temps restant, phase de fusion.
- 🔐 **Mot de passe optionnel** (`APP_PASSWORD`) pour exposer l'instance sur Internet en privé.
- 🧹 **Purge automatique** des fichiers après quelques heures (configurable).
- 🐳 **Déploiement Docker en une commande**, avec mise à jour automatique de yt-dlp au démarrage.

## Démarrage rapide (Docker — recommandé)

```bash
git clone <url-du-repo>
cd MKDownloader
docker compose up -d --build
```

Ouvrez ensuite **http://localhost:8000**, collez un lien (ex. `https://www.facebook.com/share/v/…`), choisissez la qualité, téléchargez.

## Démarrage sans Docker

Prérequis : Python 3.11+ et **ffmpeg** installé (`sudo apt install ffmpeg` / `brew install ffmpeg`).

```bash
pip install -r requirements.txt
uvicorn server.main:app --host 0.0.0.0 --port 8000
```

## Configuration

Toutes les options passent par des variables d'environnement (voir `.env.example`) :

| Variable | Défaut | Rôle |
|---|---|---|
| `APP_PASSWORD` | *(vide)* | Si défini, l'interface et l'API exigent ce mot de passe. |
| `DOWNLOAD_DIR` | `./data` (`/data` en Docker) | Dossier de stockage temporaire. |
| `COOKIES_FILE` | `./cookies.txt` | Cookies pour les contenus privés (voir ci-dessous). |
| `RETENTION_MINUTES` | `180` | Durée de vie des fichiers avant purge. |
| `MAX_CONCURRENT_JOBS` | `3` | Téléchargements simultanés maximum. |
| `YTDLP_AUTO_UPDATE` | `1` (Docker) | Met yt-dlp à jour à chaque démarrage du conteneur. |

## Contenus privés ou limités (Instagram, Facebook)

Instagram exige presque toujours une session connectée, et Facebook la demande pour certains groupes/contenus privés. Solution : fournir vos cookies.

1. Installez une extension navigateur comme **Get cookies.txt LOCALLY**.
2. Connectez-vous à instagram.com / facebook.com, exportez les cookies au format Netscape.
3. Enregistrez le fichier sous `cookies.txt` à la racine du projet (en Docker, décommentez le montage dans `docker-compose.yml`).

⚠️ Ces cookies donnent accès à votre compte : ne les partagez jamais et ne les committez pas (le `.gitignore` les exclut déjà).

## Exposer l'instance sur Internet

1. **Définissez `APP_PASSWORD`** — sinon n'importe qui peut utiliser votre serveur et votre bande passante.
2. Placez l'application derrière un reverse proxy HTTPS (Caddy, Nginx + Let's Encrypt, Traefik…). Exemple Caddy :
   ```
   videos.mondomaine.fr {
       reverse_proxy localhost:8000
   }
   ```
3. Hébergement : n'importe quel VPS à ~5 €/mois (Hetzner, OVH, Scaleway…) suffit largement. Évitez les plateformes serverless (Vercel, Netlify) : les téléchargements longs et ffmpeg y sont incompatibles. Railway/Render/Fly.io fonctionnent avec le Dockerfile fourni.

## Maintenance

Les extracteurs YouTube/Facebook/Instagram changent régulièrement. Si un site cesse de fonctionner :

```bash
# Docker : il suffit de redémarrer (mise à jour auto de yt-dlp au boot)
docker compose restart

# Sans Docker :
pip install -U yt-dlp
```

## Note légale

Cet outil est destiné à un **usage personnel** : téléchargez uniquement des contenus que vous avez le droit d'enregistrer (vos propres vidéos, contenus libres de droits, ou avec l'autorisation de l'auteur). Le téléchargement peut contrevenir aux conditions d'utilisation des plateformes ; vous êtes seul responsable de l'usage que vous en faites.
