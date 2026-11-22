package com.lagradost.cloudstream3.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.celium.CeliumArtwork
import com.lagradost.cloudstream3.ui.player.StreamChoice

/** Top-bar cast icon. Tapping it connects to a TV, or shows the connected TV with Disconnect. */
@Composable
fun CastButton(modifier: Modifier = Modifier) {
    val state by CastSession.state.collectAsState()
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(
            painterResource(R.drawable.cast),
            contentDescription = if (state.connected) "Casting to ${state.device?.name}" else "Cast to a TV",
            tint = if (state.connected) MaterialTheme.colorScheme.primary else LocalContentColor.current,
            modifier = Modifier.size(22.dp),
        )
    }
    if (open) {
        if (state.connected) ConnectedDialog(state, onClose = { open = false })
        else DevicePickerDialog(onClose = { open = false })
    }
}

@Composable
private fun ConnectedDialog(state: CastState, onClose: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Connected to ${state.device?.name}") },
        text = {
            Text(state.nowPlaying?.let { "Playing ${it.title}${it.label?.let { l -> " · $l" }.orEmpty()}" }
                ?: "Tap Play on any title or episode and it will play on ${state.device?.name}.")
        },
        confirmButton = {
            if (state.nowPlaying != null) TextButton(onClick = { CastSession.openController(true); onClose() }) { Text("Open controls") }
            else TextButton(onClick = onClose) { Text("Close") }
        },
        dismissButton = { TextButton(onClick = { CastSession.disconnect(context); onClose() }) { Text("Disconnect") } },
    )
}

@Composable
private fun DevicePickerDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var devices by remember { mutableStateOf<List<DlnaDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var mirror by remember { mutableStateOf(false) }
    if (mirror) return MirrorScreenDialog(onClose = { mirror = false; onClose() })
    LaunchedEffect(attempt) {
        scanning = true
        devices = runCatching { DlnaDiscovery.scan(context) }.getOrDefault(emptyList())
        scanning = false
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Cast to a TV or device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (scanning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Looking for devices on your Wi-Fi…")
                } else if (devices.isEmpty()) {
                    Text("Nothing found. Make sure the TV or device is on, on the same Wi-Fi as this phone, and that media sharing (DLNA) is turned on.")
                }
                if (!scanning) TextButton(onClick = { mirror = true }, contentPadding = PaddingValues(0.dp)) {
                    Icon(painterResource(R.drawable.screen_mirror), null, Modifier.size(18.dp))
                    Text("Or mirror your screen instead", Modifier.padding(start = 8.dp))
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(devices, key = { it.id }) { device ->
                        Row(
                            Modifier.fillMaxWidth().clickable { CastSession.connect(context, device); onClose() }.padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_baseline_tv_24), null, Modifier.size(22.dp))
                            Text(device.name, Modifier.padding(start = 16.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Cancel") } },
        dismissButton = { if (!scanning) TextButton(onClick = { attempt++ }) { Text("Scan again") } },
    )
}

/** Mini bar while casting (tap to open the full controls), plus the full-screen controls. */
@Composable
fun CastOverlay(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Celium may have been closed while the TV kept playing: take the controls back.
    androidx.compose.runtime.LaunchedEffect(Unit) { CastSession.restore(context) }
    val state by CastSession.state.collectAsState()
    val playing = state.nowPlaying ?: return
    if (state.controllerOpen) CastController(state, playing)
    else CastMiniBar(state, playing, modifier)
}

@Composable
private fun CastMiniBar(state: CastState, playing: CastNowPlaying, modifier: Modifier) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp)).clickable { CastSession.openController(true) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.cast), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(playing.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        playing.message ?: listOfNotNull(playing.label, "On ${state.device?.name}").joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (playing.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    playing.loading -> CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp), strokeWidth = 2.dp)
                    !playing.failed -> IconButton(onClick = CastSession::togglePause) {
                        Icon(painterResource(if (playing.paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24),
                            if (playing.paused) "Play" else "Pause")
                    }
                }
                IconButton(onClick = CastSession::stop) { Icon(painterResource(R.drawable.ic_baseline_close_24), "Stop casting this title") }
            }
            if (!playing.loading && !playing.failed && playing.durationMs > 0) LinearProgressIndicator(
                progress = { (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp),
            )
        }
    }
}

