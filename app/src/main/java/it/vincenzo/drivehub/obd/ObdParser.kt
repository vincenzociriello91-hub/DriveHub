package it.vincenzo.drivehub.obd

/** Decodifica pura delle risposte ELM327 (testabile senza hardware) */
object ObdParser {
    private val errors = listOf("NO DATA", "UNABLE", "ERROR", "STOPPED", "?", "BUS INIT")

    fun isError(raw: String) = errors.any { raw.uppercase().contains(it) }

    /** Byte di dati dopo "41<pid>", es. pid="0C"; null se assente o errore */
    fun dataBytes(raw: String, pid: String): IntArray? {
        if (isError(raw)) return null
        for (line in raw.uppercase().replace("SEARCHING...", "").split('\r', '\n')) {
            val hex = line.filter { it.isLetterOrDigit() }
            val i = hex.indexOf("41$pid")
            if (i < 0) continue
            val data = hex.substring(i + 4)
            if (data.length < 2 || !data.all { it in "0123456789ABCDEF" }) continue
            return IntArray(data.length / 2) { data.substring(it * 2, it * 2 + 2).toInt(16) }
        }
        return null
    }

    /** Valore in unità reali per i PID supportati */
    fun decode(pid: String, b: IntArray): Double? = when (pid) {
        "0C" -> if (b.size >= 2) (b[0] * 256 + b[1]) / 4.0 else null   // giri/min
        "0D" -> b[0].toDouble()                                       // km/h
        "05", "0F", "5C" -> b[0] - 40.0                               // °C
        "04", "11", "2F" -> b[0] * 100.0 / 255                        // %
        "10" -> if (b.size >= 2) (b[0] * 256 + b[1]) / 100.0 else null // MAF g/s
        else -> null
    }

    /** Tensione da "ATRV", es. "12.6V" */
    fun voltage(raw: String): Double? = Regex("""(\d+(\.\d+)?)\s*V""").find(raw.uppercase())?.groupValues?.get(1)?.toDouble()

    /** Numero di telaio dal modo 09 PID 02 (anche su più righe numerate "0:", "1:"…) */
    fun vin(raw: String): String? {
        if (isError(raw)) return null
        val hex = raw.uppercase().split('\r', '\n')
            .map { it.replace(Regex("^\\s*[0-9A-F]:"), "") }
            .joinToString("") { l -> l.filter { it in "0123456789ABCDEF" } }
        val i = hex.indexOf("4902")
        if (i < 0) return null
        val text = hex.substring(i + 6).chunked(2).filter { it.length == 2 }
            .map { it.toInt(16).toChar() }.filter { it.isLetterOrDigit() }.joinToString("")
        return text.takeLast(17).takeIf { it.length == 17 }
    }

    /** Nome del protocollo dalla risposta ad ATDPN (es. "A6" = automatico, CAN 11 bit 500 kbaud) */
    fun protocol(raw: String): String? = when (raw.trim().removePrefix("A").firstOrNull()) {
        '1' -> "SAE J1850 PWM"
        '2' -> "SAE J1850 VPW"
        '3' -> "ISO 9141-2"
        '4' -> "ISO 14230 KWP2000 (5 baud)"
        '5' -> "ISO 14230 KWP2000 (veloce)"
        '6' -> "CAN 11 bit, 500 kbaud"
        '7' -> "CAN 29 bit, 500 kbaud"
        '8' -> "CAN 11 bit, 250 kbaud"
        '9' -> "CAN 29 bit, 250 kbaud"
        'A' -> "SAE J1939"
        else -> null
    }

    /** Litri/ora da MAF per motore a benzina (stechiometrico 14.7, densità 745 g/l) */
    fun litersPerHour(mafGs: Double) = mafGs * 3600 / (14.7 * 745)

    /** l/100 km; null sotto i 3 km/h (a veicolo fermo non ha senso) */
    fun litersPer100(mafGs: Double, kmh: Double) = if (kmh < 3) null else litersPerHour(mafGs) * 100 / kmh

    /** Codici errore dalla risposta ai modi 03, 07 o 0A (formato CAN con contatore o classico a coppie) */
    fun dtcs(raw: String, mode: Int = 0x03): List<String> {
        if (isError(raw)) return emptyList()
        val prefix = "%02X".format(0x40 + mode)
        val out = linkedSetOf<String>()
        for (line in raw.uppercase().split('\r', '\n')) {
            val hex = line.filter { it in "0123456789ABCDEF" }
            val i = hex.indexOf(prefix)
            if (i < 0) continue
            var bytes = hex.substring(i + 2).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
            if (bytes.isNotEmpty() && bytes.size - 1 == bytes[0] * 2) bytes = bytes.drop(1) // CAN: primo byte = numero codici
            bytes.chunked(2).filter { it.size == 2 && (it[0] != 0 || it[1] != 0) }.forEach { (a, b) ->
                out += "${"PCBU"[a shr 6]}${(a shr 4) and 3}${"%X%02X".format(a and 0xF, b)}"
            }
        }
        return out.toList()
    }
}
