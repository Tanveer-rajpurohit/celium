package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.USER_AGENT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CeliumScreen(
    state: CeliumState, searchMode: Boolean,
    onSection: (CeliumSection) -> Unit, onSource: (CeliumSource) -> Unit,
    onQuery: (String) -> Unit, onRefresh: () -> Unit,
    onMore: (CeliumShelf) -> Unit, onMoreSearch: () -> Unit,
    onOpen: (SearchResponse, Boolean) -> Unit,
    onSections: () -> Unit,
) {
    var sourceSheet by rememberSaveable { mutableStateOf(false) }
    var collectionKey by rememberSaveable(state.section, state.source.id) { mutableStateOf<String?>(null) }
    CeliumTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val wide = maxWidth >= 840.dp
                val edge = if (maxWidth >= 840.dp) 32.dp else 16.dp
                val width = if (maxWidth >= 840.dp) 168.dp else ((maxWidth - 44.dp) / 2.25f).coerceIn(132.dp, 160.dp)
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = edge).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (searchMode) "Search" else "celium", Modifier.weight(1f).semantics { heading() },
                            fontSize = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp)
                        TextButton(onClick = { sourceSheet = true }) {
                            Text(state.source.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(" ▾")
                        }
                        if (!searchMode) IconButton(onClick = onRefresh, enabled = !state.busy) {
                            Icon(painterResource(R.drawable.ic_refresh), "Refresh feed", Modifier.size(22.dp))
                        }
                    }
                    LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        items(CeliumSection.entries.filter { state.adultEnabled || it != CeliumSection.Adult }, key = { it.name }) { section ->
                            Column(Modifier.clickable { onSection(section) }.padding(vertical = 12.dp)) {
                                Text(section.label, fontWeight = if (state.section == section) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (state.section == section) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                                Box(Modifier.padding(top = 8.dp).width(if (state.section == section) 24.dp else 0.dp).height(2.dp).background(MaterialTheme.colorScheme.onBackground))
                            }
                        }
                    }
                    if (searchMode) {
                        if (state.source.id == "celium") Text("Celium Feed searches its first ready source. Choose a source for other results.",
                            Modifier.padding(horizontal = edge, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val focus = LocalFocusManager.current
                        OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth().padding(horizontal = edge, vertical = 8.dp),
                            placeholder = { Text("Search ${state.section.label.lowercase()}") },
                            leadingIcon = { Icon(painterResource(R.drawable.search_icon), null, Modifier.size(22.dp)) },
                            trailingIcon = if (state.query.isNotEmpty()) ({ TextButton(onClick = { onQuery("") }) { Text("Clear") } }) else null,
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onQuery(state.query) }))
                    }
                    if (state.searching || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    key(state.section, state.source.id, searchMode) {
                        if (searchMode) SearchContent(state, edge, width, onQuery, onMoreSearch, onOpen, Modifier.weight(1f))
                        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            if (state.message != null) item("notice") {
                                Column(Modifier.padding(horizontal = edge)) {
                                    Text(state.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        TextButton(onClick = onRefresh) { Text("Retry") }
                                        TextButton(onClick = { sourceSheet = true }) { Text("Change source") }
                                    }
                                }
                            }
                            val hero = state.shelves.firstOrNull()?.cards?.firstOrNull()
                            if (hero != null) item("hero") {
                                Row(Modifier.padding(horizontal = edge).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface).clickable { onOpen(hero, false) }
                                    .padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CeliumArtwork(hero.posterUrl, hero.posterHeaders, Modifier.width(if (wide) 168.dp else 104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("FEATURED", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(hero.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(state.section.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { onOpen(hero, false) }, shape = RoundedCornerShape(8.dp)) { Text("View details") }
                                    }
                                }
                            }
                            if (state.resume.isNotEmpty()) item("continue") {
                                CeliumShelfView("Continue watching", state.resume, width, edge, { onOpen(it, true) })
                            }
                            items(state.shelves, key = { it.key }, contentType = { "shelf" }) { shelf ->
                                CeliumShelfView(shelf.title, shelf.cards.take(20), width, edge, { onOpen(it, false) },
                                    onAll = { collectionKey = shelf.key })
                            }
                            if (state.remainingSections > 0) item("sections") {
                                OutlinedButton(onClick = onSections, enabled = !state.busy, modifier = Modifier.padding(horizontal = edge).fillMaxWidth()) {
                                    Text("Explore more collections (${state.remainingSections})")
                                }
                            }
                            if (state.shelves.isEmpty()) item("empty") {
                                CeliumEmpty(if (state.busy) "Preparing your feed" else "Your feed is unavailable",
                                    "Retry, or choose another source. Search is available in its own tab.")
                            }
                        }
                    }
                }
                val shelf = state.shelves.firstOrNull { it.key == collectionKey }
                if (shelf != null) {
                    androidx.activity.compose.BackHandler { collectionKey = null }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { collectionKey = null }) { Text("Back") }
                                Text(shelf.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                            }
                            LazyVerticalGrid(GridCells.Adaptive(width), Modifier.weight(1f), contentPadding = PaddingValues(edge),
                                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                items(shelf.cards, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                                    CeliumPoster(card, Modifier.fillMaxWidth()) { onOpen(card, false) }
                                }
                            }
                            if (shelf.hasNext) TextButton(onClick = { onMore(shelf) }, enabled = shelf.key !in state.paging, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                Text(if (shelf.key in state.paging) "Loading…" else "Load more")
                            }
                        }
                    }
                }
                if (sourceSheet) ModalBottomSheet(onDismissRequest = { sourceSheet = false }) {
                    Text("Choose source", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
                    Text("Only this source loads and searches.", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.weight(1f, false)) {
                        items(state.sources, key = { it.source.id }) { status ->
                            Row(Modifier.fillMaxWidth().clickable { onSource(status.source); sourceSheet = false }.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(status.source.id == state.source.id, onClick = null)
                                Column(Modifier.padding(start = 12.dp)) {
                                    Text(status.source.label, fontWeight = FontWeight.Medium)
                                    Text(if (status.installed) "Ready" else "Preparing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchContent(state: CeliumState, edge: Dp, width: Dp, onQuery: (String) -> Unit,
    onMore: () -> Unit, onOpen: (SearchResponse, Boolean) -> Unit, modifier: Modifier) {
    Column(modifier) {
        Text(when {
            state.query.trim().length < 2 -> "Search the selected category and source."
            state.searching -> "Searching ${state.source.label}…"
            state.searchError != null -> state.searchError
            state.searched -> "${state.results.size} results · ${state.source.label}"
            else -> "Checking ${state.source.label}…"
        }, Modifier.padding(horizontal = edge, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (state.searchError != null) TextButton(onClick = { onQuery(state.query) }) { Text("Retry search") }
        if (state.searched && !state.searching && state.results.isEmpty() && state.searchError == null) {
            CeliumEmpty("No matches here", "Try a different title or choose another source.")
        }
        LazyVerticalGrid(GridCells.Adaptive(width), Modifier.weight(1f), contentPadding = PaddingValues(edge),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(state.results, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                CeliumPoster(card, Modifier.fillMaxWidth()) { onOpen(card, false) }
            }
        }
        if (state.searchHasNext) TextButton(onClick = onMore, enabled = !state.searching, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Load more results") }
    }
}

@Composable
internal fun CeliumShelfView(title: String, cards: List<SearchResponse>, width: Dp, edge: Dp,
    onClick: (SearchResponse) -> Unit, onAll: (() -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (onAll != null) TextButton(onClick = onAll) { Text("See all") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                CeliumPoster(card, Modifier.width(width)) { onClick(card) }
            }
        }
    }
}

@Composable
internal fun CeliumPoster(card: SearchResponse, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            Text(card.name, Modifier.padding(12.dp), maxLines = 4, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CeliumArtwork(card.posterUrl, card.posterHeaders, Modifier.matchParentSize())
        }
        Text(card.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium, modifier = Modifier.heightIn(min = 40.dp))
        (card as? CeliumTile)?.year?.let { Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun CeliumArtwork(url: String?, headers: Map<String, String>? = null, modifier: Modifier = Modifier, crop: Boolean = true) {
    val context = LocalContext.current
    val request = remember(url, headers, context) {
        ImageRequest.Builder(context).data(url).httpHeaders(NetworkHeaders.Builder().also { values ->
            values["User-Agent"] = USER_AGENT
            headers?.forEach { (name, value) -> values[name] = value }
        }.build()).build()
    }
    // AsyncImage resolves the target size from constraints rather than decoding original-size posters.
    AsyncImage(request, contentDescription = null, modifier = modifier, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
}

@Composable
internal fun CeliumEmpty(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
}
