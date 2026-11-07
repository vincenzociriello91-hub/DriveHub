package it.vincenzo.drivehub.features

import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import it.vincenzo.drivehub.DriveHubApp
import it.vincenzo.drivehub.core.Reports
import java.util.Locale

/** Voce di DriveHub: le risposte e gli avvisi escono dalle casse dell'auto */
object Speech {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()

    fun say(text: String) {
        Reports.log("Voce DriveHub: $text")
        main.post {
            val t = tts
            when {
                t == null -> {
                    pending += text
                    tts = TextToSpeech(DriveHubApp.ctx) { status -> onInit(status) }
                }
                !ready -> pending += text
                else -> speak(t, text)
            }
        }
    }

    private fun onInit(status: Int) {
        val t = tts ?: return
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            t.language = Locale.ITALY
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            pending.forEach { speak(t, it) }
        } else {
            Reports.log("Sintesi vocale non disponibile (stato $status)")
            tts = null
        }
        pending.clear()
    }

    private fun speak(t: TextToSpeech, text: String) {
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "dh${text.hashCode()}")
    }
}
