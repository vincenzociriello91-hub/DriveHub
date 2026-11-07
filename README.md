# DriveHub

App personale per Android Auto (Pixel 8 Pro + Ford Fiesta): display dell'auto come secondo tablet, diagnostica OBD completa (ex OBDTool), prezzi carburante MIMIT, meteo, musica, voce, viaggi, parcheggio, dashcam.

`config/codici.tsv`: significato dei codici errore, scaricato dall'app una volta a settimana.

Si installa da fonti sconosciute: scarica `DriveHub.apk` dall'ultima [Release](../../releases/latest).

Compilazione: GitHub Actions (`.github/workflows/build.yml`). Ogni nuovo `versionCode` in `app/build.gradle.kts` pubblica una Release.
Secret richiesti: `KEYSTORE_B64`, `KEYSTORE_PASS`.
Rapporti: file JSON in `Download/DriveHub_report` sul telefono (nessun token nell'app, perché l'APK è pubblico).
