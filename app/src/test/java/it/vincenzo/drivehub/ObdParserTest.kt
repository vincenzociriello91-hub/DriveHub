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

    @Test fun dtcInAttesa() = assertEquals(listOf("P0133"), ObdParser.dtcs("47 01 33 00 00 00 00\r", 0x07))

    // VIN di prova inventato, su più righe come lo manda un ELM327 su CAN
    @Test fun vin() = assertEquals("WF0AXXGAHA1234567",
        ObdParser.vin("014\r0: 49 02 01 57 46 30\r1: 41 58 58 47 41 48 41\r2: 31 32 33 34 35 36 37\r"))

    @Test fun protocollo() = assertEquals("CAN 11 bit, 500 kbaud", ObdParser.protocol("A6\r"))
}
