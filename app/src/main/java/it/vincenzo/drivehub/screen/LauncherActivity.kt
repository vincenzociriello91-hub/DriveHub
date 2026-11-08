package it.vincenzo.drivehub.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.core.Reports
import it.vincenzo.drivehub.core.SpeedMonitor
import it.vincenzo.drivehub.core.Locator
import it.vincenzo.drivehub.features.AlertMonitor
import it.vincenzo.drivehub.features.AlertRules
import it.vincenzo.drivehub.features.DashcamService
import it.vincenzo.drivehub.features.Refuels
import it.vincenzo.drivehub.weather.Weather
import it.vincenzo.drivehub.ui.CarColors
import it.vincenzo.drivehub.ui.CarColorsLight
import it.vincenzo.drivehub.core.SunTimes
import android.location.Location
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Schermata iniziale del display dell'auto: barra con ora e stato, poi tutte le app del telefono */
class LauncherActivity : ComponentActivity() {
    // cresce a ogni ritorno sul launcher: riordina le app con le ultime usate in cima
    private val resumes = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.log("Launcher auto aperto sul display ${display?.displayId}")
        setContent {
            LauncherScreen(resumes.intValue)
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        // la fotocamera in background si può avviare solo con un'app visibile: il launcher sull'auto lo è
        if (Prefs.autoDashcam(this)) DashcamService.start(this)
    }
}

private class AppEntry(val label: String, val component: ComponentName, val icon: ImageBitmap)

/** Colori del display in uso (scuri o chiari) */
private val LocalCar = staticCompositionLocalOf { CarColors }

/** Tema scelto in Altro: "auto" segue il sole (chiaro di giorno, scuro dall'imbrunire) */
internal fun carPalette(theme: String, day: Boolean) = when (theme) {
    "chiaro" -> CarColorsLight
    "scuro" -> CarColors
    else -> if (day) CarColorsLight else CarColors
}

@Composable
private fun LauncherScreen(resumes: Int) {
    val ctx = LocalContext.current
    // una posizione ogni 20 minuti basta per meteo e alba/tramonto (meno GPS, meno batteria)
    var loc by remember { mutableStateOf<Location?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            loc = withContext(Dispatchers.IO) { runCatching { Locator.current(ctx) }.getOrNull() } ?: loc
            delay(20 * 60_000L)
        }
    }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute = System.currentTimeMillis() } }
    val theme = remember(resumes) { Prefs.carTheme(ctx) }
    val day = SunTimes.isDay(loc?.latitude ?: SunTimes.DEFAULT_LAT, loc?.longitude ?: SunTimes.DEFAULT_LON, minute)
    val c = carPalette(theme, day)
    val scheme = if (c === CarColors) darkColorScheme(primary = c.accent) else lightColorScheme(primary = c.accent)
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalCar provides c) {
            Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(c.bgTop, c.bgBottom)))) {
                TopBar(loc)
                AppGrid(resumes)
            }
        }
    }
}

/** Ora, data, meteo, avviso attivo, autonomia, velocità e modalità passeggero */
@Composable
private fun TopBar(loc: Location?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val speed = if (SpeedMonitor.known()) SpeedMonitor.kmh else null
    val ctx = LocalContext.current
    var weather by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(loc) {
        val l = loc ?: return@LaunchedEffect
        weather = withContext(Dispatchers.IO) {
            runCatching { val f = Weather.load(l.latitude, l.longitude); "${f.temp.toInt()}° ${Weather.describe(f.code)}" }.getOrNull()
        } ?: weather
    }
    LaunchedEffect(Unit) { while (true) { range = withContext(Dispatchers.IO) { Refuels.currentRangeKm(ctx) }; delay(60_000) } }
    val c = LocalCar.current
    val alert = AlertMonitor.lastAlert?.takeIf { now - it.second < 10 * 60_000L }?.first
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(now)), color = c.text,
            fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp))
        // su schermi stretti (SYNC 2.5, 800x480) data e meteo si accorciano per lasciare posto alle pastiglie
        Text(SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(Date(now)).replaceFirstChar { it.uppercase() } +
            (weather?.let { " · $it" } ?: ""),
            color = c.muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        alert?.let { Pill(AlertRules.label(it), c.warn); Spacer(Modifier.width(10.dp)) }
        range?.let { Pill("${it.toInt()} km", c.ok); Spacer(Modifier.width(10.dp)) }
        if (ScreenController.passengerMode) Pill("Passeggero", c.warn)
        if (speed != null) {
            Spacer(Modifier.width(10.dp))
            Pill("${speed.toInt()} km/h", c.accent)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}

@Composable
private fun AppGrid(resumes: Int) {
    val ctx = LocalContext.current
    val c = LocalCar.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val list = remember(apps, resumes) {
        val recent = Prefs.recentApps(ctx)
        apps?.sortedBy { a -> recent.indexOf(a.component.flattenToString()).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Caricamento app…", color = c.muted) }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(list, key = { it.component.flattenToString() }) { app ->
            Column(
                Modifier.clip(RoundedCornerShape(24.dp)).background(c.tile)
                    .clickable { ScreenController.launch(app.component) }
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(60.dp))
                Spacer(Modifier.size(8.dp))
                Text(app.label, color = c.text, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** App installate con un'icona nel launcher (esclusa DriveHub) */
internal fun launchableActivities(ctx: Context): List<ResolveInfo> =
    ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .filter { it.activityInfo.packageName != ctx.packageName }

private fun loadApps(ctx: Context): List<AppEntry> {
    val pm = ctx.packageManager
    return launchableActivities(ctx)
        .map {
            AppEntry(
                it.loadLabel(pm).toString(),
                ComponentName(it.activityInfo.packageName, it.activityInfo.name),
                it.loadIcon(pm).toBitmap(160, 160).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}
