package com.lagradost.cloudstream3.ui.celium

import android.os.Bundle
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.navigate

/** New details surface, retaining the existing resolver/actions and download queue. */
class CeliumDetailsFragment : BaseComposeFragment() {
    private val model: ResultViewModel2 by viewModels()
    private var page by mutableStateOf<Resource<ResultData>?>(null)
    /** Last successfully loaded details, kept on screen while refreshing (Requirement 10.6). */
    private var shown by mutableStateOf<ResultData?>(null)
    private var refreshing by mutableStateOf(false)
    private var refreshError by mutableStateOf<String?>(null)
    private var episodes by mutableStateOf<List<ResultEpisode>>(emptyList())
    private var movie by mutableStateOf<ResultEpisode?>(null)
    private var resume by mutableStateOf<ResumeWatchingStatus?>(null)
    private var saved by mutableStateOf(false)
    private var seasons by mutableStateOf<List<Pair<UiText?, Int>>>(emptyList())
    private var ranges by mutableStateOf<List<Pair<UiText?, EpisodeRange>>>(emptyList())
    private var dubs by mutableStateOf<List<Pair<UiText?, com.lagradost.cloudstream3.DubStatus>>>(emptyList())
    // Selected labels/indices observed as state so selector buttons recompose (Requirement 8.7).
    private var seasonText by mutableStateOf<UiText?>(null)
    private var rangeText by mutableStateOf<UiText?>(null)
    private var dubText by mutableStateOf<UiText?>(null)
    private var seasonIndex by mutableIntStateOf(-1)
    private var rangeIndex by mutableIntStateOf(-1)
    private var dubIndex by mutableIntStateOf(-1)
    private var recommendations by mutableStateOf<List<SearchResponse>>(emptyList())
    private var popup by mutableStateOf<SelectPopup?>(null)
    private var resolving by mutableStateOf(false)
    private var selection by mutableStateOf<String?>(null)
    private var started = false

