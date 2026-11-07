package it.vincenzo.drivehub.fuel

import android.content.Context
import it.vincenzo.drivehub.core.Http
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import java.io.File

object FuelRepo {
    private const val BASE = "https://www.mimit.gov.it/images/exportCSV/"
    private const val MAX_AGE_MS = 6 * 3600_000L

    private fun fresh(ctx: Context, name: String): File {
        val f = File(ctx.cacheDir, name)
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > MAX_AGE_MS) {
            try {
                Http.download(BASE + name, f)
                Reports.log("Scaricato $name (${f.length() / 1024} KB)")
            } catch (e: Exception) {
                // senza rete si usa la copia vecchia, se c'è
                if (!f.exists()) throw e
                Reports.log("Download $name fallito, uso la copia del ${java.util.Date(f.lastModified())}: ${e.message}")
            }
        }
        return f
    }

    /** Da chiamare in background: scarica (se serve) e cerca i più economici entro radiusKm */
    fun search(ctx: Context, fuel: String, lat: Double, lon: Double, radiusKm: Double = 10.0): List<Offer> {
        val stations = fresh(ctx, "anagrafica_impianti_attivi.csv").bufferedReader().useLines {
            FuelParser.stationsNear(it, lat, lon, radiusKm)
        }
        val offers = fresh(ctx, "prezzo_alle_8.csv").bufferedReader().useLines {
            FuelParser.cheapest(it, stations, fuel, lat, lon)
        }
        offers.firstOrNull()?.let { Prefs.setLastFuel(ctx, "${it.station.brand} ${it.station.town}".trim(), it.price) }
        Reports.log("Ricerca $fuel: ${stations.size} impianti entro $radiusKm km, ${offers.size} con prezzo; migliore ${offers.firstOrNull()?.price}")
        return offers
    }
}
