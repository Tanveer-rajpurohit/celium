package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import kotlinx.coroutines.launch

/** Icon-only back control with a 48dp touch target. */
@Composable
fun CeliumBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), contentDescription = "Back")
    }
}

/** Icon-only refresh control with a 48dp touch target. */
@Composable
fun CeliumRefreshButton(onClick: () -> Unit, enabled: Boolean = true, modifier: Modifier = Modifier) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Icon(painterResource(R.drawable.ic_refresh), contentDescription = "Refresh")
    }
}

/**
 * Outlined selector showing the current [value] with a trailing dropdown icon.
 * Semantics are merged into one node announcing [name] and [value].
 */
@Composable
fun CeliumSelectorButton(name: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .sizeIn(minHeight = 48.dp)
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = value
                role = Role.Button
                onClick(label = "Open $name options") { onClick(); true }
            },
    ) {
        Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(4.dp))
        Icon(
            painterResource(R.drawable.ic_baseline_keyboard_arrow_down_24),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Requests the next page when the last visible item comes within [threshold] items of the
 * end of [loadedCount] items while [status] is Idle (Requirement 6.1, 6.2, 21.4).
 * Fires once per transition into the "should request" state.
 */
@Composable
fun InfiniteScrollEffect(
    lastVisibleIndex: () -> Int?,
    loadedCount: Int,
    threshold: Int,
    status: PageStatus,
    onLoadMore: () -> Unit,
) {
    val count by rememberUpdatedState(loadedCount)
    val current by rememberUpdatedState(status)
    val load by rememberUpdatedState(onLoadMore)
    val visible by rememberUpdatedState(lastVisibleIndex)
    LaunchedEffect(threshold) {
        snapshotFlow { CeliumPaging.shouldRequest(visible(), count, threshold, current) }
            .distinctUntilChanged()
            .filter { it }
            .collect { load() }
    }
}

/** Trailing list item: a small spinner while loading, or a message with Retry on error (6.3, 6.6). */
@Composable
fun PagingFooter(paging: ListPaging, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    when (paging.status) {
        PageStatus.Loading -> Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier.size(24.dp).semantics { contentDescription = "Loading more" },
                strokeWidth = 2.dp,
            )
        }
        PageStatus.Error -> Column(
            modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                paging.error ?: "Could not load more",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("Retry") }
        }
        else -> Unit
    }
}

/**
 * Home Featured_Carousel (8.1–8.9). Picks 3–4 unique items via [CeliumCarouselLogic.pickFeatured]
 * and renders nothing when fewer than 3 are unique. Swipe with the pager, or use the wrapping
 * prev/next buttons. Play and Details act on the current item. All controls have 48dp targets.
 */
@Composable
fun CeliumFeaturedCarousel(
    items: List<SearchResponse>,
    width: Dp,
    edge: Dp,
    onPlay: (SearchResponse) -> Unit,
    onDetails: (SearchResponse) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val featured = remember(items) { CeliumCarouselLogic.pickFeatured(items) }
    if (featured.isEmpty()) return
    // Reset the pager when the featured set changes.
    androidx.compose.runtime.key(featured.map { it.url }) {
        val pager = rememberPagerState(pageCount = { featured.size })
        val scope = rememberCoroutineScope()
        // Matches the old Featured card: 104dp on phones, 168dp on wide layouts.
        val posterWidth = if (wide) 168.dp else width.coerceIn(104.dp, 168.dp)
        fun go(delta: Int) {
            val target = CeliumCarouselLogic.wrapIndex(pager.currentPage + delta, featured.size)
            scope.launch { pager.animateScrollToPage(target) }
        }
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalPager(
                state = pager,
                contentPadding = PaddingValues(horizontal = edge),
                pageSpacing = 12.dp,
                key = { featured[it].url },
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val item = featured[page]
                val position = stringResource(R.string.celium_featured_position, page + 1, featured.size)
                val playLabel = stringResource(R.string.celium_play_title, item.name)
                val detailsLabel = stringResource(R.string.celium_details_title, item.name)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable { onDetails(item) }
                        .semantics { stateDescription = position }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CeliumArtwork(
                        item.posterUrl, item.posterHeaders,
                        Modifier.width(posterWidth).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            stringResource(R.string.celium_featured),
                            style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.name, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onPlay(item) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = playLabel },
                            ) {
                                Icon(painterResource(R.drawable.ic_baseline_play_arrow_24), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.celium_play))
                            }
                            OutlinedButton(
                                onClick = { onDetails(item) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                                    .semantics { contentDescription = detailsLabel },
                            ) { Text(stringResource(R.string.celium_details)) }
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = edge),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { go(-1) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_keyboard_arrow_left_24),
                        contentDescription = stringResource(R.string.celium_featured_previous),
                    )
                }
                Text(
                    "${pager.currentPage + 1} / ${featured.size}",
                    Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { go(1) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_baseline_keyboard_arrow_right_24),
                        contentDescription = stringResource(R.string.celium_featured_next),
                    )
                }
            }
        }
    }
}

/**
 * Skeleton for a shelf that is still loading: a title bar and a row of poster-shaped
 * surfaceVariant boxes sized like [CeliumShelfView] cards. Announced once as "Loading shelf".
 */
@Composable
fun CeliumShelfPlaceholder(width: Dp, edge: Dp, modifier: Modifier = Modifier, count: Int = 6) {
    val label = stringResource(R.string.celium_loading_shelf)
    val tone = MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.padding(horizontal = edge).width(160.dp).height(24.dp)
                .clip(RoundedCornerShape(6.dp)).background(tone),
        )
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Row(
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).padding(horizontal = edge),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                repeat(count) {
                    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(tone))
                        Box(Modifier.fillMaxWidth(0.8f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(tone))
                    }
                }
            }
        }
    }
}
