package it.vincenzo.drivehub.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import it.vincenzo.drivehub.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapporti completi di ogni utilizzo: ogni operazione finisce nel registro eventi,
 * e flush() salva il pacchetto in Download/DriveHub_report sul telefono
 * (da sincronizzare su Google Drive, come i rapporti della Odin).
 * Gli eventi si scrivono anche su disco man mano: se Android chiude l'app senza avvisare
 * (memoria, blocco, crash nativo, app tolta dalle recenti, batteria), al riavvio il rapporto
 * "chiusura-improvvisa" recupera tutto, con il motivo dato dal sistema.
 * Niente token nell'app: l'APK è pubblico e un token sarebbe estraibile.
 */
object Reports {
    const val FOLDER = "DriveHub_report"
    private val events = mutableListOf<Pair<Long, String>>()
    private var start = System.currentTimeMillis()
    private var app: Context? = null
    private var journal: File? = null
    /** Ultimi valori da allegare (es. dati OBD), aggiornati dai moduli */
    val extras = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        journal = File(ctx.filesDir, "eventi-in-corso.txt")
        recoverPrevious(ctx)
    }

    fun log(msg: String) {
        synchronized(events) {
            val t = System.currentTimeMillis()
            events += t to msg
            if (events.size > 2000) events.removeAt(0)
            // una riga per evento: sopravvive anche se il processo viene ucciso subito dopo
            runCatching { journal?.appendText("$t\t${msg.replace('\n', ' ')}\n") }
        }
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY).format(Date(t))

    private fun build(reason: String, error: Throwable? = null, exit: JSONObject? = null): JSONObject = synchronized(events) {
        val ev = JSONArray()
        events.forEach { (t, m) -> ev.put(JSONObject().put("ora", iso(t)).put("msg", m)) }
        val json = JSONObject()
            .put("app", "DriveHub")
            .put("versione", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("motivo", reason)
            .put("inizio", iso(events.firstOrNull()?.first ?: start))
            .put("fine", iso(events.lastOrNull()?.first?.takeIf { exit != null } ?: System.currentTimeMillis()))
            .put("eventi", ev)
            .put("dati", JSONObject(extras.toMap()))
        if (error != null) json.put("errore", error.stackTraceToString())
        if (exit != null) json.put("uscita", exit)
        events.clear()
        runCatching { journal?.writeText("") }
        start = System.currentTimeMillis()
        json
    }

    /** Scrive in Download/DriveHub_report (nessun permesso richiesto da Android 10) */
    private fun save(json: JSONObject, reason: String) {
        val ctx = app ?: return
        val name = "${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ITALY).format(Date())}-$reason.json"
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toString(2).toByteArray()) }
        } catch (_: Exception) {
            // memoria piena o simili: il rapporto si perde ma l'app non deve bloccarsi
        }
    }

    /** Chiude il blocco di eventi corrente e lo salva */
    fun flush(reason: String) = save(build(reason), reason)

    /** Chiamato dal gestore crash: sincrono */
    fun saveCrash(e: Throwable) {
        log("CRASH: ${e.javaClass.simpleName}: ${e.message}")
        save(build("crash", e), "crash")
    }

    /** Spiegazione in italiano del motivo di uscita dato da Android */
    fun exitReasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_ANR -> "app bloccata (non rispondeva)"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "memoria del telefono esaurita"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "troppe risorse usate"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "chiusa dall'utente (arresto forzato o aggiornamento)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "fermata dall'utente"
        ApplicationExitInfo.REASON_SIGNALED -> "terminata dal sistema"
        ApplicationExitInfo.REASON_EXIT_SELF -> "uscita normale"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permessi cambiati"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "un servizio da cui dipendeva si è fermato"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "avvio non riuscito"
        else -> "altro motivo ($reason)"
    }

    /** All'avvio: se la sessione precedente non ha salvato il suo rapporto, lo salva ora */
    private fun recoverPrevious(ctx: Context) {
        val lines = runCatching { journal?.readLines() }.getOrNull().orEmpty().filter { it.isNotBlank() }
        val prefs = ctx.getSharedPreferences("drivehub", Context.MODE_PRIVATE)
        val exit = runCatching {
            ctx.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(ctx.packageName, 0, 1).firstOrNull()
        }.getOrNull()?.takeIf { it.timestamp > prefs.getLong("ultima_uscita", 0) }
        exit?.let { prefs.edit().putLong("ultima_uscita", it.timestamp).apply() }

        // i crash Java hanno già il loro rapporto: qui servono gli eventi rimasti e i blocchi o crash nativi
        val serious = exit?.reason == ApplicationExitInfo.REASON_ANR || exit?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
        if (lines.isEmpty() && !serious) return
        synchronized(events) {
            lines.forEach { l ->
                val tab = l.indexOf('\t')
                val t = l.substring(0, tab.coerceAtLeast(0)).toLongOrNull() ?: return@forEach
                events += t to l.substring(tab + 1)
            }
        }
        val info = exit?.let {
            JSONObject()
                .put("motivo", exitReasonText(it.reason))
                .put("descrizione", it.description ?: "")
                .put("ora", iso(it.timestamp))
        } ?: JSONObject().put("motivo", "sconosciuto")
        save(build("chiusura-improvvisa", exit = info), "chiusura-improvvisa")
    }
}
