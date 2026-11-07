package it.vincenzo.drivehub.screen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import it.vincenzo.drivehub.core.Prefs
import it.vincenzo.drivehub.features.DashcamService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import it.vincenzo.drivehub.core.Reports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Schermata iniziale del display dell'auto: tutte le app installate sul telefono */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.log("Launcher auto aperto sul display ${display?.displayId}")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { AppGrid() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // la fotocamera in background si può avviare solo con un'app visibile: il launcher sull'auto lo è
        if (Prefs.autoDashcam(this)) DashcamService.start(this)
    }
}

private class AppEntry(val label: String, val component: ComponentName, val icon: ImageBitmap)

@Composable
private fun AppGrid() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }
    val list = apps
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Caricamento app…") }
        return
    }
    LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), contentPadding = PaddingValues(12.dp)) {
        items(list, key = { it.component.flattenToString() }) { app ->
            Column(
                Modifier.clickable { ScreenController.launch(app.component) }.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(app.icon, contentDescription = app.label, modifier = Modifier.size(56.dp))
                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
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
                it.loadIcon(pm).toBitmap(144, 144).asImageBitmap(),
            )
        }
        .sortedBy { it.label.lowercase() }
}
