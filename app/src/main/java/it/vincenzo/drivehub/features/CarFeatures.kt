package it.vincenzo.drivehub.features

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import it.vincenzo.drivehub.core.Prefs

/** Cosa succede quando il telefono si collega all'auto e quando si scollega */
object CarFeatures {
    fun onStart(ctx: Context) {
        TripRecorder.start()
        AlertMonitor.reset()
        AlertMonitor.maintenance(ctx)
        Parking.cancelTimer(ctx) // si è tornati in auto: la sosta è finita
        if (Prefs.autoMusic(ctx)) {
            // qualche secondo per far passare l'audio sulle casse dell'auto
            Handler(Looper.getMainLooper()).postDelayed({ VoiceAssistant.mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY) }, 4000)
        }
        if (Prefs.autoDashcam(ctx)) DashcamService.start(ctx)
    }

    fun onStop(ctx: Context) {
        TripRecorder.stop(ctx)
        Parking.save(ctx, TripRecorder.lastLocation)
        DashcamService.stop(ctx)
    }
}
