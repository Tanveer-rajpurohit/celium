package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.utils.Qualities

/** Display labels only; provider names and link identities stay untouched. */
object StreamLabels {
    fun source(name: String): String = name
        .replace(Regex("[\\p{So}\\p{Sk}\\p{Cf}\\uFE0F\\u20E3]"), "")
        .replace(Regex("[\\[\\]]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim(' ', '-', '|', '·')
        .ifBlank { "Source" }

    fun quality(height: Int?): String = when (height) {
        null, Qualities.Unknown.value -> "Quality unknown"
        0 -> "Auto"
        else -> if (height > 0) "${height}p" else "Quality unknown"
    }

    fun choice(name: String, height: Int?, index: Int): String =
        "${index + 1}. ${source(name)} · ${quality(height)}"
}
