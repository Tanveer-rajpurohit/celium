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
import com.lagradost.cloudstream3.R

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
