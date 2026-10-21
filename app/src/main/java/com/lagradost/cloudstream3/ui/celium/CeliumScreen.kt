package com.lagradost.cloudstream3.ui.celium

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
    onHome: () -> Unit,
    onQuery: (String) -> Unit, onRefresh: () -> Unit,
    onMore: (CeliumShelf) -> Unit, onMoreSearch: () -> Unit,
    onOpen: (SearchResponse, Boolean) -> Unit,
    onSections: () -> Unit,
    onRetry: () -> Unit = onRefresh,
    onRetryPaging: (String) -> Unit = {},
    onRetrySlot: (CategorySlot) -> Unit = {},
    randomEnabled: Boolean = false,
    getScroll: (String) -> Pair<Int, Int> = { Pair(0, 0) },
    setScroll: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    val preparing = state.binding == BindStatus.Waiting
    val bindFailure = (state.binding as? BindStatus.Failed)?.message
    var sourceSheet by rememberSaveable { mutableStateOf(false) }
    var collectionKey by rememberSaveable(state.home, state.section, state.source.id) { mutableStateOf<String?>(null) }
    // Quick-return header (Requirement 11): resets to visible on Home/Section, source or mode change (11.7).
    val quickReturn = rememberQuickReturnState(state.home, state.section, state.source.id, searchMode)
    var searchFocused by remember { mutableStateOf(false) }
    var scrollTrigger by remember { mutableStateOf(0) }
    val touchExploring = rememberTouchExploration()
    // Header stays fully visible while typing a query or while touch exploration is on (11.5, 11.10).
    val locked = (searchMode && searchFocused) || touchExploring
    SideEffect { if (quickReturn.locked != locked) quickReturn.locked = locked }
    CeliumTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints {
                val wide = maxWidth >= 840.dp
                val edge = if (maxWidth >= 840.dp) 32.dp else 16.dp
                // Medium card width, then scaled by the chosen Layout_Size (Req 8).
                val medium = if (maxWidth >= 840.dp) 168.dp else ((maxWidth - 44.dp) / 2.25f).coerceIn(132.dp, 160.dp)
                val width = rememberCeliumCardWidth(medium)
                QuickReturnLayout(quickReturn, Modifier.fillMaxSize(), header = { Column {
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
                    if (searchMode) {
                        if (state.source.id == "celium") Text("Celium Feed searches its first ready source. Choose a source for other results.",
                            Modifier.padding(horizontal = edge, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val focus = LocalFocusManager.current
                        OutlinedTextField(state.query, onQuery, Modifier.fillMaxWidth().padding(horizontal = edge, vertical = 8.dp)
                            .onFocusChanged { searchFocused = it.isFocused },
                            placeholder = { Text("Search ${state.section.label.lowercase()}") },
                            leadingIcon = { Icon(painterResource(R.drawable.search_icon), null, Modifier.size(22.dp)) },
                            trailingIcon = if (state.query.isNotEmpty()) ({ TextButton(onClick = { onQuery("") }) { Text("Clear") } }) else null,
                            singleLine = true, shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onQuery(state.query) }))
                    }
                } }, pinned = { Column {
                    LazyRow(contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        // Home is the first tab (Req 3); other tabs leave Home via selectSection.
                        item(key = "home") {
                            CategoryTab("Home", state.home) {
                                if (state.home) { scrollTrigger++; quickReturn.offset = 0f }
                                onHome()
                            }
                        }
                        items(CeliumSection.entries.filter { state.adultEnabled || (it != CeliumSection.Adult && it != CeliumSection.AdultGames) }, key = { it.name }) { section ->
                            CategoryTab(section.label, !state.home && state.section == section) {
                                if (!state.home && state.section == section) { scrollTrigger++; quickReturn.offset = 0f }
                                onSection(section)
                            }
                        }
                    }
                    if (state.searching || state.busy || preparing) LinearProgressIndicator(Modifier.fillMaxWidth())
                } }) {
                    key(state.home, state.section, state.source.id, searchMode, scrollTrigger) {
                        if (searchMode) SearchContent(state, edge, width, onQuery, onMoreSearch, onOpen, Modifier.fillMaxSize(), onRetry,
                            onRetryPaging = { onRetryPaging(CeliumState.SEARCH) }, header = quickReturn)
                        else {
                        val feedKey = "${state.home}_${state.section.name}_${state.source.id}_$scrollTrigger"
                        val initialScrollPos = remember(feedKey) { getScroll(feedKey) }
                        val feedList = rememberSaveable(feedKey, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
                            androidx.compose.foundation.lazy.LazyListState(initialScrollPos.first, initialScrollPos.second)
                        }
                        var userHasScrolled by remember(feedKey) { mutableStateOf(initialScrollPos.first > 0 || initialScrollPos.second > 0) }

                        LaunchedEffect(feedList.isScrollInProgress) {
                            if (feedList.isScrollInProgress) {
                                userHasScrolled = true
                            }
                        }

                        LaunchedEffect(feedList, feedKey) {
                            snapshotFlow { Pair(feedList.firstVisibleItemIndex, feedList.firstVisibleItemScrollOffset) }
                                .collect { (idx, off) ->
                                    if (feedList.isScrollInProgress || idx > 0 || off > 0) {
                                        setScroll(feedKey, idx, off)
                                    }
                                }
                        }

                        // Prevent downward drift when parallel slots load into Celium Feed:
                        // If the user hasn't scrolled, pin to top
                        LaunchedEffect(state.shelves.firstOrNull()?.key) {
                            if (!userHasScrolled && (feedList.firstVisibleItemIndex != 0 || feedList.firstVisibleItemScrollOffset != 0)) {
                                feedList.scrollToItem(0, 0)
                                quickReturn.offset = 0f
                            }
                        }

                        LaunchedEffect(feedKey) { quickReturn.offset = 0f }
                        ResetHeaderAtTop(quickReturn) { feedList.firstVisibleItemIndex == 0 && feedList.firstVisibleItemScrollOffset == 0 }
                        val notice = bindFailure ?: state.feedError?.let { "$it Retry, or choose another source." } ?: state.message
                        val hero = state.shelves.firstOrNull()?.cards?.firstOrNull()
                        // Items placed before the shelves, so visible indexes map onto shelf positions.
                        val lead = listOf(notice != null, hero != null, state.resume.isNotEmpty()).count { it }
                        val sectionsPaging = state.page(CeliumState.SECTIONS)
                        InfiniteScrollEffect(
                            lastVisibleIndex = { feedList.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.minus(lead) },
                            loadedCount = state.shelves.size,
                            threshold = CeliumPaging.SHELF_THRESHOLD,
                            status = when {
                                state.busy -> PageStatus.Loading
                                state.remainingSections > 0 -> sectionsPaging.status
                                else -> PageStatus.End
                            },
                            onLoadMore = onSections,
                        )
                        LazyColumn(Modifier.fillMaxSize(), state = feedList, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            // Binding failure (16.7) or feed failure (16.8) first; shown shelves stay below it.
                            if (notice != null) item("notice") {
                                Column(Modifier.padding(horizontal = edge)) {
                                    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Row {
                                        TextButton(onClick = onRetry) { Text("Retry") }
                                        TextButton(onClick = { sourceSheet = true }) { Text("Change source") }
                                    }
                                }
                            }
                            if (hero != null) item("hero") {
                                Row(Modifier.padding(horizontal = edge).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surface).clickable { onOpen(hero, false) }.padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                    CeliumArtwork(hero.posterUrl, hero.posterHeaders,
                                        Modifier.width(if (wide) 168.dp else 104.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("FEATURED", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(hero.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(if (state.home) "Home" else state.section.label, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Button(onClick = { onOpen(hero, false) }, shape = RoundedCornerShape(8.dp)) { Text("View details") }
                                    }
                                }
                            }
                            if (state.resume.isNotEmpty()) item("continue") {
                                CeliumShelfView("Continue watching", state.resume, width, edge, { onOpen(it, true) })
                            }
                            items(state.shelves, key = { it.key }, contentType = { "shelf" }) { shelf ->
                                CeliumShelfView(shelf.title, shelf.cards, width, edge, { onOpen(it, false) },
                                    onAll = { collectionKey = shelf.key },
                                    listKey = shelf.key,
                                    paging = if (shelf.hasNext) state.page(shelf.key) else null,
                                    onLoadMore = { state.shelves.firstOrNull { it.key == shelf.key }?.let(onMore) },
                                    onRetry = { onRetryPaging(shelf.key) })
                            }
                            // Failed categories keep a retry row instead of disappearing (21.8).
                            items(state.failedSlots, key = { "failed:${it.providerId}:${it.index}" }, contentType = { "failed" }) { slot ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(state.slotTitles[slot] ?: "Collection", style = MaterialTheme.typography.titleLarge,
                                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("Could not load", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    TextButton(onClick = { onRetrySlot(slot) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Retry") }
                                }
                            }
                            if (state.remainingSections > 0 || sectionsPaging.status == PageStatus.Error) item("sections-footer") {
                                PagingFooter(sectionsPaging, onRetry = { onRetryPaging(CeliumState.SECTIONS) }, Modifier.padding(horizontal = edge))
                            }
                            // Loading with nothing shown yet: shelf placeholders instead of an empty message.
                            val hasError = bindFailure != null || state.feedError != null || state.message != null
                            val loading = state.busy || preparing || (!hasError && state.shelves.isEmpty())
                            if (state.shelves.isEmpty() && loading && !hasError) items(3, key = { "placeholder:$it" }, contentType = { "placeholder" }) {
                                CeliumShelfPlaceholder(width, edge)
                            } else if (state.shelves.isEmpty() && !loading) item("empty") {
                                CeliumEmpty("Your feed is unavailable",
                                    "Retry, or choose another source. Search is available in its own tab.")
                            }
                        }
                        }
                    }
                }
                // Random button: Feed only, opt-in, picks from loaded shelves without a network request (12.1, 12.4–12.7).
                if (randomEnabled && !searchMode) {
                    val candidates = remember(state.shelves) { CeliumRandom.candidates(state.shelves) }
                    val canPick = candidates.isNotEmpty()
                    ExtendedFloatingActionButton(
                        text = { Text("Random") },
                        icon = { Icon(painterResource(R.drawable.ic_baseline_play_arrow_24), null) },
                        onClick = { if (canPick) CeliumRandom.pick(candidates)?.let { onOpen(it, false) } },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.align(Alignment.BottomStart).padding(edge)
                            .alpha(if (canPick) 1f else 0.38f)
                            .semantics {
                                contentDescription = "Open random title"
                                if (!canPick) disabled()
                            },
                    )
                }
                val shelf = state.shelves.firstOrNull { it.key == collectionKey }
                if (shelf != null) {
                    androidx.activity.compose.BackHandler { collectionKey = null }
                    CeliumCollectionOverlay(
                        shelf = shelf, paging = state.page(shelf.key), width = width, edge = edge,
                        onBack = { collectionKey = null },
                        onOpen = { onOpen(it, false) },
                        // Always page the latest copy of the shelf from state.
                        onLoadMore = { state.shelves.firstOrNull { it.key == shelf.key }?.let(onMore) },
                        onRetry = { onRetryPaging(shelf.key) },
                    )
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
                                    Text(if (status.installed) "Ready" else status.reason ?: "Preparing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    onMore: () -> Unit, onOpen: (SearchResponse, Boolean) -> Unit, modifier: Modifier, onRetry: () -> Unit,
    onRetryPaging: () -> Unit, header: QuickReturnState) {
    val bindFailure = (state.binding as? BindStatus.Failed)?.message
    Column(modifier) {
        Text(when {
            state.query.trim().length < 2 -> bindFailure ?: "Search the selected category and source."
            bindFailure != null -> bindFailure
            state.binding == BindStatus.Waiting -> "Preparing ${state.source.label}…"
            state.searching -> "Searching ${state.source.label}…"
            state.searchError != null -> state.searchError
            state.searched -> "${state.results.size} results · ${state.source.label}"
            else -> "Checking ${state.source.label}…"
        }, Modifier.padding(horizontal = edge, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        // Binding failures retry the provider (16.9); search failures repeat the query and keep shown results (16.8).
        if (bindFailure != null) TextButton(onClick = onRetry) { Text("Retry") }
        else if (state.searchError != null) TextButton(onClick = { onQuery(state.query) }) { Text("Retry search") }
        if (state.searched && !state.searching && state.results.isEmpty() && state.searchError == null) {
            CeliumEmpty("No matches here", "Try a different title or choose another source.")
        }
        val grid = rememberLazyGridState()
        ResetHeaderAtTop(header) { grid.firstVisibleItemIndex == 0 && grid.firstVisibleItemScrollOffset == 0 }
        val paging = state.page(CeliumState.SEARCH)
        InfiniteScrollEffect(
            lastVisibleIndex = { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
            loadedCount = state.results.size,
            threshold = CeliumPaging.ITEM_THRESHOLD,
            status = if (state.searchHasNext && !state.searching) paging.status else PageStatus.End,
            onLoadMore = onMore,
        )
        LazyVerticalGrid(GridCells.Adaptive(width), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(edge),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            items(state.results, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                CeliumPoster(card, Modifier.fillMaxWidth()) { onOpen(card, false) }
            }
            if (state.results.isNotEmpty()) item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                PagingFooter(paging, onRetry = onRetryPaging)
            }
        }
    }
}

/** One category tab (Home or a Section); the selected tab is bold with an underline. */
@Composable
private fun CategoryTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.padding(top = 8.dp).width(if (selected) 24.dp else 0.dp).height(2.dp).background(MaterialTheme.colorScheme.onBackground))
    }
}

/** Shows the header fully whenever the list is back at its very top (11.4, 11.6). */
@Composable
private fun ResetHeaderAtTop(header: QuickReturnState, atTop: () -> Boolean) {
    LaunchedEffect(header) {
        snapshotFlow(atTop).collect { top -> if (top && header.offset != 0f) header.offset = 0f }
    }
}

/** Live touch-exploration state; while on, the quick-return header stays visible (11.10). */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** Full-screen grid of one shelf, paged as the user scrolls (6.1–6.4). */
@Composable
private fun CeliumCollectionOverlay(shelf: CeliumShelf, paging: ListPaging, width: Dp, edge: Dp,
    onBack: () -> Unit, onOpen: (SearchResponse) -> Unit, onLoadMore: () -> Unit, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
                CeliumBackButton(onClick = onBack)
                Text(shelf.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
            }
            val grid = rememberLazyGridState()
            InfiniteScrollEffect(
                lastVisibleIndex = { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
                loadedCount = shelf.cards.size,
                threshold = CeliumPaging.ITEM_THRESHOLD,
                status = if (shelf.hasNext) paging.status else PageStatus.End,
                onLoadMore = onLoadMore,
            )
            LazyVerticalGrid(GridCells.Adaptive(width), Modifier.weight(1f), state = grid, contentPadding = PaddingValues(edge),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                items(shelf.cards, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                    CeliumPoster(card, Modifier.fillMaxWidth()) { onOpen(card) }
                }
                item(key = "footer", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                    PagingFooter(paging, onRetry = onRetry)
                }
            }
        }
    }
}

@Composable
internal fun CeliumShelfView(title: String, cards: List<SearchResponse>, width: Dp, edge: Dp,
    onClick: (SearchResponse) -> Unit, onAll: (() -> Unit)? = null,
    listKey: String = title, paging: ListPaging? = null,
    onLoadMore: () -> Unit = {}, onRetry: () -> Unit = {}) {
    // Row scroll state survives recomposition and is tied to this shelf (6.9).
    val row = key(listKey) { rememberLazyListState() }
    if (paging != null) InfiniteScrollEffect(
        lastVisibleIndex = { row.layoutInfo.visibleItemsInfo.lastOrNull()?.index },
        loadedCount = cards.size,
        threshold = CeliumPaging.ITEM_THRESHOLD,
        status = paging.status,
        onLoadMore = onLoadMore,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = edge), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (onAll != null) TextButton(onClick = onAll) { Text("See all") }
        }
        LazyRow(state = row, contentPadding = PaddingValues(horizontal = edge), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(cards, key = { "${it.apiName}:${it.url}" }, contentType = { "poster" }) { card ->
                CeliumPoster(card, Modifier.width(width)) { onClick(card) }
            }
            if (paging != null) item(key = "footer", contentType = "footer") {
                PagingFooter(paging, onRetry = onRetry, modifier = Modifier.width(width))
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
