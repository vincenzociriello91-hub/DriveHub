package it.vincenzo.drivehub

import it.vincenzo.drivehub.obd.ObdParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObdParserTest {
    @Test fun rpm() {
        val b = ObdParser.dataBytes("410C1AF8\r", "0C")!!
        assertEquals(1726.0, ObdParser.decode("0C", b)!!, 0.01)
    }

    @Test fun conSpazieSearching() {
        val b = ObdParser.dataBytes("SEARCHING...\r41 05 7B\r", "05")!!
        assertEquals(83.0, ObdParser.decode("05", b)!!, 0.01)
    }

    @Test fun noData() = assertNull(ObdParser.dataBytes("NO DATA\r", "2F"))

    @Test fun voltaggio() = assertEquals(12.6, ObdParser.voltage("12.6V")!!, 0.001)

    @Test fun consumoFermo() = assertNull(ObdParser.litersPer100(2.0, 0.0))

    @Test fun dtcCan() = assertEquals(listOf("P0133", "P0220"), ObdParser.dtcs("43020133 0220\r"))

    @Test fun dtcClassico() = assertEquals(listOf("P0133"), ObdParser.dtcs("43 01 33 00 00 00 00\r"))

    @Test fun dtcNessuno() = assertEquals(emptyList<String>(), ObdParser.dtcs("4300\r"))
}
