# MKDownloader — application Android (APK)

Application Android native (Kotlin) qui embarque le moteur yt-dlp + ffmpeg via [youtubedl-android](https://github.com/yausername/youtubedl-android). Aucune connexion à un serveur : tout se passe sur le téléphone.

## Télécharger l'APK

L'APK est compilé automatiquement par GitHub Actions à chaque mise à jour :
**GitHub → Releases → « MKDownloader — dernier APK » → `MKDownloader.apk`**.

À l'installation, Android demandera d'autoriser les « sources inconnues » pour votre navigateur : c'est normal pour tout APK hors Play Store (Google y interdit les téléchargeurs de vidéos).

## Fonctionnalités

- Coller un lien ou **« Partager → MKDownloader »** directement depuis Facebook, YouTube, Instagram…
- Analyse : titre, miniature, durée.
- Presets : qualité maximale, meilleur MP4, 1080p, 720p, audio MP3.
- Progression en direct, fichier enregistré dans **Téléchargements/MKDownloader**.
- Bouton « Mettre à jour yt-dlp » intégré (sans réinstaller l'app).

## Notes techniques

- `minSdk 29` (Android 10+), binaires ARM (`arm64-v8a`, `armeabi-v7a`).
- Signature : clé personnelle `signing/mkdownloader.p12` committée dans le repo. Elle ne sert qu'à installer/mettre à jour votre propre APK — ne l'utilisez pas pour publier sur un store. Pour la régénérer :
  `openssl req -x509 -newkey rsa:2048 -keyout k.pem -out c.pem -days 10950 -nodes -subj "/CN=MKDownloader" && openssl pkcs12 -export -inkey k.pem -in c.pem -out mkdownloader.p12 -name mkdownloader`
- Compilation locale : `gradle -p android :app:assembleRelease` (Android SDK requis) — mais GitHub Actions s'en charge, aucun PC nécessaire.
