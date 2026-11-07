package it.vincenzo.drivehub.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock

/** Soglia con isteresi: "in movimento" sopra 5 km/h, di nuovo "fermo" solo sotto 3 km/h */
class SpeedGate {
    var moving = false
        private set

    fun update(kmh: Double) {
        moving = if (moving) kmh >= 3 else kmh > 5
    }
}

/**
 * Velocità del veicolo da OBD (preferita) o GPS.
 * Senza dati recenti si considera il veicolo in movimento: nel dubbio lo schermo resta bloccato.
 */
object SpeedMonitor {
    private val gate = SpeedGate()
    @Volatile private var lastUpdate = 0L
    @Volatile var kmh: Double? = null
        private set
    private var lm: LocationManager? = null
    private val listener = LocationListener { loc -> if (loc.hasSpeed()) update(loc.speed * 3.6) }

    fun update(v: Double) = synchronized(gate) {
        kmh = v
        lastUpdate = SystemClock.elapsedRealtime()
        gate.update(v)
    }

    fun known() = lastUpdate > 0 && SystemClock.elapsedRealtime() - lastUpdate < 10_000

    fun moving() = !known() || gate.moving

    @SuppressLint("MissingPermission")
    fun start(ctx: Context) {
        if (lm != null || !Locator.hasPermission(ctx)) return
        val m = ctx.getSystemService(LocationManager::class.java)
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener, Looper.getMainLooper())
            lm = m
        } catch (e: Exception) {
            Reports.log("Velocità GPS non disponibile: ${e.message}")
        }
    }

    fun stop() {
        lm?.removeUpdates(listener)
        lm = null
    }
}
