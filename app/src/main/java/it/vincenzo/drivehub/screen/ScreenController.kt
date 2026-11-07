package it.vincenzo.drivehub.screen

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import it.vincenzo.drivehub.BuildConfig
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.core.SpeedMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Schermo dell'auto come secondo tablet.
 * Il display virtuale (Shizuku) o lo specchio del telefono disegnano in un ImageReader;
 * ogni fotogramma viene copiato sulla superficie di Android Auto, così DriveHub può
 * coprirlo con il blocco di sicurezza quando il veicolo si muove.
 */
object ScreenController {
    enum class Mode { NONE, SHIZUKU, MIRROR }

    /** App che restano utilizzabili in movimento (oltre a quelle di categoria Mappe e Audio) */
    private val ALLOWED_MOVING = setOf(
        "com.google.android.apps.maps", "com.waze", "com.spotify.music",
        "com.google.android.apps.youtube.music", "deezer.android.app", "com.amazon.mp3", "com.apple.android.music",
    )

    private val thread = HandlerThread("schermo-auto").apply { start() }
    private val h = Handler(thread.looper)
    private val io = Executors.newSingleThreadExecutor()
    private val drawLock = Any()
    private val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
    private val filter = Paint(Paint.FILTER_BITMAP_FLAG)

    val status = MutableStateFlow("Schermo dell'auto non collegato")

    private var app: Context? = null
    @Volatile private var mode = Mode.NONE
    private var sessionActive = false

    // superficie di Android Auto (protetta da drawLock: il sistema la può ritirare in ogni momento)
    private var surface: Surface? = null
    private var surfW = 1
    private var surfH = 1

    // sorgente dei fotogrammi
    private var reader: ImageReader? = null
    private var dispW = 1
    private var dispH = 1
    private var dpi = 160
    private var current: Image? = null
    private var previous: Image? = null
    private var bitmap: Bitmap? = null

    @Volatile private var client: DisplayClient? = null
    private var args: Shizuku.UserServiceArgs? = null
    private var binding = false
    private var shizukuFailed = false

    private var projection: MediaProjection? = null
    private var mirror: VirtualDisplay? = null

    @Volatile private var topPackage: String? = null
    private var lastBlocked: Boolean? = null
    private var gesture: FloatArray? = null

    // ---------- chiamate dall'esterno (qualsiasi thread) ----------

    fun startSession(ctx: Context) {
        h.post {
            app = ctx.applicationContext
            sessionActive = true
            shizukuFailed = false
            h.removeCallbacks(tick)
            h.post(tick)
            Reports.log("Schermo: sessione Android Auto avviata")
        }
    }

    fun stopSession() {
        h.post {
            sessionActive = false
            h.removeCallbacks(tick)
            client?.let { c -> runCatching { c.release() } }
            client = null
            args?.let { a -> runCatching { Shizuku.unbindUserService(a, connection, true) } }
            binding = false
            mirror?.release(); mirror = null
            projection?.stop(); projection = null
            synchronized(drawLock) {
                bitmap = null
                previous?.close(); previous = null
                current?.close(); current = null
            }
            reader?.close(); reader = null
            mode = Mode.NONE
            topPackage = null
            lastBlocked = null
            status.value = "Schermo dell'auto non collegato"
            Reports.log("Schermo: sessione chiusa")
            Reports.flush("schermo")
        }
    }

    /** Dalla SurfaceCallback di Android Auto (thread principale) */
    fun attach(s: Surface, w: Int, hgt: Int, d: Int) {
        synchronized(drawLock) { surface = s; surfW = w.coerceAtLeast(1); surfH = hgt.coerceAtLeast(1) }
        h.post {
            if (reader == null) createReader(w, hgt, d)
            Reports.log("Schermo auto: superficie ${w}x$hgt, $d dpi")
            connect()
            render()
        }
    }

    /** Deve essere sincrono: dopo il ritorno Android Auto rilascia la superficie */
    fun detach() = synchronized(drawLock) { surface = null }

    fun tap(x: Float, y: Float) {
        h.post {
            if (mode == Mode.NONE || blocked()) return@post
            val dx = x * dispW / surfW
            val dy = y * dispH / surfH
            when (mode) {
                Mode.SHIZUKU -> client?.let { c -> io.execute { runCatching { c.tap(dx, dy) } } }
                Mode.MIRROR -> phonePoint(dx, dy)?.let { (px, py) -> TouchService.instance?.swipe(px, py, px, py, 50) }
                Mode.NONE -> {}
            }
        }
    }

