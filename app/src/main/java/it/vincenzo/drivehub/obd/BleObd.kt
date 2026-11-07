package it.vincenzo.drivehub.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Adattatori ELM327 Bluetooth Low Energy (Vgate iCar Pro BLE, OBDLink CX, Viecar, cloni FFF0/FFE0…).
 * Non c'è un servizio standard: si usa il primo servizio non di sistema che ha una caratteristica
 * di notifica (risposte) e una di scrittura (comandi).
 */
@SuppressLint("MissingPermission")
object BleObd {
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val SYSTEM = listOf("00001800", "00001801", "0000180a", "0000180f")

    /** Cerca adattatori BLE per qualche secondo; onFound(nome, indirizzo) può arrivare più volte */
    fun scan(ctx: Context, onFound: (String, String) -> Unit, durationMs: Long = 8000) {
        val scanner = ctx.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner ?: return
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.device.name ?: result.scanRecord?.deviceName ?: return
                onFound(name, result.device.address)
            }
        }
        scanner.startScan(cb)
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { scanner.stopScan(cb) } }, durationMs)
    }

    class Link(val input: InputStream, val output: OutputStream, val closer: Closeable)

    @Suppress("DEPRECATION") // le API vecchie servono solo sotto Android 13
    fun open(ctx: Context, mac: String): Link {
        val device = ctx.getSystemService(BluetoothManager::class.java).adapter?.getRemoteDevice(mac) ?: error("Bluetooth assente")
        val incoming = LinkedBlockingQueue<Byte>()
        val ready = CountDownLatch(1)
        var written = CountDownLatch(1)
        var failure: String? = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) g.discoverServices()
                else { failure = "BLE disconnesso (stato $status)"; ready.countDown() }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = ready.countDown()
            override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = written.countDown()
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
                value.forEach { incoming.put(it) }
            }
            @Deprecated("Android 12 e precedenti")
            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                c.value?.forEach { incoming.put(it) }
            }
        }

        val gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS) || failure != null) {
            gatt.close()
            error(failure ?: "adattatore BLE non risponde")
        }
        val (notify, write) = gatt.services
            .filter { s -> SYSTEM.none { s.uuid.toString().startsWith(it) } }
            .firstNotNullOfOrNull { s ->
                val n = s.characteristics.firstOrNull { it.properties and (PROP_NOTIFY or PROP_INDICATE) != 0 }
                val w = s.characteristics.firstOrNull { it.properties and (PROP_WRITE or PROP_WRITE_NR) != 0 }
                if (n != null && w != null) n to w else null
            } ?: run { gatt.close(); error("servizio OBD BLE non trovato") }

        gatt.setCharacteristicNotification(notify, true)
        notify.getDescriptor(CCCD)?.let { d ->
            val v = if (notify.properties and PROP_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(d, v)
            else { d.value = v; gatt.writeDescriptor(d) }
            Thread.sleep(300)
        }
        val writeType = if (write.properties and PROP_WRITE_NR != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val input = object : InputStream() {
            override fun available() = incoming.size
            override fun read(): Int = (incoming.poll(2, TimeUnit.SECONDS) ?: return -1).toInt() and 0xFF
        }
        val output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                // pacchetti da 20 byte: il minimo che ogni adattatore BLE accetta
                b.copyOfRange(off, off + len).toList().chunked(20).forEach { chunk ->
                    written = CountDownLatch(1)
                    val bytes = chunk.toByteArray()
                    if (Build.VERSION.SDK_INT >= 33) gatt.writeCharacteristic(write, bytes, writeType)
                    else { write.writeType = writeType; write.value = bytes; gatt.writeCharacteristic(write) }
                    if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) written.await(2, TimeUnit.SECONDS)
                    else Thread.sleep(15)
                }
            }
        }
        return Link(input, output) { gatt.disconnect(); gatt.close() }
    }

    private const val PROP_NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
    private const val PROP_INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
    private const val PROP_WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
    private const val PROP_WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
}
