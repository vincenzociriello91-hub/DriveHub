package it.vincenzo.drivehub.screen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import it.vincenzo.drivehub.R
import it.vincenzo.drivehub.core.Reports

/** Tiene viva la registrazione dello schermo per lo specchio (obbligatorio da Android 10) */
class MirrorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("specchio", "Specchio dello schermo", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "specchio")
            .setSmallIcon(R.drawable.ic_gauge)
            .setContentTitle("DriveHub")
            .setContentText("Specchio dello schermo sul display dell'auto")
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (mp == null) {
            Reports.log("Specchio: consenso non valido")
            stopSelf()
            return START_NOT_STICKY
        }
        ScreenController.setProjection(this, mp)
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
