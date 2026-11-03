package com.lagradost.cloudstream3.ui.search

import java.text.Normalizer
import java.util.Locale

/** A provider title reduced to what identifies the work (Celium search, step 3). */
internal data class CleanTitle(
    /** Normalized words joined by one space, without quality, language, size and season tags. */
    val text: String,
    /** [text] without spaces, so "spiderman" and "Spider-Man" meet. */
    val compact: String,
    /** A year taken from the title ("(2021)" or a trailing "2021"), if any. */
    val year: Int?,
    val season: Int?,
    /** [compact] without a trailing bare year, so "Dune 2021" and "Dune (2021)" group together. */
    val baseCompact: String = compact,
)

/** The two comparable forms of one title: as written, and cleaned. */
internal data class TitleForms(val normalized: String, val cleaned: CleanTitle)

/**
 * Cleans noisy provider titles such as "Dune Part Two (2024) 1080p WEB-DL Hindi Dual Audio [x264] 2.5GB".
 * All rules live here, in data, so a changed provider format is a one-line fix with a test.
 * No Android dependencies.
 */
internal object TitleCleaner {
    private val latinMarks = Regex("(?<=[A-Za-z])\\p{M}+")
    private val punctuation = Regex("[^\\p{L}\\p{N}\\p{M}]+")
    private val whitespace = Regex("\\s+")

    /** Case, accents on Latin letters and punctuation folded away. Same rules the ranking always used. */
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(latinMarks, "").lowercase(Locale.ROOT).replace(punctuation, " ").trim()

    fun compact(normalized: String): String = normalized.replace(" ", "")

    private val squareOrCurly = Regex("""\[[^\]]*\]|\{[^\}]*\}""")
    private val parenGroup = Regex("""\(([^\(\)]*)\)""")
    private val bracketedYear = Regex("""[\(\[]\s*((?:19|20)\d{2})\s*[\)\]]""")
    private val yearWord = Regex("""(?:19|20)\d{2}""")

    /** Multi-word tags, matched before punctuation is folded. */
    private val phrases = Regex(
        """web[\s._-]?dl|web[\s._-]?rip|blu[\s._-]?ray|bd[\s._-]?rip|br[\s._-]?rip|dvd[\s._-]?rip|hd[\s._-]?rip|hd[\s._-]?tv|""" +
            """dual[\s._-]?audio|multi[\s._-]?audio|full[\s._-]?movie|watch[\s._-]?online|free[\s._-]?download|""" +
            """hindi[\s._-]?dubbed|org[\s._-]?hindi|\d+(?:\.\d+)?\s?(?:gb|mb)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val noiseWords = setOf(
        "hd", "uhd", "fhd", "4k", "2k", "hdrip", "webrip", "bluray", "brrip", "bdrip", "dvdrip", "hdtv",
        "hdcam", "camrip", "hdts", "dvdscr", "predvd", "hevc", "avc", "x264", "x265", "h264", "h265",
        "10bit", "8bit", "hdr", "hdr10", "sdr", "aac", "ac3", "eac3", "ddp", "atmos", "esub", "esubs", "msub",
        "subbed", "dubbed", "hindi", "english", "tamil", "telugu", "malayalam", "kannada", "punjabi", "bengali",
        "marathi", "gujarati", "bangla", "urdu", "amzn", "dsnp", "zee5", "download",
    )
    private val noisePattern = Regex("""\d{3,4}p|\d+bit|x26[45]|h26[45]|ddp?\d(?:\d)?|dd\d""")
    private val seasonEpisodeToken = Regex("""s\d{1,2}(?:e\d{1,3})?|e\d{1,3}|ep\d{1,3}""")
    private val digitsOnly = Regex("""\d{1,3}""")

    private fun isNoise(token: String): Boolean =
        token in noiseWords || noisePattern.matches(token)

    fun forms(title: String, maxYear: Int = SearchQuery.currentYear() + 1): TitleForms =
        TitleForms(normalize(title), clean(title, maxYear))

    private val memo = object : LinkedHashMap<String, CleanTitle>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CleanTitle>?): Boolean = size > 1000
    }

    /** [clean] with the default year limit, remembered: the same provider titles are cleaned on every search update. */
    @Synchronized fun cleanCached(raw: String): CleanTitle {
        memo[raw]?.let { return it }
        return clean(raw).also { memo[raw] = it }
    }

    fun clean(raw: String, maxYear: Int = SearchQuery.currentYear() + 1): CleanTitle {
        var year: Int? = bracketedYear.findAll(raw).lastOrNull { it.groupValues[1].toInt() <= maxYear }
            ?.groupValues?.get(1)?.toInt()

        var s = raw.replace(squareOrCurly, " ")
        // A parenthesis that holds only tags or a year is metadata; any other is part of the title.
        s = s.replace(parenGroup) { m ->
            val tokens = normalize(m.groupValues[1]).split(' ').filter { it.isNotEmpty() }
            val metadata = tokens.isNotEmpty() && tokens.all { yearWord.matches(it) || isNoise(it) || seasonEpisodeToken.matches(it) }
            if (metadata) " " else " ${m.groupValues[1]} "
        }
        s = s.replace(phrases, " ")

        val words = normalize(s).split(' ').filter { it.isNotEmpty() }
        val kept = ArrayList<String>(words.size)
        var season: Int? = null
        var i = 0
        while (i < words.size) {
            val w = words[i]
            when {
                isNoise(w) -> {}
                w == "season" || w == "seasons" || w == "episode" || w == "episodes" -> {
                    // "Season 1 5" or "Episodes 1 12": the marker and the numbers after it are tags.
                    var j = i + 1
                    var first = true
                    while (j < words.size && digitsOnly.matches(words[j]) && j - i <= 2) {
                        if (first && season == null && (w == "season" || w == "seasons")) season = words[j].toInt()
                        first = false
                        j++
                    }
                    i = j - 1
                }
                seasonEpisodeToken.matches(w) -> {
                    if (season == null && w.startsWith("s")) season = Regex("""\d+""").find(w)?.value?.toInt()
                }
                else -> kept.add(w)
            }
            i++
        }
        if (season != null) kept.removeAll { it == "complete" }

        // Never clean a title down to nothing ("Hindi Medium" must stay searchable).
        val text = if (kept.isEmpty()) normalize(raw) else kept.joinToString(" ")
        var baseWords: List<String> = kept
        val last = kept.lastOrNull()
        if (last != null && kept.size > 1 && yearWord.matches(last) && last.toInt() <= maxYear) {
            if (year == null) year = last.toInt()
            baseWords = kept.dropLast(1)
        }
        val base = if (kept.isEmpty()) compact(text) else compact(baseWords.joinToString(" "))
        return CleanTitle(text, compact(text), year, season, base)
    }
}
