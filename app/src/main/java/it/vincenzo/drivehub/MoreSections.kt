package it.vincenzo.drivehub

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.features.DashcamService
import it.vincenzo.drivehub.features.Parking
import it.vincenzo.drivehub.features.Trips
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun f(v: Double?, d: Int = 1) = v?.let { String.format(Locale.ITALY, "%.${d}f", it) } ?: "—"
private fun date(t: Long, pattern: String = "dd/MM HH:mm") = SimpleDateFormat(pattern, Locale.ITALY).format(Date(t))

@Composable
fun TripsSection() {
    val ctx = LocalContext.current
    var odo by remember { mutableStateOf(Prefs.odometerKm(ctx).takeIf { it > 0 }?.toInt()?.toString() ?: "") }
    var service by remember { mutableStateOf(Prefs.nextServiceKm(ctx).takeIf { it > 0 }?.toString() ?: "") }
    var tyres by remember { mutableStateOf(Prefs.nextTyresKm(ctx).takeIf { it > 0 }?.toString() ?: "") }
    var saved by remember { mutableStateOf("") }
    val trips = remember { Trips.list(ctx).take(10) }
    val numbers = KeyboardOptions(keyboardType = KeyboardType.Number)

    Section("Viaggi e manutenzione") {
        Text("Il contachilometri cresce da solo con i km dei viaggi. I promemoria partono a 500 km dalla scadenza, a voce e sull'orologio.")
        OutlinedTextField(odo, { odo = it.filter(Char::isDigit) }, label = { Text("Km attuali dell'auto") }, keyboardOptions = numbers)
        OutlinedTextField(service, { service = it.filter(Char::isDigit) }, label = { Text("Prossimo tagliando a km") }, keyboardOptions = numbers)
        OutlinedTextField(tyres, { tyres = it.filter(Char::isDigit) }, label = { Text("Prossimo cambio gomme a km") }, keyboardOptions = numbers)
        Button(onClick = {
            odo.toDoubleOrNull()?.let { Prefs.setOdometerKm(ctx, it) }
            Prefs.setMaintenance(ctx, service.toIntOrNull() ?: 0, tyres.toIntOrNull() ?: 0)
            saved = "Salvato"
        }) { Text("Salva") }
        if (saved.isNotEmpty()) Text(saved)
        if (trips.isEmpty()) Text("Nessun viaggio registrato.")
        trips.forEach { t ->
            Text("${date(t.start)} · ${f(t.km)} km · ${t.minutes} min · ${f(t.l100)} l/100 · € ${f(t.cost, 2)}")
        }
    }
}

@Composable
fun ParkingSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val spot = remember(tick) { Prefs.parking(ctx) }
    val end = remember(tick) { Prefs.parkingEnd(ctx) }

    Section("Parcheggio") {
        Text(spot?.let { "Auto lasciata il ${date(it.time)}" } ?: "Nessuna posizione salvata: si salva da sola quando scolleghi il telefono dall'auto.")
        if (end > 0) Text("Sosta fino alle ${date(end, "HH:mm")}")
        if (spot != null) {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${spot.lat},${spot.lon}(Auto)")))
            }) { Text("Apri in Maps") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Parking.startTimer(ctx, 60); tick++ }) { Text("Sosta 1 h") }
            OutlinedButton(onClick = { Parking.startTimer(ctx, 120); tick++ }) { Text("Sosta 2 h") }
            OutlinedButton(onClick = { Parking.cancelTimer(ctx); tick++ }) { Text("Annulla") }
        }
    }
}

@Composable
fun DashcamSection() {
    val ctx = LocalContext.current
    var autoCam by remember { mutableStateOf(Prefs.autoDashcam(ctx)) }
    var autoMusic by remember { mutableStateOf(Prefs.autoMusic(ctx)) }
    var tick by remember { mutableStateOf(0) }
    val running = remember(tick) { DashcamService.running != null }

    Section("Dashcam e avvio automatico") {
        Text(
            "La dashcam usa la fotocamera posteriore del Pixel sul supporto: registra a ciclo gli ultimi 5 minuti, senza audio. " +
                "A una frenata brusca o un urto, o con il pulsante sul display, salva la clip in Movies/DriveHub. " +
                "Se pubblichi un video, attenzione a targhe e persone riconoscibili."
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(autoCam, { autoCam = it; Prefs.setAutoDashcam(ctx, it) })
            Text("  Dashcam all'avvio in auto")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(autoMusic, { autoMusic = it; Prefs.setAutoMusic(ctx, it) })
            Text("  Riprendi la musica all'avvio in auto")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DashcamService.start(ctx); tick++ }, enabled = !running) { Text("Avvia dashcam") }
            OutlinedButton(onClick = { DashcamService.stop(ctx); tick++ }) { Text("Ferma") }
        }
        Text(if (running) "Dashcam in registrazione" else "Dashcam spenta")
    }
}
