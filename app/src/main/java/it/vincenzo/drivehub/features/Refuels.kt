package it.vincenzo.drivehub.features

import android.content.Context
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.obd.ObdManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Un rifornimento: litri, prezzo al litro (se detto) e contachilometri in quel momento */
data class Refuel(val time: Long, val liters: Double, val price: Double?, val odoKm: Double)

/** Rifornimenti in files/rifornimenti.json (il più recente per primo), consumo reale e autonomia */
object Refuels {
    /** Serbatoio della Fiesta Mk8 1.0 EcoBoost */
    const val TANK_L = 42.0

    private fun file(ctx: Context) = File(ctx.filesDir, "rifornimenti.json")
    private fun f(v: Double, d: Int = 1) = String.format(Locale.ITALY, "%.${d}f", v)

    fun list(ctx: Context): List<Refuel> = try {
        val a = JSONArray(file(ctx).readText())
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Refuel(o.getLong("ora"), o.getDouble("litri"), if (o.has("prezzo")) o.getDouble("prezzo") else null, o.getDouble("km"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** Salva il rifornimento con i km attuali; il prezzo diventa quello per stimare il costo dei viaggi */
    fun add(ctx: Context, liters: Double, price: Double?): Refuel {
        val r = Refuel(System.currentTimeMillis(), liters, price, Prefs.odometerKm(ctx))
        val a = JSONArray()
        (listOf(r) + list(ctx)).take(200).forEach { x ->
            val o = JSONObject().put("ora", x.time).put("litri", x.liters).put("km", x.odoKm)
            x.price?.let { o.put("prezzo", it) }
            a.put(o)
        }
        file(ctx).writeText(a.toString())
        price?.let { Prefs.setLastFuel(ctx, "rifornimento", it) }
        Reports.log("Rifornimento: ${f(liters)} l${price?.let { " a ${f(it, 3)} €/l" } ?: ""}")
        return r
    }

    /**
     * Consumo reale (l/100 km) dagli ultimi rifornimenti: litri messi dopo il più vecchio diviso i km percorsi.
     * shortcut: ogni rifornimento conta come un pieno; con rabbocchi parziali il valore oscilla, si media su più pieni.
     */
    fun realL100(newestFirst: List<Refuel>): Double? {
        val r = newestFirst.filter { it.odoKm > 0 }.take(6)
        if (r.size < 2) return null
        val km = r.first().odoKm - r.last().odoKm
        if (km < 50) return null
        return r.dropLast(1).sumOf { it.liters } * 100 / km
    }

    /** Km che restano: dal livello OBD se c'è, altrimenti dal serbatoio pieno meno il consumato dall'ultimo rifornimento */
    fun rangeKm(fuelPct: Double?, l100: Double?, kmSinceRefuel: Double?): Double? {
        if (l100 == null || l100 <= 0) return null
        val left = when {
            fuelPct != null && fuelPct > 0 -> fuelPct / 100 * TANK_L
            kmSinceRefuel != null -> TANK_L - kmSinceRefuel * l100 / 100
            else -> return null
        }
        return left.coerceAtLeast(0.0) * 100 / l100
    }

    /** Consumo da usare: reale dai rifornimenti, altrimenti la media dei viaggi registrati con l'OBD */
    fun bestL100(ctx: Context): Double? = realL100(list(ctx)) ?: Trips.list(ctx).filter { it.liters > 0 }.let { t ->
        val km = t.sumOf { it.km }
        if (km >= 50) t.sumOf { it.liters } * 100 / km else null
    }

    fun currentRangeKm(ctx: Context, l100: Double? = bestL100(ctx)): Double? {
        val last = list(ctx).firstOrNull()
        val since = last?.let { (Prefs.odometerKm(ctx) - it.odoKm).takeIf { k -> it.odoKm > 0 && k >= 0 } }
        return rangeKm(ObdManager.state.value.values["2F"], l100, since)
    }

    fun rangeText(ctx: Context): String {
        val l100 = bestL100(ctx)
        val range = currentRangeKm(ctx, l100)
        return when {
            range != null -> "Autonomia circa ${range.toInt()} chilometri, con un consumo di ${f(l100!!)} litri ogni cento."
            l100 == null -> "Non conosco ancora il consumo: segna i rifornimenti dicendo per esempio \"ho messo 30 litri a 1,85\"."
            else -> "Non so quanto carburante c'è: segna il prossimo pieno."
        }
    }

    private const val NUM = """(\d+(?:[.,]\d+)?)"""
    private val litersRe = Regex("""$NUM\s*(?:litri|l\b)""")
    private val amountRe = Regex("""$NUM\s*euro""")
    private val priceRe = Regex("""(?:a|da)\s+(\d+[.,]\d+)|(\d+[.,]\d+)\s*(?:euro\s*)?al\s+litro""")
    private val triggers = listOf("messo", "pieno", "rifornit", "fatto", "segna")

    /** "ho messo 30 litri a 1,85" / "pieno di 50 euro a 1,799": litri e prezzo al litro; null se non è un rifornimento */
    fun parse(text: String): Pair<Double, Double?>? {
        val t = text.lowercase(Locale.ITALY)
        if (triggers.none { it in t }) return null
        fun d(s: String) = s.replace(',', '.').toDoubleOrNull()
        val price = priceRe.find(t)?.let { m -> d(m.groupValues[1].ifEmpty { m.groupValues[2] }) }?.takeIf { it in 0.5..5.0 }
        val liters = litersRe.find(t)?.let { d(it.groupValues[1]) }
            ?: amountRe.findAll(t).mapNotNull { d(it.groupValues[1]) }.firstOrNull { it > 5 }?.let { eur -> price?.let { eur / it } }
        return liters?.takeIf { it in 1.0..TANK_L + 5 }?.let { it to price }
    }
}
