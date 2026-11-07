package it.vincenzo.drivehub.screen

object ScreenMath {
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
}
