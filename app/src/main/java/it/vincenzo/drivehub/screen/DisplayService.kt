package it.vincenzo.drivehub.screen

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.RequiresApi
import kotlin.system.exitProcess

/** Codici delle chiamate tra l'app e il servizio che gira con Shizuku */
object DisplayProtocol {
    const val CREATE = 1
    const val RELEASE = 2
    const val TAP = 3
    const val SWIPE = 4
    const val KEY = 5
    const val LAUNCH = 6
    const val RESIZE = 7
    /** Diagnosi di Android Auto: righe utili del registro di sistema (comando fisso, nessun comando arbitrario) */
    const val DIAG = 8
    /** Chiamata "destroy" che Shizuku invia ai propri servizi utente */
    const val DESTROY = 16777115
}

/**
 * Gira nel processo avviato da Shizuku con i permessi della shell (uid 2000):
 * crea il display virtuale dell'auto, avvia le app su quel display e ci inietta tocchi e tasti.
 * Shizuku la istanzia con il costruttore (Context).
 */
class DisplayService(base: Context) : Binder() {
    private val shell = ShellContext(base)
    private var vd: VirtualDisplay? = null
    private var displayId = -1

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == DisplayProtocol.DESTROY) {
            vd?.release()
            exitProcess(0)
        }
        try {
            when (code) {
                DisplayProtocol.CREATE -> {
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val id = create(surface, data.readInt(), data.readInt(), data.readInt())
                    reply?.writeNoException(); reply?.writeInt(id)
                }
                DisplayProtocol.RELEASE -> { vd?.release(); vd = null; displayId = -1; reply?.writeNoException() }
                DisplayProtocol.TAP -> { tap(data.readFloat(), data.readFloat()); reply?.writeNoException() }
                DisplayProtocol.SWIPE -> {
                    swipe(data.readFloat(), data.readFloat(), data.readFloat(), data.readFloat(), data.readLong())
                    reply?.writeNoException()
                }
                DisplayProtocol.KEY -> { key(data.readInt()); reply?.writeNoException() }
                DisplayProtocol.RESIZE -> {
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val d = vd ?: error("display non creato")
                    d.resize(data.readInt(), data.readInt(), data.readInt())
                    d.setSurface(surface)
                    reply?.writeNoException()
                }
                DisplayProtocol.LAUNCH -> { val out = launch(data.readString().orEmpty()); reply?.writeNoException(); reply?.writeString(out) }
                DisplayProtocol.DIAG -> { val out = diagnose(); reply?.writeNoException(); reply?.writeString(out) }
                else -> return super.onTransact(code, data, reply, flags)
            }
        } catch (e: Throwable) {
            reply?.writeException(IllegalStateException("${e.javaClass.simpleName}: ${e.message}"))
        }
        return true
    }

    private fun create(surface: Surface, w: Int, h: Int, dpi: Int): Int {
        vd?.release()
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(shell)
        val base = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or ROTATES_WITH_CONTENT or DESTROY_CONTENT_ON_REMOVAL
        val trusted = base or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        val full = if (Build.VERSION.SDK_INT >= 34) trusted or OWN_FOCUS or DEVICE_DISPLAY_GROUP else trusted
        // Dal più completo al più semplice: alcune versioni di Android rifiutano certe combinazioni
        var last: Exception? = null
        for (flags in listOf(full, trusted, base)) {
            try {
                val d = dm.createVirtualDisplay("DriveHub", w, h, dpi, surface, flags) ?: continue
                vd = d
                displayId = d.display.displayId
                return displayId
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("display virtuale non creato")
    }

    private fun launch(component: String): String {
        check(displayId >= 0) { "display non creato" }
        // windowingMode 1 = schermo intero (evita le finestre "desktop" sui display esterni)
        val p = ProcessBuilder(
            "am", "start", "--display", displayId.toString(), "--windowingMode", "1",
            "-f", "0x10200000", "-n", component
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    /**
     * Perché Android Auto mostra o scarta DriveHub: righe del logcat (la shell può leggerlo) su validatore delle app
     * e Car App Library, più i servizi di DriveHub visti dal sistema.
     */
    private fun diagnose(): String {
        fun run(vararg cmd: String) = runCatching {
            val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            out
        }.getOrElse { "errore: ${it.message}" }
        val keys = listOf("drivehub", "VALIDATOR", "CarApp", "car.app", "CarAppService")
        val log = run("logcat", "-d", "-v", "time", "-t", "30000").lineSequence()
            .filter { l -> keys.any { l.contains(it, ignoreCase = true) } }.toList().takeLast(400)
        val pkg = run("dumpsys", "package", "it.vincenzo.drivehub").lineSequence()
            .filter { l -> listOf("Service", "versionName", "installer", "enabled", "targetSdk", "androidx.car").any { it in l } }
            .toList().take(80)
        return "== logcat (${log.size} righe) ==\n" + log.joinToString("\n") + "\n== dumpsys package ==\n" + pkg.joinToString("\n")
    }

    // Iniezione input con API nascoste (consentite alla shell)
    private val inputManager: Any by lazy {
        try {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)!!
        } catch (_: Exception) {
            InputManager::class.java.getDeclaredMethod("getInstance").invoke(null)!!
        }
    }
    private val injectMethod by lazy {
        inputManager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
    }
    private val setDisplayId by lazy { InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType) }

    private fun inject(ev: InputEvent) {
        setDisplayId.invoke(ev, displayId)
        injectMethod.invoke(inputManager, ev, 0) // 0 = asincrono
    }

    private fun motion(action: Int, x: Float, y: Float, down: Long) {
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        inject(ev)
        ev.recycle()
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        motion(MotionEvent.ACTION_DOWN, x, y, t)
        motion(MotionEvent.ACTION_UP, x, y, t)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val t = SystemClock.uptimeMillis()
        val steps = (ms / 16).coerceAtLeast(5)
        motion(MotionEvent.ACTION_DOWN, x1, y1, t)
        for (i in 1..steps) {
            val f = i.toFloat() / steps
            motion(MotionEvent.ACTION_MOVE, x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, t)
            Thread.sleep(ms / steps)
        }
        motion(MotionEvent.ACTION_UP, x2, y2, t)
    }

    private fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(t, t, action, code, 0)
            ev.source = InputDevice.SOURCE_KEYBOARD
            inject(ev)
        }
    }

    /** Il sistema accetta il display solo se il pacchetto dichiarato corrisponde all'uid (shell) */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL
        override fun getOpPackageName(): String = SHELL

        @RequiresApi(Build.VERSION_CODES.S)
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build()
    }

    private companion object {
        const val SHELL = "com.android.shell"
        // Flag di DisplayManager (molti sono nascosti nell'SDK)
        const val PUBLIC = 1
        const val PRESENTATION = 1 shl 1
        const val OWN_CONTENT_ONLY = 1 shl 3
        const val SUPPORTS_TOUCH = 1 shl 6
        const val ROTATES_WITH_CONTENT = 1 shl 7
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
    }
}

