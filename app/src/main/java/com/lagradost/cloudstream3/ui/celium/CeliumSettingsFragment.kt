package com.lagradost.cloudstream3.ui.celium

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AutoDownloadMode
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

class CeliumSettingsFragment : BaseComposeFragment() {
    private var status by mutableStateOf<String?>(null)
    private var preparing by mutableStateOf(false)
    private var sourcesVersion by mutableIntStateOf(0)
    private var preparation: Job? = null

    @OptIn(InternalAPI::class)
    private fun prepareSources() {
        if (preparing) return
        val host = activity ?: return
        preparation = viewLifecycleOwner.lifecycleScope.launch {
            preparing = true
            status = "Preparing enabled sources…"
            try {
                withContext(Dispatchers.IO) {
                    PluginManager.___DO_NOT_CALL_FROM_A_PLUGIN_downloadNotExistingPluginsAndLoad(host, AutoDownloadMode.All)
                }
                status = "Enabled sources checked. Availability depends on each source."
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e); status = "Sources could not refresh. Check your connection and retry."
            } finally { preparing = false; CeliumSources.ready.invoke(false) }
        }
    }

    private fun adult(enabled: Boolean) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        preferences.edit { putBoolean(getString(R.string.enable_nsfw_on_providers_key), enabled) }
        MainAPI.settingsForProvider.enableAdult = enabled
        // Publish eligibility immediately, so active coordinators cancel their old work.
        CeliumSources.ready.invoke(false)
        if (enabled) prepareSources()
        else {
            preparation?.cancel()
            viewLifecycleOwner.lifecycleScope.launch {
            try { withContext(Dispatchers.IO) {
                val adultNames = CeliumSources.all.filter { it.adult }.map { it.plugin }.toSet()
                (PluginManager.getPluginsOnline().toList() + PluginManager.getPluginsLocal().toList())
                    .filter { it.internalName.removeSuffix(".cs3").removeSuffix(".zip") in adultNames }
                    .forEach { PluginManager.unloadPlugin(it.filePath) }
            } } catch (e: CancellationException) { throw e
            } catch (e: Exception) { logError(e); status = "Adult content is hidden. Some source resources could not unload until restart." }
            CeliumSources.ready.invoke(false)
            }
        }
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember { PreferenceManager.getDefaultSharedPreferences(context) }
        var adultEnabled by remember { mutableStateOf(MainAPI.settingsForProvider.enableAdult) }
        val mode by CeliumStyle.mode.collectAsState()
        val accent by CeliumStyle.accent.collectAsState()
        var accentDraft by remember(accent) { mutableStateOf(accent) }
        var invalidAccent by remember { mutableStateOf(false) }
        var connections by remember { mutableIntStateOf(prefs.getInt(getString(R.string.download_concurrent_key), 3)) }
        var downloads by remember { mutableIntStateOf(prefs.getInt(getString(R.string.download_parallel_key), 3)) }
        var page by remember { mutableStateOf("Settings") }
        var fontLicense by remember { mutableStateOf(false) }
        androidx.activity.compose.BackHandler(page != "Settings") { page = "Settings" }
        CeliumTheme {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    item("title") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (page != "Settings") TextButton(onClick = { page = "Settings" }) { Text("Back") }
                            Text(page, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (page == "Settings" && status != null) item("status") {
                        Text(status.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (page == "Settings") {
                        item("appearance") {
                            SettingsGroup("Appearance") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("Dark", "Light", "System").forEach { label -> FilterChip(mode == label, {
                                        CeliumStyle.mode(label)
                                        prefs.edit { putString(getString(R.string.app_theme_key), if (label == "Dark") "Black" else label) }
                                        activity?.recreate()
                                    }, label = { Text(label) }) }
                                }
                                Text("Neutral by default. Artwork keeps its original colors.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedTextField(accentDraft, { accentDraft = it.take(7); invalidAccent = false }, label = { Text("Optional accent · HEX") }, placeholder = { Text("e.g. 56785A") }, singleLine = true, isError = invalidAccent, modifier = Modifier.fillMaxWidth())
                                Row {
                                    TextButton(onClick = { invalidAccent = !CeliumStyle.accent(accentDraft) }) { Text("Apply accent") }
                                    TextButton(onClick = { CeliumStyle.accent(""); accentDraft = "" }) { Text("Use neutral") }
                                }
                                if (invalidAccent) Text("Enter six hexadecimal characters, or leave empty.")
                            }
                        }
                        item("content") {
                            SettingsGroup("Content") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Adult content", fontWeight = FontWeight.Medium)
                                        Text("Off hides adult sources, history and saved titles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(adultEnabled, { adultEnabled = it; adult(it) })
                                }
                                OutlinedButton(onClick = { page = "Content sources" }) { Text("Manage content sources") }
                            }
                        }
                        item("downloads") {
                            SettingsGroup("Downloads") {
                                Text("Keep the existing parallel downloader. More connections can help supported hosts, but too many can reduce speed.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Connections per file: $connections")
                                Slider(connections.toFloat(), {
                                    connections = it.toInt()
                                    prefs.edit { putInt(getString(R.string.download_concurrent_key), connections) }
                                }, valueRange = 1f..6f, steps = 4)
                                Text("Simultaneous files: $downloads")
                                Slider(downloads.toFloat(), {
                                    downloads = it.toInt()
                                    prefs.edit { putInt(getString(R.string.download_parallel_key), downloads) }
                                }, valueRange = 1f..4f, steps = 2)
                            }
                        }
                        item("about") {
                            SettingsGroup("About Celium") {
                                Text("Personal media app", style = MaterialTheme.typography.bodyMedium)
                                Text("Playback and legacy adapter compatibility powered by Cloudstream. Source licenses and attribution are retained in the code distribution.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                TextButton(onClick = { fontLicense = true }) { Text("DM Sans font license") }
                                Text("Use your device VPN settings when needed. Access and playback depend on the source and network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        item("source-intro") {
                            Text("Choose which sources are available. Home and Search use only the selected source.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = ::prepareSources, enabled = !preparing) { Text(if (preparing) "Preparing…" else "Refresh enabled sources") }
                            status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                        items(CeliumSources.all.filter { adultEnabled || !it.adult }, key = { it.id }) { source ->
                            @Suppress("UNUSED_VARIABLE") val version = sourcesVersion
                            val enabled = CeliumSources.enabled(source)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(source.label, fontWeight = FontWeight.Medium)
                                    Text(if (source.adult) "Adult" else if (source.anime) "Anime" else "Movies / TV Shows",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(enabled, { value ->
                                    val group = if (source.adult) CeliumSection.Adult else if (source.anime) CeliumSection.Anime else CeliumSection.Movies
                                    if (!value && CeliumSources.choices(group).count { it.plugin.isNotEmpty() } <= 1) status = "Keep at least one source enabled in each category."
                                    else {
                                        CeliumSources.setEnabled(source, value); sourcesVersion++
                                        if (value) prepareSources()
                                    }
                                })
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                }
                if (fontLicense) {
                    val license = remember { context.resources.openRawResource(R.raw.dm_sans_license).bufferedReader().use { it.readText() } }
                    AlertDialog(onDismissRequest = { fontLicense = false }, title = { Text("DM Sans · Open Font License") },
                        text = { Text(license, Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) },
                        confirmButton = { TextButton(onClick = { fontLicense = false }) { Text("Close") } })
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        content()
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

