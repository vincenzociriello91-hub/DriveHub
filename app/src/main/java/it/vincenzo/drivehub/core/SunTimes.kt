package it.vincenzo.drivehub.core

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Altezza del sole sull'orizzonte, senza internet: serve al tema giorno/notte del display dell'auto */
object SunTimes {
    /** Sopra questa altezza (gradi) c'è abbastanza luce per il tema chiaro; all'alba e al tramonto resta scuro */
    const val LIGHT_ABOVE = 2.0

    // shortcut: senza posizione si usa il centro Italia (alba e tramonto sbagliano al massimo di circa mezz'ora)
    const val DEFAULT_LAT = 41.9
    const val DEFAULT_LON = 12.5

    fun isDay(lat: Double, lon: Double, millis: Long) = elevation(lat, lon, millis) > LIGHT_ABOVE

    /** Formula astronomica semplificata (precisione di qualche decimo di grado, più che sufficiente qui) */
    fun elevation(lat: Double, lon: Double, millis: Long): Double {
        val d = millis / 86_400_000.0 + 2440587.5 - 2451545.0 // giorni dal 1° gennaio 2000
        val g = Math.toRadians((357.529 + 0.98560028 * d) % 360)
        val q = (280.459 + 0.98564736 * d) % 360
        val l = Math.toRadians(q + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val e = Math.toRadians(23.439 - 0.00000036 * d)
        val ra = atan2(cos(e) * sin(l), cos(l))
        val dec = asin(sin(e) * sin(l))
        val gmst = (18.697374558 + 24.06570982441908 * d) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(ha)))
    }
}
