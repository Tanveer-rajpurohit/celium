package com.lagradost.cloudstream3.cast

import android.content.Context
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.player.CS3IPlayer
import com.lagradost.cloudstream3.ui.player.ConnectionQuality
import com.lagradost.cloudstream3.ui.player.LangMatch
import com.lagradost.cloudstream3.ui.player.QualityRow
import com.lagradost.cloudstream3.ui.player.StreamChoice
import com.lagradost.cloudstream3.ui.player.StreamOption
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.VideoWatchState
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class CastSourceStatus { Waiting, Checking, Playing, Failed }

data class CastSourceInfo(val label: String, val status: CastSourceStatus, val reason: String? = null)

/** A question for the user with one button per option (e.g. "Play 720p in Hindi"). */
data class CastPrompt(val title: String, val message: String, val options: List<Pair<String, () -> Unit>>)

data class CastNowPlaying(
    val title: String,
    val label: String?,
    val poster: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val paused: Boolean = false,
    val loading: Boolean = true,
    val message: String? = null,
    val hasNext: Boolean = false,
    val failed: Boolean = false,
    /** Heading for the failure alert, e.g. "No playable link found". */
    val failTitle: String? = null,
    /** The phone may still play this title: offer mirroring the screen instead. */
    val suggestMirror: Boolean = false,
    /** Sources are still being collected in the background. */
    val findingSources: Boolean = true,
    val sources: List<CastSourceInfo> = emptyList(),
    val currentSource: Int? = null,
    val subtitles: List<String> = emptyList(),
    val currentSubtitle: Int? = null,
    /** Chosen quality bucket; null = Auto. */
    val quality: Int? = null,
    /** Quality of the source that is playing. */
    val playingQuality: Int? = null,
    /** Highest quality Auto picks for this connection. */
    val autoCap: Int = 1080,
    val qualities: List<QualityRow> = emptyList(),
    /** Audio language the user wants (two-letter tag); null = no preference yet. */
    val language: String? = null,
    /** Audio languages named by the sources found. */
    val languages: List<String> = emptyList(),
    /** Short information that stays visible, e.g. "720p wasn't available, playing 1080p". */
    val notice: String? = null,
    val prompt: CastPrompt? = null,
)

/**
 * What is being cast, saved so Celium can take the controls back after Android closes it
 * (e.g. swiped away from recents) while the TV keeps playing.
 */
data class CastResume(
    val deviceId: String,
    val deviceName: String,
    val avTransportUrl: String,
    val renderingControlUrl: String?,
    val apiName: String,
    val title: String,
    val poster: String?,
    val episodes: List<ResultEpisode>,
    val index: Int,
    val linkSource: String,
    val linkName: String,
    val linkUrl: String,
    val linkReferer: String,
    val linkQuality: Int,
    val linkType: ExtractorLinkType,
    val linkHeaders: Map<String, String>,
    val subtitleUrl: String?,
    val relayHost: String,
    val relayPort: Int,
    val relay: List<CastRelay.Saved>,
    val quality: Int?,
    val language: String?,
    val subtitleLang: String? = null,
)

data class CastState(
    val device: DlnaDevice? = null,
    val nowPlaying: CastNowPlaying? = null,
    /** Full-screen cast controls are showing (otherwise the mini bar). */
    val controllerOpen: Boolean = false,
) {
    val connected get() = device != null
}

/**
 * App-wide "connect once" casting session. While connected, every Play in Celium is sent to the
 * TV (see ResultViewModel2), the next episode starts automatically, and progress is saved.
 */
object CastSession {
    private data class Queue(
        val apiName: String, val title: String, val poster: String?,
        val episodes: List<ResultEpisode>, val index: Int,
    ) {
        val episode get() = episodes[index]
        val next get() = episodes.getOrNull(index + 1)
    }

