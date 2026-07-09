# Déployer MKDownloader sur Hugging Face Spaces

> ⚠️ **Obsolète (juillet 2026)** : Hugging Face a rendu les Spaces Docker payants (abonnement PRO). Ce guide est conservé au cas où une offre gratuite reviendrait. Voir plutôt [`../auto-hebergement.md`](../auto-hebergement.md) ou [`../android-termux.md`](../android-termux.md).

Hugging Face Spaces héberge gratuitement des conteneurs Docker (2 vCPU, 16 Go de RAM sur l'offre gratuite) — largement assez pour cette application, ffmpeg compris. Aucune carte bancaire n'est demandée.

## Prérequis : rendre le repo GitHub public

Le Space clone ce repo au moment du build, il doit donc être public :
GitHub → **MKDownloader** → **Settings** → tout en bas, **Danger Zone** → **Change visibility** → **Make public**.

(Votre *instance* restera protégée par le mot de passe `APP_PASSWORD` — seul le code source devient visible, et il ne contient aucun secret.)

## Créer le Space (5 minutes)

1. Créez un compte gratuit sur [huggingface.co](https://huggingface.co/join).
2. En haut à droite : **+ New Space**.
   - **Space name** : `mkdownloader`
   - **License** : laissez vide ou `mit`
   - **SDK** : **Docker** → template **Blank**
   - **Hardware** : **CPU basic (free)**
   - **Visibility** : **Public** (l'accès reste contrôlé par votre mot de passe) — un Space *Private* fonctionne aussi mais exige d'être connecté à Hugging Face pour ouvrir la page.
3. Une fois le Space créé : onglet **Files** → **+ Add file** → **Create a new file**.
   - Nom du fichier : `Dockerfile`
   - Contenu : copiez-collez intégralement le fichier [`Dockerfile`](./Dockerfile) de ce dossier.
   - **Commit new file**.
4. Ajoutez le mot de passe : onglet **Settings** du Space → **Variables and secrets** → **New secret** :
   - Name : `APP_PASSWORD`
   - Value : un mot de passe fort de votre choix.
5. Le Space se construit tout seul (~3 min). Quand le statut passe à **Running**, votre application est en ligne sur :
   `https://<votre-pseudo>-mkdownloader.hf.space`

## Mise à jour

Quand le repo GitHub évolue, ou si un site ne fonctionne plus (yt-dlp à mettre à jour) :
Space → **Settings** → **Factory rebuild**. Le build re-clone le repo et réinstalle la dernière version de yt-dlp.

## Limites de l'offre gratuite

- Le Space s'endort après ~48 h sans visite (il redémarre tout seul à la visite suivante, ~1 min).
- Les fichiers téléchargés sont temporaires (c'est déjà le fonctionnement normal de l'application : purge automatique).
- Les IP des datacenters sont parfois filtrées par YouTube (« confirm you're not a bot ») et Instagram exige une session. Solution : exportez vos cookies (procédure dans le README principal), puis ajoutez-les en **secret** dans le Space — Settings → Variables and secrets → New secret, Name : `COOKIES_CONTENT`, Value : le contenu intégral de votre `cookies.txt`. Le secret n'est jamais visible publiquement, même dans un Space public. Redémarrez le Space pour l'appliquer.

## Alternative 100 % gratuite et sans limite : héberger chez soi

Si vous avez un PC ou un Raspberry Pi qui reste allumé :

```bash
git clone https://github.com/MKMemories/MKDownloader.git
cd MKDownloader
docker compose up -d --build
```

Puis exposez-le gratuitement avec un tunnel Cloudflare (aucun port à ouvrir sur la box) :

```bash
cloudflared tunnel --url http://localhost:8000
```

Avantages : aucune veille, aucune limite de RAM, et votre IP résidentielle n'est pas filtrée par YouTube — c'est la meilleure configuration pour le 4K intensif.
