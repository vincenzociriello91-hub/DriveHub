package it.vincenzo.drivehub

import it.vincenzo.drivehub.core.SunTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SunTimesTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private val lat = 41.9
    private val lon = 12.5

    @Test fun mezzogiornoDEstateSoleAlto() {
        assertEquals(71.4, SunTimes.elevation(lat, lon, at("2026-06-21T11:00:00Z")), 1.0)
        assertTrue(SunTimes.isDay(lat, lon, at("2026-06-21T11:00:00Z")))
    }

    @Test fun mezzogiornoDInvernoSoleBasso() {
        assertEquals(24.6, SunTimes.elevation(lat, lon, at("2026-12-21T11:00:00Z")), 1.0)
    }

    @Test fun notteScuro() {
        assertFalse(SunTimes.isDay(lat, lon, at("2026-06-21T22:00:00Z")))
        assertFalse(SunTimes.isDay(lat, lon, at("2026-11-08T23:00:00Z")))
    }

    @Test fun dopoIlTramontoTornaScuro() {
        // a Roma l'8 novembre il sole tramonta verso le 17 (16 UTC)
        assertTrue(SunTimes.isDay(lat, lon, at("2026-11-08T15:30:00Z")))
        assertFalse(SunTimes.isDay(lat, lon, at("2026-11-08T16:30:00Z")))
        assertFalse(SunTimes.isDay(lat, lon, at("2026-11-08T05:30:00Z")))
        assertTrue(SunTimes.isDay(lat, lon, at("2026-11-08T06:30:00Z")))
    }
}
