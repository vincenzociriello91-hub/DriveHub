package it.vincenzo.drivehub

import android.app.ApplicationExitInfo
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.features.AlertRules
import it.vincenzo.drivehub.features.Cmd
import it.vincenzo.drivehub.features.Refuel
import it.vincenzo.drivehub.features.Refuels
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

    @Test fun motivoUscita() {
        assertEquals("app bloccata (non rispondeva)", Reports.exitReasonText(ApplicationExitInfo.REASON_ANR))
        assertEquals("memoria del telefono esaurita", Reports.exitReasonText(ApplicationExitInfo.REASON_LOW_MEMORY))
    }

    @Test fun elencoCodici() =
        assertEquals(mapOf("P0420" to "Catalizzatore"), DtcInfo.parse("# commento\nP0420\tCatalizzatore\tSAE J2012\n"))

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

    @Test fun comandiNuovi() {
        assertEquals(Cmd.Refuel(30.0, 1.85), VoiceCommands.parse("Ho messo 30 litri a 1,85"))
        assertEquals(Cmd.Parked, VoiceCommands.parse("dove ho parcheggiato"))
        assertEquals(Cmd.Maintenance, VoiceCommands.parse("quanto manca al tagliando"))
        assertEquals(Cmd.Range, VoiceCommands.parse("quanti chilometri mi restano"))
        assertEquals(Cmd.Range, VoiceCommands.parse("quanta benzina mi resta"))
        assertEquals(Cmd.Trip, VoiceCommands.parse("quanti chilometri ho fatto nel viaggio"))
    }

    @Test fun rifornimentoAVoce() {
        assertEquals(30.0 to 1.85, Refuels.parse("ho messo 30 litri a 1,85"))
        assertEquals(25.0 to 1.8, Refuels.parse("pieno di 45 euro a 1,80")!!.let { (l, p) -> Math.round(l * 10) / 10.0 to p })
        assertEquals(40.0 to null, Refuels.parse("fatto il pieno 40 litri"))
        assertNull(Refuels.parse("quanti litri consumo")) // nessuna parola del rifornimento
        assertNull(Refuels.parse("ho messo 300 litri")) // oltre il serbatoio
    }

    @Test fun consumoEAutonomia() {
        val r = listOf(Refuel(3, 30.0, null, 1600.0), Refuel(2, 35.0, null, 1000.0), Refuel(1, 40.0, null, 400.0))
        assertEquals(65.0 * 100 / 1200, Refuels.realL100(r)!!, 0.001) // il primo pieno non conta, serve solo da partenza
        assertNull(Refuels.realL100(r.take(1)))
        assertEquals(630.0, Refuels.rangeKm(null, 5.0, 210.0)!!, 0.001) // 42 l - 10,5 l = 31,5 l a 5 l/100
        assertEquals(420.0, Refuels.rangeKm(50.0, 5.0, null)!!, 0.001) // 50 % di 42 l = 21 l
        assertNull(Refuels.rangeKm(null, null, 100.0))
    }

    @Test fun scadenze() {
        val due = AlertRules.parseDate("28/02/2027")!!
        assertEquals(0L, AlertRules.parseDate(""))
        assertNull(AlertRules.parseDate("31/02/2027"))
        assertNull(AlertRules.deadline("bollo", due, due - 31))
        assertTrue(AlertRules.deadline("revisione", due, due - 10)!!.contains("tra 10 giorni"))
        assertTrue(AlertRules.deadline("revisione", due, due)!!.contains("oggi"))
        assertTrue(AlertRules.deadline("assicurazione", due, due + 3)!!.contains("passata"))
        assertNull(AlertRules.deadline("bollo", 0, due))
        assertEquals("28/02/2027", AlertRules.formatDate(due))
    }
}
