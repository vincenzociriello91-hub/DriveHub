package it.vincenzo.drivehub.features

import android.content.Context
import it.vincenzo.drivehub.DriveHubApp
import it.vincenzo.drivehub.core.Notify
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.obd.DtcInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

    /** Promemoria di una scadenza per data: nei 30 giorni prima e dopo che è passata; null se manca più tempo */
    fun deadline(what: String, dueDay: Long, today: Long): String? {
        if (dueDay <= 0) return null
        val days = dueDay - today
        val date = LocalDate.ofEpochDay(dueDay).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        return when {
            days > 30 -> null
            days > 1 -> "Promemoria: $what in scadenza tra $days giorni, il $date."
            days == 1L -> "Promemoria: $what in scadenza domani."
            days == 0L -> "Promemoria: $what in scadenza oggi."
            else -> "Attenzione: $what, scadenza passata il $date."
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

    /** "28/02/2027" -> giorno epoch; testo vuoto -> 0 (scadenza tolta); data non valida -> null */
    fun parseDate(s: String): Long? = if (s.isBlank()) 0 else runCatching { LocalDate.parse(s.trim(), dayFmt).toEpochDay() }.getOrNull()

    fun formatDate(day: Long): String = if (day <= 0) "" else LocalDate.ofEpochDay(day).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

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
            (if (first) "Codici errore memorizzati nella centralina: " else "Nuovo codice errore: ") +
                new.joinToString("; ") { "$it, ${DtcInfo.describe(it)}" })
    }

    fun maintenance(ctx: Context) {
        val today = LocalDate.now().toEpochDay()
        Prefs.DEADLINES.forEach { what -> AlertRules.deadline(what, Prefs.deadline(ctx, what), today)?.let { fire("scad-$what", it) } }
        val odo = Prefs.odometerKm(ctx)
        if (odo <= 0) return
        listOf("tagliando" to Prefs.nextServiceKm(ctx), "cambio gomme" to Prefs.nextTyresKm(ctx)).forEach { (what, at) ->
            if (at <= 0) return@forEach
            val left = (at - odo).toInt()
            if (left <= 500) fire("manut-$what",
                if (left > 0) "Promemoria: $what tra $left chilometri." else "Promemoria: $what superato di ${-left} chilometri.")
        }
    }

    /** Risposta a "quanto manca al tagliando": km per tagliando e gomme, date di bollo, assicurazione e revisione */
    fun summary(ctx: Context): String {
        val odo = Prefs.odometerKm(ctx)
        val km = listOf("tagliando" to Prefs.nextServiceKm(ctx), "cambio gomme" to Prefs.nextTyresKm(ctx))
            .filter { it.second > 0 && odo > 0 }
            .map { (what, at) -> val left = (at - odo).toInt(); if (left >= 0) "$what tra $left chilometri" else "$what superato di ${-left} chilometri" }
        val today = LocalDate.now().toEpochDay()
        val dates = Prefs.DEADLINES.mapNotNull { what ->
            Prefs.deadline(ctx, what).takeIf { it > 0 }?.let { d ->
                val date = LocalDate.ofEpochDay(d).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALY))
                if (d >= today) "$what il $date, tra ${d - today} giorni" else "$what, scadenza passata il $date"
            }
        }
        val all = km + dates
        return if (all.isEmpty()) "Non ci sono scadenze impostate: inseriscile nell'app sul telefono, scheda Viaggi."
        else all.joinToString(". ") { it.replaceFirstChar(Char::uppercase) } + "."
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
