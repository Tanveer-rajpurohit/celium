package com.lagradost.cloudstream3.ui.celium

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.flow.MutableStateFlow

/** Preferences shared by every consumer screen, independent of the extension runtime. */
object CeliumStyle {
    const val DEFAULT_MODE = "Light"
    val MODES = listOf("Dark", "Light", "System")

    /** Pure: a missing or unknown saved mode renders as [DEFAULT_MODE]. The stored value is not rewritten. */
    fun normalizeMode(saved: String?): String = saved?.takeIf { it in MODES } ?: DEFAULT_MODE

    val mode = MutableStateFlow(normalizeMode(getKey<String>("celium_theme_v2")))
    val accent = MutableStateFlow(getKey<String>("celium_accent_v2") ?: "")
    fun mode(value: String) {
        if (value !in MODES) return
        mode.value = value
        setKey("celium_theme_v2", value)
    }
    /** Stores an empty value (neutral) or exactly 6 hex digits with an optional '#'. Returns false and stores nothing otherwise. */
    fun accent(value: String): Boolean {
        val trimmed = value.trim()
        val safe = if (trimmed.isEmpty()) "" else CeliumColor.parseHex(trimmed)?.let(CeliumColor::formatHex) ?: return false
        accent.value = safe
        setKey("celium_accent_v2", safe)
        return true
    }

    /** Pure: whether [mode] renders dark, given the system night setting. */
    fun isDark(mode: String, systemDark: Boolean): Boolean = mode == "Dark" || (mode == "System" && systemDark)

    /** Theme background as opaque ARGB; shared by Compose, the window bars, and the bottom nav. */
    fun backgroundArgb(dark: Boolean): Int = if (dark) 0xFF0C0C0C.toInt() else 0xFFFAFAFA.toInt()

    /** Foreground on [backgroundArgb] as opaque ARGB. */
    fun onBackgroundArgb(dark: Boolean): Int = if (dark) 0xFFF5F5F5.toInt() else 0xFF242424.toInt()

    /**
     * Colours the status and navigation bars with the theme background and picks icon contrast
     * (dark icons on light themes, light icons on dark themes). Only changes colour/appearance,
     * never visibility, so the player's immersive mode is unaffected (Req 11.2–11.5, 11.7).
     */
    fun applySystemBars(window: Window, view: View, dark: Boolean) {
        val background = backgroundArgb(dark)
        @Suppress("DEPRECATION")
        run {
            // Ignored on API 35+, where edge-to-edge bars are transparent and show the content behind.
            window.statusBarColor = background
            window.navigationBarColor = background
        }
        // Without this, API 29+ draws a translucent scrim over the gesture/nav bar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

/** Unwraps a [Context] to its hosting [Activity], if any. */
internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * System-bar padding for Celium screens that are NOT hosted in a BaseComposeFragment.
 * BaseComposeFragment already pads its root view via fixSystemBarsPadding (top + end, and bottom
 * in landscape), and the bottom nav pads the navigation bar, so applying this there would double-pad.
 */
fun Modifier.celiumSystemBars(): Modifier =
    windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))

/** CeliumColor returns 24-bit 0xRRGGBB; add opaque alpha for Compose. */
internal fun opaque(rgb: Int): Color = Color(0xFF000000.toInt() or (rgb and 0xFFFFFF))

@Composable
fun CeliumTheme(content: @Composable () -> Unit) {
    val mode by CeliumStyle.mode.collectAsState()
    val custom by CeliumStyle.accent.collectAsState()
    val dark = CeliumStyle.isDark(mode, isSystemInDarkTheme())
    // Re-colour the system bars whenever the theme changes, without a restart (Req 11.5).
    val view = LocalView.current
    if (!view.isInEditMode) {
        DisposableEffect(view, dark) {
            view.context.findActivity()?.window?.let { CeliumStyle.applySystemBars(it, view, dark) }
            onDispose { }
        }
    }
    // Saved accent (or the mode's neutral), passed through the 4.5:1 contrast guard.
    val accentRgb = androidx.compose.runtime.remember(custom, dark) { CeliumColor.effectiveAccent(custom, dark) }
    val primary = opaque(accentRgb)
    // Choose the foreground by luminance, including arbitrary user-selected accents.
    val foreground = opaque(CeliumColor.foregroundFor(accentRgb))
    val colors = if (dark) darkColorScheme(
        primary = primary, onPrimary = foreground,
        secondary = primary, onSecondary = foreground,
        background = Color(CeliumStyle.backgroundArgb(true)), onBackground = Color(CeliumStyle.onBackgroundArgb(true)),
        surface = Color(0xFF161616), onSurface = Color(0xFFF5F5F5),
        surfaceVariant = Color(0xFF242424), onSurfaceVariant = Color(0xFFBBBBBB),
        secondaryContainer = Color(0xFF303030), onSecondaryContainer = Color.White,
        outline = Color(0xFF777777), error = Color(0xFFFFB4AB),
    ) else lightColorScheme(
        primary = primary, onPrimary = foreground,
        secondary = primary, onSecondary = foreground,
        background = Color(CeliumStyle.backgroundArgb(false)), onBackground = Color(CeliumStyle.onBackgroundArgb(false)),
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