private fun time(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun CastController(state: CastState, playing: CastNowPlaying) {
    val context = LocalContext.current
    val tv = state.device?.name ?: "TV"
    var sheet by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var dismissedFailure by remember { mutableStateOf<String?>(null) }
    var showMirror by remember { mutableStateOf(false) }
    val controlsEnabled = !playing.loading && !playing.failed

    Dialog(onDismissRequest = { CastSession.openController(false) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                if (playing.poster != null) CeliumArtwork(playing.poster, modifier = Modifier.fillMaxSize().alpha(0.12f))
                Column(
                    Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { CastSession.openController(false) }) {
                            Icon(painterResource(R.drawable.ic_baseline_arrow_back_24), "Minimise")
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { CastSession.disconnect(context) }) { Text("Disconnect") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (playing.poster != null) CeliumArtwork(
                        playing.poster,
                        modifier = Modifier.width(150.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(playing.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    playing.label?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center, maxLines = 2)
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(R.drawable.cast), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Casting to $tv", Modifier.padding(start = 8.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (playing.loading) LinearProgressIndicator(Modifier.fillMaxWidth(0.6f))
                    playing.message?.let {
                        Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                            color = if (playing.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    playing.notice?.let {
                        Surface(Modifier.padding(top = 10.dp), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                    Spacer(Modifier.weight(1f))

                    val fraction = if (playing.durationMs > 0) (playing.positionMs.toFloat() / playing.durationMs).coerceIn(0f, 1f) else 0f
                    Slider(
                        value = dragging ?: fraction,
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            dragging?.let { CastSession.seekTo((it * playing.durationMs).toLong()) }
                            dragging = null
                        },
                        enabled = controlsEnabled && playing.durationMs > 0,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(time(dragging?.let { (it * playing.durationMs).toLong() } ?: playing.positionMs), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.weight(1f))
                        Text(if (playing.durationMs > 0) time(playing.durationMs) else "--:--", style = MaterialTheme.typography.bodySmall)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { CastSession.seekBy(-10_000) }, enabled = controlsEnabled) { Text("−10s") }
                        FilledIconButton(onClick = CastSession::togglePause, enabled = controlsEnabled, modifier = Modifier.size(64.dp)) {
                            Icon(painterResource(if (playing.paused) R.drawable.ic_baseline_play_arrow_24 else R.drawable.ic_baseline_pause_24),
                                if (playing.paused) "Play" else "Pause", Modifier.size(32.dp))
                        }
                        TextButton(onClick = { CastSession.seekBy(30_000) }, enabled = controlsEnabled) { Text("+30s") }
                        if (playing.hasNext) IconButton(onClick = CastSession::next) {
                            Icon(painterResource(R.drawable.ic_baseline_skip_next_24), "Next episode")
                        }
                        IconButton(onClick = CastSession::stop) { Icon(painterResource(R.drawable.baseline_stop_24), "Stop") }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OptionButton("Quality", qualityText(playing), Modifier.weight(1f)) { CastSession.findSources(); sheet = "quality" }
                        OptionButton("Subtitles", playing.currentSubtitle?.let { playing.subtitles.getOrNull(it) }
                            ?: if (playing.subtitles.isEmpty()) "None" else "Off", Modifier.weight(1f)) { CastSession.findSources(); sheet = "subs" }
                        OptionButton("Audio", playing.language?.let(StreamChoice::languageName) ?: "Any", Modifier.weight(1f)) { CastSession.findSources(); sheet = "audio" }
                    }
                }
            }
        }

        when (sheet) {
            "quality" -> QualityDialog(playing, canSwitch = controlsEnabled || playing.failed, onAdvanced = { sheet = "source" }) { sheet = null }
            "source" -> SourcesDialog(playing, controlsEnabled || playing.failed) { sheet = null }
            "subs" -> SubtitlesDialog(playing) { sheet = null }
            "audio" -> AudioDialog(playing, tv, canSwitch = controlsEnabled || playing.failed) { sheet = null }
        }

        playing.prompt?.let { prompt ->
            AlertDialog(
                onDismissRequest = { prompt.options.lastOrNull()?.second?.invoke() ?: CastSession.dismissPrompt() },
                title = { Text(prompt.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(prompt.message, Modifier.padding(bottom = 8.dp))
                        prompt.options.dropLast(1).forEach { (label, action) ->
                            OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) { Text(label) }
                        }
                    }
                },
                confirmButton = {
                    prompt.options.lastOrNull()?.let { (label, action) -> TextButton(onClick = action) { Text(label) } }
                },
            )
        }

        if (playing.failed && sheet == null && dismissedFailure != playing.message) {
            val failed = playing.sources.filter { it.status == CastSourceStatus.Failed }
            AlertDialog(
                onDismissRequest = { dismissedFailure = playing.message },
                title = { Text(playing.failTitle ?: "Couldn't play on $tv") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(playing.message ?: "No source would play.")
                        if (failed.isNotEmpty()) LazyColumn(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(failed) { source ->
                                Column {
                                    Text(source.label, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                                    source.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                            }
                        }
                        if (playing.suggestMirror) {
                            HorizontalDivider()
                            Text("Watch it on $tv anyway: mirror your phone screen instead of casting. Play the video on the phone " +
                                "and the screen shows on $tv. The picture is a little lower quality.",
                                style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { dismissedFailure = playing.message; showMirror = true }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp)) {
                                Icon(painterResource(R.drawable.screen_mirror), null, Modifier.size(18.dp))
                                Text("Mirror screen instead", Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dismissedFailure = playing.message; CastSession.retry() }) { Text("Try again") } },
                dismissButton = { TextButton(onClick = { dismissedFailure = playing.message; CastSession.stop() }) { Text("Close") } },
            )
        }
        if (showMirror) MirrorScreenDialog(onClose = { showMirror = false })
    }
}

@Composable
private fun OptionButton(title: String, value: String, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), shape = RoundedCornerShape(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SourcesDialog(playing: CastNowPlaying, canSwitch: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!canSwitch) Text("Wait until the video starts to switch source.", style = MaterialTheme.typography.bodySmall)
                if (playing.findingSources) Text("Still finding more sources…", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (playing.sources.isEmpty() && !playing.findingSources) Text("No sources that can play on a TV.")
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    itemsIndexed(playing.sources) { index, source ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(index == playing.currentSource, enabled = canSwitch, role = Role.RadioButton) {
                                    CastSession.switchSource(index); onClose()
                                }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(index == playing.currentSource, onClick = null, enabled = canSwitch)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(source.label, fontWeight = if (index == playing.currentSource) FontWeight.SemiBold else FontWeight.Normal)
                                val note = when (source.status) {
                                    CastSourceStatus.Playing -> "Playing now"
                                    CastSourceStatus.Checking -> "Checking…"
                                    CastSourceStatus.Failed -> source.reason ?: "Didn't play"
                                    CastSourceStatus.Waiting -> null
                                }
                                note?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall,
                                        color = if (source.status == CastSourceStatus.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun SubtitlesDialog(playing: CastNowPlaying, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Subtitles") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playing.subtitles.isEmpty()) Text(
                    if (playing.findingSources) "Still looking for subtitles…"
                    else "This source has no separate subtitles. Subtitles built into the video can be turned on with the TV's remote."
                )
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    item {
                        SubtitleRow("Off", playing.currentSubtitle == null) { CastSession.setSubtitle(null); onClose() }
                    }
                    itemsIndexed(playing.subtitles) { index, name ->
                        SubtitleRow(name, index == playing.currentSubtitle) { CastSession.setSubtitle(index); onClose() }
                    }
                }
                Text("Changing subtitles restarts the video on the TV at the same point.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun SubtitleRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(name, Modifier.padding(start = 12.dp))
    }
}

private fun qualityText(playing: CastNowPlaying): String {
    val now = playing.playingQuality?.let(StreamChoice::label)
    return when {
        playing.quality == null -> "Auto" + (now?.let { " · $it" } ?: "")
        else -> StreamChoice.label(playing.quality)
    }
}

/** YouTube-style quality menu: Auto, then the qualities that exist, then Advanced. */
@Composable
private fun QualityDialog(playing: CastNowPlaying, canSwitch: Boolean, onAdvanced: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Quality") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Audio: ${playing.language?.let(StreamChoice::languageName) ?: "any language"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canSwitch) Text("Wait until the video starts to change quality.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    item {
                        QualityRowView(
                            title = "Auto" + (playing.playingQuality?.takeIf { playing.quality == null }?.let { " (${StreamChoice.label(it)})" } ?: ""),
                            note = "Best for your connection, up to ${StreamChoice.label(playing.autoCap)}",
                            selected = playing.quality == null, enabled = canSwitch,
                        ) { CastSession.setQuality(null); onClose() }
                    }
                    items(playing.qualities, key = { it.bucket }) { row ->
                        QualityRowView(
                            title = row.label,
                            note = when {
                                !row.working -> "Didn't work"
                                row.bucket == playing.playingQuality -> "Playing now"
                                else -> null
                            },
                            selected = playing.quality == row.bucket, enabled = canSwitch && row.working,
                        ) { CastSession.setQuality(row.bucket); onClose() }
                    }
                    if (playing.findingSources) item {
                        Text("Still finding more sources…", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                TextButton(onClick = onAdvanced, contentPadding = PaddingValues(0.dp)) { Text("Advanced: choose source") }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
private fun QualityRowView(title: String, note: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (enabled) LocalContentColor.current else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Audio language: picks among languages named by the sources; Celium then finds that language. */
@Composable
private fun AudioDialog(playing: CastNowPlaying, tv: String, canSwitch: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Audio language") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (playing.languages.isEmpty()) {
                    Text("The sources for this title don't say which language they are in.")
                } else {
                    Text("Celium finds a source in this language at your chosen quality.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(playing.languages) { tag ->
                            QualityRowView(StreamChoice.languageName(tag), null, playing.language == tag, canSwitch) {
                                CastSession.setLanguage(tag); onClose()
                            }
                        }
                    }
                }
                Text("If a video has several languages built in (\"Dual Audio\"), $tv chooses between them: change it with the TV's remote " +
                    "(in Kodi: the audio settings in its player).", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

/**
 * Opens Android's own screen casting (Screen Cast / Smart View / Screen mirroring). Ends a TV
 * casting connection first, so Play uses the phone player and the mirror shows it.
 */
fun openScreenMirroring(context: android.content.Context) {
    if (CastSession.isConnected) CastSession.disconnect(context)
    val intents = listOf(
        android.content.Intent("android.settings.CAST_SETTINGS"),
        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        android.content.Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: android.content.ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}

/** Top-bar button next to the cast icon: mirror the whole phone screen to a TV. */
@Composable
fun MirrorScreenButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
        Icon(painterResource(R.drawable.screen_mirror), contentDescription = "Mirror screen to a TV", modifier = Modifier.size(22.dp))
    }
    if (open) MirrorScreenDialog(onClose = { open = false })
}

@Composable
fun MirrorScreenDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val state by CastSession.state.collectAsState()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Mirror your screen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your whole phone screen appears on the TV, so anything that plays on the phone also shows there. " +
                    "Then play the video in Celium as usual.")
                Text("Quality is a little lower than casting, and the phone has to stay on with the screen showing.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("In the next screen, choose your TV. On some phones it's called Smart View, Screen Cast or Screen mirroring; " +
                    "it's also in the quick settings panel (swipe down from the top).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.device?.let {
                    Text("Casting to ${it.name} will stop, so videos play on the phone.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onClose(); openScreenMirroring(context) }) { Text("Open screen mirroring") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
