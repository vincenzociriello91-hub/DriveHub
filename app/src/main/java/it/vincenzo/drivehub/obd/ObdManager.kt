package it.vincenzo.drivehub.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.core.SpeedMonitor
import it.vincenzo.drivehub.features.AlertMonitor
import it.vincenzo.drivehub.features.TripRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

data class ObdState(
    val status: String = "Non connesso",
    val connected: Boolean = false,
    val values: Map<String, Double> = emptyMap(),
    val dtcs: List<String>? = null,
)

/** Connessione universale ELM327 (Bluetooth classico o Wi-Fi) con lettura continua dei PID */
object ObdManager {
    /** PID letti a ogni giro, con etichetta e unità per lo schermo */
    val PIDS = linkedMapOf(
        "0C" to ("Giri motore" to "giri/min"),
        "0D" to ("Velocità" to "km/h"),
        "05" to ("Temperatura liquido" to "°C"),
        "0F" to ("Temperatura aria aspirata" to "°C"),
        "04" to ("Carico motore" to "%"),
        "11" to ("Farfalla" to "%"),
        "10" to ("Flusso aria (MAF)" to "g/s"),
        "2F" to ("Livello carburante" to "%"),
    )
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _state = MutableStateFlow(ObdState())
    val state: StateFlow<ObdState> = _state

    @Volatile private var running = false
    @Volatile private var dtcRequested = false
    @Volatile private var clearRequested = false
    /** Lettura automatica dei codici errore ogni 5 minuti (0 = subito alla connessione) */
    private var lastAutoDtc = 0L
    private var thread: Thread? = null
    private val unsupported = mutableSetOf<String>()

    private class Link(val input: InputStream, val output: OutputStream, val closer: Closeable)

    fun start(ctx: Context) {
        if (running) return
        val device = Prefs.obdDevice(ctx) ?: run {
            _state.value = ObdState("Scegli l'adattatore OBD nell'app sul telefono")
            return
        }
        running = true
        lastAutoDtc = 0L
        val app = ctx.applicationContext
        thread = Thread({ loop(app, device) }, "obd").apply { isDaemon = true; start() }
    }

    fun stop() {
        if (!running) return
        running = false
        thread?.interrupt()
        Reports.log("OBD fermato")
        Reports.flush("obd")
    }

    /** Cancellazione dei codici (modo 04): solo su conferma dell'utente e a veicolo fermo */
    fun requestClear() {
        clearRequested = true
        requestDtc()
    }

    /** dtcs torna null finché la nuova lettura non arriva */
    fun requestDtc() {
        _state.value = _state.value.copy(dtcs = null)
        dtcRequested = true
    }

    private fun loop(ctx: Context, device: String) {
        while (running) {
            var link: Link? = null
            try {
                _state.value = _state.value.copy(status = "Connessione all'adattatore…", connected = false)
                link = open(ctx, device)
                for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) send(link, cmd, 3000)
                send(link, "0100", 10_000) // la prima richiesta fa cercare il protocollo all'ELM
                Reports.log("OBD connesso ($device)")
                _state.value = _state.value.copy(status = "Connesso", connected = true)
                while (running) poll(link)
            } catch (e: Exception) {
                if (!running) break
                Reports.log("OBD errore: ${e.javaClass.simpleName}: ${e.message}")
                _state.value = _state.value.copy(status = "Errore: ${e.message ?: "connessione persa"}. Riprovo…", connected = false)
                try { Thread.sleep(5000) } catch (_: InterruptedException) { break }
            } finally {
                try { link?.closer?.close() } catch (_: Exception) {}
            }
        }
        _state.value = _state.value.copy(status = "Non connesso", connected = false)
    }

    private fun poll(link: Link) {
        val values = _state.value.values.toMutableMap()
        for (pid in PIDS.keys) {
            if (pid in unsupported) continue
            val raw = send(link, "01$pid", 2000)
            val v = ObdParser.dataBytes(raw, pid)?.let { ObdParser.decode(pid, it) }
            if (v == null && ObdParser.isError(raw)) {
                unsupported += pid
                Reports.log("PID 01$pid non supportato dall'auto: ${raw.trim()}")
            } else if (v != null) {
                values[pid] = v
                if (pid == "0D") SpeedMonitor.update(v) // la velocità OBD è la più affidabile per il blocco schermo
            }
        }
        ObdParser.voltage(send(link, "ATRV", 2000))?.let { values["V"] = it }
        val maf = values["10"]; val kmh = values["0D"]
        if (maf != null && kmh != null) {
            values["LH"] = ObdParser.litersPerHour(maf)
            TripRecorder.onFuelRate(values.getValue("LH"))
            ObdParser.litersPer100(maf, kmh)?.let { values["L100"] = it } ?: values.remove("L100")
        }
        AlertMonitor.check(values)
        var dtcs = _state.value.dtcs
        val now = System.currentTimeMillis()
        if (now - lastAutoDtc > 300_000) {
            lastAutoDtc = now
            dtcRequested = true
        }
        if (clearRequested) {
            clearRequested = false
            Reports.log("Cancellazione codici errore: ${send(link, "04", 5000).trim()}")
        }
        if (dtcRequested) {
            dtcRequested = false
            dtcs = ObdParser.dtcs(send(link, "03", 5000))
            Reports.log("Codici errore letti: ${dtcs.ifEmpty { listOf("nessuno") }}")
            AlertMonitor.onDtcs(dtcs)
        }
        values.forEach { (k, v) -> Reports.extras["obd_$k"] = "%.1f".format(v) }
        _state.value = _state.value.copy(values = values, dtcs = dtcs)
    }

    @SuppressLint("MissingPermission")
    private fun open(ctx: Context, device: String): Link {
        if (device == Prefs.OBD_WIFI) {
            val s = Socket()
            s.connect(InetSocketAddress("192.168.0.10", 35000), 5000)
            return Link(s.getInputStream(), s.getOutputStream(), s)
        }
        if (ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            error("permesso Bluetooth mancante")
        val adapter = ctx.getSystemService(BluetoothManager::class.java).adapter ?: error("Bluetooth assente")
        val dev = adapter.getRemoteDevice(device)
        val sock = try {
            dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        } catch (e: Exception) {
            // molti cloni ELM327 rispondono solo sul canale 1
            (dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1)
                as android.bluetooth.BluetoothSocket).also { it.connect() }
        }
        return Link(sock.inputStream, sock.outputStream, sock)
    }

    /** Invia un comando e legge fino al prompt '>' */
    private fun send(link: Link, cmd: String, timeoutMs: Long): String {
        while (link.input.available() > 0) link.input.read()
        link.output.write("$cmd\r".toByteArray()); link.output.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (link.input.available() > 0) {
                val c = link.input.read()
                if (c < 0) error("connessione chiusa")
                if (c == '>'.code) return sb.toString()
                sb.append(c.toChar())
            } else Thread.sleep(10)
        }
        return sb.toString()
    }
}
