package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.result.ResultData
import kotlinx.coroutines.delay

/** Shape of a loaded header image, decided from its measured pixels (Requirement 20.3). */
enum class HeaderShape { Wide, Portrait }

/** Pure header layout rules. No Android imports so it can be tested on the JVM. */
object HeaderLayout {
    const val WIDE_RATIO = 1.3f
    const val LOAD_TIMEOUT_MS = 15_000L

    /** Wide exactly when w / h >= 1.3. Non-positive sizes count as Portrait. */
    fun classify(w: Int, h: Int): HeaderShape =
        if (w > 0 && h > 0 && w.toFloat() / h >= WIDE_RATIO) HeaderShape.Wide else HeaderShape.Portrait

    /** Non-blank, distinct URLs with the backdrop first (Requirement 20.5). */
    fun candidates(backdrop: String?, poster: String?): List<String> =
        listOfNotNull(backdrop, poster).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /**
     * Display size keeping the image's own ratio (Requirement 20.7).
     * Wide: contentWidth wide. Portrait: posterWidth wide. Height = width * h / w.
     */
    fun size(w: Int, h: Int, contentWidth: Float, posterWidth: Float): Pair<Float, Float> {
        val width = if (classify(w, h) == HeaderShape.Wide) contentWidth else posterWidth
        if (w <= 0 || h <= 0) return width to width * 1.5f
        return width to width * h / w
    }
}

/**
 * Details header image. Loads the backdrop first, then the poster on error or after 15 s.
 * Shows a surfaceVariant placeholder while loading, then draws the image at its own ratio:
 * wide images span [contentWidth], portrait posters keep [posterWidth] with no wide box around them.
 * Emits nothing when no candidate loads (Requirement 20.6).
 *
 * [onShape] reports the measured shape so the caller can place wide images above the copy.
 */
@Composable
fun CeliumHeaderImage(
    data: ResultData,
    contentWidth: Dp,
    posterWidth: Dp,
    modifier: Modifier = Modifier,
    onShape: (HeaderShape?) -> Unit = {},
) {
    val candidates = remember(data.posterBackgroundImage, data.backgroundPosterUrl, data.posterImage) {
        HeaderLayout.candidates(data.posterBackgroundImage ?: data.backgroundPosterUrl, data.posterImage)
    }
    var index by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(index)
    if (url == null) {
        LaunchedEffect(Unit) { onShape(null) }
        return
    }
    key(url) {
        HeaderCandidate(url, data.posterHeaders, contentWidth, posterWidth, modifier, onShape) { index++ }
    }
}

@Composable
private fun HeaderCandidate(
    url: String,
    headers: Map<String, String>?,
    contentWidth: Dp,
    posterWidth: Dp,
    modifier: Modifier,
    onShape: (HeaderShape?) -> Unit,
    onFail: () -> Unit,
) {
    val context = LocalContext.current
    var measured by remember(url) { mutableStateOf<Pair<Int, Int>?>(null) }
    // Same headers as CeliumArtwork. A fixed bound (not the placeholder's constraints) keeps the
    // decoded ratio true to the source and avoids a blurry upscale once the box grows to full width.
    val request = remember(url, headers, context) {
        ImageRequest.Builder(context).data(url).httpHeaders(NetworkHeaders.Builder().also { values ->
            values["User-Agent"] = USER_AGENT
            headers?.forEach { (name, value) -> values[name] = value }
        }.build())
            .size(Size(1600, 1600)).scale(Scale.FIT).precision(Precision.INEXACT)
            .build()
    }
    LaunchedEffect(url) {
        delay(HeaderLayout.LOAD_TIMEOUT_MS)
        if (measured == null) onFail()
    }
    val shape = RoundedCornerShape(12.dp)
    val current = measured
    val sized = if (current == null) {
        // Placeholder at today's poster size with the existing Celium placeholder colour (20.4).
        Modifier.width(posterWidth).aspectRatio(2f / 3f)
    } else {
        val (w, h) = current
        val wide = HeaderLayout.classify(w, h) == HeaderShape.Wide
        Modifier.width(if (wide) contentWidth else posterWidth).aspectRatio(w.toFloat() / h)
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        modifier = modifier.then(sized).clip(shape)
            .then(if (current == null) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier),
        contentScale = ContentScale.Fit,
        onSuccess = { state ->
            val w = state.result.image.width
            val h = state.result.image.height
            if (w > 0 && h > 0) {
                measured = w to h
                onShape(HeaderLayout.classify(w, h))
            } else onFail()
        },
        onError = { onFail() },
    )
}
