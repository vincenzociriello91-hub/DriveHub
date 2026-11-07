package it.vincenzo.drivehub.features

import android.content.Context
import android.location.Location
import android.os.SystemClock
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class Trip(
    val start: Long, val end: Long, val km: Double, val liters: Double, val price: Double?,
    val brakes: Int = 0, val accels: Int = 0,
) {
    val minutes get() = ((end - start) / 60_000).toInt()
    /** Punteggio di guida 0–100 (null per viaggi troppo corti) */
    val eco: Int? get() = DrivingStyle.score(brakes + accels, km)
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
                if (o.has("prezzo")) o.getDouble("prezzo") else null, o.optInt("frenate"), o.optInt("scatti"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun add(ctx: Context, t: Trip) {
        val a = JSONArray()
        (listOf(t) + list(ctx)).take(300).forEach { x ->
            val o = JSONObject().put("inizio", x.start).put("fine", x.end).put("km", x.km).put("litri", x.liters)
                .put("frenate", x.brakes).put("scatti", x.accels)
            x.price?.let { o.put("prezzo", it) }
            a.put(o)
        }
        file(ctx).writeText(a.toString())
    }
}

/** Km e litri di un periodo (settimana o mese che inizia il giorno start) */
data class PeriodStats(val start: LocalDate, val km: Double, val liters: Double) {
    val l100: Double? get() = if (km > 1 && liters > 0) liters * 100 / km else null
}

object TripStats {
    fun weekly(trips: List<Trip>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), n: Int = 8): List<PeriodStats> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return group(trips, zone, (n - 1 downTo 0).map { monday.minusWeeks(it.toLong()) }) {
            it.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }
    }

    fun monthly(trips: List<Trip>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault(), n: Int = 6): List<PeriodStats> =
        group(trips, zone, (n - 1 downTo 0).map { today.withDayOfMonth(1).minusMonths(it.toLong()) }) { it.withDayOfMonth(1) }

    private fun group(trips: List<Trip>, zone: ZoneId, periods: List<LocalDate>, key: (LocalDate) -> LocalDate): List<PeriodStats> {
        val byPeriod = trips.groupBy { key(Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate()) }
        return periods.map { p -> byPeriod[p].orEmpty().let { t -> PeriodStats(p, t.sumOf { it.km }, t.sumOf { it.liters }) } }
    }
}

/** Frenate e accelerazioni brusche dalla variazione di velocità in circa 2 secondi (OBD o GPS) */
class DrivingStyle {
    var brakes = 0
        private set
    var accels = 0
        private set
    private val samples = ArrayDeque<Pair<Long, Double>>()
    private var quietUntil = 0L

    fun reset() {
        brakes = 0; accels = 0; samples.clear(); quietUntil = 0
    }

    /** t in millisecondi da un orologio monotono */
    fun onSpeed(kmh: Double, t: Long) {
        samples.addLast(t to kmh)
        while (samples.size > 1 && t - samples.first().first > 2500) samples.removeFirst()
        val (t0, v0) = samples.first()
        val dt = (t - t0) / 1000.0
        // finestra di almeno 1,5 s: OBD e GPS insieme danno piccoli salti che su tempi brevi sembrerebbero frenate
        if (dt < 1.5 || t < quietUntil) return
        val a = (kmh - v0) / 3.6 / dt
        if (a > -BRAKE && a < ACCEL) return
        if (a <= -BRAKE) brakes++ else accels++
        quietUntil = t + 5000 // una manovra conta una volta sola
        samples.clear()
        samples.addLast(t to kmh)
    }

    companion object {
        /** m/s²: frenata decisa (circa 13 km/h in meno al secondo) */
        const val BRAKE = 3.5
        /** m/s²: per la Fiesta 1.0 (0–100 in circa 10 s, 2,7 m/s² di media) è uno scatto deciso */
        const val ACCEL = 3.0

        /** 100 senza manovre brusche, 15 punti in meno per ogni manovra ogni 10 km (viaggi corti contati come 10 km) */
        fun score(events: Int, km: Double): Int? =
            if (km < 2) null else (100 - 15.0 * events * 10 / km.coerceAtLeast(10.0)).toInt().coerceIn(0, 100)
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
    private val style = DrivingStyle()

    /** Ultima posizione nota, anche fuori dal viaggio (serve per il parcheggio) */
    @Volatile var lastLocation: Location? = null
        private set

    @Synchronized fun start() {
        active = true
        start = System.currentTimeMillis()
        meters = 0.0; liters = 0.0; lastFix = null; lastFuelAt = 0
        style.reset()
    }

    @Synchronized fun onSpeed(kmh: Double) {
        if (active) style.onSpeed(kmh, SystemClock.elapsedRealtime())
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
        val eco = synchronized(this) { DrivingStyle.score(style.brakes + style.accels, km) }
            ?.let { ". Stile di guida $it su 100, ${style.brakes} frenate e ${style.accels} accelerazioni brusche" } ?: ""
        return "Viaggio: ${String.format(Locale.ITALY, "%.1f", km)} chilometri in $min minuti$avg$eco."
    }

    fun stop(ctx: Context): Trip? {
        synchronized(this) {
            if (!active) return null
            active = false
            val km = meters / 1000
            if (km < 0.2) return null
            val t = Trip(start, System.currentTimeMillis(), km, liters, Prefs.lastFuelPrice(ctx), style.brakes, style.accels)
            Trips.add(ctx, t)
            Prefs.addOdometer(ctx, km)
            Reports.log("Viaggio: ${"%.1f".format(km)} km, ${t.minutes} min, ${"%.2f".format(liters)} l, costo ${t.cost?.let { "%.2f".format(it) } ?: "n.d."} €, frenate brusche ${t.brakes}, scatti ${t.accels}, eco ${t.eco ?: "n.d."}")
            return t
        }
    }
}
