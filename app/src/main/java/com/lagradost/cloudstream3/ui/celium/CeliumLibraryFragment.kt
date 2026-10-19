package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CeliumLibraryFragment : BaseComposeFragment() {
    private var history by mutableStateOf<List<DataStoreHelper.ResumeWatchingResult>>(emptyList())
    private var saved by mutableStateOf<List<DataStoreHelper.BookmarkedData>>(emptyList())
    private var error by mutableStateOf<String?>(null)
    private var loading by mutableStateOf(true)

    override fun onStart() { super.onStart(); refresh() }

    private fun refresh() {
        lifecycleScope.launch {
            try {
                loading = true
                val recent = (withContext(Dispatchers.IO) { CeliumHistory.all() } + HomeViewModel.getResumeWatching().orEmpty()).distinctBy { it.parentId }
                val bookmarks = withContext(Dispatchers.IO) { DataStoreHelper.getAllBookmarkedData() }
                history = recent.filter { eligible(it) }
                saved = bookmarks.filter { eligible(it) }.sortedByDescending { it.bookmarkedTime }
                error = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e); error = "Your library could not load. Try again."
            } finally { loading = false }
        }
    }

    private fun eligible(card: SearchResponse) =
        MainAPI.settingsForProvider.enableAdult || !CeliumSources.isAdult(card.type, card.apiName)

    private fun remove(card: SearchResponse, fromHistory: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (fromHistory && card is DataStoreHelper.ResumeWatchingResult) CeliumHistory.remove(card.parentId)
                    else DataStoreHelper.deleteBookmarkedData(card.id)
                }
                refresh()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e); error = "The item could not be removed. Try again." }
        }
    }

    @Composable
    override fun Content() {
        var tab by rememberSaveable { mutableStateOf("Saved") }
        var clear by remember { mutableStateOf(false) }
        CeliumTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Your library", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = ::refresh) { Text("Refresh") }
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Saved", "History").forEach { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
                    }
                    Text(if (tab == "History") "Continue watching and manage your viewing history." else "Titles you have saved for later.",
                        Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, Modifier.padding(24.dp)) }
                    if (tab == "History" && history.isNotEmpty()) TextButton(onClick = { clear = true }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Clear visible history") }
                    val cards: List<SearchResponse> = if (tab == "History") history else saved
                    if (cards.isEmpty() && !loading) CeliumEmpty(if (tab == "History") "No watch history" else "Nothing saved yet",
                        if (tab == "History") "Titles you start watching will appear here." else "Save a movie or show from its details page.")
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(cards, key = { "${it.apiName}:${it.url}" }, contentType = { "library-row" }) { card ->
                            Column {
                                Row(Modifier.fillMaxWidth().clickable {
                                    if (card is DataStoreHelper.ResumeWatchingResult) SearchHelper.handleSearchClickCallback(SearchClickCallback(SEARCH_ACTION_PLAY_FILE, requireView(), -1, card))
                                    else loadSearchResult(card)
                                }, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CeliumArtwork(card.posterUrl, card.posterHeaders, Modifier.width(72.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(card.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(card.apiName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        if (card is DataStoreHelper.ResumeWatchingResult) {
                                            if (card.episode != null) Text("Season ${card.season ?: 1} · Episode ${card.episode}", style = MaterialTheme.typography.bodySmall)
                                            val position = card.watchPos
                                            if (position != null && position.duration > 0) LinearProgressIndicator(progress = { (position.position.toFloat() / position.duration).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                        }
                                    }
                                }
                                Row {
                                    TextButton(onClick = { loadSearchResult(card) }) { Text("Details") }
                                    TextButton(onClick = { remove(card, tab == "History") }) { Text(if (tab == "History") "Remove from history" else "Remove saved") }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                            }
                        }
                    }
                }
                if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear visible history?") },
                    text = { Text("Remove these ${history.size} titles from Continue watching. Saved titles, video files and hidden adult history stay unchanged.") },
                    confirmButton = { TextButton(onClick = {
                        clear = false
                        val ids = history.mapNotNull { it.parentId }
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) { ids.forEach(CeliumHistory::remove) }
                                refresh()
                            } catch (e: CancellationException) { throw e
                            } catch (e: Exception) { logError(e); error = "History could not be cleared." }
                        }
                    }) { Text("Clear history") } },
                    dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
            }
        }
    }
}

