package com.lagradost.cloudstream3.ui.player

/**
 * Android-free description of one playable link for quality/language choices.
 *
 * @param key stable link key
 * @param source extractor/host family (ExtractorLink.source), used to prefer "the same provider"
 * @param name link display name, which often carries language tags ("Dual Audio [Hindi-English]")
 * @param quality raw quality value (height, or Qualities.Unknown = 400)
 */
data class StreamOption(val key: String, val source: String, val name: String, val quality: Int) {
    val bucket: Int? get() = StreamChoice.bucket(quality)
    val languages: Set<String>? get() = StreamChoice.languages(name)
}

/** One row of the quality menu. [working] is false when every link at this quality failed. */
data class QualityRow(val bucket: Int, val label: String, val count: Int, val working: Boolean)

/** What to offer when no link at the wanted quality has the wanted audio language. */
data class QualityFallback(
    /** Other audio languages available at the wanted quality (language tags). */
    val otherLanguages: List<String>,
    /** Nearest quality that has the wanted language: lower first, then higher. */
    val nearestQuality: Int?,
)

enum class LangMatch { Match, Multi, Unknown, Other }

/** Pure YouTube-style quality and audio-language choices (phone player and casting). */
object StreamChoice {
    private const val UNKNOWN_QUALITY = 400 // Qualities.Unknown
    val BUCKETS = listOf(2160, 1440, 1080, 720, 480, 360, 240)

    /** Groups a raw height into a menu quality; null when the quality is unknown. */
    fun bucket(quality: Int): Int? = when {
        quality <= 0 || quality == UNKNOWN_QUALITY -> null
        quality >= 2000 -> 2160
        quality >= 1300 -> 1440
        quality >= 1000 -> 1080
        quality >= 650 -> 720
        quality >= 420 -> 480
        quality >= 300 -> 360
        else -> 240
    }

    fun label(bucket: Int): String = when (bucket) {
        2160 -> "4K"
        1440 -> "1440p (2K)"
        else -> "${bucket}p"
    }

    /** Menu rows for qualities that exist, highest first. */
    fun available(options: List<StreamOption>, failed: Set<String>): List<QualityRow> =
        options.groupBy { it.bucket }.filterKeys { it != null }
            .map { (bucket, links) ->
                QualityRow(bucket!!, label(bucket), links.size, links.any { it.key !in failed })
            }
            .sortedByDescending { it.bucket }

    /** Highest quality Auto may start with, from an estimated bitrate (bits per second). */
    fun capFor(bitrateBps: Long?): Int {
        val kbps = (bitrateBps ?: return 1080) / 1000
        return when {
            kbps >= 25_000 -> 2160
            kbps >= 12_000 -> 1440
            kbps >= 6_000 -> 1080
            kbps >= 3_000 -> 720
            kbps >= 1_500 -> 480
            else -> 360
        }
    }

    // ---- Audio language ----

    private val names = mapOf(
        "english" to "en", "eng" to "en",
        "hindi" to "hi", "hin" to "hi",
        "tamil" to "ta", "tam" to "ta",
        "telugu" to "te", "tel" to "te",
        "malayalam" to "ml", "mal" to "ml",
        "kannada" to "kn", "kan" to "kn",
        "bengali" to "bn", "bangla" to "bn", "ben" to "bn",
        "marathi" to "mr", "mar" to "mr",
        "punjabi" to "pa", "pan" to "pa",
        "gujarati" to "gu", "guj" to "gu",
        "urdu" to "ur", "urd" to "ur",
        "japanese" to "ja", "jap" to "ja", "jpn" to "ja",
        "korean" to "ko", "kor" to "ko",
        "chinese" to "zh", "mandarin" to "zh", "cantonese" to "zh", "chi" to "zh",
        "spanish" to "es", "spa" to "es", "esp" to "es",
        "french" to "fr", "fre" to "fr", "fra" to "fr",
        "german" to "de", "ger" to "de", "deu" to "de",
        "italian" to "it", "ita" to "it",
        "portuguese" to "pt", "por" to "pt",
        "russian" to "ru", "rus" to "ru",
        "arabic" to "ar", "ara" to "ar",
        "turkish" to "tr", "tur" to "tr",
        "thai" to "th",
        "indonesian" to "id",
    )
    private val displayNames = mapOf(
        "en" to "English", "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam",
        "kn" to "Kannada", "bn" to "Bengali", "mr" to "Marathi", "pa" to "Punjabi", "gu" to "Gujarati",
        "ur" to "Urdu", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "es" to "Spanish",
        "fr" to "French", "de" to "German", "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian",
        "ar" to "Arabic", "tr" to "Turkish", "th" to "Thai", "id" to "Indonesian", MULTI to "Multiple languages",
    )
    private val subtitleWords = setOf("sub", "subs", "subbed", "subtitle", "subtitles", "esub", "esubs", "msub", "msubs")

