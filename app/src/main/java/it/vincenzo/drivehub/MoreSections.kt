package it.vincenzo.drivehub

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import it.vincenzo.drivehub.ui.Section
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.Modifier
import it.vincenzo.drivehub.features.AlertRules
import it.vincenzo.drivehub.features.Refuels
import it.vincenzo.drivehub.features.TripStats
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
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

    Section("Viaggi e manutenzione", Icons.Default.DateRange) {
        Text("I km dei viaggi si aggiungono da soli. Promemoria a 500 km dalla scadenza, a voce e sull'orologio.")
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
            Text("${date(t.start)} · ${f(t.km)} km · ${t.minutes} min · ${f(t.l100)} l/100 · € ${f(t.cost, 2)}${t.eco?.let { " · eco $it" } ?: ""}")
        }
    }
}

/** Km per settimana o per mese in barre, con il consumo medio del periodo */
@Composable
fun StatsSection() {
    val ctx = LocalContext.current
    var monthly by remember { mutableStateOf(false) }
    val trips = remember { Trips.list(ctx) }
    val today = remember { java.time.LocalDate.now() }
    val stats = remember(monthly) { if (monthly) TripStats.monthly(trips, today) else TripStats.weekly(trips, today) }
    val max = stats.maxOf { it.km }.coerceAtLeast(1.0)
    val label = java.time.format.DateTimeFormatter.ofPattern(if (monthly) "MMM" else "d/M", Locale.ITALY)

    Section("Km e consumi", Icons.Default.DateRange) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !monthly, onClick = { monthly = false }, label = { Text("Settimane") })
            FilterChip(selected = monthly, onClick = { monthly = true }, label = { Text("Mesi") })
        }
        if (trips.isEmpty()) Text("Le barre compaiono dopo i primi viaggi in auto.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            stats.forEach { p ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(p.km.toInt().toString(), style = MaterialTheme.typography.labelSmall)
                    Box(
                        Modifier.fillMaxWidth().height((110 * p.km / max).dp.coerceAtLeast(2.dp))
                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Text(p.start.format(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        val km = stats.sumOf { it.km }
        val liters = stats.sumOf { it.liters }
        Text("Totale ${f(km, 0)} km${if (km > 1 && liters > 0) " · media ${f(liters * 100 / km)} l/100 km" else ""}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DeadlinesSection() {
    val ctx = LocalContext.current
    val dates = remember { Prefs.DEADLINES.associateWith { mutableStateOf(AlertRules.formatDate(Prefs.deadline(ctx, it))) } }
    var msg by remember { mutableStateOf("") }

    Section("Scadenze", Icons.Default.Notifications) {
        Text("Promemoria a voce e sull'orologio nei 30 giorni prima, quando sali in auto.")
        dates.forEach { (what, value) ->
            OutlinedTextField(value.value, { value.value = it.filter { c -> c.isDigit() || c == '/' } },
                label = { Text("${what.replaceFirstChar(Char::uppercase)} (gg/mm/aaaa)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button(onClick = {
            val parsed = dates.mapValues { AlertRules.parseDate(it.value.value) }
            val bad = parsed.filterValues { it == null }.keys
            msg = if (bad.isNotEmpty()) "Data non valida: ${bad.joinToString()}" else {
                parsed.forEach { (what, day) -> Prefs.setDeadline(ctx, what, day!!) }
                "Salvato"
            }
        }) { Text("Salva") }
        if (msg.isNotEmpty()) Text(msg)
    }
}

@Composable
fun ParkingSection() {
    val ctx = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val spot = remember(tick) { Prefs.parking(ctx) }
    val end = remember(tick) { Prefs.parkingEnd(ctx) }

    Section("Parcheggio", Icons.Default.Place) {
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

    Section("Dashcam e avvio automatico", Icons.Default.PlayArrow) {
        Text(if (running) "In registrazione" else "Spenta", style = MaterialTheme.typography.titleMedium)
        Text(
            "Fotocamera posteriore del Pixel sul supporto: tiene gli ultimi 5 minuti, senza audio. Con frenata brusca, urto o pulsante " +
                "sul display salva la clip in Movies/DriveHub. Se pubblichi un video, attenzione a targhe e volti.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchRow("Dashcam all'avvio in auto", autoCam) { autoCam = it; Prefs.setAutoDashcam(ctx, it) }
        SwitchRow("Riprendi la musica all'avvio in auto", autoMusic) { autoMusic = it; Prefs.setAutoMusic(ctx, it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { DashcamService.start(ctx); tick++ }, enabled = !running) { Text("Avvia dashcam") }
            OutlinedButton(onClick = { DashcamService.stop(ctx); tick++ }, enabled = running) { Text("Ferma") }
        }
    }
}

/** Interruttore con la sua scritta: si tocca tutta la riga e TalkBack legge il nome */
@Composable
private fun SwitchRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(on, onCheckedChange = null)
        Text("  $label")
    }
}

@Composable
fun CarThemeSection() {
    val ctx = LocalContext.current
    var theme by remember { mutableStateOf(Prefs.carTheme(ctx)) }
    Section("Colori del display dell'auto", Icons.Default.Star) {
        Text(
            "Automatico: chiaro di giorno, quando il sole sullo schermo rende difficile leggere, e scuro dall'imbrunire " +
                "per non abbagliare. L'ora di alba e tramonto la calcola DriveHub dalla posizione, senza internet.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("auto" to "Automatico", "scuro" to "Scuro", "chiaro" to "Chiaro").forEach { (k, label) ->
                FilterChip(theme == k, { theme = k; Prefs.setCarTheme(ctx, k) }, { Text(label) })
            }
        }
    }
}

@Composable
fun FuelSection() {
    val ctx = LocalContext.current
    var fuel by remember { mutableStateOf(Prefs.fuel(ctx)) }
    var liters by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val refuels = remember(tick) { Refuels.list(ctx) }
    val real = remember(tick) { Refuels.realL100(refuels) }
    val range = remember(tick) { Refuels.rangeText(ctx) }
    val decimal = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Section("Carburante e rifornimenti", Icons.Default.LocationOn) {
        Text(range, style = MaterialTheme.typography.titleMedium)
        Text("Consumo reale: ${real?.let { "${f(it)} l/100 km" } ?: "servono almeno due pieni"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(liters, { liters = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                label = { Text("Litri") }, keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(price, { price = it.filter { c -> c.isDigit() || c == ',' || c == '.' } },
                label = { Text("€ al litro") }, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }
        Button(enabled = liters.replace(',', '.').toDoubleOrNull()?.let { it in 1.0..Refuels.TANK_L + 5 } == true, onClick = {
            Refuels.add(ctx, liters.replace(',', '.').toDouble(), price.replace(',', '.').toDoubleOrNull()?.takeIf { it in 0.5..5.0 })
            liters = ""; price = ""; tick++
        }) { Text("Segna il pieno") }
        Text("In auto puoi dirlo a voce: \"ho messo 30 litri a 1,85\". Tieni aggiornati i km dell'auto qui sotto, in Viaggi e manutenzione.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        refuels.take(5).forEach { r ->
            Text("${date(r.time)} · ${f(r.liters)} l${r.price?.let { " · € ${f(it, 3)}/l" } ?: ""}${if (r.odoKm > 0) " · ${r.odoKm.toInt()} km" else ""}")
        }
        Text("Carburante per i prezzi sul display:", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Benzina", "Gasolio", "GPL", "Metano").forEach { f ->
                FilterChip(selected = fuel == f, onClick = { fuel = f; Prefs.setFuel(ctx, f) }, label = { Text(f) })
            }
        }
    }
}
