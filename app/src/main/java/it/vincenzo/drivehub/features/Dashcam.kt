package it.vincenzo.drivehub.features

import android.Manifest
import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import it.vincenzo.drivehub.R
import it.vincenzo.drivehub.core.Notify
import it.vincenzo.drivehub.core.Reports
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Dashcam con la fotocamera posteriore del telefono sul supporto.
 * Registra a ciclo pezzi da 1 minuto (tiene gli ultimi 5, senza audio);
 * a una frenata o un urto forte, o su richiesta, copia gli ultimi pezzi in Movies/DriveHub.
 */
class DashcamService : LifecycleService(), SensorEventListener {
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private val segments = ArrayDeque<File>()
    private var protectPending = false
    private var stopping = false
    private var lastEvent = 0L
    private val main = Handler(Looper.getMainLooper())
    private lateinit var dir: File

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (running != null) return START_NOT_STICKY
        val n = Notification.Builder(this, Notify.DASHCAM)
            .setSmallIcon(R.drawable.ic_record)
            .setContentTitle("DriveHub")
            .setContentText("Dashcam in registrazione")
            .build()
        try {
            startForeground(3, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } catch (e: Exception) {
            Reports.log("Dashcam: avvio non consentito ora (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        running = this
        dir = File(filesDir, "dashcam").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val rec = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                    .build()
                val vc = VideoCapture.withOutput(rec)
                val provider = future.get()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, vc)
                capture = vc
                startSegment()
                val sm = getSystemService(SensorManager::class.java)
                sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
                Reports.log("Dashcam: registrazione avviata")
            } catch (e: Exception) {
                Reports.log("Dashcam: errore fotocamera ${e.javaClass.simpleName}: ${e.message}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
        return START_NOT_STICKY
    }

    private fun startSegment() {
        val vc = capture ?: return
        val f = File(dir, "seg-${System.currentTimeMillis()}.mp4")
        recording = vc.output.prepareRecording(this, FileOutputOptions.Builder(f).build())
            .start(ContextCompat.getMainExecutor(this)) { ev -> if (ev is VideoRecordEvent.Finalize) onSegmentDone(f) }
        main.postDelayed(rotate, SEGMENT_MS)
    }

    private val rotate = Runnable { recording?.stop() }

    private fun onSegmentDone(f: File) {
        main.removeCallbacks(rotate)
        if (f.length() > 0) segments.addLast(f) else f.delete()
        if (protectPending) {
            protectPending = false
            val keep = segments.takeLast(2)
            Thread { keep.forEach { saveClip(it) } }.start()
        }
        // i pezzi appena protetti sono gli ultimi due: non vengono cancellati qui
        while (segments.size > KEEP) segments.removeFirst().delete()
        if (!stopping) startSegment()
    }

    /** Chiude il pezzo corrente tra 10 s (per avere anche il dopo) e lo salva con il precedente */
    fun protect() {
        main.post {
            protectPending = true
            main.removeCallbacks(rotate)
            main.postDelayed(rotate, 10_000)
            Reports.log("Dashcam: evento, salvo la clip")
        }
    }

    private fun saveClip(f: File) {
        try {
            val name = "DriveHub-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date(f.lastModified()))}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/DriveHub")
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            Reports.log("Dashcam: clip salvata $name")
        } catch (e: Exception) {
            Reports.log("Dashcam: salvataggio fallito ${e.message}")
        }
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        val now = System.currentTimeMillis()
        // oltre ~0,7 g: frenata di emergenza o urto
        if (a > 7f && now - lastEvent > 30_000) {
            lastEvent = now
            Reports.log("Dashcam: accelerazione ${"%.1f".format(a)} m/s²")
            protect()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        stopping = true
        main.removeCallbacks(rotate)
        recording?.stop()
        recording = null
        getSystemService(SensorManager::class.java).unregisterListener(this)
        capture = null
        if (running === this) running = null
        Reports.log("Dashcam: fermata")
        super.onDestroy()
    }

    companion object {
        private const val SEGMENT_MS = 60_000L
        private const val KEEP = 5

        @Volatile var running: DashcamService? = null
            private set

        /** Android consente la fotocamera in background solo se l'avvio parte con l'app visibile */
        fun start(ctx: Context): Boolean {
            if (running != null) return true
            if (ctx.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                Reports.log("Dashcam: permesso fotocamera mancante")
                return false
            }
            return try {
                ctx.startForegroundService(Intent(ctx, DashcamService::class.java))
                true
            } catch (e: Exception) {
                Reports.log("Dashcam: avvio rimandato (${e.javaClass.simpleName})")
                false
            }
        }

        fun stop(ctx: Context) {
            if (running != null) ctx.stopService(Intent(ctx, DashcamService::class.java))
        }

        fun protectNow(): Boolean = running?.let { it.protect(); true } ?: false
    }
}
