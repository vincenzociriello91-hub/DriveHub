package it.vincenzo.drivehub

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.log("Telefono: app aperta")
        setContent {
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            MaterialTheme(colorScheme = scheme) { Surface(Modifier.fillMaxSize()) { Home() } }
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

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
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
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("DriveHub ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineMedium)

        Section("1. Permessi") {
            Text("Posizione (carburante e meteo), Bluetooth (OBD), audio (musica), notifiche.")
            Button(onClick = {
                permissions.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                ))
            }) { Text("Concedi i permessi") }
        }

        Section("2. Attiva DriveHub in Android Auto") {
            Text(
                "Impostazioni → App connesse → Android Auto → tocca 10 volte \"Versione\" per diventare sviluppatore. " +
                    "Poi menu ⋮ → Impostazioni sviluppatore → attiva \"Origini sconosciute\". " +
                    "In Android Auto → Personalizza menu Avvio app, abilita DriveHub (app e musica)."
            )
        }

        Section("3. Adattatore OBD") {
            val options = listOf("Wi-Fi (192.168.0.10:35000)" to Prefs.OBD_WIFI) + paired
            if (paired.isEmpty()) Text("Nessun dispositivo Bluetooth associato: associa l'ELM327 dalle impostazioni Bluetooth, poi concedi i permessi.")
            options.forEach { (name, addr) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = device == addr, onClick = {
                        device = addr; Prefs.setObdDevice(ctx, addr); Reports.log("OBD scelto: $name")
                    })
                    Text(name ?: addr)
                }
            }
            Text("Stato: ${obd.status}")
            obd.values.forEach { (k, v) ->
                val label = ObdManager.PIDS[k]?.let { "${it.first} (${it.second})" } ?: when (k) {
                    "V" -> "Batteria (V)"; "LH" -> "Consumo (l/h)"; "L100" -> "Consumo (l/100 km)"; else -> k
                }
                Text("$label: ${String.format(Locale.ITALY, "%.1f", v)}")
            }
            obd.dtcs?.let { Text(if (it.isEmpty()) "Nessun codice di errore" else "Codici errore: ${it.joinToString()}") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = device != null, onClick = { ObdManager.start(ctx) }) { Text("Prova") }
                OutlinedButton(enabled = obd.connected, onClick = { ObdManager.requestDtc() }) { Text("Leggi errori") }
                OutlinedButton(onClick = { ObdManager.stop() }) { Text("Ferma") }
            }
        }

        Section("4. Carburante per la ricerca prezzi") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Benzina", "Gasolio", "GPL", "Metano").forEach { f ->
                    FilterChip(selected = fuel == f, onClick = { fuel = f; Prefs.setFuel(ctx, f) }, label = { Text(f) })
                }
            }
        }

        Section("Aggiornamenti") {
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

        Section("Rapporti") {
            Text("Salvati in automatico dopo ogni operazione, a ogni errore e alla chiusura, nella cartella Download/${Reports.FOLDER}. Sincronizzala su Google Drive per farli leggere a Claude.")
            OutlinedButton(onClick = { Reports.flush("manuale"); saved = "Rapporto salvato" }) { Text("Salva rapporto ora") }
            if (saved.isNotEmpty()) Text(saved)
        }

        Section("Novità") {
            Text(remember { ctx.assets.open("CHANGELOG.md").bufferedReader().use { it.readText() } })
        }
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