    /** Several audio languages, not named ("Dual Audio", "Multi Audio"). */
    const val MULTI = "mul"

    /**
     * Audio languages named in a link title, as two-letter tags; [MULTI] for unnamed dual/multi audio.
     * Words describing subtitles ("English Subs", "ESub") are ignored. Null when nothing is said.
     */
    fun languages(name: String): Set<String>? {
        val words = name.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val found = linkedSetOf<String>()
        words.forEachIndexed { i, word ->
            val tag = names[word] ?: return@forEachIndexed
            if (words.getOrNull(i + 1) in subtitleWords) return@forEachIndexed
            found += tag
        }
        if (found.isEmpty() && words.any { it == "dual" || it == "multi" || it == "multiaudio" || it == "dualaudio" }) found += MULTI
        return found.takeIf { it.isNotEmpty() }
    }

    /** Normalises a player/ISO language code ("eng", "en-US", "hin") to a two-letter tag. */
    fun normalize(code: String?): String? {
        val base = code?.lowercase()?.substringBefore('-')?.substringBefore('_')?.trim()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        return if (base.length == 2) base else names[base] ?: base
    }

    fun languageName(tag: String?): String = tag?.let { displayNames[it] ?: it.uppercase() } ?: "Unknown"

    fun match(languages: Set<String>?, wanted: String?): LangMatch = when {
        wanted == null -> LangMatch.Match
        languages == null -> LangMatch.Unknown
        wanted in languages -> LangMatch.Match
        MULTI in languages -> LangMatch.Multi
        else -> LangMatch.Other
    }

    // ---- Ordering ----

    /**
     * Links to try for quality [bucket] in audio [language]: matching language first, then
     * multi-audio, then unnamed language; links from [currentSource] first within each group.
     * Links in another language are never included (they need the user's consent).
     */
    fun candidates(options: List<StreamOption>, bucket: Int, language: String?, currentSource: String?, failed: Set<String> = emptySet()): List<StreamOption> =
        options.filter { it.bucket == bucket && it.key !in failed }
            .map { it to match(it.languages, language) }
            .filter { it.second != LangMatch.Other }
            .sortedWith(compareBy<Pair<StreamOption, LangMatch>> { it.second.ordinal }.thenBy { if (it.first.source == currentSource) 0 else 1 })
            .map { it.first }

    /**
     * Auto: every link in the order to try. Highest quality within [cap] in the wanted language
     * first; then unnamed language; then qualities above the cap (nearest first); then unknown
     * quality; links in other languages come last so playback can still start.
     */
    fun autoOrder(options: List<StreamOption>, cap: Int, language: String?, failed: Set<String> = emptySet()): List<StreamOption> {
        fun tier(o: StreamOption): Int {
            val m = match(o.languages, language)
            val b = o.bucket
            return when {
                m == LangMatch.Other -> 5
                b == null -> 4
                b > cap -> 3
                m == LangMatch.Unknown -> 2
                else -> 1
            }
        }
        return options.filter { it.key !in failed }.sortedWith(
            compareBy<StreamOption> { tier(it) }
                .thenBy { o -> val b = o.bucket ?: 0; if (b > cap) b else -b } // within cap: highest; above: nearest
                .thenBy { match(it.languages, language).ordinal }
        )
    }

    /** Options when nothing at [bucket] matches [language]. */
    fun fallback(options: List<StreamOption>, bucket: Int, language: String?, failed: Set<String> = emptySet()): QualityFallback {
        val usable = options.filter { it.key !in failed }
        val others = usable.filter { it.bucket == bucket }
            .flatMap { it.languages.orEmpty() }
            .filter { it != language && it != MULTI }
            .distinct()
        val withLanguage = usable.filter { it.bucket != null && it.bucket != bucket && match(it.languages, language) != LangMatch.Other }
            .mapNotNull { it.bucket }.distinct()
        val nearest = withLanguage.filter { it < bucket }.maxOrNull() ?: withLanguage.filter { it > bucket }.minOrNull()
        return QualityFallback(others, nearest)
    }

    /** The one language a link is known to be in, or null when unknown or mixed. */
    fun singleLanguage(name: String): String? = languages(name)?.singleOrNull()?.takeIf { it != MULTI }
}