/** Lato app: chiamate verso DisplayService */
class DisplayClient(private val binder: IBinder) {
    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            write(d)
            binder.transact(code, d, r, 0)
            r.readException()
            return read(r)
        } finally {
            d.recycle(); r.recycle()
        }
    }

    fun create(s: Surface, w: Int, h: Int, dpi: Int): Int =
        call(DisplayProtocol.CREATE, { s.writeToParcel(it, 0); it.writeInt(w); it.writeInt(h); it.writeInt(dpi) }) { it.readInt() }
    fun release() = call(DisplayProtocol.RELEASE, {}) {}
    fun resize(s: Surface, w: Int, h: Int, dpi: Int) =
        call(DisplayProtocol.RESIZE, { s.writeToParcel(it, 0); it.writeInt(w); it.writeInt(h); it.writeInt(dpi) }) {}
    fun tap(x: Float, y: Float) = call(DisplayProtocol.TAP, { it.writeFloat(x); it.writeFloat(y) }) {}
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        call(DisplayProtocol.SWIPE, { it.writeFloat(x1); it.writeFloat(y1); it.writeFloat(x2); it.writeFloat(y2); it.writeLong(ms) }) {}
    fun key(code: Int) = call(DisplayProtocol.KEY, { it.writeInt(code) }) {}
    fun launch(component: String): String = call(DisplayProtocol.LAUNCH, { it.writeString(component) }) { it.readString().orEmpty() }
    fun diagnose(): String = call(DisplayProtocol.DIAG, {}) { it.readString().orEmpty() }
}
