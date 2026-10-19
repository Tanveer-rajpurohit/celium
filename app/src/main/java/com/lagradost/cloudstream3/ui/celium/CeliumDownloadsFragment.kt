package com.lagradost.cloudstream3.ui.celium

import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.view.View
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.download.*
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DOWNLOAD_EPISODE_CACHE
import com.lagradost.cloudstream3.utils.DataStore.getFolderName
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CeliumDownloadsFragment : BaseComposeFragment() {
    private val model: DownloadViewModel by viewModels()
    private var headers by mutableStateOf<List<VisualDownloadCached.Header>>(emptyList())
    private var children by mutableStateOf<List<VisualDownloadCached.Child>>(emptyList())
    private var selected by mutableStateOf<VisualDownloadCached.Header?>(null)
    private var tick by mutableIntStateOf(0)
    private var loaded by mutableStateOf(false)
    private var failed by mutableStateOf(false)

    private fun eligible(header: VisualDownloadCached.Header) =
        MainAPI.settingsForProvider.enableAdult || !CeliumSources.isAdult(header.data.type, header.data.apiName)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        model.headerCards.observe(viewLifecycleOwner) { result ->
            when (result) {
                is Resource.Success -> { headers = result.value.filter(::eligible); loaded = true; failed = false }
                is Resource.Failure -> { failed = true; loaded = true }
                else -> Unit
            }
        }
        model.childCards.observe(viewLifecycleOwner) { result ->
            if (result is Resource.Success) children = result.value
        }
        // Refresh disk/progress state only while this screen is visible, not during feed scrolling.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    model.updateHeaderList(requireContext()).join()
                    selected?.let { model.updateChildList(requireContext(), getFolderName(DOWNLOAD_EPISODE_CACHE, it.data.id.toString())).join() }
                    tick++
                    delay(2000)
                }
            }
        }
    }

    private fun open(header: VisualDownloadCached.Header) {
        if (header.child != null && header.totalDownloads == 1 && header.totalBytes > 0 && header.currentBytes >= header.totalBytes) {
            DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PLAY_FILE, header.child))
        } else {
            selected = header
            children = emptyList()
            model.updateChildList(requireContext(), getFolderName(DOWNLOAD_EPISODE_CACHE, header.data.id.toString()))
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        // The tick invalidates only this destination's lightweight progress rows.
        @Suppress("UNUSED_VARIABLE") val progressTick = tick
        BackHandler(selected != null) { selected = null }
        CeliumTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selected != null) TextButton(onClick = { selected = null }) { Text("Back") }
                        Text(selected?.data?.name ?: "Downloads", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                    Text("Your offline library", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!loaded) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (failed) CeliumEmpty("Downloads could not load", "Check that the download location is still accessible.")
                    if (loaded && headers.isEmpty() && !failed) CeliumEmpty("No downloads yet", "Open a title and choose Download. Available quality and speed depend on the source.")
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        if (selected == null) items(headers, key = { it.data.id }, contentType = { "download-header" }) { header ->
                            Column {
                                Row(Modifier.fillMaxWidth().clickable { open(header) }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    CeliumArtwork(header.data.poster, modifier = Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(header.data.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${header.totalDownloads} file(s) · ${formatShortFileSize(context, header.currentBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (header.totalBytes > 0) LinearProgressIndicator(progress = { (header.currentBytes.toFloat() / header.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                                Row {
                                    TextButton(onClick = { open(header) }) { Text(if (header.child != null && header.totalDownloads == 1 && header.totalBytes > 0 && header.currentBytes >= header.totalBytes) "Play" else "View files") }
                                    TextButton(onClick = { model.handleSingleDelete(context, header.data.id) }) { Text("Delete files") }
                                }
                                header.child?.let { child -> DownloadControls(child, header.currentBytes, header.totalBytes) }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        } else items(children, key = { it.data.id }, contentType = { "download-file" }) { child ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(child.data.name ?: "Episode ${child.data.episode}", style = MaterialTheme.typography.titleMedium)
                                Text("Season ${child.data.season ?: 1} · Episode ${child.data.episode} · ${formatShortFileSize(context, child.currentBytes)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (child.totalBytes > 0) LinearProgressIndicator(progress = { (child.currentBytes.toFloat() / child.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                DownloadControls(child.data, child.currentBytes, child.totalBytes)
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadControls(data: com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadEpisodeCached, current: Long, total: Long) {
        val context = LocalContext.current
        val status = VideoDownloadManager.downloadStatus[data.id]
        val active = status == VideoDownloadManager.DownloadType.IsDownloading
        val complete = total > 0 && current >= total
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (complete) TextButton(onClick = { DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(DOWNLOAD_ACTION_PLAY_FILE, data)) }) { Text("Play offline") }
            if (!complete) TextButton(onClick = {
                DownloadButtonSetup.handleDownloadClick(DownloadClickEvent(if (active) DOWNLOAD_ACTION_PAUSE_DOWNLOAD else DOWNLOAD_ACTION_RESUME_DOWNLOAD, data))
            }) { Text(if (active) "Pause" else "Resume / retry") }
            TextButton(onClick = { model.handleSingleDelete(context, data.id) }) { Text("Delete") }
        }
    }
}

