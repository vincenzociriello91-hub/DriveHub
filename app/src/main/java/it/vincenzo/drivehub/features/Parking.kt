package it.vincenzo.drivehub.features

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.location.Location
import android.net.Uri
import it.vincenzo.drivehub.R
import it.vincenzo.drivehub.core.Notify
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Posizione dell'auto alla fine del viaggio e timer della sosta */
object Parking {
    private const val ID = 7001
    const val ACTION_TIMER = "it.vincenzo.drivehub.SOSTA"
    const val ACTION_EXPIRING = "it.vincenzo.drivehub.SOSTA_SCADE"
    const val EXTRA_MIN = "minuti"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    private fun hhmm(t: Long) = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(t))

    fun save(ctx: Context, loc: Location?) {
        if (loc == null) {
            Reports.log("Parcheggio: posizione non disponibile")
            return
        }
        val now = System.currentTimeMillis()
        Prefs.setParking(ctx, loc.latitude, loc.longitude, now)
        Reports.log("Parcheggio salvato (precisione ${loc.accuracy.toInt()} m)")
        Notify.show(
            ctx, ID, Notify.PARKING, "Auto parcheggiata",
            "Posizione salvata alle ${hhmm(now)}. Tocca per vederla in Maps.",
            tap = mapsIntent(ctx),
            actions = listOf(timerAction(ctx, 60, "Sosta 1 ora"), timerAction(ctx, 120, "Sosta 2 ore")),
        )
    }

    fun mapsIntent(ctx: Context): PendingIntent? {
        val p = Prefs.parking(ctx) ?: return null
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${p.lat},${p.lon}(Auto)"))
        return PendingIntent.getActivity(ctx, 1, i, FLAGS)
    }

    private fun timerAction(ctx: Context, min: Int, label: String): Notification.Action {
        val i = Intent(ctx, ParkingReceiver::class.java).setAction(ACTION_TIMER).putExtra(EXTRA_MIN, min)
        return Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_gauge), label,
            PendingIntent.getBroadcast(ctx, min, i, FLAGS)).build()
    }

    private fun expiringIntent(ctx: Context) =
        PendingIntent.getBroadcast(ctx, 2, Intent(ctx, ParkingReceiver::class.java).setAction(ACTION_EXPIRING), FLAGS)

    /** Avviso 10 minuti prima della scadenza (sveglia non esatta: può tardare di qualche minuto) */
    fun startTimer(ctx: Context, minutes: Int) {
        val end = System.currentTimeMillis() + minutes * 60_000L
        Prefs.setParkingEnd(ctx, end)
        ctx.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end - 10 * 60_000L, expiringIntent(ctx))
        Notify.show(ctx, ID, Notify.PARKING, "Sosta avviata", "Scade alle ${hhmm(end)}: ti avviso 10 minuti prima.", tap = mapsIntent(ctx))
        Reports.log("Parcheggio: sosta di $minutes minuti")
    }

    fun cancelTimer(ctx: Context) {
        if (Prefs.parkingEnd(ctx) == 0L) return
        ctx.getSystemService(AlarmManager::class.java).cancel(expiringIntent(ctx))
        Prefs.setParkingEnd(ctx, 0)
        Reports.log("Parcheggio: timer annullato")
    }
}

class ParkingReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Parking.ACTION_TIMER -> Parking.startTimer(ctx, intent.getIntExtra(Parking.EXTRA_MIN, 60))
            Parking.ACTION_EXPIRING -> {
                Prefs.setParkingEnd(ctx, 0)
                Notify.show(ctx, 7002, Notify.PARKING, "Sosta in scadenza", "Mancano 10 minuti alla fine della sosta.", tap = Parking.mapsIntent(ctx))
            }
        }
    }
}
