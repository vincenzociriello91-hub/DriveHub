package it.vincenzo.drivehub.features

import android.content.Context
import android.location.Location
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class Trip(val start: Long, val end: Long, val km: Double, val liters: Double, val price: Double?) {
    val minutes get() = ((end - start) / 60_000).toInt()
    val l100: Double? get() = if (km > 1 && liters > 0) liters * 100 / km else null
    val cost: Double? get() = if (liters > 0 && price != null) liters * price else null
}

/** Archivio dei viaggi in files/viaggi.json (ultimi 300, il più recente per primo) */
object Trips {
    private fun file(ctx: Context) = File(ctx.filesDir, "viaggi.json")

    fun list(ctx: Context): List<Trip> = try {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Trip(o.getLong("inizio"), o.getLong("fine"), o.getDouble("km"), o.getDouble("litri"),
                if (o.has("prezzo")) o.getDouble("prezzo") else null)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(ctx: Context, t: Trip) {
        val a = JSONArray()
        (listOf(t) + list(ctx)).take(300).forEach { x ->
            val o = JSONObject().put("inizio", x.start).put("fine", x.end).put("km", x.km).put("litri", x.liters)
            x.price?.let { o.put("prezzo", it) }
            a.put(o)
        }
        file(ctx).writeText(a.toString())
    }
}

/** Viaggio in corso: km dal GPS, litri integrando il consumo istantaneo dell'OBD */
object TripRecorder {
    private var active = false
    private var start = 0L
    private var meters = 0.0
    private var liters = 0.0
    private var lastFix: Location? = null
    private var lastFuelAt = 0L

    /** Ultima posizione nota, anche fuori dal viaggio (serve per il parcheggio) */
    @Volatile var lastLocation: Location? = null
        private set

    @Synchronized fun start() {
        active = true
        start = System.currentTimeMillis()
        meters = 0.0; liters = 0.0; lastFix = null; lastFuelAt = 0
    }

    @Synchronized fun onLocation(l: Location) {
        lastLocation = l
        if (!active || !l.hasAccuracy() || l.accuracy > 30) return
        lastFix?.let { meters += it.distanceTo(l) }
        lastFix = l
    }

    @Synchronized fun onFuelRate(litersPerHour: Double) {
        val now = System.currentTimeMillis()
        val hours = (now - lastFuelAt) / 3_600_000.0
        // salti oltre 30 s (OBD scollegato) non si contano
        if (active && lastFuelAt > 0 && hours < 30.0 / 3600) liters += litersPerHour * hours
        lastFuelAt = now
    }

    @Synchronized fun km() = meters / 1000
    @Synchronized fun avgL100(): Double? = if (meters > 1000 && liters > 0) liters * 100 / (meters / 1000) else null

    fun summary(): String {
        val km = km()
        val min = ((System.currentTimeMillis() - start) / 60_000).toInt()
        val avg = avgL100()?.let { ", media ${String.format(Locale.ITALY, "%.1f", it)} litri ogni cento chilometri" } ?: ""
        return "Viaggio: ${String.format(Locale.ITALY, "%.1f", km)} chilometri in $min minuti$avg."
    }

    fun stop(ctx: Context): Trip? {
        synchronized(this) {
            if (!active) return null
            active = false
            val km = meters / 1000
            if (km < 0.2) return null
            val t = Trip(start, System.currentTimeMillis(), km, liters, Prefs.lastFuelPrice(ctx))
            Trips.add(ctx, t)
            Prefs.addOdometer(ctx, km)
            Reports.log("Viaggio: ${"%.1f".format(km)} km, ${t.minutes} min, ${"%.2f".format(liters)} l, costo ${t.cost?.let { "%.2f".format(it) } ?: "n.d."} €")
            return t
        }
    }
}