    /** Android Auto manda solo spostamenti: si ricostruisce un trascinamento dal centro */
    fun scroll(distX: Float, distY: Float) {
        h.post {
            if (mode == Mode.NONE || blocked()) return@post
            val g = gesture ?: floatArrayOf(dispW / 2f, dispH / 2f, dispW / 2f, dispH / 2f).also { gesture = it }
            g[2] -= distX * dispW / surfW
            g[3] -= distY * dispH / surfH
            h.removeCallbacks(endGesture)
            h.postDelayed(endGesture, 150)
        }
    }

    fun back() {
        h.post {
            when (mode) {
                Mode.SHIZUKU -> client?.let { c -> io.execute { runCatching { c.key(KeyEvent.KEYCODE_BACK) } } }
                Mode.MIRROR -> TouchService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Mode.NONE -> {}
            }
        }
    }

    fun home() {
        h.post {
            val ctx = app ?: return@post
            when (mode) {
                Mode.SHIZUKU -> launchNow(ComponentName(ctx, LauncherActivity::class.java))
                Mode.MIRROR -> TouchService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                Mode.NONE -> {}
            }
        }
    }

    /** Dal launcher sul display dell'auto */
    fun launch(component: ComponentName) = h.post { launchNow(component) }

    /** Dal MirrorService dopo il consenso alla registrazione dello schermo */
    fun setProjection(ctx: Context, mp: MediaProjection) {
        h.post {
            if (app == null) app = ctx.applicationContext
            projection?.stop()
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    h.post {
                        if (projection !== mp) return@post
                        projection = null
                        mirror?.release(); mirror = null
                        if (mode == Mode.MIRROR) mode = Mode.NONE
                        app?.stopService(Intent(app, MirrorService::class.java))
                        Reports.log("Specchio: terminato")
                        render()
                    }
                }
            }, h)
            Reports.log("Specchio: autorizzato")
            status.value = "Specchio pronto: si attiva quando apri DriveHub in Android Auto"
            connect()
        }
    }

    fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) {
        false
    }

    // ---------- interno (thread "schermo-auto") ----------

    private fun createReader(w: Int, hgt: Int, d: Int) {
        dispW = w.coerceAtLeast(1); dispH = hgt.coerceAtLeast(1); dpi = d.coerceAtLeast(120)
        reader = ImageReader.newInstance(dispW, dispH, PixelFormat.RGBA_8888, 4, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE).apply {
            setOnImageAvailableListener({ r -> onFrame(r) }, h)
        }
    }

    private fun onFrame(r: ImageReader) {
        val img = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val hb = img.hardwareBuffer
        val bmp = hb?.let { Bitmap.wrapHardwareBuffer(it, srgb) }
        hb?.close()
        synchronized(drawLock) {
            // il fotogramma precedente resta aperto finché la GPU può ancora leggerlo
            previous?.close()
            previous = current
            current = img
            bitmap = bmp
        }
        render()
    }

    private fun connect() {
        val ctx = app ?: return
        if (!sessionActive || mode != Mode.NONE || reader == null) return
        if (!shizukuFailed && shizukuReady()) {
            if (!binding) bindShizuku(ctx)
        } else if (projection != null) {
            startMirror()
        } else {
            status.value = "In attesa: avvia Shizuku o lo specchio dall'app DriveHub sul telefono"
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            h.post {
                binding = false
                val r = reader
                if (binder == null || r == null || !sessionActive) return@post
                try {
                    val c = DisplayClient(binder)
                    val id = c.create(r.surface, dispW, dispH, dpi)
                    client = c
                    mode = Mode.SHIZUKU
                    status.value = "Schermo dell'auto attivo (display $id)"
                    Reports.log("Schermo: display virtuale $id creato (${dispW}x$dispH, $dpi dpi)")
                    home()
                } catch (e: Exception) {
                    shizukuFailed = true
                    status.value = "Errore display virtuale: ${e.message}"
                    Reports.log("Schermo: creazione display fallita: ${e.message}")
                    connect() // passa allo specchio se disponibile
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            h.post {
                client = null
                binding = false
                if (mode == Mode.SHIZUKU) mode = Mode.NONE
                Reports.log("Shizuku: servizio schermo scollegato")
                render()
            }
        }
    }

    private fun bindShizuku(ctx: Context) {
        val a = args ?: Shizuku.UserServiceArgs(ComponentName(ctx.packageName, DisplayService::class.java.name))
            .daemon(false)
            .processNameSuffix("schermo")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
            .also { args = it }
        binding = true
        try {
            Shizuku.bindUserService(a, connection)
            Reports.log("Shizuku: avvio servizio schermo")
        } catch (e: Exception) {
            binding = false
            shizukuFailed = true
            Reports.log("Shizuku: avvio servizio fallito: ${e.message}")
        }
    }

    private fun startMirror() {
        val mp = projection ?: return
        val r = reader ?: return
        try {
            mirror = mp.createVirtualDisplay(
                "DriveHub-specchio", dispW, dispH, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, h
            )
            mode = Mode.MIRROR
            status.value = "Specchio del telefono attivo"
            Reports.log("Specchio: avviato (${dispW}x$dispH)")
        } catch (e: Exception) {
            projection = null
            status.value = "Specchio non avviato: ${e.message}"
            Reports.log("Specchio: errore ${e.message}")
        }
    }

    private fun launchNow(component: ComponentName) {
        val c = client ?: return
        topPackage = component.packageName
        io.execute {
            try {
                val out = c.launch(component.flattenToShortString())
                Reports.log("Schermo: avvio ${component.packageName} -> ${out.lines().lastOrNull().orEmpty()}")
            } catch (e: Exception) {
                Reports.log("Schermo: avvio ${component.packageName} fallito: ${e.message}")
            }
        }
        lastBlocked = null // ricalcola subito il blocco con la nuova app in primo piano
        tick.run()
    }

    private val endGesture = Runnable {
        val g = gesture ?: return@Runnable
        gesture = null
        val x2 = g[2].coerceIn(0f, dispW - 1f)
        val y2 = g[3].coerceIn(0f, dispH - 1f)
        when (mode) {
            Mode.SHIZUKU -> client?.let { c -> io.execute { runCatching { c.swipe(g[0], g[1], x2, y2, 200) } } }
            Mode.MIRROR -> {
                val a = phonePoint(g[0], g[1])
                val b = phonePoint(x2, y2)
                if (a != null && b != null) TouchService.instance?.swipe(a.first, a.second, b.first, b.second, 200)
            }
            Mode.NONE -> {}
        }
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            h.removeCallbacks(this)
            if (!sessionActive) return
            connect()
            val b = blocked()
            if (b != lastBlocked) {
                lastBlocked = b
                Reports.log(if (b) "Schermo: bloccato (${blockReason()})" else "Schermo: sbloccato")
                render()
            }
            h.postDelayed(this, 1000)
        }
    }

    private fun allowed(pkg: String?): Boolean {
        val ctx = app ?: return false
        if (pkg == null || pkg == ctx.packageName || pkg in ALLOWED_MOVING) return true
        val cat = try { ctx.packageManager.getApplicationInfo(pkg, 0).category } catch (_: Exception) { return false }
        return cat == ApplicationInfo.CATEGORY_MAPS || cat == ApplicationInfo.CATEGORY_AUDIO
    }

    /** Nello specchio non si sa quale app è aperta: in movimento si blocca sempre */
    private fun blocked() = SpeedMonitor.moving() && !(mode == Mode.SHIZUKU && allowed(topPackage))

    private fun blockReason() = if (SpeedMonitor.known()) "veicolo in movimento" else "velocità sconosciuta"

    @Suppress("DEPRECATION")
    private fun phonePoint(x: Float, y: Float): Pair<Float, Float>? {
        val ctx = app ?: return null
        val p = Point()
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p)
        return ScreenMath.mirrorToPhone(x, y, dispW, dispH, p.x, p.y)
    }

    private fun render() {
        synchronized(drawLock) {
            val s = surface ?: return
            if (!s.isValid) return
            val c = try { s.lockHardwareCanvas() } catch (_: Exception) { return }
            try {
                val bmp = bitmap
                val msg = when {
                    mode == Mode.NONE -> "DriveHub\n\nApri DriveHub sul telefono e avvia Shizuku\noppure lo specchio dello schermo"
                    blocked() -> if (SpeedMonitor.known())
                        "Veicolo in movimento\n\nVideo e app bloccati fino all'arresto.\nNavigatore e musica restano disponibili."
                    else "In attesa della velocità\n\nServono GPS o adattatore OBD\nper sbloccare lo schermo."
                    else -> null
                }
                if (msg == null && bmp != null) {
                    c.drawBitmap(bmp, null, Rect(0, 0, surfW, surfH), filter)
                } else {
                    c.drawColor(Color.BLACK)
                    if (msg != null) drawMessage(c, msg)
                }
            } finally {
                s.unlockCanvasAndPost(c)
            }
        }
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }

    private fun drawMessage(c: android.graphics.Canvas, msg: String) {
        textPaint.textSize = surfH / 14f
        val lines = msg.lines()
        val step = textPaint.textSize * 1.4f
        var y = surfH / 2f - step * (lines.size - 1) / 2
        for (line in lines) {
            c.drawText(line, surfW / 2f, y, textPaint)
            y += step
        }
    }
}
