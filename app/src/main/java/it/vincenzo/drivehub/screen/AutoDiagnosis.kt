package it.vincenzo.drivehub.screen

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import it.vincenzo.drivehub.BuildConfig
import it.vincenzo.drivehub.core.Reports
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Pulsante "Diagnosi Android Auto": avvia il servizio Shizuku in un processo a parte (non tocca lo schermo dell'auto),
 * legge il registro di sistema e salva il rapporto "diagnosi-aa".
 */
object AutoDiagnosis {
    private val io = Executors.newSingleThreadExecutor()

    /** done riceve il messaggio da mostrare (su un thread qualsiasi) */
    fun run(ctx: Context, done: (String) -> Unit) {
        if (!ScreenController.shizukuReady()) {
            Reports.log("Diagnosi Android Auto: Shizuku non pronto")
            done("Shizuku non è avviato: apri Shizuku, tocca Avvia, poi riprova")
            return
        }
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, DisplayService::class.java.name))
            .daemon(false)
            .tag("diagnosi") // Shizuku distingue i servizi per tag: così non riusa quello dello schermo dell'auto
            .processNameSuffix("diagnosi")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                val conn = this
                io.execute {
                    val msg = try {
                        val out = DisplayClient(binder ?: error("servizio non disponibile")).diagnose()
                        Reports.extras["diagnosiAndroidAuto"] = out
                        Reports.log("Diagnosi Android Auto: ${out.length} caratteri")
                        Reports.flush("diagnosi-aa")
                        "Rapporto \"diagnosi-aa\" salvato in Download/${Reports.FOLDER}: mandalo a Claude"
                    } catch (e: Exception) {
                        Reports.log("Diagnosi Android Auto fallita: ${e.message}")
                        "Diagnosi non riuscita: ${e.message}"
                    } finally {
                        Reports.extras.remove("diagnosiAndroidAuto")
                        runCatching { Shizuku.unbindUserService(args, conn, true) }
                    }
                    done(msg)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            done("Diagnosi non riuscita: ${e.message}")
        }
    }
}
