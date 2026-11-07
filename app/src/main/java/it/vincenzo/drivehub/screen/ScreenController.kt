package it.vincenzo.drivehub.screen

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
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
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.features.Speech
import it.vincenzo.drivehub.features.TripRecorder
import it.vincenzo.drivehub.obd.ObdManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private const val SPLIT = 0.62f
    private const val LONG_MS = 800L

    /** App che restano utilizzabili in movimento (oltre a quelle di categoria Mappe e Audio) */
    /** App video o di gioco note (oltre alle categorie Video e Giochi): in movimento sempre coperte */
    private val VIDEO_APPS = setOf(
        "com.google.android.youtube", "com.netflix.mediaclient", "com.amazon.avod.thirdpartyclient",
        "com.disney.disneyplus", "com.zhiliaoapp.musically", "com.instagram.android", "tv.twitch.android.app",
        "com.google.android.apps.youtube.kids",
    )

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
    /** larghezza del display intero; con lo schermo diviso il display virtuale ne usa il 62% */
    private var fullW = 1
    private var split = false
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
    /** Il prossimo tocco diventa una pressione lunga (Android Auto non la invia) */
    @Volatile private var longPressNext = false

    /**
     * Modalità passeggero: in movimento sblocca le app che non sono video o giochi (tocchi, tastiera, dettatura).
     * Si attiva solo con conferma esplicita e si spegne alla fine di ogni viaggio.
     */
    @Volatile var passengerMode = false
        private set

    fun setPassenger(on: Boolean) {
        passengerMode = on
        Reports.log(if (on) "Modalità passeggero attivata (confermata)" else "Modalità passeggero spenta")
        h.post { lastBlocked = null; tick.run() }
    }

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
            split = false
            passengerMode = false // a fine viaggio la modalità passeggero si spegne sempre
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
            if (reader == null) {
                fullW = w.coerceAtLeast(1)
                createReader(w, hgt, d)
            }
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
            val pw = paneW()
            if (x >= pw) return@post // tocco sul pannello cruscotto
            val dx = x * dispW / pw
            val dy = y * dispH / surfH
            val long = longPressNext
            longPressNext = false
            when (mode) {
                Mode.SHIZUKU -> client?.let { c ->
                    io.execute { runCatching { if (long) c.swipe(dx, dy, dx, dy, LONG_MS) else c.tap(dx, dy) } }
                }
                Mode.MIRROR -> phonePoint(dx, dy)?.let { (px, py) ->
                    TouchService.instance?.swipe(px, py, px, py, if (long) LONG_MS else 50)
                }
                Mode.NONE -> {}
            }
        }
    }

    /** Android Auto manda solo spostamenti: si ricostruisce un trascinamento dal centro */
    fun scroll(distX: Float, distY: Float) {
        h.post {
            if (mode == Mode.NONE || blocked()) return@post
            val g = gesture ?: floatArrayOf(dispW / 2f, dispH / 2f, dispW / 2f, dispH / 2f).also { gesture = it }
            g[2] -= distX * dispW / paneW()
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

    fun isShizuku() = mode == Mode.SHIZUKU

    fun armLongPress() {
        longPressNext = true
    }

    /**
     * Dettatura nel campo di testo selezionato sul display: con Shizuku si incolla dagli appunti
     * (funziona anche con lettere accentate), nello specchio si usa l'accessibilità.
     */
    fun typeText(text: String, submit: Boolean) {
        h.post {
            val ctx = app ?: return@post
            if (mode == Mode.NONE) { Speech.say("Nessuno schermo DriveHub attivo"); return@post }
            if (blocked()) { Speech.say("Non posso scrivere in quest'app mentre guidi"); return@post }
            when (mode) {
                Mode.SHIZUKU -> {
                    val c = client ?: return@post
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("DriveHub", text))
                    io.execute {
                        runCatching {
                            c.key(KeyEvent.KEYCODE_PASTE)
                            if (submit) c.key(KeyEvent.KEYCODE_ENTER)
                        }.onFailure { Reports.log("Dettatura fallita: ${it.message}") }
                    }
                }
                Mode.MIRROR -> if (TouchService.instance?.typeText(text, submit) != true) {
                    Speech.say("Tocca prima il campo di testo, poi riprova")
                }
                Mode.NONE -> {}
            }
            Reports.log("Dettatura (${text.length} caratteri${if (submit) ", invio" else ""})")
        }
    }

    /** Schermo diviso: app a sinistra (display virtuale ristretto), cruscotto DriveHub a destra */
    fun toggleSplit() {
        h.post {
            val c = client
            if (mode != Mode.SHIZUKU || c == null) {
                Speech.say("Lo schermo diviso funziona con Shizuku attivo")
                return@post
            }
            val on = !split
            val w = if (on) (fullW * SPLIT).toInt() else fullW
            val old = reader
            val oldW = dispW
            createReader(w, dispH, dpi)
            try {
                c.resize(reader!!.surface, w, dispH, dpi)
            } catch (e: Exception) {
                reader?.close()
                reader = old
                dispW = oldW
                Reports.log("Schermo diviso non riuscito: ${e.message}")
                return@post
            }
            split = on
            synchronized(drawLock) {
                bitmap = null
                previous?.close(); previous = null
                current?.close(); current = null
            }
            old?.close()
            Reports.log("Schermo diviso ${if (on) "attivo" else "spento"} (app ${w}x$dispH)")
            render()
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
            } else if (split) render() // aggiorna il cruscotto ogni secondo
            h.postDelayed(this, 1000)
        }
    }

    private fun allowed(pkg: String?): Boolean {
        val ctx = app ?: return false
        if (pkg == null || pkg == ctx.packageName || pkg in ALLOWED_MOVING) return true
        val cat = try { ctx.packageManager.getApplicationInfo(pkg, 0).category } catch (_: Exception) { return false }
        return cat == ApplicationInfo.CATEGORY_MAPS || cat == ApplicationInfo.CATEGORY_AUDIO
    }

    private fun videoOrGame(pkg: String?): Boolean {
        val ctx = app ?: return true
        if (pkg == null || pkg == ctx.packageName) return false
        if (pkg in VIDEO_APPS) return true
        val cat = try { ctx.packageManager.getApplicationInfo(pkg, 0).category } catch (_: Exception) { return true }
        return cat == ApplicationInfo.CATEGORY_VIDEO || cat == ApplicationInfo.CATEGORY_GAME
    }

    /** Nello specchio non si sa quale app è aperta: in movimento si blocca sempre */
    private fun blocked() = ScreenMath.blocked(
        moving = SpeedMonitor.moving(),
        shizuku = mode == Mode.SHIZUKU,
        essential = allowed(topPackage),
        passenger = passengerMode,
        videoOrGame = videoOrGame(topPackage),
    )

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
                // prima riga = titolo, il resto = spiegazione
                val msg: Triple<String, String, Int>? = when {
                    mode == Mode.NONE -> Triple("DriveHub", "Apri DriveHub sul telefono e avvia Shizuku\noppure lo specchio dello schermo", ACCENT)
                    blocked() -> if (SpeedMonitor.known())
                        Triple("Veicolo in movimento", "Immagine coperta, l'audio continua.\nA voce: pausa, prossima, indietro.", WARN)
                    else Triple("In attesa della velocità", "Servono GPS o adattatore OBD\nper sbloccare lo schermo.", WARN)
                    else -> null
                }
                val pane = Rect(0, 0, paneW(), surfH)
                if (msg == null && bmp != null) {
                    c.drawColor(Color.BLACK)
                    c.drawBitmap(bmp, null, pane, filter)
                } else {
                    drawBackground(c, pane)
                    if (msg != null) drawMessage(c, msg.first, msg.second, msg.third, pane)
                }
                if (split) drawPanel(c, Rect(pane.right, 0, surfW, surfH))
                if (passengerMode) drawBadge(c, "Modalità passeggero", WARN)
            } finally {
                s.unlockCanvasAndPost(c)
            }
        }
    }

    // Palette del display dell'auto (uguale al launcher): sempre scura per non abbagliare di notte
    private val BG_TOP = Color.rgb(11, 18, 32)
    private val BG_BOTTOM = Color.rgb(19, 28, 46)
    private val CARD = Color.rgb(27, 37, 55)
    private val TEXT = Color.rgb(241, 244, 249)
    private val MUTED = Color.rgb(154, 166, 186)
    private val ACCENT = Color.rgb(138, 180, 248)
    private val WARN = Color.rgb(255, 193, 7)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; isFakeBoldText = true }
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED }

    private fun paneW() = if (split) (surfW * SPLIT).toInt().coerceAtLeast(1) else surfW

    private fun drawBackground(c: android.graphics.Canvas, r: Rect) {
        fill.shader = android.graphics.LinearGradient(0f, r.top.toFloat(), 0f, r.bottom.toFloat(), BG_TOP, BG_BOTTOM,
            android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(r, fill)
        fill.shader = null
    }

    /** Scheda arrotondata al centro con barra colorata a sinistra, titolo e testo */
    private fun drawMessage(c: android.graphics.Canvas, head: String, text: String, accent: Int, r: Rect) {
        val u = r.height() / 100f
        title.textSize = u * 8
        body.textSize = u * 5.2f
        val lines = text.lines()
        val w = (r.width() * 0.82f).coerceAtMost(u * 170)
        val h = u * 14 + title.textSize + lines.size * body.textSize * 1.45f
        val card = android.graphics.RectF(r.exactCenterX() - w / 2, r.exactCenterY() - h / 2, r.exactCenterX() + w / 2, r.exactCenterY() + h / 2)
        fill.color = CARD
        c.drawRoundRect(card, u * 6, u * 6, fill)
        fill.color = accent
        c.drawRoundRect(android.graphics.RectF(card.left, card.top, card.left + u * 1.6f, card.bottom), u, u, fill)
        val x = card.left + u * 7
        var y = card.top + u * 6 + title.textSize
        c.drawText(head, x, y, title)
        y += u * 3
        for (line in lines) {
            y += body.textSize * 1.45f
            c.drawText(line, x, y, body)
        }
    }

    /** Pastiglia in basso a sinistra (es. modalità passeggero) */
    private fun drawBadge(c: android.graphics.Canvas, text: String, color: Int) {
        val u = surfH / 100f
        body.textSize = u * 4.5f
        val w = body.measureText(text) + u * 6
        val box = android.graphics.RectF(u * 2.5f, surfH - u * 11, u * 2.5f + w, surfH - u * 3)
        fill.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
        c.drawRoundRect(box, box.height() / 2, box.height() / 2, fill)
        body.color = color
        c.drawText(text, box.left + u * 3, box.bottom - u * 2.6f, body)
        body.color = MUTED
    }

    private fun f(v: Double?, d: Int = 0) = v?.let { String.format(Locale.ITALY, "%.${d}f", it) } ?: "—"

    /** Cruscotto a destra con lo schermo diviso: sempre visibile, anche in movimento */
    private fun drawPanel(c: android.graphics.Canvas, r: Rect) {
        drawBackground(c, r)
        fill.color = Color.argb(40, 255, 255, 255)
        c.drawRect(r.left.toFloat(), r.top.toFloat(), r.left + 2f, r.bottom.toFloat(), fill)
        val v = ObdManager.state.value.values
        val u = r.height() / 100f
        val left = r.left + r.width() * 0.09f
        val right = r.right - r.width() * 0.09f

        body.textSize = u * 5.5f
        c.drawText(SimpleDateFormat("HH:mm", Locale.ITALY).format(Date()), left, r.top + u * 10, body)

        // velocità grande con l'unità accanto
        title.textSize = u * 22
        val speed = f(if (SpeedMonitor.known()) SpeedMonitor.kmh else null)
        val sy = r.top + u * 33
        c.drawText(speed, left, sy, title)
        body.color = ACCENT
        c.drawText("km/h", left + title.measureText(speed) + u * 2, sy, body)
        body.color = MUTED

        val rows = mutableListOf(
            "Giri" to "${f(v["0C"])} /min",
            "Consumo" to "${f(v["L100"], 1)} l/100",
            "Media viaggio" to "${f(TripRecorder.avgL100(), 1)} l/100",
            "Motore" to "${f(v["05"])} °C",
            "Batteria" to "${f(v["V"], 1)} V",
            "Viaggio" to "${f(TripRecorder.km(), 1)} km",
        )
        app?.let { ctx -> Prefs.lastFuelPrice(ctx)?.let { rows += "Carburante" to "€ ${String.format(Locale.ITALY, "%.3f", it)}" } }
        val value = Paint(title).apply { textSize = u * 5.8f; textAlign = Paint.Align.RIGHT }
        body.textSize = u * 5.2f
        var y = sy + u * 6
        for ((label, text) in rows) {
            y += u * 8.6f
            if (y > r.bottom - u * 3) break
            c.drawText(label, left, y, body)
            c.drawText(text, right, y, value)
        }
    }
}
