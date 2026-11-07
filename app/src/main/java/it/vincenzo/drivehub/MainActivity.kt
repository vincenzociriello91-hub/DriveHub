package it.vincenzo.drivehub

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.core.SpeedMonitor
import it.vincenzo.drivehub.core.Updater
import it.vincenzo.drivehub.features.DashcamService
import it.vincenzo.drivehub.obd.BleObd
import it.vincenzo.drivehub.obd.DtcInfo
import it.vincenzo.drivehub.obd.ObdManager
import it.vincenzo.drivehub.screen.MirrorService
import it.vincenzo.drivehub.screen.ScreenController
import it.vincenzo.drivehub.screen.TouchService
import it.vincenzo.drivehub.ui.DriveHubTheme
import it.vincenzo.drivehub.ui.Section
import it.vincenzo.drivehub.ui.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val music = packageManager.getComponentEnabledSetting(android.content.ComponentName(this, it.vincenzo.drivehub.media.MusicService::class.java))
        Reports.log("Telefono: app aperta (DriveHub Musica in Android Auto: ${if (music == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) "accesa" else "spenta"})")
        setContent { DriveHubTheme { Home() } }
    }

    // onStop arriva anche quando l'app viene tolta dalle recenti (onDestroy spesso no)
    override fun onStop() {
        if (!isChangingConfigurations) {
            Reports.log(if (isFinishing) "Telefono: app chiusa" else "Telefono: app in secondo piano o tolta dalle recenti")
            Reports.flush("chiusura")
        }
        super.onStop()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    AUTO("Auto", Icons.Default.Home),
    DIAGNOSI("Diagnosi", Icons.Default.Build),
    VIAGGI("Viaggi", Icons.Default.Place),
    ALTRO("Altro", Icons.Default.Settings),
}

private val PERMISSIONS = arrayOf(
    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.READ_MEDIA_AUDIO,
    Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
)

private fun allPermissions(ctx: Context) =
    PERMISSIONS.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }

/** Lo stato cambia fuori dall'app (Shizuku, accessibilità, Android Auto): si ricontrolla ogni 2 secondi */
@Composable
private fun pollTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    return tick
}

@Composable
private fun Home() {
    var tab by rememberSaveable { mutableStateOf(Tab.AUTO) }
    var permTick by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        Reports.log("Permessi: $it")
        permTick++
    }
    val askPermissions = { permissions.launch(PERMISSIONS) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; Reports.log("Telefono: scheda ${t.label}") },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 16.dp, vertical = 20.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                Tab.AUTO -> AutoTab(permTick, askPermissions) { tab = it }
                Tab.DIAGNOSI -> DiagnosiTab(permTick)
                Tab.VIAGGI -> { FuelSection(); StatsSection(); TripsSection(); DeadlinesSection(); ParkingSection() }
                Tab.ALTRO -> AltroTab(askPermissions)
            }
        }
    }
}

// ---------- Auto ----------

@Composable
private fun AutoTab(permTick: Int, askPermissions: () -> Unit, goTo: (Tab) -> Unit) {
    val ctx = LocalContext.current
    val tick = pollTick()
    val obd by ObdManager.state.collectAsState()
    val perms = remember(tick, permTick) { allPermissions(ctx) }
    val carSeen = remember(tick) { Prefs.carSeen(ctx) }
    val screenReady = remember(tick) { ScreenController.shizukuReady() || TouchService.instance != null }
    val obdChosen = remember(tick) { Prefs.obdDevice(ctx) != null }
    var showAaHelp by remember { mutableStateOf(false) }

    Header(obd.connected, tick)

    if (!(perms && carSeen && screenReady && obdChosen)) {
        Section("Prima di partire", Icons.Default.CheckCircle) {
            Step(perms, "Permessi", "Posizione, Bluetooth, notifiche, fotocamera, microfono.", "Concedi", askPermissions)
            Step(carSeen, "DriveHub in Android Auto", "Lo schermo DriveHub non si è ancora aperto in auto.", "Come fare") { showAaHelp = !showAaHelp }
            if (showAaHelp && !carSeen) { AndroidAutoHelp(); MusicInAndroidAuto() }
            Step(screenReady, "Schermo come tablet", "Avvia Shizuku, oppure attiva lo specchio qui sotto.", "Apri Shizuku") { openShizuku(ctx) }
            Step(obdChosen, "Adattatore OBD", "Scegli come è collegato l'adattatore.", "Scegli") { goTo(Tab.DIAGNOSI) }
        }
    }

    ScreenSection(tick)
    DashcamSection()
}

