# DriveHub

App personale per Android Auto (Pixel 8 Pro + Ford Fiesta): cruscotto OBD dal vivo, prezzi carburante MIMIT, meteo, musica locale, rapporti automatici.

Si installa da fonti sconosciute: scarica `DriveHub.apk` dall'ultima [Release](../../releases/latest).

Compilazione: GitHub Actions (`.github/workflows/build.yml`). Ogni nuovo `versionCode` in `app/build.gradle.kts` pubblica una Release.
Secret richiesti: `KEYSTORE_B64`, `KEYSTORE_PASS`.
Rapporti: file JSON in `Download/DriveHub_report` sul telefono (nessun token nell'app, perché l'APK è pubblico).
