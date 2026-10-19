package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.flow.MutableStateFlow

/** Preferences shared by every consumer screen, independent of the extension runtime. */
object CeliumStyle {
    val mode = MutableStateFlow(getKey<String>("celium_theme_v2") ?: "Dark")
    val accent = MutableStateFlow(getKey<String>("celium_accent_v2") ?: "")
    fun mode(value: String) {
        if (value !in listOf("Dark", "Light", "System")) return
        mode.value = value
        setKey("celium_theme_v2", value)
    }
    fun accent(value: String): Boolean {
        val safe = value.trim().removePrefix("#")
        if (safe.isNotEmpty() && !safe.matches(Regex("[0-9a-fA-F]{6}"))) return false
        accent.value = safe
        setKey("celium_accent_v2", safe)
        return true
    }
}

@Composable
fun CeliumTheme(content: @Composable () -> Unit) {
    val mode by CeliumStyle.mode.collectAsState()
    val custom by CeliumStyle.accent.collectAsState()
    val dark = mode == "Dark" || (mode == "System" && isSystemInDarkTheme())
    val requested = custom.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        ?: if (dark) Color(0xFFF5F5F5) else Color(0xFF242424)
    val canvas = if (dark) 0xFF0C0C0C.toInt() else 0xFFFAFAFA.toInt()
    val primary = androidx.compose.runtime.remember(requested, dark) {
        val original = requested.toArgb()
        var safe = original
        var step = 0
        while (androidx.core.graphics.ColorUtils.calculateContrast(safe, canvas) < 4.5 && step < 20) {
            step++
            safe = androidx.core.graphics.ColorUtils.blendARGB(original, if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK, step / 20f)
        }
        Color(safe)
    }
    // Choose the foreground by luminance, including arbitrary user-selected accents.
    val luminance = androidx.core.graphics.ColorUtils.calculateLuminance(primary.toArgb())
    val foreground = if (luminance > 0.179) Color.Black else Color.White
    val colors = if (dark) darkColorScheme(
        primary = primary, onPrimary = foreground,
        secondary = primary, onSecondary = foreground,
        background = Color(0xFF0C0C0C), onBackground = Color(0xFFF5F5F5),
        surface = Color(0xFF161616), onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFBBBBBB),
        secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White,
        outline = Color(0xFF777777), error = Color(0xFFFFB4AB),
    ) else lightColorScheme(
        primary = primary, onPrimary = foreground,
        secondary = primary, onSecondary = foreground,
        background = Color(0xFFFAFAFA), onBackground = Color(0xFF242424),
        surface = Color.White, onSurface = Color(0xFF242424),
        surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF606060),
        secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF242424),
        outline = Color(0xFF747474),
    )
    val base = Typography()
    val family = FontFamily(Font(R.font.dm_sans))
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
    MaterialTheme(colorScheme = colors, typography = type, content = content)
}
