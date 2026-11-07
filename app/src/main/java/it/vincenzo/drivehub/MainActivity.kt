package it.vincenzo.drivehub

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.core.Updater
import it.vincenzo.drivehub.obd.ObdManager
import it.vincenzo.drivehub.obd.BleObd
import androidx.compose.foundation.clickable
import it.vincenzo.drivehub.ui.DriveHubTheme
import it.vincenzo.drivehub.ui.Section
import it.vincenzo.drivehub.ui.StatusChip
import it.vincenzo.drivehub.features.DashcamService
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import it.vincenzo.drivehub.obd.DtcInfo
import it.vincenzo.drivehub.core.SpeedMonitor
import it.vincenzo.drivehub.screen.MirrorService
import it.vincenzo.drivehub.screen.ScreenController
import rikka.shizuku.Shizuku
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.log("Telefono: app aperta")
        setContent {
            DriveHubTheme { Surface(Modifier.fillMaxSize()) { Home() } }
        }
    }

    override fun onDestroy() {
        if (isFinishing) {
            Reports.log("Telefono: app chiusa")
            Reports.flush("chiusura")
        }
        super.onDestroy()
    }
}

/** Intestazione: icona, nome, versione e stato di tutto quello che serve in auto */
@Composable
private fun Header(obdConnected: Boolean) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val shizuku = remember(tick) { ScreenController.shizukuReady() }
    val dashcam = remember(tick) { DashcamService.running != null }
    val passenger = remember(tick) { ScreenController.passengerMode }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(56.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4F8CFF), Color(0xFF0B57D0)))),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(56.dp)) }
            Box(Modifier.size(14.dp))
            Column {
                Text("DriveHub", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Versione ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(if (shizuku) "Shizuku pronto" else "Shizuku spento", shizuku)
            StatusChip(if (obdConnected) "OBD collegato" else "OBD scollegato", obdConnected)
            StatusChip(if (dashcam) "Dashcam attiva" else "Dashcam spenta", dashcam)
            if (passenger) StatusChip("Passeggero", true)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun Home() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var device by remember { mutableStateOf(Prefs.obdDevice(ctx)) }
    var fuel by remember { mutableStateOf(Prefs.fuel(ctx)) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var updateMsg by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var permTick by remember { mutableStateOf(0) }
    val obd by ObdManager.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var bleFound by remember { mutableStateOf(listOf<Pair<String, String>>()) }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Reports.log("Permessi: $it")
        permTick++
    }

    val paired = remember(permTick) {
        try {
            ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.map { it.name to it.address } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Header(obd.connected)

        Section("1. Permessi", Icons.Default.Lock) {
            Text("Posizione (carburante, meteo, viaggi, parcheggio), Bluetooth (OBD), audio (musica), notifiche (avvisi anche sul Pixel Watch), fotocamera (dashcam), microfono (comandi vocali).")
            Button(onClick = {
                permissions.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                ))
            }) { Text("Concedi i permessi") }
        }

        Section("2. Attiva DriveHub in Android Auto", Icons.Default.Settings) {
            Text(
                "Impostazioni → App connesse → Android Auto → tocca 10 volte \"Versione\" per diventare sviluppatore. " +
                    "Poi menu ⋮ → Impostazioni sviluppatore → attiva \"Origini sconosciute\". " +
                    "In Android Auto → Personalizza menu Avvio app, abilita DriveHub (app e musica)."
            )
        }

        ScreenSection()

        Section("4. Adattatore OBD", Icons.Default.Build) {
            val options = listOf(
                "Wi-Fi (192.168.0.10:35000)" to Prefs.OBD_WIFI,
                "Cavo USB (OTG)" to Prefs.OBD_USB,
            ) + paired.map { (n, a) -> "Bluetooth · ${n ?: a}" to a } + bleFound.map { (n, a) -> "Bluetooth LE · $n" to Prefs.OBD_BLE + a }
            Text("Bluetooth classico: associa l'ELM327 dalle impostazioni del telefono. Bluetooth LE (Vgate, OBDLink CX…): usa la ricerca qui sotto. " +
                "Wi-Fi: collega il telefono alla rete dell'adattatore (non insieme ad Android Auto senza fili, che usa già il Wi-Fi). " +
                "USB: adattatore con cavo OTG nella presa del telefono (se Android Auto usa il cavo, serve un adattatore wireless per Android Auto).")
            OutlinedButton(onClick = {
                BleObd.scan(ctx, { name, addr -> if (bleFound.none { it.second == addr }) bleFound = bleFound + (name to addr) })
            }) { Text("Cerca adattatori Bluetooth LE") }
            options.forEach { (name, addr) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = device == addr, onClick = {
                        ObdManager.stop()
                        device = addr; Prefs.setObdDevice(ctx, addr); Reports.log("OBD scelto: $name")
                    })
                    Text(name)
                }
            }
            Text("Stato: ${obd.status}")
            obd.values.forEach { (k, v) ->
                val label = ObdManager.PIDS[k]?.let { "${it.first} (${it.second})" } ?: when (k) {
                    "V" -> "Batteria (V)"; "LH" -> "Consumo (l/h)"; "L100" -> "Consumo (l/100 km)"; else -> k
                }
                Text("$label: ${String.format(Locale.ITALY, "%.1f", v)}")
            }
            obd.info.forEach { (k, v) -> Text("$k: $v") }
            obd.dtcs?.let { codes ->
                if (codes.isEmpty() && obd.pending.isEmpty() && obd.permanent.isEmpty()) Text("Nessun codice di errore")
                val all = codes.map { it to "" } + obd.pending.map { it to " · in attesa" } + obd.permanent.map { it to " · permanente" }
                all.forEach { (code, kind) ->
                    // un tocco cerca il codice sul web
                    Text(
                        "$code$kind\n${DtcInfo.describe(code)}",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("$code Ford Fiesta"))))
                        },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = device != null, onClick = { ObdManager.start(ctx) }) { Text("Prova") }
                OutlinedButton(enabled = obd.connected, onClick = { ObdManager.requestDtc() }) { Text("Leggi errori") }
                OutlinedButton(onClick = { ObdManager.stop() }) { Text("Ferma") }
            }
            if (!obd.dtcs.isNullOrEmpty()) {
                OutlinedButton(onClick = { confirmClear = true }) { Text("Cancella errori") }
            }
        }

        Section("5. Carburante per la ricerca prezzi", Icons.Default.LocationOn) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Benzina", "Gasolio", "GPL", "Metano").forEach { f ->
                    FilterChip(selected = fuel == f, onClick = { fuel = f; Prefs.setFuel(ctx, f) }, label = { Text(f) })
                }
            }
        }

        TripsSection()
        ParkingSection()
        DashcamSection()

        Section("Aggiornamenti", Icons.Default.Refresh) {
            Button(onClick = {
                updateMsg = "Controllo…"
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { Updater.latest() } }
                    r.onSuccess {
                        if (it != null && Updater.isNewer(it)) release = it
                        updateMsg = if (it != null && Updater.isNewer(it)) "" else "Hai già l'ultima versione"
                    }.onFailure { updateMsg = "Errore: ${it.message}"; Reports.log("Aggiornamento: errore ${it.message}") }
                }
            }) { Text("Cerca aggiornamenti") }
            if (updateMsg.isNotEmpty()) Text(updateMsg)
        }

        Section("Rapporti", Icons.Default.Info) {
            Text("Salvati in automatico dopo ogni operazione, a ogni errore e alla chiusura, nella cartella Download/${Reports.FOLDER}. Sincronizzala su Google Drive per farli leggere a Claude.")
            OutlinedButton(onClick = { Reports.flush("manuale"); saved = "Rapporto salvato" }) { Text("Salva rapporto ora") }
            if (saved.isNotEmpty()) Text(saved)
        }

        Section("Novità", Icons.Default.Star) {
            Text(remember { ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } })
        }
    }

    if (confirmClear) {
        val stopped = !SpeedMonitor.moving()
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Cancellare i codici errore?") },
            text = {
                Text(
                    if (!stopped) "Si può fare solo a veicolo fermo."
                    else "La spia si spegne, ma se il guasto c'è ancora i codici tornano. Si azzerano anche i controlli " +
                        "delle emissioni: prima di una revisione guida qualche giorno. Meglio farlo a motore spento, quadro acceso."
                )
            },
            confirmButton = {
                TextButton(enabled = stopped, onClick = {
                    confirmClear = false
                    Reports.log("Cancellazione codici confermata dall'utente")
                    ObdManager.requestClear()
                }) { Text("Cancella") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annulla") } },
        )
    }

    release?.let { r ->
        AlertDialog(
            onDismissRequest = { release = null },
            title = { Text("Nuova versione: ${r.name}") },
            text = { Text(r.notes.ifBlank { "Nessuna nota." }) },
            confirmButton = {
                TextButton(onClick = {
                    release = null
                    updateMsg = "Scaricamento…"
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { Updater.install(ctx, r) } }
                        updateMsg = res.fold({ "" }, { "Errore: ${it.message}" })
                    }
                }) { Text("Installa") }
            },
            dismissButton = { TextButton(onClick = { release = null }) { Text("Dopo") } },
        )
    }
}

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

