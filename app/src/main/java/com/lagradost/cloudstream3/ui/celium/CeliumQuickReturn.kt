// Quick-return header for Celium Search and Feed (Requirement 11).
package com.lagradost.cloudstream3.ui.celium

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.roundToInt

/** Result of one scroll step: the new header offset and the vertical delta the header consumed. */
data class HeaderStep(val offset: Float, val consumedY: Float)

/** Pure header offset math. No Android imports. */
object HeaderMath {
    /**
     * [offset] is in [-height, 0]. Negative [deltaY] = scroll down (content moves up).
     * When disabled, or the header has no height, the offset is unchanged and nothing is consumed.
     */
    fun step(offset: Float, deltaY: Float, height: Float, enabled: Boolean): HeaderStep {
        if (!enabled || height <= 0f || deltaY == 0f) return HeaderStep(offset, 0f)
        val new = (offset + deltaY).coerceIn(-height, 0f)
        return HeaderStep(new, new - offset)
    }

    /** True when the content fits in the viewport measured with the header fully visible (11.6). */
    fun fits(contentHeight: Int, viewportWithHeaderVisible: Int): Boolean =
        contentHeight <= viewportWithHeaderVisible
}

@Stable
class QuickReturnState(initialOffset: Float = 0f) {
    /** Measured header height in px, written by [QuickReturnLayout]. */
    var heightPx by mutableIntStateOf(0)

    /** Header offset in [-heightPx, 0]. */
    var offset by mutableFloatStateOf(initialOffset)

    private var lockedState by mutableStateOf(false)

    /** While locked the header is forced fully visible and does not move on scroll. */
    var locked: Boolean
        get() = lockedState
        set(value) {
            lockedState = value
            if (value) offset = 0f
        }

    companion object {
        val Saver: Saver<QuickReturnState, Float> = Saver(
            save = { it.offset },
            restore = { QuickReturnState(it) },
        )
    }
}

/**
 * Remembers a [QuickReturnState] across configuration changes. When any of [resetKeys] change,
 * the header animates back to fully visible in 200 ms (Requirement 11.7).
 */
@Composable
fun rememberQuickReturnState(vararg resetKeys: Any?): QuickReturnState {
    val state = rememberSaveable(saver = QuickReturnState.Saver) { QuickReturnState() }
    // Skip the first run so a restored offset is not reset on recreation.
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(*resetKeys) {
        if (firstRun[0]) {
            firstRun[0] = false
            return@LaunchedEffect
        }
        if (state.offset != 0f) {
            animate(state.offset, 0f, animationSpec = tween(durationMillis = 200)) { value, _ ->
                state.offset = value
            }
        }
    }
    return state
}

/** Nested-scroll connection that moves the header by the vertical delta only (11.1, 11.2, 11.8, 11.9). */
fun QuickReturnState.connection(): NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val s = HeaderMath.step(offset, available.y, heightPx.toFloat(), enabled = !locked)
        offset = s.offset
        return Offset(0f, s.consumedY)
    }
}

/**
 * Places [header] at y = offset, [pinned] directly below the header's visible bottom edge,
 * and [content] below the pinned row, filling the rest. Clipped so nothing draws under the
 * header or the pinned row (11.3, 11.10).
 */
@Composable
fun QuickReturnLayout(
    state: QuickReturnState,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    pinned: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val connection = remember(state) { state.connection() }
    Layout(
        content = {
            Box { header() }
            Box { pinned() }
            Box { content() }
        },
        modifier = modifier
            .clipToBounds()
            .nestedScroll(connection),
    ) { measurables, constraints ->
        // Header and pinned row take their natural height.
        val loose = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val headerP = measurables[0].measure(loose)
        val headerH = headerP.height
        if (state.heightPx != headerH) state.heightPx = headerH

        val offset = if (state.locked) 0 else state.offset.coerceIn(-headerH.toFloat(), 0f).roundToInt()
        val pinnedP = measurables[1].measure(loose)
        val top = headerH + offset + pinnedP.height

        val width = constraints.maxWidth
        val total = if (constraints.hasBoundedHeight) constraints.maxHeight else top
        val contentH = (total - top).coerceAtLeast(0)
        val contentP = measurables[2].measure(
            constraints.copy(minHeight = contentH, maxHeight = contentH)
        )

        layout(width, total) {
            headerP.placeRelative(0, offset)
            pinnedP.placeRelative(0, headerH + offset)
            contentP.placeRelative(0, top)
        }
    }
}
