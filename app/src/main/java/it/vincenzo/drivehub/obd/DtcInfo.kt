package it.vincenzo.drivehub.obd

import android.content.Context
import it.vincenzo.drivehub.core.Http
import it.vincenzo.drivehub.core.Reports
import java.io.File

/**
 * Spiegazione in italiano dei codici errore OBD-II (SAE J2012).
 * Prima l'elenco codici.tsv (ereditato da OBDTool, aggiornato da solo dal repository una volta a settimana),
 * poi i codici scritti qui, infine il sistema interessato ricavato dalla struttura del codice.
 */
object DtcInfo {
    private const val URL = "https://raw.githubusercontent.com/vincenzociriello91-hub/DriveHub/main/config/codici.tsv"
    private const val WEEK_MS = 7 * 24 * 3600_000L

    @Volatile private var db: Map<String, String> = emptyMap()

    /** Righe "CODICE<tab>significato<tab>fonte"; le righe con # sono commenti */
    fun parse(text: String): Map<String, String> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { it[0].trim().uppercase() to it[1].trim() } }
        .toMap()

    private fun copy(ctx: Context) = File(ctx.filesDir, "codici.tsv")

    /** Carica la copia aggiornata se c'è, altrimenti quella dentro l'app */
    fun load(ctx: Context) {
        val text = runCatching { copy(ctx).takeIf { it.exists() }?.readText() }.getOrNull()
            ?: runCatching { ctx.assets.open("codici.tsv").bufferedReader().use { it.readText() } }.getOrNull()
        text?.let { parse(it) }?.takeIf { it.isNotEmpty() }?.let { db = it }
    }

    /** In background: scarica l'elenco aggiornato al massimo una volta a settimana */
    fun refresh(ctx: Context) {
        val f = copy(ctx)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < WEEK_MS) return
        try {
            val tmp = File(ctx.cacheDir, "codici.tsv")
            Http.download(URL, tmp)
            val fresh = parse(tmp.readText())
            if (fresh.size >= 100) {  // protezione da file vuoti o rovinati
                tmp.copyTo(f, overwrite = true)
                db = fresh
                Reports.log("Elenco codici errore aggiornato: ${fresh.size} codici")
            }
        } catch (e: Exception) {
            Reports.log("Aggiornamento elenco codici non riuscito: ${e.message}")
        }
    }

    private val known = mapOf(
        "P0010" to "Fasatura variabile aspirazione: circuito dell'attuatore",
        "P0011" to "Fasatura variabile aspirazione: troppo anticipata",
        "P0012" to "Fasatura variabile aspirazione: troppo ritardata",
        "P0013" to "Fasatura variabile scarico: circuito dell'attuatore",
        "P0014" to "Fasatura variabile scarico: troppo anticipata",
        "P0016" to "Albero motore e albero a camme non sono allineati",
        "P0030" to "Riscaldatore della sonda lambda a monte: circuito",
        "P0036" to "Riscaldatore della sonda lambda a valle: circuito",
        "P0101" to "Sensore del flusso d'aria (MAF): valori fuori dal normale",
        "P0102" to "Sensore del flusso d'aria (MAF): segnale basso",
        "P0103" to "Sensore del flusso d'aria (MAF): segnale alto",
        "P0106" to "Sensore di pressione del collettore (MAP): valori fuori dal normale",
        "P0111" to "Sensore temperatura aria aspirata: valori fuori dal normale",
        "P0112" to "Sensore temperatura aria aspirata: segnale basso",
        "P0113" to "Sensore temperatura aria aspirata: segnale alto",
        "P0116" to "Sensore temperatura liquido: valori fuori dal normale",
        "P0117" to "Sensore temperatura liquido: segnale basso",
        "P0118" to "Sensore temperatura liquido: segnale alto",
        "P0121" to "Sensore posizione farfalla: valori fuori dal normale",
        "P0122" to "Sensore posizione farfalla: segnale basso",
        "P0123" to "Sensore posizione farfalla: segnale alto",
        "P0128" to "Termostato: il motore non arriva in temperatura (termostato bloccato aperto)",
        "P0130" to "Sonda lambda a monte: circuito",
        "P0131" to "Sonda lambda a monte: tensione bassa",
        "P0132" to "Sonda lambda a monte: tensione alta",
        "P0133" to "Sonda lambda a monte: risposta lenta",
        "P0134" to "Sonda lambda a monte: nessuna attività",
        "P0135" to "Sonda lambda a monte: riscaldatore",
        "P0136" to "Sonda lambda a valle: circuito",
        "P0137" to "Sonda lambda a valle: tensione bassa",
        "P0138" to "Sonda lambda a valle: tensione alta",
        "P0141" to "Sonda lambda a valle: riscaldatore",
        "P0171" to "Miscela troppo magra (poco carburante o aria in più, spesso una perdita di vuoto)",
        "P0172" to "Miscela troppo ricca (troppo carburante)",
        "P0190" to "Sensore pressione carburante: circuito",
        "P0191" to "Sensore pressione carburante: valori fuori dal normale",
        "P0217" to "Motore surriscaldato",
        "P0230" to "Pompa carburante: circuito",
        "P0234" to "Turbo: pressione troppo alta",
        "P0236" to "Sensore pressione turbo: valori fuori dal normale",
        "P0299" to "Turbo: pressione troppo bassa",
        "P0300" to "Mancate accensioni in più cilindri",
        "P0325" to "Sensore di battito: circuito",
        "P0335" to "Sensore albero motore: circuito",
        "P0340" to "Sensore albero a camme: circuito",
        "P0401" to "Valvola EGR: flusso insufficiente",
        "P0420" to "Catalizzatore: efficienza sotto la soglia",
        "P0440" to "Sistema vapori carburante (EVAP): guasto",
        "P0442" to "Sistema vapori carburante (EVAP): piccola perdita",
        "P0455" to "Sistema vapori carburante (EVAP): grande perdita (controlla il tappo del serbatoio)",
        "P0456" to "Sistema vapori carburante (EVAP): perdita molto piccola",
        "P0457" to "Tappo del serbatoio allentato o mancante",
        "P0500" to "Sensore velocità veicolo",
        "P0505" to "Controllo del minimo",
        "P0506" to "Minimo più basso del previsto",
        "P0507" to "Minimo più alto del previsto",
        "P0562" to "Tensione dell'impianto elettrico bassa",
        "P0563" to "Tensione dell'impianto elettrico alta",
        "P0571" to "Interruttore del pedale freno",
        "P0603" to "Centralina motore: errore di memoria",
        "P0606" to "Centralina motore: errore del processore",
        "P0700" to "Cambio: la centralina del cambio segnala un guasto",
        "U0100" to "Comunicazione persa con la centralina motore",
        "U0121" to "Comunicazione persa con la centralina ABS",
        "U0140" to "Comunicazione persa con la centralina carrozzeria",
        "U0155" to "Comunicazione persa con il quadro strumenti",
    )

    fun describe(code: String): String {
        val c = code.uppercase()
        db[c]?.let { return it }
        known[c]?.let { return it }
        if (c.length != 5) return "Codice non standard"
        val n = c.substring(3).toIntOrNull()
        when {
            c.startsWith("P030") || c.startsWith("P031") -> if (n != null && n in 1..12) return "Mancata accensione nel cilindro $n"
            c.startsWith("P020") || c.startsWith("P021") -> if (n != null && n in 1..12) return "Iniettore del cilindro $n: circuito"
            c.startsWith("P035") || c.startsWith("P036") -> if (n != null && n in 51..62) return "Bobina del cilindro ${n - 50}: circuito"
        }
        val maker = c[1] == '1' || (c[0] == 'P' && c[1] == '3' && c[2] < '4')
        val area = when (c[0]) {
            'P' -> when {
                c[1] == '0' && c[2] == 'A' -> "Sistema ibrido"
                else -> when (c[2]) {
                    '0', '1', '2' -> "Aria e carburante (sensori, sonde lambda, iniettori)"
                    '3' -> "Accensione o mancate accensioni"
                    '4' -> "Controllo emissioni (EGR, vapori carburante, catalizzatore)"
                    '5' -> "Velocità, minimo e ingressi ausiliari"
                    '6' -> "Centralina motore e uscite"
                    '7', '8', '9' -> "Cambio e trasmissione"
                    else -> "Motore"
                }
            }
            'B' -> "Carrozzeria (airbag, clima, luci, comfort)"
            'C' -> "Telaio (ABS, controllo stabilità, sterzo)"
            'U' -> "Comunicazione tra centraline"
            else -> "Sistema non riconosciuto"
        }
        return if (maker) "$area · codice specifico Ford: dettagli dall'officina" else area
    }
}
