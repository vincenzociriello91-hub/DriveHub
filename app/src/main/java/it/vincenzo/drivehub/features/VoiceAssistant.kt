package it.vincenzo.drivehub.features

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import it.vincenzo.drivehub.core.Locator
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.fuel.FuelRepo
import it.vincenzo.drivehub.obd.ObdManager
import it.vincenzo.drivehub.screen.ScreenController
import it.vincenzo.drivehub.screen.launchableActivities
import it.vincenzo.drivehub.weather.Weather
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Assistente vocale "stile Jarvis": ascolta dal microfono del telefono e risponde dalle casse */
object VoiceAssistant {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recognizer: SpeechRecognizer? = null

    private fun f1(v: Double) = String.format(Locale.ITALY, "%.1f", v)

    /** Da chiamare sul thread principale; show mostra un breve testo sul display */
    fun listen(ctx: Context, show: (String) -> Unit) {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Speech.say("Concedi il microfono nell'app DriveHub sul telefono")
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            Speech.say("Riconoscimento vocale non disponibile su questo telefono")
            return
        }
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                done(r)
                if (text.isNullOrBlank()) Speech.say("Non ho sentito") else {
                    show("\"$text\"")
                    handle(ctx.applicationContext, text)
                }
            }

            override fun onError(error: Int) {
                done(r)
                Reports.log("Voce: errore riconoscimento $error")
                Speech.say("Non ho capito, riprova")
            }

            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        )
        show("Ti ascolto…")
    }

    private fun done(r: SpeechRecognizer) {
        r.destroy()
        if (recognizer === r) recognizer = null
    }

    fun handle(ctx: Context, text: String) {
        val cmd = VoiceCommands.parse(text)
        Reports.log("Voce: \"$text\" -> $cmd")
        when (cmd) {
            is Cmd.Open -> openApp(ctx, cmd.app)
            Cmd.Back -> ScreenController.back()
            Cmd.Home -> ScreenController.home()
            Cmd.Split -> ScreenController.toggleSplit()
            Cmd.Consumption -> Speech.say(consumption())
            Cmd.Engine -> Speech.say(engine())
            Cmd.Trip -> Speech.say(TripRecorder.summary())
            Cmd.Errors -> errors()
            Cmd.Fuel -> fuel(ctx)
            Cmd.Weather -> weather(ctx)
            Cmd.PlayPause -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            Cmd.Next -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT)
            Cmd.Previous -> mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            Cmd.SaveClip -> Speech.say(if (DashcamService.protectNow()) "Salvo la clip della dashcam" else "La dashcam è spenta")
            is Cmd.Unknown -> Speech.say("Non ho capito. Puoi dire: apri un'app, consumi, benzina, errori, motore, meteo, viaggio.")
        }
    }

    /** Tasti multimediali: comandano l'app audio in riproduzione (Spotify, YouTube Music, DriveHub…) */
    fun mediaKey(ctx: Context, code: Int) {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun openApp(ctx: Context, name: String) {
        if (!ScreenController.isShizuku()) {
            Speech.say("Per aprire le app sul display serve Shizuku attivo")
            return
        }
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = ctx.packageManager
                launchableActivities(ctx).map { it.loadLabel(pm).toString() to it }
            }
            val i = VoiceCommands.bestMatch(name, apps.map { it.first })
            if (i == null) {
                Speech.say("Non trovo l'app $name")
            } else {
                val ai = apps[i].second.activityInfo
                Speech.say("Apro ${apps[i].first}")
                ScreenController.launch(android.content.ComponentName(ai.packageName, ai.name))
            }
        }
    }

    private fun consumption(): String {
        val v = ObdManager.state.value.values
        val inst = v["L100"]
        val avg = TripRecorder.avgL100()
        if (inst == null && avg == null) return "Dati di consumo non disponibili: serve l'adattatore OBD collegato."
        return listOfNotNull(
            inst?.let { "Ora consumi ${f1(it)} litri ogni cento chilometri" },
            avg?.let { "media del viaggio ${f1(it)}" },
        ).joinToString(", ") + "."
    }

    private fun engine(): String {
        val v = ObdManager.state.value.values
        if (v.isEmpty()) return "Dati del motore non disponibili: serve l'adattatore OBD collegato."
        return listOfNotNull(
            v["05"]?.let { "Motore a ${it.toInt()} gradi" },
            v["V"]?.let { "batteria a ${f1(it)} volt" },
            v["0C"]?.let { "${it.toInt()} giri al minuto" },
        ).joinToString(", ") + "."
    }

    private fun errors() {
        if (!ObdManager.state.value.connected) {
            Speech.say("L'adattatore OBD non è collegato")
            return
        }
        ObdManager.requestDtc()
        scope.launch {
            val codes = withTimeoutOrNull(10_000) { ObdManager.state.first { it.dtcs != null }.dtcs }
            Speech.say(
                when {
                    codes == null -> "La centralina non ha risposto"
                    codes.isEmpty() -> "Nessun codice di errore memorizzato"
                    else -> "Codici errore: ${codes.joinToString(", ")}"
                }
            )
        }
    }

    private fun fuel(ctx: Context) {
        val type = Prefs.fuel(ctx)
        Speech.say("Cerco $type")
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val loc = Locator.current(ctx) ?: error("posizione non disponibile")
                    FuelRepo.search(ctx, type, loc.latitude, loc.longitude)
                }
            }
            r.onSuccess { offers ->
                val b = offers.firstOrNull()
                Speech.say(
                    if (b == null) "Nessun distributore con prezzo entro dieci chilometri"
                    else "$type più economica: ${b.station.brand} a ${b.station.town}, " +
                        "${String.format(Locale.ITALY, "%.3f", b.price)} euro, a ${f1(b.km)} chilometri. " +
                        "Per navigare apri Strumenti, Carburante."
                )
            }.onFailure { Speech.say("Non riesco a cercare i prezzi: ${it.message}") }
        }
    }

    private fun weather(ctx: Context) {
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val loc = Locator.current(ctx) ?: error("posizione non disponibile")
                    Weather.load(loc.latitude, loc.longitude)
                }
            }
            r.onSuccess { f ->
                val rain = f.next.maxOfOrNull { it.rainProb } ?: 0
                Speech.say("Ora ${f.temp.toInt()} gradi, ${Weather.describe(f.code).lowercase(Locale.ITALY)}. " +
                    "Nelle prossime ore pioggia fino al $rain per cento.")
            }.onFailure { Speech.say("Meteo non disponibile: ${it.message}") }
        }
    }
}
