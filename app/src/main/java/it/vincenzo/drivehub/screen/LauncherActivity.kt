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
import it.vincenzo.drivehub.features.DashcamService
import it.vincenzo.drivehub.ui.CarColors
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
            MaterialTheme(colorScheme = darkColorScheme(primary = CarColors.accent)) { LauncherScreen(resumes.intValue) }
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

@Composable
private fun LauncherScreen(resumes: Int) {
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(CarColors.bgTop, CarColors.bgBottom)))
    ) {
        TopBar()
        AppGrid(resumes)
    }
}

/** Ora, data, velocità e modalità passeggero, aggiornate ogni secondo */
@Composable
private fun TopBar() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val speed = if (SpeedMonitor.known()) SpeedMonitor.kmh else null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(now)), color = CarColors.text,
            fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(16.dp))
        Text(SimpleDateFormat("EEEE d MMMM", Locale.ITALY).format(Date(now)).replaceFirstChar { it.uppercase() },
            color = CarColors.muted, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        if (ScreenController.passengerMode) Pill("Passeggero", CarColors.warn)
        if (speed != null) {
            Spacer(Modifier.width(10.dp))
            Pill("${speed.toInt()} km/h", CarColors.accent)
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
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val list = remember(apps, resumes) {
        val recent = Prefs.recentApps(ctx)
        apps?.sortedBy { a -> recent.indexOf(a.component.flattenToString()).let { if (it < 0) Int.MAX_VALUE else it } }
    }
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Caricamento app…", color = CarColors.muted) }
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
                Modifier.clip(RoundedCornerShape(24.dp)).background(CarColors.tile)
                    .clickable { ScreenController.launch(app.component) }
                    .padding(vertical = 14.dp, horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(60.dp))
                Spacer(Modifier.size(8.dp))
                Text(app.label, color = CarColors.text, fontSize = 14.sp, maxLines = 1,
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
