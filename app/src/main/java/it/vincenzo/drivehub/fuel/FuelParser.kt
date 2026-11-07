package it.vincenzo.drivehub.fuel

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Station(val id: Int, val name: String, val brand: String, val address: String, val town: String, val lat: Double, val lon: Double)
data class Offer(val station: Station, val price: Double, val self: Boolean, val km: Double)

/**
 * Lettura degli open data MIMIT (prezzo_alle_8.csv e anagrafica_impianti_attivi.csv).
 * Il separatore è cambiato negli anni (";" poi "|"), quindi si rileva dall'intestazione,
 * e le colonne si cercano per nome.
 */
object FuelParser {
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = Math.toRadians(1.0)
        val a = sin((lat2 - lat1) * r / 2).pow(2) + cos(lat1 * r) * cos(lat2 * r) * sin((lon2 - lon1) * r / 2).pow(2)
        return 2 * 6371 * asin(sqrt(a))
    }

    private class Table(val sep: Char, val cols: Map<String, Int>)

    /** Trova la riga d'intestazione (salta "Estrazione del …") */
    private fun header(lines: Iterator<String>): Table? {
        while (lines.hasNext()) {
            val l = lines.next()
            if (!l.contains("idImpianto", ignoreCase = true)) continue
            val sep = if (l.count { it == '|' } > l.count { it == ';' }) '|' else ';'
            return Table(sep, l.split(sep).withIndex().associate { (i, n) -> n.trim().lowercase() to i })
        }
        return null
    }

    fun stationsNear(lines: Sequence<String>, lat: Double, lon: Double, radiusKm: Double): Map<Int, Station> {
        val it = lines.iterator()
        val t = header(it) ?: return emptyMap()
        fun col(name: String) = t.cols[name.lowercase()] ?: -1
        val cId = col("idImpianto"); val cName = col("Nome Impianto"); val cBrand = col("Bandiera")
        val cAddr = col("Indirizzo"); val cTown = col("Comune"); val cLat = col("Latitudine"); val cLon = col("Longitudine")
        val out = HashMap<Int, Station>()
        while (it.hasNext()) {
            val f = it.next().split(t.sep)
            if (f.size < t.cols.size) continue // riga rovinata da un separatore nel nome
            val la = f.getOrNull(cLat)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            val lo = f.getOrNull(cLon)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (km(lat, lon, la, lo) > radiusKm) continue
            val id = f.getOrNull(cId)?.trim()?.toIntOrNull() ?: continue
            out[id] = Station(id, f.getOrNull(cName)?.trim().orEmpty(), f.getOrNull(cBrand)?.trim().orEmpty(),
                f.getOrNull(cAddr)?.trim().orEmpty(), f.getOrNull(cTown)?.trim().orEmpty(), la, lo)
        }
        return out
    }

    /** Prezzo più basso per impianto del carburante scelto, ordinati dal più economico */
    fun cheapest(lines: Sequence<String>, stations: Map<Int, Station>, fuel: String, lat: Double, lon: Double): List<Offer> {
        val it = lines.iterator()
        val t = header(it) ?: return emptyList()
        fun col(name: String) = t.cols[name.lowercase()] ?: -1
        val cId = col("idImpianto"); val cDesc = col("descCarburante"); val cPrice = col("prezzo"); val cSelf = col("isSelf")
        val best = HashMap<Int, Offer>()
        while (it.hasNext()) {
            val f = it.next().split(t.sep)
            val st = f.getOrNull(cId)?.trim()?.toIntOrNull()?.let { stations[it] } ?: continue
            if (!f.getOrNull(cDesc)?.trim().equals(fuel, ignoreCase = true)) continue
            val price = f.getOrNull(cPrice)?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: continue
            if (price <= 0) continue
            if ((best[st.id]?.price ?: Double.MAX_VALUE) <= price) continue
            best[st.id] = Offer(st, price, f.getOrNull(cSelf)?.trim() == "1", km(lat, lon, st.lat, st.lon))
        }
        return best.values.sortedWith(compareBy({ it.price }, { it.km }))
    }
}