private fun shizukuState(ctx: Context): String {
    val installed = try { ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }
    val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
    return when {
        !installed -> "Shizuku non installato"
        !running -> "Shizuku installato ma non avviato"
        runCatching { Shizuku.isPreV11() }.getOrDefault(true) -> "Shizuku troppo vecchio: aggiornalo"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku avviato: manca il permesso per DriveHub"
        else -> "Shizuku pronto"
    }
}

/** Schermo dell'auto come secondo tablet: Shizuku (consigliato) o specchio */
@Composable
private fun ScreenSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val state = remember(tick) { shizukuState(ctx) }
    val screen by ScreenController.status.collectAsState()

    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, result ->
            Reports.log("Shizuku: permesso ${if (result == PackageManager.PERMISSION_GRANTED) "concesso" else "negato"}")
            tick++
        }
        Shizuku.addRequestPermissionResultListener(l)
        onDispose { Shizuku.removeRequestPermissionResultListener(l) }
    }

    val mirror = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        if (res.resultCode == Activity.RESULT_OK && data != null) {
            ctx.startForegroundService(
                Intent(ctx, MirrorService::class.java)
                    .putExtra(MirrorService.EXTRA_CODE, res.resultCode)
                    .putExtra(MirrorService.EXTRA_DATA, data)
            )
        } else Reports.log("Specchio: consenso negato")
    }

    Section("3. Schermo dell'auto come tablet", Icons.Default.Home) {
        Text(
            "Con Shizuku il display dell'auto ha le sue app, indipendenti dal telefono. " +
                "Prima volta: installa Shizuku, aprilo, attiva \"Debug wireless\" nelle Opzioni sviluppatore e segui l'abbinamento. " +
                "Dopo ogni riavvio del telefono: apri Shizuku e tocca \"Avvia\". Poi concedi il permesso a DriveHub."
        )
        Text("Stato: $state")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val open = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
                ctx.startActivity(open)
            }) { Text("Apri / installa Shizuku") }
            Button(onClick = {
                if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) Shizuku.requestPermission(1)
                tick++
            }) { Text("Concedi") }
        }
        OutlinedButton(onClick = { tick++ }) { Text("Aggiorna stato") }

        Text("Senza Shizuku: specchio dello schermo del telefono. Per i tocchi dal display attiva \"DriveHub\" in Accessibilità " +
            "(se è bloccato: Impostazioni › App › DriveHub › ⋮ › Consenti impostazioni con limitazioni).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                mirror.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }) { Text("Avvia specchio") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibilità") }
        }
        Text("Display auto: $screen")
        Text("Sicurezza: sopra i 5 km/h l'immagine di video e app si copre (l'audio continua); restano navigatori e musica.")
        var passenger by remember { mutableStateOf(ScreenController.passengerMode) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Switch(passenger, { passenger = it; ScreenController.setPassenger(it) })
            Text("  Modalità passeggero: in movimento sblocca le app che non sono video o giochi. Usala solo se lo schermo lo usa il passeggero; si spegne a fine viaggio.")
        }
    }
}