    private fun load(force: Boolean = false) {
        if (started && !force) return
        val data = getStoredData() ?: return
        if (!com.lagradost.cloudstream3.MainAPI.settingsForProvider.enableAdult && CeliumSources.isAdult(null, data.apiName)) {
            page = Resource.Failure(false, "Adult content is disabled")
            return
        }
        started = true
        if (force && shown != null) { refreshing = true; refreshError = null }
        // A new (non-refresh) load or a different title never shows the previous list (Requirement 1.5).
        if (!refreshing || shown?.url?.let(CeliumDedup::key) != CeliumDedup.key(data.url)) recommendations = emptyList()
        model.load(activity, data.url, data.apiName, data.showFillers, data.dubStatus, data.start, loadTrailers = false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.page.observe(viewLifecycleOwner) {
            page = it
            when (it) {
                is Resource.Success -> {
                    val previous = shown?.url
                    if (previous != null && CeliumDedup.key(previous) != CeliumDedup.key(it.value.url)) {
                        // Title changed: drop the old row and take only this title's list (Requirement 1.5).
                        // ResultViewModel2.postPage posts recommendations before the page, so the
                        // model value here already belongs to the new title.
                        recommendations = model.recommendations.value.orEmpty()
                    }
                    shown = it.value; refreshing = false; refreshError = null
                }
                is Resource.Failure -> if (refreshing) { refreshing = false; refreshError = "Refresh failed. Showing the last loaded details." }
                else -> Unit
            }
        }
        model.episodes.observe(viewLifecycleOwner) {
            when (it) {
                is Resource.Success -> episodes = it.value
                is Resource.Failure -> if (!refreshing) episodes = emptyList()
                else -> if (!refreshing) episodes = emptyList()
            }
        }
        model.movie.observe(viewLifecycleOwner) { if (it is Resource.Success) movie = it.value.second else if (!refreshing) movie = null }
        model.resumeWatching.observe(viewLifecycleOwner) { resume = it }
        model.watchStatus.observe(viewLifecycleOwner) { saved = it != WatchType.NONE }
        model.seasonSelections.observe(viewLifecycleOwner) { seasons = it }
        model.rangeSelections.observe(viewLifecycleOwner) { ranges = it }
        model.dubSubSelections.observe(viewLifecycleOwner) { dubs = it }
        model.selectedSeason.observe(viewLifecycleOwner) { seasonText = it }
        model.selectedRange.observe(viewLifecycleOwner) { rangeText = it }
        model.selectedDubStatus.observe(viewLifecycleOwner) { dubText = it }
        model.selectedSeasonIndex.observe(viewLifecycleOwner) { seasonIndex = it }
        model.selectedRangeIndex.observe(viewLifecycleOwner) { rangeIndex = it }
        model.selectedDubStatusIndex.observe(viewLifecycleOwner) { dubIndex = it }
        model.recommendations.observe(viewLifecycleOwner) { recommendations = it.orEmpty() }
        model.selectPopup.observe(viewLifecycleOwner) { popup = it }
        model.loadedLinks.observe(viewLifecycleOwner) { resolving = it != null }
        load()
    }

    override fun onStart() {
        super.onStart()
        if (page is Resource.Success) model.reloadEpisodes()
        afterPluginsLoadedEvent += ::pluginsLoaded
    }
    override fun onStop() {
        afterPluginsLoadedEvent -= ::pluginsLoaded
        super.onStop()
    }
    private fun pluginsLoaded(force: Boolean) { if (page !is Resource.Success) load(force) }

    override fun onDestroyView() {
        model.cancelLinks()
        super.onDestroyView()
    }

    private fun action(episode: ResultEpisode, download: Boolean = false) {
        model.handleAction(EpisodeClickEvent(if (download) ACTION_DOWNLOAD_EPISODE else ACTION_PLAY_EPISODE_IN_PLAYER, episode))
        // Show the queue right after adding (Requirement 19.2).
        if (download) activity.navigate(R.id.navigation_downloads)
    }

    /**
     * Opens a recommendation's details the same way Home does (Requirement 9.4). The global
     * results action has no launchSingleTop/popUpTo, so this pushes a new back-stack entry with
     * its own fragment and fragment-scoped ResultViewModel2 (Requirement 1.5).
     */
    private fun open(card: SearchResponse) = loadSearchResult(card, 0)

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val seasonOptions = seasons.map { SelectorOption(it.first?.asString(context) ?: "Season ${it.second}") }
        val dubOptions = dubs.map { SelectorOption(it.first?.asString(context) ?: it.second.name) }
        val rangeOptions = ranges.map { SelectorOption(it.first?.asString(context) ?: "${it.second.startEpisode}–${it.second.endEpisode}") }
        // Keyed by title so the "More like this" grid resets when the title changes (Requirement 1.5).
        var showAllRecs by rememberSaveable(shown?.url) { mutableStateOf(false) }
        CeliumTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CeliumBackButton({ findNavController().popBackStack() })
                        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        CeliumRefreshButton(onClick = { load(true) }, enabled = !refreshing)
                    }
                    if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    refreshError?.let {
                        Text(it, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                    val current = shown
                    val response = page
                    when {
                        current != null -> BoxWithConstraints(Modifier.weight(1f)) {
                            val data = current
                            val wide = maxWidth >= 700.dp
                            val edge = 24.dp
                            val cardWidth = if (wide) 160.dp else 128.dp
                            val recs = remember(recommendations, data.url) {
                                CeliumRecommendations.filter(recommendations, data.url,
                                    adultEnabled = com.lagradost.cloudstream3.MainAPI.settingsForProvider.enableAdult,
                                    isAdult = { CeliumSources.isAdult(it.type, it.apiName) })
                            }
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                item("summary") {
                                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = edge)) {
                                        var shape by remember(data.url) { mutableStateOf<HeaderShape?>(null) }
                                        val posterWidth = if (wide) 280.dp else 180.dp
                                        val contentWidth = maxWidth
                                        // Wide image spans the content above the copy; a portrait poster keeps its own size (Requirement 20).
                                        if (wide && shape != HeaderShape.Wide) Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                            CeliumHeaderImage(data, contentWidth, posterWidth, onShape = { shape = it })
                                            DetailCopy(data, Modifier.weight(1f))
                                        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                            CeliumHeaderImage(data, contentWidth, posterWidth,
                                                if (shape == HeaderShape.Wide) Modifier else Modifier.align(Alignment.CenterHorizontally),
                                                onShape = { shape = it })
                                            DetailCopy(data, Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                                val showSeason = CeliumSelector.visible(seasonOptions)
                                val showDub = CeliumSelector.visible(dubOptions)
                                val showRange = CeliumSelector.visible(rangeOptions)
                                if (showSeason || showDub || showRange) item("selectors") {
                                    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (showSeason) item { CeliumSelectorButton("Season", CeliumSelector.label(seasonText?.asString(context), seasonOptions, seasonIndex, "Season"), { selection = "Season" }) }
                                        if (showDub) item { CeliumSelectorButton("Audio", CeliumSelector.label(dubText?.asString(context), dubOptions, dubIndex, "Audio"), { selection = "Audio" }) }
                                        if (showRange) item { CeliumSelectorButton("Episodes", CeliumSelector.label(rangeText?.asString(context), rangeOptions, rangeIndex, "Episodes"), { selection = "Episodes" }) }
                                    }
                                }
                                if (episodes.isNotEmpty()) item("episode-title") {
                                    Text("Episodes", Modifier.padding(horizontal = edge), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                items(episodes, key = { it.id }, contentType = { "episode" }) { episode ->
                                    Column(Modifier.padding(horizontal = edge), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth().clickable { action(episode) }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (episode.poster != null) CeliumArtwork(episode.poster, modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                                            Column(Modifier.weight(1f)) {
                                                Text("${episode.episode}. ${episode.name ?: "Episode ${episode.episode}"}", fontWeight = FontWeight.Medium)
                                                episode.description?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                if (episode.duration > 0) LinearProgressIndicator(progress = { (episode.position.toFloat() / episode.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                            }
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            TextButton(onClick = { action(episode) }) { Text("Play") }
                                            if (model.currentRepo?.api?.hasDownloadSupport == true) CeliumDownloadControl(episode.id, onDownload = { action(episode, true) }, onPlay = { action(episode) })
                                            TextButton(onClick = { model.handleAction(EpisodeClickEvent(ACTION_MARK_AS_WATCHED, episode)) }) {
                                                Text(if (episode.videoWatchState == VideoWatchState.Watched) "Mark unwatched" else "Mark watched")
                                            }
                                        }
                                        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                                    }
                                }
                                if (episodes.isEmpty() && movie == null) item("no-episodes") {
                                    CeliumEmpty(if (data.comingSoon) "Coming soon" else "No playable episodes", "This source has not returned a playable item. Try refreshing or another source.")
                                }
                                // "More like this" is always the last item (Requirement 9.1).
                                if (recs.isNotEmpty()) item("recommendations") {
                                    CeliumShelfView("More like this", recs, cardWidth, edge, onClick = { open(it) }, onAll = { showAllRecs = true })
                                }
                            }
                            if (showAllRecs && recs.isNotEmpty()) {
                                BackHandler { showAllRecs = false }
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                                    Column {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                                            CeliumBackButton(onClick = { showAllRecs = false })
                                            Text("More like this", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                                        }
                                        LazyVerticalGrid(GridCells.Adaptive(cardWidth), Modifier.weight(1f), contentPadding = PaddingValues(edge),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                            items(recs.size, key = { "${recs[it].apiName}:${recs[it].url}" }, contentType = { "poster" }) { i ->
                                                CeliumPoster(recs[i], Modifier.fillMaxWidth()) { open(recs[i]) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        response is Resource.Failure -> {
                            CeliumEmpty("Details could not load", "The source may be unavailable or its page may have changed.")
                            TextButton(onClick = { load(true) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Try again") }
                        }
                        else -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            CeliumEmpty("Loading details", "Getting the title and available episodes from the selected source.")
                        }
                    }
                }
                if (resolving) AlertDialog(onDismissRequest = { model.cancelLinks() },
                    title = { Text("Preparing playback") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Finding available video and subtitle tracks.") } },
                    confirmButton = { TextButton(onClick = { model.skipLoading() }) { Text("Use ready links") } },
                    dismissButton = { TextButton(onClick = { model.cancelLinks() }) { Text("Cancel") } })
                popup?.let { active ->
                    AlertDialog(onDismissRequest = { active.callback(null); popup = null },
                        title = { Text(active.getTitle(context)) },
                        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(active.getOptions(context).size) { index ->
                            TextButton(onClick = { popup = null; active.callback(index) }, modifier = Modifier.fillMaxWidth()) { Text(active.getOptions(context)[index]) }
                        } } },
                        confirmButton = { TextButton(onClick = { popup = null; active.callback(null) }) { Text("Cancel") } })
                }
                selection?.let { kind ->
                    val (options, selected) = when (kind) {
                        "Season" -> seasonOptions to seasonIndex
                        "Audio" -> dubOptions to dubIndex
                        else -> rangeOptions to rangeIndex
                    }
                    // Dismissing leaves the current selection unchanged (Requirement 8.9).
                    AlertDialog(onDismissRequest = { selection = null }, title = { Text(kind) },
                        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(options.size) { i ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(i == selected, role = Role.RadioButton) {
                                when (kind) {
                                    "Season" -> model.changeSeason(seasons[i].second)
                                    "Audio" -> model.changeDubStatus(dubs[i].second)
                                    else -> model.changeRange(ranges[i].second)
                                }
                                selection = null
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(i == selected, onClick = null)
                                Text(options[i].label, Modifier.padding(start = 12.dp))
                            }
                        } } }, confirmButton = { TextButton(onClick = { selection = null }) { Text("Close") } })
                }
            }
        }
    }

    @Composable
    private fun DetailCopy(data: ResultData, modifier: Modifier) {
        val context = LocalContext.current
        Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(data.typeText.asString(context).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(data.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(data.yearText?.asString(context), data.durationText?.asString(context), data.ratingText?.asString(context)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val playable = resume?.result ?: movie ?: episodes.firstOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (playable != null && !data.comingSoon) Button(onClick = { action(playable) }, shape = RoundedCornerShape(8.dp)) { Text(if (resume?.progress != null) "Resume" else "Play") }
                OutlinedButton(onClick = { model.updateWatchStatus(if (saved) WatchType.NONE else WatchType.PLANTOWATCH, context) }, shape = RoundedCornerShape(8.dp)) { Text(if (saved) "Saved · Remove" else "Save") }
            }
            movie?.takeIf { model.currentRepo?.api?.hasDownloadSupport == true }?.let { film ->
                CeliumDownloadControl(film.id, onDownload = { action(film, true) }, onPlay = { action(film) })
            }
            var expanded by remember(data.url) { mutableStateOf(false) }
            Text(data.plotText.asString(context), style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Read less" else "Read more") }
            if (data.tags.isNotEmpty()) Text(data.tags.joinToString(" / "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Text("Source", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(data.apiName.asString(context), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
