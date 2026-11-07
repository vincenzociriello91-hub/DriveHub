package it.vincenzo.drivehub.features

import java.util.Locale

sealed interface Cmd {
    data class Open(val app: String) : Cmd
    /** Testo da scrivere nel campo selezionato sul display; submit = premi Invio (per le ricerche) */
    data class Type(val text: String, val submit: Boolean) : Cmd
    data object Back : Cmd
    data object Home : Cmd
    data object Split : Cmd
    data object Consumption : Cmd
    data object Fuel : Cmd
    data object Errors : Cmd
    data object Engine : Cmd
    data object Weather : Cmd
    data object PlayPause : Cmd
    data object Next : Cmd
    data object Previous : Cmd
    data object SaveClip : Cmd
    data object Trip : Cmd
    data class Unknown(val text: String) : Cmd
}

/** Comandi vocali in italiano: riconoscimento a parole chiave, l'ordine dei controlli conta */
object VoiceCommands {
    private val open = Regex("""^(apri|avvia|lancia|fammi vedere)\s+(.+)$""")
    private val write = Regex("""^(scrivi|detta)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val search = Regex("""^cerca\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val fuelWords = listOf("benzin", "carburant", "distributor", "gasolio", "rifornimento")

    fun parse(text: String): Cmd {
        val t = text.lowercase(Locale.ITALY).trim()
        open.find(t)?.let { m ->
            val app = m.groupValues[2].removePrefix("l'").removePrefix("il ").removePrefix("la ").removePrefix("lo ").trim()
            return Cmd.Open(app)
        }
        // il testo dettato mantiene maiuscole e minuscole originali
        write.find(text.trim())?.let { return Cmd.Type(it.groupValues[2].trim(), false) }
        search.find(text.trim())?.let { m -> if (fuelWords.none { it in t }) return Cmd.Type(m.groupValues[1].trim(), true) }
        fun has(vararg words: String) = words.any { it in t }
        return when {
            has("indietro") -> Cmd.Back
            has("schermata principale", "tutte le app") || t == "home" -> Cmd.Home
            has("dividi", "schermo diviso", "affianca") -> Cmd.Split
            has("consum") -> Cmd.Consumption
            fuelWords.any { it in t } -> Cmd.Fuel
            has("error", "guast", "spia") -> Cmd.Errors
            has("temperatura", "motore", "batteria") -> Cmd.Engine
            has("meteo", "piov", "che tempo") -> Cmd.Weather
            has("pausa", "riprendi", "play", "ferma la musica") -> Cmd.PlayPause
            has("precedent") -> Cmd.Previous
            has("prossim", "successiv", "salta") -> Cmd.Next
            has("salva", "registra") -> Cmd.SaveClip
            has("viaggio", "quanti chilometri", "quanti km") -> Cmd.Trip
            else -> Cmd.Unknown(text)
        }
    }

    /** Indice dell'app il cui nome somiglia di più a quanto detto; null se nessuna */
    fun bestMatch(query: String, labels: List<String>): Int? {
        val q = norm(query)
        if (q.isEmpty()) return null
        val names = labels.map { norm(it) }
        names.indexOfFirst { it == q }.takeIf { it >= 0 }?.let { return it }
        names.indexOfFirst { it.startsWith(q) }.takeIf { it >= 0 }?.let { return it }
        names.indexOfFirst { it.contains(q) || (it.length >= 3 && q.contains(it)) }.takeIf { it >= 0 }?.let { return it }
        return null
    }

    private fun norm(s: String) = s.lowercase(Locale.ITALY).filter { it.isLetterOrDigit() }
}
