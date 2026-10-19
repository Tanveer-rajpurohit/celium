package com.lagradost.cloudstream3.ui.celium

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.ui.result.*
import com.lagradost.cloudstream3.ui.result.ResultFragment.getStoredData
import com.lagradost.cloudstream3.utils.BaseComposeFragment

/** New details surface, retaining the existing resolver/actions and download queue. */
class CeliumDetailsFragment : BaseComposeFragment() {
    private val model: ResultViewModel2 by viewModels()
    private var page by mutableStateOf<Resource<ResultData>?>(null)
    private var episodes by mutableStateOf<List<ResultEpisode>>(emptyList())
    private var movie by mutableStateOf<ResultEpisode?>(null)
    private var resume by mutableStateOf<ResumeWatchingStatus?>(null)
    private var saved by mutableStateOf(false)
    private var seasons by mutableStateOf<List<Pair<com.lagradost.cloudstream3.utils.UiText?, Int>>>(emptyList())
    private var ranges by mutableStateOf<List<Pair<com.lagradost.cloudstream3.utils.UiText?, EpisodeRange>>>(emptyList())
    private var dubs by mutableStateOf<List<Pair<com.lagradost.cloudstream3.utils.UiText?, com.lagradost.cloudstream3.DubStatus>>>(emptyList())
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
        model.load(activity, data.url, data.apiName, data.showFillers, data.dubStatus, data.start, loadTrailers = false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.page.observe(viewLifecycleOwner) { page = it }
        model.episodes.observe(viewLifecycleOwner) { episodes = (it as? Resource.Success)?.value.orEmpty() }
        model.movie.observe(viewLifecycleOwner) { movie = (it as? Resource.Success)?.value?.second }
        model.resumeWatching.observe(viewLifecycleOwner) { resume = it }
        model.watchStatus.observe(viewLifecycleOwner) { saved = it != WatchType.NONE }
        model.seasonSelections.observe(viewLifecycleOwner) { seasons = it }
        model.rangeSelections.observe(viewLifecycleOwner) { ranges = it }
        model.dubSubSelections.observe(viewLifecycleOwner) { dubs = it }
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
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        CeliumTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { findNavController().popBackStack() }) { Text("Back") }
                        Text("Details", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { load(true) }) { Text("Refresh") }
                    }
                    when (val response = page) {
                        is Resource.Success -> {
                            val data = response.value
                            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                item("summary") {
                                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                        val wide = maxWidth >= 700.dp
                                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                                            DetailArt(data, Modifier.width(280.dp))
                                            DetailCopy(data, Modifier.weight(1f))
                                        } else Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                            DetailArt(data, Modifier.width(180.dp).align(Alignment.CenterHorizontally))
                                            DetailCopy(data, Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                                if (seasons.size > 1 || dubs.size > 1 || ranges.size > 1) item("selectors") {
                                    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (seasons.size > 1) item { OutlinedButton(onClick = { selection = "Season" }) { Text(model.selectedSeason.value?.asString(context) ?: "Season") } }
                                        if (dubs.size > 1) item { OutlinedButton(onClick = { selection = "Audio" }) { Text(model.selectedDubStatus.value?.asString(context) ?: "Audio") } }
                                        if (ranges.size > 1) item { OutlinedButton(onClick = { selection = "Episodes" }) { Text(model.selectedRange.value?.asString(context) ?: "Episodes") } }
                                    }
                                }
                                if (episodes.isNotEmpty()) item("episode-title") {
                                    Text("Episodes", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                }
                                items(episodes, key = { it.id }, contentType = { "episode" }) { episode ->
                                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(Modifier.fillMaxWidth().clickable { action(episode) }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            if (episode.poster != null) CeliumArtwork(episode.poster, modifier = Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
                                            Column(Modifier.weight(1f)) {
                                                Text("${episode.episode}. ${episode.name ?: "Episode ${episode.episode}"}", fontWeight = FontWeight.Medium)
                                                episode.description?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                                if (episode.duration > 0) LinearProgressIndicator(progress = { (episode.position.toFloat() / episode.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                            }
                                        }
                                        Row {
                                            TextButton(onClick = { action(episode) }) { Text("Play") }
                                            if (model.currentRepo?.api?.hasDownloadSupport == true) TextButton(onClick = { action(episode, true) }) { Text("Download") }
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
                            }
                        }
                        is Resource.Failure -> {
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
                    val labels = when (kind) {
                        "Season" -> seasons.map { it.first?.asString(context) ?: "Season ${it.second}" }
                        "Audio" -> dubs.map { it.first?.asString(context) ?: it.second.name }
                        else -> ranges.map { it.first?.asString(context) ?: "${it.second.startEpisode}–${it.second.endEpisode}" }
                    }
                    AlertDialog(onDismissRequest = { selection = null }, title = { Text(kind) },
                        text = { LazyColumn(Modifier.heightIn(max = 400.dp)) { items(labels.size) { i ->
                            TextButton(onClick = {
                                when (kind) {
                                    "Season" -> model.changeSeason(seasons[i].second)
                                    "Audio" -> model.changeDubStatus(dubs[i].second)
                                    else -> model.changeRange(ranges[i].second)
                                }
                                selection = null
                            }, modifier = Modifier.fillMaxWidth()) { Text(labels[i]) }
                        } } }, confirmButton = { TextButton(onClick = { selection = null }) { Text("Close") } })
                }
            }
        }
    }

    @Composable
    private fun DetailArt(data: ResultData, modifier: Modifier) {
        CeliumArtwork(data.posterImage, data.posterHeaders, modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), crop = false)
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
                if (movie != null && model.currentRepo?.api?.hasDownloadSupport == true) TextButton(onClick = { movie?.let { action(it, true) } }) { Text("Download") }
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

