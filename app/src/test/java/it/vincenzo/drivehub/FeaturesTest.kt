package it.vincenzo.drivehub

import it.vincenzo.drivehub.features.AlertRules
import it.vincenzo.drivehub.features.Cmd
import it.vincenzo.drivehub.features.VoiceCommands
import it.vincenzo.drivehub.obd.DtcInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    @Test fun comandiVocali() {
        assertEquals(Cmd.Open("youtube"), VoiceCommands.parse("Apri YouTube"))
        assertEquals(Cmd.Consumption, VoiceCommands.parse("quanto sto consumando"))
        assertEquals(Cmd.Fuel, VoiceCommands.parse("dov'è la benzina più economica"))
        assertEquals(Cmd.Errors, VoiceCommands.parse("ci sono errori nel motore"))
        assertEquals(Cmd.PlayPause, VoiceCommands.parse("metti in pausa"))
        assertTrue(VoiceCommands.parse("raccontami una barzelletta") is Cmd.Unknown)
        assertEquals(Cmd.Type("Ciao Marco", false), VoiceCommands.parse("Scrivi Ciao Marco"))
        assertEquals(Cmd.Type("canzoni rock", true), VoiceCommands.parse("cerca canzoni rock"))
        assertEquals(Cmd.Fuel, VoiceCommands.parse("cerca la benzina"))
    }

    @Test fun descrizioneCodici() {
        assertEquals("Catalizzatore: efficienza sotto la soglia", DtcInfo.describe("P0420"))
        assertTrue(DtcInfo.describe("P0302").contains("cilindro 2"))
        assertTrue(DtcInfo.describe("P0352").contains("cilindro 2"))
        assertTrue(DtcInfo.describe("P1234").contains("Ford"))
        assertTrue(DtcInfo.describe("C0035").startsWith("Telaio"))
    }

    @Test fun nomeApp() {
        val apps = listOf("Maps", "YouTube Music", "YouTube", "Spotify: Music and Podcasts")
        assertEquals(2, VoiceCommands.bestMatch("youtube", apps))
        assertEquals(3, VoiceCommands.bestMatch("spotify", apps))
        assertNull(VoiceCommands.bestMatch("netflix", apps))
    }

    @Test fun avvisi() {
        assertTrue(AlertRules.check(mapOf("05" to 112.0)).any { it.first == "caldo" })
        assertTrue(AlertRules.check(mapOf("V" to 11.6, "0C" to 800.0)).any { it.first == "batteria" })
        assertTrue(AlertRules.check(mapOf("V" to 11.6, "0C" to 0.0)).isEmpty()) // a motore spento non è un guasto
        assertTrue(AlertRules.check(mapOf("V" to 12.4, "0C" to 2000.0)).isEmpty()) // ricarica intelligente Ford
    }

    @Test fun codiciNuovi() {
        assertEquals(listOf("P0133"), AlertRules.newCodes(null, listOf("P0133")))
        assertEquals(listOf("P0300"), AlertRules.newCodes(setOf("P0133"), listOf("P0133", "P0300")))
    }
}
