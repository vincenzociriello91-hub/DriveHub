package it.vincenzo.drivehub.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import it.vincenzo.drivehub.R

/** Notifiche del telefono: arrivano anche sul Pixel Watch, che vibra */
object Notify {
    const val ALERTS = "avvisi"
    const val PARKING = "parcheggio"
    const val DASHCAM = "dashcam"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Avvisi auto", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(PARKING, "Parcheggio", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(DASHCAM, "Dashcam", NotificationManager.IMPORTANCE_LOW))
    }

    fun show(
        ctx: Context, id: Int, channel: String, title: String, text: String,
        tap: PendingIntent? = null, actions: List<Notification.Action> = emptyList(),
    ) {
        if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_gauge)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
        tap?.let { b.setContentIntent(it) }
        actions.forEach { b.addAction(it) }
        ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}
