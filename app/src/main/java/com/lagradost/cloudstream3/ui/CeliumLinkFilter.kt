package com.lagradost.cloudstream3.ui

import java.util.Locale

/**
 * Link sources Celium never shows, even when an aggregator (e.g. CineStream) returns them.
 * Dudefilms: mostly theatre camera recordings.
 */
object CeliumLinkFilter {
    private val blocked = listOf("dudefilms")

    private fun normalize(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun isBlocked(source: String, name: String): Boolean {
        val s = normalize(source)
        val n = normalize(name)
        return blocked.any { s.contains(it) || n.contains(it) }
    }
}
