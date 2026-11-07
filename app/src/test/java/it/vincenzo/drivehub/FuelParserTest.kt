package it.vincenzo.drivehub

import it.vincenzo.drivehub.fuel.FuelParser
import org.junit.Assert.assertEquals
import org.junit.Test

class FuelParserTest {
    // Dati sintetici nel formato MIMIT
    private val stations = """
        Estrazione del 2026-11-07
        idImpianto|Gestore|Bandiera|Tipo Impianto|Nome Impianto|Indirizzo|Comune|Provincia|Latitudine|Longitudine
        1|G1|Agip|Stradale|Uno|Via A|Napoli|NA|40.85|14.27
        2|G2|IP|Stradale|Due|Via B|Napoli|NA|40.86|14.28
        3|G3|Q8|Stradale|Lontano|Via C|Roma|RM|41.90|12.49
    """.trimIndent().lineSequence()

    private val prices = """
        Estrazione del 2026-11-07
        idImpianto;descCarburante;prezzo;isSelf;dtComu
        1;Benzina;1.799;1;07/11/2026 07:00:00
        1;Benzina;1.899;0;07/11/2026 07:00:00
        2;Benzina;1.759;1;07/11/2026 07:00:00
        2;Gasolio;1.659;1;07/11/2026 07:00:00
        3;Benzina;1.500;1;07/11/2026 07:00:00
    """.trimIndent().lineSequence()

    @Test fun piuEconomicoVicino() {
        val near = FuelParser.stationsNear(stations, 40.85, 14.27, 10.0)
        assertEquals(setOf(1, 2), near.keys)
        val offers = FuelParser.cheapest(prices, near, "benzina", 40.85, 14.27)
        assertEquals(listOf(2, 1), offers.map { it.station.id })
        assertEquals(1.799, offers[1].price, 0.0001) // per lo stesso impianto vince il prezzo più basso
    }
}