/** Intestazione: icona, nome, versione e stato di quello che serve in auto */
@Composable
private fun Header(obdConnected: Boolean, tick: Int) {
    val shizuku = remember(tick) { ScreenController.shizukuReady() }
    val dashcam = remember(tick) { DashcamService.running != null }
    val passenger = remember(tick) { ScreenController.passengerMode }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF4F8CFF), Color(0xFF0B57D0)))),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(52.dp)) }
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

/** Riga della lista "Prima di partire": fatto (spunta) o da fare (pulsante) */
@Composable
private fun Step(done: Boolean, title: String, todo: String, action: String, onAction: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (done) Icons.Default.CheckCircle else Icons.Default.Warning, contentDescription = if (done) "Fatto" else "Da fare",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (!done) Text(todo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!done) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun AndroidAutoHelp() {
    Text(
        "1. Impostazioni › App connesse › Android Auto: tocca 10 volte \"Versione\".\n" +
            "2. Menu ⋮ › Impostazioni sviluppatore › attiva \"Origini sconosciute\".\n" +
            "3. Impostazioni › App › Android Auto › Forza interruzione, così rilegge le app.\n" +
            "4. Personalizza menu Avvio app: metti la spunta a DriveHub.\n" +
            "5. In auto apri DriveHub: la spunta qui compare da sola.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

/** Il lettore musicale nel menu di Android Auto: acceso, nasconde lo schermo DriveHub (una sola voce per app) */
@Composable
private fun MusicInAndroidAuto() {
    val ctx = LocalContext.current
    val component = remember { android.content.ComponentName(ctx, it.vincenzo.drivehub.media.MusicService::class.java) }
    var on by remember {
        mutableStateOf(ctx.packageManager.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text("DriveHub Musica in Android Auto", style = MaterialTheme.typography.titleMedium)
            Text("Lasciala spenta: Android Auto mostra una sola voce per app e con la musica accesa nasconde lo schermo DriveHub. " +
                "Dopo ogni cambio fai Forza interruzione di Android Auto.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, {
            on = it
            ctx.packageManager.setComponentEnabledSetting(component,
                if (it) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
            Reports.log("DriveHub Musica in Android Auto: ${if (it) "accesa" else "spenta"}")
        })
    }
}

private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

private fun openShizuku(ctx: Context) {
    val open = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)
        ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PKG"))
    ctx.startActivity(open)
}

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
private fun ScreenSection(tick: Int) {
    val ctx = LocalContext.current
    val state = remember(tick) { shizukuState(ctx) }
    val screen by ScreenController.status.collectAsState()
    var passenger by remember { mutableStateOf(ScreenController.passengerMode) }
    LaunchedEffect(tick) { passenger = ScreenController.passengerMode } // si spegne da solo a fine viaggio

    DisposableEffect(Unit) {
        val l = Shizuku.OnRequestPermissionResultListener { _, result ->
            Reports.log("Shizuku: permesso ${if (result == PackageManager.PERMISSION_GRANTED) "concesso" else "negato"}")
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

    Section("Schermo dell'auto", Icons.Default.Home) {
        Text(screen, style = MaterialTheme.typography.titleMedium)
        Text("Con Shizuku il display dell'auto ha app sue, indipendenti dal telefono. Dopo ogni riavvio: apri Shizuku e tocca \"Avvia\".",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openShizuku(ctx) }) { Text("Apri Shizuku") }
            if (state.endsWith("manca il permesso per DriveHub")) Button(onClick = { Shizuku.requestPermission(1) }) { Text("Concedi") }
        }
        HorizontalDivider()
        Text("Senza Shizuku: specchio del telefono. Per i tocchi attiva DriveHub in Accessibilità (se è grigio: Impostazioni › App › DriveHub › ⋮ › Consenti impostazioni con limitazioni).",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                mirror.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            }) { Text("Avvia specchio") }
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Accessibilità") }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("Modalità passeggero", style = MaterialTheme.typography.titleMedium)
                Text("In movimento sblocca tutto tranne video e giochi. Si spegne a fine viaggio.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(passenger, { passenger = it; ScreenController.setPassenger(it) })
        }
        Text("Sopra i 5 km/h l'immagine di video e app si copre, l'audio continua.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------- Diagnosi ----------

@SuppressLint("MissingPermission")
@Composable
private fun DiagnosiTab(permTick: Int) {
    val ctx = LocalContext.current
    var device by remember { mutableStateOf(Prefs.obdDevice(ctx)) }
    val obd by ObdManager.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var bleFound by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    val paired = remember(permTick) {
        try {
            ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.map { it.name to it.address } ?: emptyList()
        } catch (_: SecurityException) { emptyList() }
    }

    Section("Cruscotto", Icons.Default.Build) {
        Text(obd.status, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = device != null && !obd.connected, onClick = { ObdManager.start(ctx) }) { Text("Collega") }
            OutlinedButton(enabled = obd.connected, onClick = { ObdManager.stop() }) { Text("Scollega") }
        }
        obd.values.forEach { (k, v) ->
            val label = ObdManager.PIDS[k]?.let { "${it.first} (${it.second})" } ?: when (k) {
                "V" -> "Batteria (V)"; "LH" -> "Consumo (l/h)"; "L100" -> "Consumo (l/100 km)"; else -> k
            }
            ValueRow(label, String.format(Locale.ITALY, "%.1f", v))
        }
        obd.info.forEach { (k, v) -> ValueRow(k, v) }
    }

    Section("Codici errore", Icons.Default.Warning) {
        val codes = obd.dtcs
        when {
            codes == null -> Text("Non ancora letti: collega l'adattatore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            codes.isEmpty() && obd.pending.isEmpty() && obd.permanent.isEmpty() -> Text("Nessun codice di errore")
            else -> {
                val all = codes.map { it to "" } + obd.pending.map { it to " · in attesa" } + obd.permanent.map { it to " · permanente" }
                all.forEach { (code, kind) ->
                    // un tocco cerca il codice sul web
                    Column(Modifier.fillMaxWidth().clickable {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("$code Ford Fiesta"))))
                    }) {
                        Text("$code$kind", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(DtcInfo.describe(code), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text("Tocca un codice per cercarlo sul web.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = obd.connected, onClick = { ObdManager.requestDtc() }) { Text("Leggi") }
            if (!codes.isNullOrEmpty()) OutlinedButton(onClick = { confirmClear = true }) { Text("Cancella") }
        }
    }

    Section("Adattatore OBD", Icons.Default.Settings) {
        val options = listOf(
            "Wi-Fi (192.168.0.10:35000)" to Prefs.OBD_WIFI,
            "Cavo USB (OTG)" to Prefs.OBD_USB,
        ) + paired.map { (n, a) -> "Bluetooth · ${n ?: a}" to a } + bleFound.map { (n, a) -> "Bluetooth LE · $n" to Prefs.OBD_BLE + a }
        options.forEach { (name, addr) ->
            Row(Modifier.fillMaxWidth().clickable { device = choose(ctx, name, addr) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = device == addr, onClick = { device = choose(ctx, name, addr) })
                Text(name)
            }
        }
        OutlinedButton(onClick = {
            BleObd.scan(ctx, { name, addr -> if (bleFound.none { it.second == addr }) bleFound = bleFound + (name to addr) })
        }) { Text("Cerca adattatori Bluetooth LE") }
        Text(
            "Bluetooth classico: associa l'ELM327 dalle impostazioni del telefono. Bluetooth LE (Vgate, OBDLink CX): usa la ricerca. " +
                "Wi-Fi: collega il telefono alla rete dell'adattatore (non con Android Auto senza fili). " +
                "USB: cavo OTG nella presa del telefono (con Android Auto via cavo serve un adattatore wireless).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
}

private fun choose(ctx: Context, name: String, addr: String): String {
    ObdManager.stop()
    Prefs.setObdDevice(ctx, addr)
    Reports.log("OBD scelto: $name")
    return addr
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- Altro ----------

@Composable
private fun AltroTab(askPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var updateMsg by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf("") }
    var allNews by remember { mutableStateOf(false) }
    val news = remember { ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } }

    Section("Aggiornamenti", Icons.Default.Refresh) {
        Text("Versione ${BuildConfig.VERSION_NAME}")
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

    Section("Novità", Icons.Default.Star) {
        Text(if (allNews) news else news.substringBefore("\n\n"))
        TextButton(onClick = { allNews = !allNews }) { Text(if (allNews) "Mostra meno" else "Tutte le versioni") }
    }

    Section("Permessi e Android Auto", Icons.Default.Lock) {
        OutlinedButton(onClick = askPermissions) { Text("Concedi i permessi") }
        AndroidAutoHelp()
        MusicInAndroidAuto()
    }

    Section("Rapporti", Icons.Default.Info) {
        Text("Salvati da soli a ogni operazione, errore e chiusura in Download/${Reports.FOLDER}; sincronizzali su Google Drive per farli leggere a Claude.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { Reports.flush("manuale"); saved = "Rapporto salvato" }) { Text("Salva rapporto ora") }
        if (saved.isNotEmpty()) Text(saved)
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
