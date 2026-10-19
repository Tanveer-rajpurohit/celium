package com.lagradost.cloudstream3.ui.celium

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Hue in degrees (0..360), saturation and value in 0..1. */
data class Hsv(val h: Float, val s: Float, val v: Float)

/**
 * Pure colour helpers for the accent picker and the contrast guard. No Android imports.
 *
 * Inputs are Ints; any alpha byte is ignored. Every returned colour is 24-bit `0xRRGGBB`
 * (callers add `0xFF000000` for an opaque ARGB value).
 * [luminance], [contrast], and [blend] mirror `androidx.core.graphics.ColorUtils`
 * (`calculateLuminance`, `calculateContrast`, `blendARGB`) so [guard] matches the
 * contrast loop that `CeliumTheme` used before this file existed.
 */
object CeliumColor {
    const val DARK_CANVAS = 0x0C0C0C
    const val LIGHT_CANVAS = 0xFAFAFA
    const val NEUTRAL_DARK = 0xF5F5F5
    const val NEUTRAL_LIGHT = 0x242424
    const val BLACK = 0x000000
    const val WHITE = 0xFFFFFF
    const val MIN_CONTRAST = 4.5
    const val GUARD_STEPS = 20

    private val HEX6 = Regex("[0-9A-Fa-f]{6}")

    fun canvas(dark: Boolean): Int = if (dark) DARK_CANVAS else LIGHT_CANVAS
    fun neutral(dark: Boolean): Int = if (dark) NEUTRAL_DARK else NEUTRAL_LIGHT

    /** Trims, removes one optional leading '#', and accepts exactly 6 hex digits. */
    fun parseHex(input: String): Int? {
        val body = input.trim().removePrefix("#")
        if (!HEX6.matches(body)) return null
        return body.toInt(16)
    }

    /** Six upper-case hex digits, no '#'. */
    fun formatHex(rgb: Int): String = "%06X".format(rgb and 0xFFFFFF)

    fun rgbToHsv(rgb: Int): Hsv {
        val r = red(rgb) / 255f
        val g = green(rgb) / 255f
        val b = blue(rgb) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val delta = maxC - minC
        val h = when {
            delta == 0f -> 0f
            maxC == r -> 60f * (((g - b) / delta) % 6f)
            maxC == g -> 60f * (((b - r) / delta) + 2f)
            else -> 60f * (((r - g) / delta) + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (maxC == 0f) 0f else delta / maxC
        return Hsv(h, s, maxC)
    }

    /** Rounds each channel to the nearest 0..255 value. Out-of-range inputs are clamped. */
    fun hsvToRgb(hsv: Hsv): Int {
        val h = ((hsv.h % 360f) + 360f) % 360f
        val s = hsv.s.coerceIn(0f, 1f)
        val v = hsv.v.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return rgbOf(ch(r1), ch(g1), ch(b1))
    }

    /** WCAG relative luminance, computed exactly like `ColorUtils.calculateLuminance`. */
    fun luminance(rgb: Int): Double {
        fun lin(c: Int): Double {
            val sc = c / 255.0
            return if (sc < 0.04045) sc / 12.92 else ((sc + 0.055) / 1.055).pow(2.4)
        }
        val y = 100 * (lin(red(rgb)) * 0.2126 + lin(green(rgb)) * 0.7152 + lin(blue(rgb)) * 0.0722)
        return y / 100
    }

    /** WCAG contrast ratio (L1 + 0.05) / (L2 + 0.05), L1 the lighter. Symmetric. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a) + 0.05
        val lb = luminance(b) + 0.05
        return max(la, lb) / min(la, lb)
    }

    /** Per-channel `a * (1 - ratio) + b * ratio` in Float, truncated like `ColorUtils.blendARGB`. */
    fun blend(a: Int, b: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = red(a) * inv + red(b) * ratio
        val g = green(a) * inv + green(b) * ratio
        val bl = blue(a) * inv + blue(b) * ratio
        return rgbOf(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), bl.toInt().coerceIn(0, 255))
    }

    /**
     * Blends toward white (dark) or black (light) in 1/20 steps until contrast with the
     * mode's canvas is at least 4.5:1. Step 20 is pure white/black, which always passes.
     */
    fun guard(rgb: Int, dark: Boolean): Int {
        val original = rgb and 0xFFFFFF
        val canvas = canvas(dark)
        val target = if (dark) WHITE else BLACK
        var safe = original
        var step = 0
        while (contrast(safe, canvas) < MIN_CONTRAST && step < GUARD_STEPS) {
            step++
            safe = blend(original, target, step / GUARD_STEPS.toFloat())
        }
        return safe
    }

    /** Saved hex, or the mode's neutral when empty or invalid; always passed through [guard]. */
    fun effectiveAccent(saved: String, dark: Boolean): Int =
        guard(parseHex(saved) ?: neutral(dark), dark)

    /** Black when luminance > 0.179, otherwise white. */
    fun foregroundFor(rgb: Int): Int = if (luminance(rgb) > 0.179) BLACK else WHITE

    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun rgbOf(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b
}
