package it.vincenzo.drivehub.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import it.vincenzo.drivehub.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rapporti completi di ogni utilizzo: ogni operazione finisce nel registro eventi,
 * e flush() salva il pacchetto in Download/DriveHub_report sul telefono
 * (da sincronizzare su Google Drive, come i rapporti della Odin).
 * Niente token nell'app: l'APK è pubblico e un token sarebbe estraibile.
 */
object Reports {
    const val FOLDER = "DriveHub_report"
    private val events = mutableListOf<Pair<Long, String>>()
    private var start = System.currentTimeMillis()
    private var app: Context? = null
    /** Ultimi valori da allegare (es. dati OBD), aggiornati dai moduli */
    val extras = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun init(ctx: Context) { app = ctx.applicationContext }

    fun log(msg: String) = synchronized(events) {
        events += System.currentTimeMillis() to msg
        if (events.size > 2000) events.removeAt(0)
    }

    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ITALY).format(Date(t))

    private fun build(reason: String, error: Throwable? = null): JSONObject = synchronized(events) {
        val ev = JSONArray()
        events.forEach { (t, m) -> ev.put(JSONObject().put("ora", iso(t)).put("msg", m)) }
        val json = JSONObject()
            .put("app", "DriveHub")
            .put("versione", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("motivo", reason)
            .put("inizio", iso(start))
            .put("fine", iso(System.currentTimeMillis()))
            .put("eventi", ev)
            .put("dati", JSONObject(extras.toMap()))
        if (error != null) json.put("errore", error.stackTraceToString())
        events.clear()
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
}
