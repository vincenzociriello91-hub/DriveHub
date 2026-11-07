package it.vincenzo.drivehub.features

import android.content.Context
import it.vincenzo.drivehub.DriveHubApp
import it.vincenzo.drivehub.core.Notify
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import java.util.Locale

/** Regole pure degli avvisi (testabili) */
object AlertRules {
    fun check(v: Map<String, Double>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        v["05"]?.let {
            if (it >= 110) out += "caldo" to "Attenzione: motore troppo caldo, ${it.toInt()} gradi. Fermati appena puoi in sicurezza."
        }
        // Con la ricarica intelligente Ford la tensione in marcia può scendere a circa 12,3 V: soglia a 12,0 per non dare falsi allarmi
        val rpm = v["0C"] ?: 0.0
        v["V"]?.let {
            if (rpm > 600 && it < 12.0) out += "batteria" to
                "Batteria non in carica: ${String.format(Locale.ITALY, "%.1f", it)} volt. Possibile problema all'alternatore."
        }
        v["2F"]?.let {
            if (it > 0 && it <= 10) out += "riserva" to "Carburante in riserva: ${it.toInt()} per cento."
        }
        return out
    }

    /** Codici nuovi rispetto a quelli già noti (alla prima lettura sono tutti nuovi) */
    fun newCodes(known: Set<String>?, now: List<String>): List<String> =
        if (known == null) now else now.filter { it !in known }
}

/** Avvisi a voce e in notifica (con vibrazione sul Pixel Watch), al massimo uno ogni 10 minuti per tipo */
object AlertMonitor {
    private val last = HashMap<String, Long>()
    private var knownDtcs: Set<String>? = null

    @Synchronized fun reset() {
        last.clear()
        knownDtcs = null
    }

    fun check(values: Map<String, Double>) = AlertRules.check(values).forEach { (k, m) -> fire(k, m) }

    @Synchronized fun onDtcs(codes: List<String>) {
        val first = knownDtcs == null
        val new = AlertRules.newCodes(knownDtcs, codes)
        knownDtcs = codes.toSet()
        if (new.isEmpty()) return
        fire("dtc-${new.joinToString()}",
            (if (first) "Codici errore memorizzati nella centralina: " else "Nuovo codice errore: ") + new.joinToString(", "))
    }

    fun maintenance(ctx: Context) {
        val odo = Prefs.odometerKm(ctx)
        if (odo <= 0) return
        listOf("tagliando" to Prefs.nextServiceKm(ctx), "cambio gomme" to Prefs.nextTyresKm(ctx)).forEach { (what, at) ->
            if (at <= 0) return@forEach
            val left = (at - odo).toInt()
            if (left <= 500) fire("manut-$what",
                if (left > 0) "Promemoria: $what tra $left chilometri." else "Promemoria: $what superato di ${-left} chilometri.")
        }
    }

    @Synchronized private fun fire(key: String, msg: String) {
        val now = System.currentTimeMillis()
        if (now - (last[key] ?: 0L) < 10 * 60_000L) return
        last[key] = now
        Reports.log("Avviso: $msg")
        Speech.say(msg)
        Notify.show(DriveHubApp.ctx, key.hashCode(), Notify.ALERTS, "DriveHub · avviso auto", msg)
    }
}