    /** Everything known about the video currently being cast. Lists are guarded by `this`. */
    private class Media(val q: Queue) {
        val links = mutableListOf<ExtractorLink>()
        val subs = mutableListOf<SubtitleFile>()
        val failures = HashMap<String, String>()
        @Volatile var checking: String? = null
        @Volatile var current: ExtractorLink? = null
        @Volatile var subtitle: SubtitleFile? = null
        @Volatile var done = false
        /** Links that passed the phone check but the TV still couldn't play. */
        @Volatile var tvFailures = 0

        fun ranked() = synchronized(this) { CastLinks.rank(links) }
        fun subtitles() = synchronized(this) { subs.distinctBy { it.url } }
        fun failure(url: String) = synchronized(this) { failures[url] }
        fun fail(url: String, reason: String) = synchronized(this) { failures[url] = reason }
        fun failedKeys() = synchronized(this) { failures.keys.toSet() }
        fun options() = ranked().map { StreamOption(it.url, it.source, it.name, it.quality) }
        fun linksFor(options: List<StreamOption>): List<ExtractorLink> {
            val byUrl = ranked().associateBy { it.url }
            return options.mapNotNull { byUrl[it.key] }
        }
    }

    /** Quality chosen by the user for this session (null = Auto); kept for the next episodes. */
    @Volatile private var chosenQuality: Int? = null
    /** Audio language the user listens to; kept for the next episodes. */
    @Volatile private var language: String? = null
    @Volatile private var appContext: Context? = null

