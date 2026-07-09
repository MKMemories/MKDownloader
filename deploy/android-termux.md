# Faire tourner MKDownloader directement sur votre téléphone Android (Termux)

Votre téléphone est un vrai ordinateur Linux : avec **Termux** (gratuit, open source), MKDownloader tourne dessus sans PC, sans cloud, sans carte bancaire. Les vidéos se téléchargent directement dans le téléphone.

## 1. Installer Termux

Installez **Termux** depuis [F-Droid](https://f-droid.org/packages/com.termux/) ou [GitHub Releases](https://github.com/termux/termux-app/releases) (versions à jour, recommandé), ou depuis le Play Store.

## 2. Installer les dépendances (une seule fois)

Ouvrez Termux et collez :

```bash
pkg update -y && pkg install -y python ffmpeg git
```

## 3. Récupérer l'application

Si le repo GitHub est public :

```bash
git clone --depth 1 https://github.com/MKMemories/MKDownloader.git
cd MKDownloader
```

S'il est resté privé : ouvrez le repo dans le navigateur (connecté à GitHub) → bouton **Code → Download ZIP**, puis dans Termux :

```bash
pkg install -y unzip
cd ~ && unzip /sdcard/Download/MKDownloader-main.zip && cd MKDownloader-main
```

(Si `Permission denied` sur /sdcard : lancez d'abord `termux-setup-storage` et acceptez.)

## 4. Installer les dépendances Python et démarrer

```bash
pip install -r requirements.txt
termux-wake-lock   # empêche Android de tuer le serveur
python -m uvicorn server.main:app --host 127.0.0.1 --port 8000
```

Ouvrez **http://localhost:8000** dans Chrome sur le même téléphone : l'interface est là. Collez un lien, téléchargez — le bouton « Enregistrer le fichier » envoie la vidéo dans vos téléchargements Android.

Pour arrêter : `Ctrl+C` dans Termux (ou fermez la session). Pour relancer plus tard :

```bash
cd ~/MKDownloader && termux-wake-lock && python -m uvicorn server.main:app --host 127.0.0.1 --port 8000
```

## 5. Mettre à jour yt-dlp (si un site ne marche plus)

```bash
pip install -U yt-dlp
```

## Astuces

- **Gros avantage** : votre connexion mobile/box a une IP résidentielle — YouTube ne vous prendra pas pour un robot, contrairement aux serveurs cloud.
- **Cookies Instagram/FB privé** : exportez `cookies.txt` (voir README principal) et placez-le à la racine du dossier `MKDownloader`.
- **Batterie** : `termux-wake-lock` maintient le processus, mais désactivez aussi l'optimisation de batterie pour Termux (Paramètres Android → Applications → Termux → Batterie → Non restreinte) si le serveur s'arrête tout seul.
- Le serveur n'écoute que sur le téléphone (`127.0.0.1`) : rien n'est exposé à Internet, pas besoin de mot de passe.
