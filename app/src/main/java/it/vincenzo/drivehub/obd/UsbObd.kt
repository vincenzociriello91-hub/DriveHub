package it.vincenzo.drivehub.obd

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/**
 * Adattatori ELM327 USB (chip FTDI, CH340, CP210x, PL2303) collegati al telefono con un cavo OTG.
 * Si usa quando il telefono è collegato all'auto senza fili (adattatore wireless per Android Auto),
 * così la presa USB del telefono resta libera.
 */
object UsbObd {
    private const val ACTION_PERMISSION = "it.vincenzo.drivehub.USB_OBD"
    // shortcut: velocità fissa 38400 (quella di fabbrica degli ELM327 USB); aggiungere 115200 se un adattatore non risponde
    private const val BAUD = 38400

    class Link(val input: InputStream, val output: OutputStream, val closer: Closeable)

    fun connected(ctx: Context): Boolean =
        UsbSerialProber.getDefaultProber().findAllDrivers(ctx.getSystemService(UsbManager::class.java)).isNotEmpty()

    fun open(ctx: Context): Link {
        val um = ctx.getSystemService(UsbManager::class.java)
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()
            ?: error("nessun adattatore OBD USB collegato")
        if (!um.hasPermission(driver.device)) {
            val intent = Intent(ACTION_PERMISSION).setPackage(ctx.packageName)
            um.requestPermission(driver.device, PendingIntent.getBroadcast(ctx, 0, intent, PendingIntent.FLAG_MUTABLE))
            error("consenti l'accesso all'adattatore USB nella finestra sul telefono")
        }
        val conn = um.openDevice(driver.device) ?: error("adattatore USB non apribile")
        val port = driver.ports.first()
        port.open(conn)
        port.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.dtr = true; port.rts = true }

        val input = object : InputStream() {
            private val buf = ByteArray(256)
            private var pos = 0
            private var len = 0
            private fun fill() {
                if (pos < len) return
                pos = 0
                len = port.read(buf, 20).coerceAtLeast(0)
            }
            override fun available(): Int { fill(); return len - pos }
            override fun read(): Int = if (available() > 0) buf[pos++].toInt() and 0xFF else -1
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = port.write(b.copyOfRange(off, off + len), 1000)
        }
        return Link(input, output) { port.close() }
    }
}