    private fun cap() = ConnectionQuality.autoCap(appContext)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastState())
    val state: StateFlow<CastState> = _state.asStateFlow()
    val isConnected get() = renderer != null

    @Volatile private var renderer: DlnaRenderer? = null
    private var relay: CastRelay? = null
    private var playJob: Job? = null
    private var switchJob: Job? = null
    private var monitorJob: Job? = null
    @Volatile private var media: Media? = null
    @Volatile private var userStopped = false

    fun connect(context: Context, device: DlnaDevice) {
        if (renderer != null) disconnect(context)
        renderer = DlnaRenderer(device, app.baseClient)
        appContext = context.applicationContext
        if (language == null) language = StreamChoice.normalize(CS3IPlayer.preferredAudioTrackLanguage)
        if (relay == null) relay = CastRelay(app.baseClient)
        _state.value = CastState(device)
        CastService.start(context)
    }

    fun disconnect(context: Context?) {
        cancelAll()
        val r = renderer
        val wasPlaying = media != null
        renderer = null; media = null
        forget()
        // Stop the TV before closing the relay, or it keeps retrying a dead stream.
        scope.launch {
            if (wasPlaying && r != null) runCatching { withTimeout(5_000) { r.stop() } }
            if (renderer == null) relay?.stop()
        }
        _state.value = CastState()
        context?.let(CastService::stop)
    }

    fun openController(open: Boolean) = _state.update { it.copy(controllerOpen = open && it.nowPlaying != null) }

    /** Sends [episodes][[index]] to the TV, starting at [startMs]. */
    fun play(
        apiName: String, title: String, poster: String?, episodes: List<ResultEpisode>, index: Int,
        startMs: Long = 0, showController: Boolean = true,
    ) {
        if (renderer == null || index !in episodes.indices) return
        cancelAll()
        val m = Media(Queue(apiName, title, poster, episodes, index))
        media = m
        _state.update { it.copy(nowPlaying = snapshot(m, null).copy(message = "Finding sources…"), controllerOpen = showController || it.controllerOpen) }
        playJob = scope.launch { start(m, startMs) }
    }

    /** Starts the whole search again for the current title. */
    fun retry() {
        val q = media?.q ?: return
        play(q.apiName, q.title, q.poster, q.episodes, q.index, _state.value.nowPlaying?.positionMs ?: 0)
    }

    fun togglePause() = control { r ->
        val paused = _state.value.nowPlaying?.paused == true
        if (paused) r.play() else r.pause()
        setPlaying { copy(paused = !paused) }
    }

    fun seekBy(deltaMs: Long) {
        val now = _state.value.nowPlaying ?: return
        seekTo(now.positionMs + deltaMs)
    }

    fun seekTo(positionMs: Long) = control { r ->
        val now = _state.value.nowPlaying ?: return@control
        val max = if (now.durationMs > 0) now.durationMs - 1_000 else Long.MAX_VALUE
        val target = positionMs.coerceIn(0, max)
        try {
            r.seek(target)
        } catch (e: java.io.IOException) {
            // Some renderers (Kodi) only seek while playing.
            if (!now.paused) throw e
            r.play(); r.seek(target); r.pause()
        }
        setPlaying { copy(positionMs = target) }
    }

    fun next() {
        val q = media?.q ?: return
        if (q.next != null) play(q.apiName, q.title, q.poster, q.episodes, q.index + 1, showController = _state.value.controllerOpen)
    }

    fun stop() {
        userStopped = true
        cancelAll()
        media = null
        forget()
        _state.update { it.copy(nowPlaying = null, controllerOpen = false) }
        renderer?.let { r -> scope.launch { runCatching { r.stop() } } }
    }

    /** Plays another source of the same title from the current position (Advanced). */
    fun switchSource(index: Int) {
        val m = media ?: return
        val link = m.ranked().getOrNull(index) ?: return
        if (link.url == m.current?.url) return
        synchronized(m) { m.failures.remove(link.url) } // a source the user picks gets another chance
        switchAmong(m, listOf(link), "Switching to ${CastLinks.label(link)}…") {
            val reason = m.failure(link.url) ?: "it didn't play"
            publish(m) { copy(notice = "${CastLinks.label(link)}: $reason") }
        }
    }

    /**
     * Picks a quality (null = Auto). Celium finds a working source at that quality in the same
     * audio language, preferring the source family already playing; the TV keeps playing until
     * the new source is confirmed. When that isn't possible the user gets options.
     */
    fun setQuality(quality: Int?) {
        val m = media ?: return
        val previousChoice = chosenQuality
        chosenQuality = quality
        val options = m.options()
        val failed = m.failedKeys()
        val current = m.current
        val currentBucket = current?.let { StreamChoice.bucket(it.quality) }
        val currentLanguageOk = current == null || StreamChoice.match(StreamChoice.languages(current.name), language) != LangMatch.Other
        publish(m) { copy(prompt = null, notice = null) }
        if (quality == null) {
            val order = StreamChoice.autoOrder(options, cap(), language, failed)
                .filter { StreamChoice.match(it.languages, language) != LangMatch.Other }
            val best = order.firstOrNull() ?: return
            if (current != null && best.bucket == currentBucket && currentLanguageOk) return
            switchAmong(m, m.linksFor(order.take(6)), "Finding the best quality for your connection…") {}
            return
        }
        if (current != null && currentBucket == quality && currentLanguageOk) return
        val candidates = StreamChoice.candidates(options, quality, language, current?.source, failed)
        if (candidates.isEmpty()) return promptFor(m, quality, previousChoice, noneWorked = false)
        val inLanguage = language?.let { " in ${StreamChoice.languageName(it)}" }.orEmpty()
        switchAmong(m, m.linksFor(candidates), "Finding ${StreamChoice.label(quality)}$inLanguage…") {
            promptFor(m, quality, previousChoice, noneWorked = true)
        }
    }

    /** Changes the wanted audio language and finds it at the chosen quality (or Auto). */
    fun setLanguage(tag: String) {
        language = tag
        setQuality(chosenQuality)
    }

    fun dismissPrompt() = _state.update { s -> s.copy(nowPlaying = s.nowPlaying?.copy(prompt = null)) }

    private fun promptFor(m: Media, quality: Int, previousChoice: Int?, noneWorked: Boolean) {
        val options = m.options()
        val fallback = StreamChoice.fallback(options, quality, language, m.failedKeys())
        val q = StreamChoice.label(quality)
        val wanted = language?.let(StreamChoice::languageName)
        val buttons = mutableListOf<Pair<String, () -> Unit>>()
        fallback.otherLanguages.forEach { tag ->
            buttons += "Play $q in ${StreamChoice.languageName(tag)}" to { setLanguage(tag) }
        }
        fallback.nearestQuality?.let { nearest ->
            buttons += "Play ${StreamChoice.label(nearest)}${wanted?.let { " in $it" }.orEmpty()}" to { setQuality(nearest) }
        }
        val playing = m.current?.let { StreamChoice.bucket(it.quality) }
        buttons += (if (playing != null) "Stay on ${StreamChoice.label(playing)}" else "Keep current") to {
            chosenQuality = previousChoice
            dismissPrompt()
        }
        val (title, message) = when {
            noneWorked -> "No working $q source" to
                "Celium tried every $q source${wanted?.let { " in $it" }.orEmpty()}, but the video hosts blocked them or they expired."
            wanted != null && fallback.otherLanguages.isNotEmpty() -> "No $q with $wanted audio" to
                "$q is only available in ${fallback.otherLanguages.joinToString(", ") { StreamChoice.languageName(it) }}."
            wanted != null -> "No $q with $wanted audio" to "There's no $q source in $wanted for this title."
            else -> "No working $q source" to "There's no $q source for this title."
        }
        publish(m) { copy(prompt = CastPrompt(title, message, buttons)) }
    }

    /**
     * Tries [candidates] in order while the TV keeps playing the current video (each is checked on
     * the phone first). If none works the previous video is restored and [onNone] runs.
     */
    private fun switchAmong(m: Media, candidates: List<ExtractorLink>, finding: String, onNone: () -> Unit) {
        val r = renderer ?: return
        val previous = m.current
        val position = _state.value.nowPlaying?.positionMs ?: 0
        monitorJob?.cancel(); switchJob?.cancel()
        switchJob = scope.launch {
            publish(m) { copy(message = finding, notice = null) }
            for (link in candidates) {
                if (previous != null && link.url == previous.url && m.current?.url == previous.url) {
                    publish(m) { copy(loading = false, message = null) }
                    monitor(m, r)
                    return@launch
                }
                if (attempt(m, link, position, check = true)) return@launch
            }
            when {
                previous != null && m.current == null -> attempt(m, previous, position, check = false)
                m.current != null -> { publish(m) { copy(loading = false, message = null) }; monitor(m, r) }
            }
            onNone()
        }
    }

    /** Tells the user when the playing source differs from what they asked for. */
    private fun explainPick(m: Media, link: ExtractorLink) {
        val bucket = StreamChoice.bucket(link.quality)
        val notes = mutableListOf<String>()
        chosenQuality?.let { wanted ->
            if (bucket != wanted) notes += "${StreamChoice.label(wanted)} wasn't available, playing ${bucket?.let(StreamChoice::label) ?: "another quality"}"
        }
        val wantedLanguage = language
        if (wantedLanguage != null && StreamChoice.match(StreamChoice.languages(link.name), wantedLanguage) == LangMatch.Other) {
            notes += "No ${StreamChoice.languageName(wantedLanguage)} source worked, playing ${StreamChoice.languageName(StreamChoice.singleLanguage(link.name))}"
        }
        if (notes.isNotEmpty()) publish(m) { copy(notice = notes.joinToString(". ")) }
    }

    /** Start order: the chosen quality first (if any), then Auto order. */
    private fun startOrder(m: Media): List<ExtractorLink> {
        val options = m.options()
        val failed = m.failedKeys()
        val chosen = chosenQuality?.let { StreamChoice.candidates(options, it, language, null, failed) }.orEmpty()
        val auto = StreamChoice.autoOrder(options, cap(), language, failed)
        return m.linksFor((chosen + auto).distinctBy { it.key })
    }

    /** Selects subtitles by index in [CastNowPlaying.subtitles], or null for none, and reloads at the same position. */
    fun setSubtitle(index: Int?) {
        val m = media ?: return
        m.subtitle = index?.let { m.subtitles().getOrNull(it) }
        val current = m.current ?: return publish(m)
        val position = _state.value.nowPlaying?.positionMs ?: 0
        monitorJob?.cancel(); switchJob?.cancel()
        switchJob = scope.launch {
            if (!attempt(m, current, position, check = false)) fail(m, "The TV couldn't reload the video with the new subtitles")
        }
    }

    private fun cancelAll() {
        lazySearch = null
        playJob?.cancel(); switchJob?.cancel(); monitorJob?.cancel()
    }

    private fun control(block: suspend (DlnaRenderer) -> Unit) {
        val r = renderer ?: return
        scope.launch {
            try { block(r) } catch (e: Exception) { setPlaying { copy(message = "The TV didn't respond") } }
        }
    }

    private fun setPlaying(change: CastNowPlaying.() -> CastNowPlaying) =
        _state.update { s -> s.copy(nowPlaying = s.nowPlaying?.change()) }

    private fun label(q: Queue): String? {
        val ep = q.episode
        if (q.episodes.size <= 1 && ep.season == null) return null
        val number = listOfNotNull(ep.season?.let { "S$it" }, "E${ep.episode}").joinToString(" ")
        return listOfNotNull(number, ep.name?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    private fun snapshot(m: Media, previous: CastNowPlaying?): CastNowPlaying {
        val ranked = m.ranked()
        val subs = m.subtitles()
        val current = m.current?.url
        val sources = ranked.map { link ->
            val reason = m.failure(link.url)
            val status = when {
                link.url == current -> CastSourceStatus.Playing
                link.url == m.checking -> CastSourceStatus.Checking
                reason != null -> CastSourceStatus.Failed
                else -> CastSourceStatus.Waiting
            }
            CastSourceInfo(CastLinks.label(link), status, reason)
        }
        val base = previous ?: CastNowPlaying(m.q.title, label(m.q), durationMs = m.q.episode.duration)
        return base.copy(
            title = m.q.title,
            label = label(m.q),
            poster = m.q.episode.poster ?: m.q.poster,
            hasNext = m.q.next != null,
            findingSources = !m.done,
            sources = sources,
            currentSource = ranked.indexOfFirst { it.url == current }.takeIf { it >= 0 },
            subtitles = subs.map { it.lang },
            currentSubtitle = subs.indexOfFirst { it.url == m.subtitle?.url }.takeIf { it >= 0 },
            quality = chosenQuality,
            playingQuality = m.current?.let { StreamChoice.bucket(it.quality) },
            autoCap = cap(),
            qualities = StreamChoice.available(m.options(), m.failedKeys()),
            language = language,
            languages = ranked.flatMap { StreamChoice.languages(it.name).orEmpty() }.filter { it != StreamChoice.MULTI }.distinct(),
        )
    }

    private fun publish(m: Media, change: CastNowPlaying.() -> CastNowPlaying = { this }) {
        if (media !== m) return
        _state.update { s -> s.copy(nowPlaying = snapshot(m, s.nowPlaying).change()) }
    }

    private fun fail(m: Media, message: String, title: String? = null, mirror: Boolean = false) =
        publish(m) { copy(loading = false, failed = true, message = message, failTitle = title, suggestMirror = mirror) }

    private suspend fun start(m: Media, startMs: Long) = coroutineScope {
        val r = renderer ?: return@coroutineScope
        val relay = relay ?: return@coroutineScope
        userStopped = false
        val api = getApiFromNameNull(m.q.apiName) ?: return@coroutineScope fail(m, "This source isn't available right now")

        // Collect sources in the background; the TV starts with the first good ones.
        launch { collectLinks(m, api) }

        val host = withContext(Dispatchers.IO) { CastRelay.localAddressFor(r.host) }
            ?: return@coroutineScope fail(m, "Connect this phone to the same Wi-Fi as ${r.device.name}")
        try { relay.start(host) } catch (e: Exception) { logError(e); return@coroutineScope fail(m, "Couldn't start casting on this phone") }

        // Start when all sources are in, or a few seconds after the first usable one arrives.
        var firstAt = 0L
        while (!m.done) {
            if (m.ranked().isNotEmpty()) {
                if (firstAt == 0L) firstAt = System.currentTimeMillis()
                if (System.currentTimeMillis() - firstAt >= 4_000) break
            }
            delay(250)
        }

        val tried = HashSet<String>()
        while (true) {
            val next = startOrder(m).firstOrNull { it.url !in tried }
            if (next == null) {
                if (m.done) break
                delay(500); continue
            }
            tried += next.url
            if (attempt(m, next, startMs, check = true)) {
                explainPick(m, next)
                return@coroutineScope
            }
        }
        val found = synchronized(m) { m.links.size }
        val castable = m.ranked().size
        val provider = m.q.apiName
        val what = if (m.q.episodes.size <= 1 && m.q.episode.season == null) "this title" else "this episode"
        when {
            found == 0 -> fail(m, "$provider didn't return any video links for $what. Try another source on the title's page.", "No playable link found")
            castable == 0 -> fail(m, "$provider only has video formats that can't be cast for $what (audio in a separate file, or DASH). It can still play on the phone.", "Can't cast this one", mirror = true)
            m.tvFailures == 0 -> fail(m, "$provider returned $castable ${if (castable == 1) "link" else "links"} for $what, but none of them worked: the video hosts blocked them or they expired. Try another source on the title's page.", "No playable link found", mirror = true)
            else -> fail(m, "${r.device.name} couldn't play the working ${if (m.tvFailures == 1) "link" else "links"} for $what. Try another source on the title's page. It can still play on the phone.", "Couldn't play on ${r.device.name}", mirror = true)
        }
    }

    private suspend fun collectLinks(m: Media, api: MainAPI, savedSubtitle: String? = null) {
        try {
            // isCasting = false: the relay supplies the headers sources need, so take every link.
            APIRepository(api).loadLinks(m.q.episode.data, false,
                { sub ->
                    synchronized(m) { m.subs.add(sub) }
                    if (savedSubtitle != null && sub.url == savedSubtitle) {
                        synchronized(m) { m.subs.removeAll { it.url == sub.url && it !== sub } }
                        m.subtitle = sub
                    }
                    else if (m.current == null && m.subtitle == null && sub.langTag?.startsWith("en") == true) m.subtitle = sub
                    publish(m)
                },
                { link ->
                    synchronized(m) { if (m.links.none { it.url == link.url }) m.links.add(link) }
                    publish(m)
                })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e)
        } finally {
            m.done = true
            publish(m)
        }
    }

    // ---- Taking the controls back after Celium was closed ----

    private const val RESUME_KEY = "celium_cast_resume"
    @Volatile private var restoreTried = false

    private fun persist(m: Media, r: DlnaRenderer, relay: CastRelay) {
        val link = m.current ?: return
        val port = relay.port ?: return
        val host = relay.host ?: return
        runCatching {
            setKey(RESUME_KEY, CastResume(
                r.device.id, r.device.name, r.device.avTransportUrl, r.device.renderingControlUrl,
                m.q.apiName, m.q.title, m.q.poster, m.q.episodes, m.q.index,
                link.source, link.name, link.url, link.referer, link.quality, link.type, link.headers,
                m.subtitle?.url, host, port, relay.saved(), chosenQuality, language, m.subtitle?.lang,
            ).toJson())
        }.onFailure(::logError)
    }

    private fun forget() {
        runCatching { removeKey(RESUME_KEY) }
    }

    /**
     * Called when Celium's screens open. If Celium was closed while casting and the TV is still
     * playing that video, the cast controls come back as if nothing happened.
     */
    fun restore(context: Context) {
        if (restoreTried || renderer != null) return
        restoreTried = true
        val saved = runCatching { tryParseJson<CastResume>(getKey<String>(RESUME_KEY)) }.getOrNull() ?: return
        val ctx = context.applicationContext
        scope.launch {
            val device = DlnaDevice(saved.deviceId, saved.deviceName, saved.avTransportUrl, saved.renderingControlUrl)
            val r = DlnaRenderer(device, app.baseClient)
            val status = runCatching { withTimeout(8_000) { r.status() } }.getOrNull()
            // Only if the TV is still on our video, not something started on the TV since.
            val ours = status?.trackUri?.contains(":${saved.relayPort}/s/") ?: true
            if (status == null || status.state !in ACTIVE_STATES || !ours || saved.index !in saved.episodes.indices || renderer != null) {
                if (renderer == null) forget()
                return@launch
            }
            val link = try {
                newExtractorLink(saved.linkSource, saved.linkName, saved.linkUrl, saved.linkType) {
                    referer = saved.linkReferer
                    quality = saved.linkQuality
                    headers = saved.linkHeaders
                }
            } catch (e: Exception) {
                logError(e); forget(); return@launch
            }

            renderer = r
            appContext = ctx
            chosenQuality = saved.quality
            language = saved.language ?: language
            userStopped = false
            val relay = relay ?: CastRelay(app.baseClient).also { relay = it }
            val host = withContext(Dispatchers.IO) { CastRelay.localAddressFor(r.host) } ?: saved.relayHost
            relay.restore(saved.relay)
            runCatching { relay.start(host, saved.relayPort) }.onFailure(::logError)
            // Same address: the TV keeps streaming through the restarted relay. Otherwise reload at the same spot.
            val sameUrls = relay.port == saved.relayPort && host == saved.relayHost

            val m = Media(Queue(saved.apiName, saved.title, saved.poster, saved.episodes, saved.index))
            synchronized(m) { m.links.add(link) }
            m.current = link
            saved.subtitleUrl?.let { url ->
                val sub = SubtitleFile(saved.subtitleLang ?: "Subtitles", url)
                synchronized(m) { m.subs.add(sub) }
                m.subtitle = sub
            }
            media = m
            val position = status.positionMs ?: 0
            _state.value = CastState(device, controllerOpen = true)
            publish(m) {
                copy(
                    loading = false, positionMs = position, durationMs = status.durationMs ?: durationMs,
                    paused = status.state == "PAUSED_PLAYBACK", message = null, notice = "Reconnected to ${device.name}",
                )
            }
            CastService.start(ctx)
            if (sameUrls) monitor(m, r)
            else switchJob = scope.launch {
                if (!attempt(m, link, position, check = false)) fail(m, "Couldn't reconnect to ${device.name}. Tap Retry.")
            }
            // Stay on this source. Others are only looked up if the user opens a menu to change something.
            m.done = true
            publish(m)
            lazySearch = { getApiFromNameNull(saved.apiName)?.let { api -> collectLinks(m, api, saved.subtitleUrl) } }
        }
    }

    /** After reconnecting, the search for other sources waits until a menu needs it. */
    @Volatile private var lazySearch: (suspend () -> Unit)? = null

    /** Called when Quality, Audio or Subtitles opens: finds the other sources if that hasn't happened yet. */
    fun findSources() {
        val search = lazySearch ?: return
        val m = media ?: return
        lazySearch = null
        m.done = false
        publish(m)
        playJob = scope.launch { try { search() } finally { m.done = true; publish(m) } }
    }

    private val ACTIVE_STATES = setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING")

    /** Tries one link on the TV. Returns true once it is playing. */
    private suspend fun attempt(m: Media, link: ExtractorLink, startMs: Long, check: Boolean): Boolean {
        val r = renderer ?: return false
        val relay = relay ?: return false
        val label = CastLinks.label(link)
        val tv = r.device.name
        val headers = link.getAllHeaders()
        val playlist = link.type == ExtractorLinkType.M3U8
        m.checking = link.url
        publish(m) { copy(loading = true, failed = false, message = "Checking $label…") }
        val problem = if (check) withContext(Dispatchers.IO) { relay.probe(link.url, headers, playlist) } else null
        if (problem != null) {
            m.fail(link.url, problem); m.checking = null
            publish(m) { copy(message = "$label: $problem. Trying the next source…") }
            return false
        }
        publish(m) { copy(message = "Starting $label on $tv…") }
        val mime = CastLinks.mime(link.type, link.url)
        val title = listOfNotNull(m.q.title, label(m.q)).joinToString(" · ")
        val ok = try {
            relay.clear()
            val subUrl = m.subtitle?.let { relay.register(it.url, it.headers.orEmpty(), "text/srt", false, "subtitle", subtitle = true) }
            val url = relay.register(link.url, headers, mime, playlist, "video", captionUrl = subUrl)
            m.current = null // the TV is about to leave the previous video
            runCatching { r.stop() }
            r.load(url, DlnaProtocol.didl(title, url, mime, subUrl))
            r.play()
            waitUntilPlaying(r)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logError(e); false
        }
        m.checking = null
        if (!ok) {
            m.tvFailures++
            m.fail(link.url, "$tv couldn't play this video")
            publish(m) { copy(message = "$label: $tv couldn't play it. Trying the next source…") }
            runCatching { r.stop() }
            return false
        }
        m.current = link
        if (language == null) language = StreamChoice.singleLanguage(link.name)
        persist(m, r, relay)
        if (startMs > 0) runCatching { r.seek(startMs) }
        publish(m) { copy(loading = false, failed = false, message = null, positionMs = startMs, paused = false) }
        monitor(m, r)
        return true
    }

    private suspend fun waitUntilPlaying(r: DlnaRenderer): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 20_000) {
            delay(1_000)
            val state = runCatching { r.status().state }.getOrNull()
            if (state == "PLAYING") return true
            // Some renderers report STOPPED briefly before starting; only give up after a grace period.
            if ((state == "STOPPED" || state == "NO_MEDIA_PRESENT") && System.currentTimeMillis() - start > 8_000) return false
        }
        return false
    }

    private fun monitor(m: Media, r: DlnaRenderer) {
        monitorJob?.cancel()
        val q = m.q
        monitorJob = scope.launch {
            var lastPos = 0L
            var lastDur = q.episode.duration
            var failures = 0
            var tick = 0
            fun save() {
                if (lastDur > 0) DataStoreHelper.setViewPosAndResume(q.episode.id, lastPos, lastDur, q.episode, q.next)
            }
            while (isActive) {
                delay(1_000)
                val s = try { r.status() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (s == null) {
                    if (++failures >= 15) { publish(m) { copy(message = "Lost connection to ${r.device.name}") }; break }
                    continue
                }
                failures = 0
                when (s.state) {
                    "PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING" -> {
                        s.positionMs?.let { lastPos = it }
                        s.durationMs?.let { lastDur = it }
                        publish(m) { copy(positionMs = lastPos, durationMs = lastDur, paused = s.state == "PAUSED_PLAYBACK", loading = s.state == "TRANSITIONING", message = null) }
                        if (++tick % 5 == 0) save()
                    }
                    "STOPPED", "NO_MEDIA_PRESENT" -> {
                        save()
                        if (CastLinks.endedNaturally(lastPos, lastDur, userStopped)) {
                            DataStoreHelper.setVideoWatchState(q.episode.id, VideoWatchState.Watched)
                            if (q.next != null) {
                                play(q.apiName, q.title, q.poster, q.episodes, q.index + 1, showController = _state.value.controllerOpen)
                                return@launch
                            }
                        }
                        if (media === m) {
                            media = null
                            forget()
                            _state.update { it.copy(nowPlaying = null, controllerOpen = false) }
                        }
                        break
                    }
                }
            }
        }
    }
}
