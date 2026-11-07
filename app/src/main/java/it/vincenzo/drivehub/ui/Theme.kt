package it.vincenzo.drivehub.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Colori fissi del display dell'auto (sempre scuro, per non abbagliare di notte) */
object CarColors {
    val bgTop = Color(0xFF0B1220)
    val bgBottom = Color(0xFF131C2E)
    val tile = Color(0x1AFFFFFF)
    val text = Color(0xFFF1F4F9)
    val muted = Color(0xFF9AA6BA)
    val accent = Color(0xFF8AB4F8)
    val warn = Color(0xFFFFC107)
    val ok = Color(0xFF6DD58C)
}

private val BrandBlue = Color(0xFF1A73E8)

/** Stile Google Pixel: colori dinamici dallo sfondo del telefono, angoli molto arrotondati */
@Composable
fun DriveHubTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = CarColors.accent)
        else -> lightColorScheme(primary = BrandBlue)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Scheda di una sezione dell'app: icona in un cerchio colorato, titolo, contenuto */
@Composable
fun Section(title: String, icon: ImageVector? = null, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Box(Modifier.size(12.dp))
                }
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            content()
        }
    }
}

/** Pastiglia di stato: pallino verde se attivo, grigio se spento */
@Composable
fun StatusChip(label: String, on: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) CarColors.ok else MaterialTheme.colorScheme.outline))
            Box(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
