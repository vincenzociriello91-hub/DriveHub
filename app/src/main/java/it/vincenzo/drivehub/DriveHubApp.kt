package it.vincenzo.drivehub

import android.app.Application
import it.vincenzo.drivehub.core.Reports

class DriveHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reports.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Il rapporto di crash si salva subito, prima che il processo muoia
            Reports.saveCrash(e)
            previous?.uncaughtException(thread, e)
        }
        Reports.log("App avviata")
    }
}
