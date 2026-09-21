package com.fileforge.converter.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val Fallback = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    onPrimary = androidx.compose.ui.graphics.Color(0xFFFFFFFF),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFEADDFF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF21005D),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFE8DEF8),
    surface = androidx.compose.ui.graphics.Color(0xFFFEF7FF),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE7E0EC),
    onSurface = androidx.compose.ui.graphics.Color(0xFF1D1B1E),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF49454F),
    outline = androidx.compose.ui.graphics.Color(0xFF79747E),
    errorContainer = androidx.compose.ui.graphics.Color(0xFFF9DEDC),
    onErrorContainer = androidx.compose.ui.graphics.Color(0xFF410E0B),
)

@Composable
fun FileForgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> runCatching { dynamicDarkColorScheme(context) }.getOrNull()
        Build.VERSION.SDK_INT >= 31 -> runCatching { dynamicLightColorScheme(context) }.getOrNull()
        else -> null
    } ?: if (dark) darkColorScheme().copy(primary = Fallback.primary) else Fallback

    MaterialTheme(colorScheme = scheme, content = content)
}
