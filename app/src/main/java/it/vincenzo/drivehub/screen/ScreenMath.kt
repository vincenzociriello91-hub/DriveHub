package it.vincenzo.drivehub.screen

object ScreenMath {
    /**
     * Regola del blocco in movimento (testata): navigatori e musica sempre ammessi;
     * in modalità passeggero anche le altre app, ma mai video e giochi.
     */
    fun blocked(moving: Boolean, shizuku: Boolean, essential: Boolean, passenger: Boolean, videoOrGame: Boolean) =
        moving && !(shizuku && (essential || (passenger && !videoOrGame)))

    /**
     * Nello specchio il telefono è rimpicciolito e centrato (con bande nere) nel display dell'auto:
     * converte un punto del display (dw x dh) nel punto del telefono (pw x ph); null se cade sulle bande.
     */
    fun mirrorToPhone(x: Float, y: Float, dw: Int, dh: Int, pw: Int, ph: Int): Pair<Float, Float>? {
        val s = minOf(dw.toFloat() / pw, dh.toFloat() / ph)
        val ox = (dw - pw * s) / 2
        val oy = (dh - ph * s) / 2
        val px = (x - ox) / s
        val py = (y - oy) / s
        return if (px in 0f..pw.toFloat() && py in 0f..ph.toFloat()) px to py else null
    }

    /**
     * Fattore (al massimo 1) che fa stare la riga più larga del cruscotto nello spazio disponibile:
     * sugli schermi stretti come il SYNC 2.5 della Fiesta (800x480) il testo si rimpicciolisce invece di sovrapporsi.
     */
    fun fitScale(widest: Float, available: Float) = if (widest <= 0f) 1f else minOf(1f, available / widest)
}
