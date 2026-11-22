package com.lagradost.cloudstream4

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream4.desktop.DesktopVideoPanel
import com.lagradost.cloudstream4.desktop.moviesmod.DesktopMoviesmod
import com.lagradost.cloudstream4.desktop.moviesmod.MoviesmodProvider
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.coroutines.*
import java.util.Collections
import javax.swing.JLabel
import javax.swing.JPanel

fun main() = application {
    val state = rememberWindowState(width = 1100.dp, height = 760.dp)
    Window(onCloseRequest = ::exitApplication, title = "Celium — Moviesmod", state = state,
        onPreviewKeyEvent = {
            if (it.type == KeyEventType.KeyDown && it.key == Key.Escape && state.placement == WindowPlacement.Fullscreen) {
                state.placement = WindowPlacement.Floating
                true
            } else false
        }) {
        CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
            DesktopApp {
                state.placement = if (state.placement == WindowPlacement.Fullscreen)
                    WindowPlacement.Floating else WindowPlacement.Fullscreen
            }
        }
    }
}

@Composable
private fun DesktopApp(onFullscreen: () -> Unit) {
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf<MoviesmodProvider?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResponse>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<LoadResponse?>(null) }
    var links by remember { mutableStateOf<List<ExtractorLink>>(emptyList()) }
    var selectedEpisode by remember { mutableStateOf<String?>(null) }
    var selectedLink by remember { mutableStateOf<ExtractorLink?>(null) }
    var status by remember { mutableStateOf("") }
    var failure by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableIntStateOf(0) }

    fun cancel() { generation++; job?.cancel(); job = null; status = "" }
    fun runStage(stage: String, operation: suspend (Int) -> Unit) {
        cancel()
        failure = null
        status = stage
        val request = generation
        job = scope.launch {
            try { withTimeout(90_000) { operation(request) }
            } catch (_: TimeoutCancellationException) {
                if (request == generation) failure = "$stage timed out. Try again."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (request == generation) failure = "$stage failed: " + (e.message ?: "Unknown error")
                    .replace(Regex("https?://\\S+"), "[website]").take(250)
            } finally { if (request == generation) status = "" }
        }
    }
    fun initialize() = runStage("Starting Moviesmod") { request ->
        val loaded = withContext(Dispatchers.IO) { DesktopMoviesmod.initialize() }
        if (request == generation) provider = loaded
    }
    fun search() {
        val api = provider ?: return
        if (query.isBlank()) return
        val term = query.trim()
        runStage("Searching Moviesmod") { request ->
            val found = withContext(Dispatchers.IO) { api.search(term, 1)?.items.orEmpty() }
            if (request == generation) { results = found; searched = true; details = null; links = emptyList() }
        }
    }
    fun extract(data: String, label: String) {
        val api = provider ?: return
        links = emptyList()
        selectedEpisode = label
        runStage("Finding sources") { request ->
            val found = Collections.synchronizedList(mutableListOf<ExtractorLink>())
            var extractionError: Exception? = null
            try {
                withContext(Dispatchers.IO) { api.loadLinks(data, false, {}, { found.add(it) }) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { extractionError = e }
            if (request == generation) {
                links = found.filter { it.url.isNotBlank() }.distinctBy { it.url }
                if (links.isEmpty()) {
                    extractionError?.let { throw it }
                    failure = "Could not extract playable sources. The source hosts may have changed or need browser support."
                } else if (extractionError != null) failure = "Some sources failed. You can try the sources listed below."
            }
        }
    }
    LaunchedEffect(Unit) { initialize() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Celium", style = MaterialTheme.typography.headlineMedium)
            Text("Moviesmod · Windows desktop prototype")
            if (selectedLink == null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Search movies and series") }, singleLine = true,
                    modifier = Modifier.weight(1f).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { search(); true } else false
                    })
                Button(onClick = ::search, enabled = provider != null && query.isNotBlank()) { Text("Search") }
                if (provider == null && status.isEmpty()) Button(onClick = ::initialize) { Text("Retry startup") }
            }
            if (status.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(status)
                if (status != "Playing") {
                    CircularProgressIndicator(Modifier.size(22.dp))
                    TextButton(onClick = { cancel(); selectedLink = null }) { Text("Cancel") }
                }
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (status == "Finding sources") Text("If a source opens Edge, complete verification there. Cancel closes the temporary browser.")
            val playing = selectedLink
            val title = details
            when {
                playing != null -> {
                    Button(onClick = { selectedLink = null; status = ""; failure = null }) { Text("Back to sources") }
                    Text(selectedEpisode.orEmpty())
                    key(playing) {
                        var panel by remember { mutableStateOf<DesktopVideoPanel?>(null) }
                        DisposableEffect(Unit) { onDispose { panel?.close() } }
                        SwingPanel(modifier = Modifier.fillMaxWidth().weight(1f), factory = {
                            try {
                                DesktopVideoPanel(playing,
                                    onStarted = { scope.launch { if (selectedLink === playing) { status = "Playing"; failure = null } } },
                                    onFailure = { message -> scope.launch { if (selectedLink === playing) { status = ""; failure = message } } },
                                    onFullscreen = { scope.launch { onFullscreen() } },
                                ).also { panel = it }
                            } catch (e: Exception) {
                                scope.launch { failure = e.message?.take(200) ?: "VLC could not start. Install 64-bit VLC 3 and restart Celium." }
                                JPanel().apply { add(JLabel("Player unavailable. Return to sources.")) }
                            } catch (_: LinkageError) {
                                scope.launch { failure = "Install 64-bit VLC 3 and restart Celium to enable playback." }
                                JPanel()
                            }
                        })
                    }
                }
                title != null -> {
                    TextButton(onClick = { cancel(); details = null; links = emptyList(); failure = null }) { Text("Back to results") }
                    Text(title.name, style = MaterialTheme.typography.titleLarge)
                    title.plot?.let { Text(it.take(700)) }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (title is MovieLoadResponse) item {
                            Button(onClick = { extract(title.dataUrl, title.name) }) { Text("Find movie sources") }
                        }
                        if (title is TvSeriesLoadResponse) items(title.episodes.sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))) { episode ->
                            val label = "Season ${episode.season ?: "?"} · Episode ${episode.episode ?: "?"} ${episode.name.orEmpty()}"
                            OutlinedButton(onClick = { extract(episode.data, label) }) { Text(label) }
                        }
                        if (links.isNotEmpty()) item { Text("Sources · ${selectedEpisode.orEmpty()}") }
                        items(links) { link ->
                            OutlinedButton(onClick = { cancel(); failure = null; selectedLink = link; status = "Starting playback" }) {
                                val quality = if (link.quality > 0) "${link.quality}p" else "Quality unknown"
                                Text("${link.source} · $quality")
                            }
                        }
                    }
                }
                else -> {
                    if (searched && results.isEmpty() && status.isEmpty() && failure == null) Text("No titles found. Try another search.")
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(results) { result ->
                            OutlinedButton(onClick = {
                                val api = provider ?: return@OutlinedButton
                                runStage("Loading title") { request ->
                                    val loaded = withContext(Dispatchers.IO) { api.load(result.url) }
                                        ?: error("The provider returned no title details")
                                    if (request == generation) { details = loaded; links = emptyList() }
                                }
                            }, modifier = Modifier.fillMaxWidth()) { Text(result.name) }
                        }
                    }
                }
            }
        }
    }
}
