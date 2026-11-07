package it.vincenzo.drivehub

import it.vincenzo.drivehub.core.SpeedGate
import it.vincenzo.drivehub.screen.ScreenMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTest {
    @Test fun centroSpecchio() {
        val (x, y) = ScreenMath.mirrorToPhone(400f, 240f, 800, 480, 1080, 2400)!!
        assertEquals(540f, x, 0.5f)
        assertEquals(1200f, y, 0.5f)
    }

    @Test fun bandaNeraSpecchio() = assertNull(ScreenMath.mirrorToPhone(10f, 240f, 800, 480, 1080, 2400))

    @Test fun regolaBlocco() {
        // fermo: mai bloccato
        assertFalse(ScreenMath.blocked(false, true, false, false, true))
        // in movimento: navigatore/musica ammessi, il resto coperto
        assertFalse(ScreenMath.blocked(true, true, true, false, false))
        assertTrue(ScreenMath.blocked(true, true, false, false, false))
        // passeggero: sblocca le app normali ma mai video e giochi
        assertFalse(ScreenMath.blocked(true, true, false, true, false))
        assertTrue(ScreenMath.blocked(true, true, false, true, true))
        // specchio: in movimento sempre coperto
        assertTrue(ScreenMath.blocked(true, false, true, true, false))
    }

    @Test fun isteresiVelocita() {
        val g = SpeedGate()
        g.update(4.0); assertFalse(g.moving)
        g.update(6.0); assertTrue(g.moving)
        g.update(4.0); assertTrue(g.moving)   // resta bloccato finché non scende sotto 3
        g.update(2.0); assertFalse(g.moving)
    }
}
