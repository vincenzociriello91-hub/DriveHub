package it.vincenzo.drivehub

import android.app.Application
import android.content.Context
import it.vincenzo.drivehub.core.Notify
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.obd.DtcInfo

class DriveHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = this
        Reports.init(this)
        Notify.createChannels(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Il rapporto di crash si salva subito, prima che il processo muoia
            Reports.saveCrash(e)
            previous?.uncaughtException(thread, e)
        }
        Reports.log("App avviata")
        DtcInfo.load(this)
        Thread { DtcInfo.refresh(this) }.start()
    }

    companion object {
        /** Contesto dell'app per i moduli che girano senza schermata (voce, avvisi) */
        lateinit var ctx: Context
            private set
    }
}
