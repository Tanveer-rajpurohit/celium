package com.lagradost.cloudstream3.ui.celium.game

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.celium.CeliumArtwork
import com.lagradost.cloudstream3.ui.celium.CeliumBackButton
import com.lagradost.cloudstream3.ui.celium.CeliumRefreshButton
import com.lagradost.cloudstream3.ui.celium.CeliumTheme
import kotlinx.coroutines.launch

class CeliumGameDetailActivity : ComponentActivity() {

    private var gameUrl: String = ""
    private var initialTitle: String = ""
    private var initialPoster: String = ""

    companion object {
        const val EXTRA_GAME_URL = "extra_game_url"
        const val EXTRA_GAME_TITLE = "extra_game_title"
        const val EXTRA_POSTER_URL = "extra_poster_url"
    }

    private var currentSaveSnapshot by mutableStateOf<CeliumGameSaveSnapshot?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gameUrl = intent.getStringExtra(EXTRA_GAME_URL).orEmpty()
        initialTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty().ifBlank { "Story Game" }
        initialPoster = intent.getStringExtra(EXTRA_POSTER_URL).orEmpty()

        if (gameUrl.isBlank()) {
            Toast.makeText(this, "Game URL is missing", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        refreshSaveState()

        setContent {
            CeliumTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    GameDetailScreenContent()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSaveState()
    }

    private fun refreshSaveState() {
        currentSaveSnapshot = CeliumGameSaveStore.load(gameUrl)
    }

    private fun launchGame(targetUrl: String, title: String) {
        val intent = Intent(this, CeliumGameActivity::class.java).apply {
            putExtra(CeliumGameActivity.EXTRA_GAME_ID, gameUrl)
            putExtra(CeliumGameActivity.EXTRA_GAME_TITLE, title)
            putExtra(CeliumGameActivity.EXTRA_PLAY_URL, targetUrl)
        }
        startActivity(intent)
    }

    private fun copyExportCodeToClipboard(code: String) {
        if (code.isBlank()) {
            Toast.makeText(this, "No save data available to export", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Celium Game Save Code", code))
        Toast.makeText(this, "Save code copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun formatSaveAge(timestamp: Long): String {
        if (timestamp <= 0L) return "No save found"
        val diffMs = System.currentTimeMillis() - timestamp
        val seconds = maxOf(0L, diffMs / 1000L)
        val minutes = seconds / 60L
        val hours = minutes / 60L
        val days = hours / 24L
        return when {
            seconds < 60L -> "Saved just now"
            minutes < 60L -> "Saved $minutes min ago"
            hours < 24L -> "Saved $hours hr ago"
            else -> "Saved $days days ago"
        }
    }

    @Composable
    private fun GameDetailScreenContent() {
        var isLoading by remember { mutableStateOf(true) }
        var gameTile by remember {
            mutableStateOf<CeliumGameTile?>(
                CeliumGameTile(
                    name = initialTitle,
                    url = gameUrl,
                    posterUrl = initialPoster
                )
            )
        }
        var previewImage by remember { mutableStateOf<String?>(null) }
        var importDialogOpen by remember { mutableStateOf(false) }
        var importInputText by remember { mutableStateOf("") }
        val refererUrl = if (gameUrl.contains("adultgamesworld", ignoreCase = true)) AdultGamesWorldProvider.MAIN_URL else PornGameHubProvider.MAIN_URL
        val scope = rememberCoroutineScope()

        fun fetchGameDetails() {
            isLoading = true
            scope.launch {
                val loaded = CeliumGameRepository.loadGame(gameUrl)
                if (loaded != null) {
                    gameTile = loaded
                }
                isLoading = false
            }
        }

        LaunchedEffect(gameUrl) {
            fetchGameDetails()
        }

        Column(Modifier.fillMaxSize()) {
            // Top Navigation Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CeliumBackButton(onClick = { finish() })
                Text(
                    text = gameTile?.name ?: initialTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                CeliumRefreshButton(onClick = { fetchGameDetails() }, enabled = !isLoading)
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }

            val current = gameTile ?: CeliumGameTile(
                name = initialTitle,
                url = gameUrl,
                posterUrl = initialPoster
            )

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Hero Poster & Header Details
                    item("hero_header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Box(
                                Modifier
                                    .width(130.dp)
                                    .height(185.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                CeliumArtwork(
                                    url = current.posterUrl ?: initialPoster,
                                    headers = mapOf("Referer" to refererUrl),
                                    crop = true,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }

                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = current.name,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )

                                current.author?.takeIf { it.isNotBlank() }?.let { author ->
                                    Text(
                                        text = "By $author",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            text = current.engine,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            color = MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }

                                    current.version?.takeIf { it.isNotBlank() }?.let { ver ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = "v$ver",
                                                style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    current.rating?.takeIf { it.isNotBlank() }?.let { rating ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF332A15)
                                        ) {
                                            Text(
                                                text = rating,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                                color = Color(0xFFFFD54F)
                                            )
                                        }
                                    }
                                }

                                current.views?.takeIf { it.isNotBlank() }?.let { views ->
                                    Text(
                                        text = views,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                current.updatedAt?.takeIf { it.isNotBlank() }?.let { updated ->
                                    Text(
                                        text = if (updated.startsWith("updated", ignoreCase = true)) updated else "Updated: $updated",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    // Save Progress & Sync Status Card
                    item("save_status_card") {
                        val snapshot = currentSaveSnapshot
                        val hasSave = snapshot != null && snapshot.localStorageDump.isNotEmpty()

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 2.dp
                        ) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (hasSave) Color(0xFF4CAF50) else Color(0xFF9E9E9E))
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = if (hasSave) "Auto-saved Progress Found" else "No Saved Progress",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = if (snapshot != null && snapshot.localStorageDump.isNotEmpty()) {
                                                "${formatSaveAge(snapshot.timestamp)} (${snapshot.localStorageDump.size} variables stored)"
                                            } else {
                                                "Your narrative choices and progress will save automatically."
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            val code = snapshot?.exportCode.orEmpty()
                                            copyExportCodeToClipboard(code)
                                        },
                                        enabled = hasSave
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_outline_share_24),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Export Save", fontSize = 12.sp)
                                    }

                                    OutlinedButton(
                                        modifier = Modifier.weight(1f),
                                        onClick = {
                                            importInputText = ""
                                            importDialogOpen = true
                                        }
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.baseline_save_as_24),
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text("Import Save", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // Primary Play / Resume CTA Button
                    item("play_action") {
                        val snapshot = currentSaveSnapshot
                        val hasSave = snapshot != null && snapshot.localStorageDump.isNotEmpty()

                        Button(
                            onClick = {
                                launchGame(current.playUrl ?: current.url, current.name)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .padding(horizontal = 24.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_play_arrow_24),
                                contentDescription = null,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (hasSave) "RESUME STORY" else "PLAY GAME",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        current.guideUrl?.takeIf { it.isNotBlank() }?.let { guideUrl ->
                            Spacer(Modifier.height(10.dp))
                            OutlinedButton(
                                onClick = {
                                    val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(guideUrl))
                                    startActivity(browserIntent)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_outline_info_24),
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "View Strategy Guide / Walkthrough (PDF)",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }

                    // Genres & Tags
                    if (current.tags.isNotEmpty()) {
                        item("tags_list") {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Tags",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(current.tags) { tag ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.secondaryContainer
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Story Overview Section
                    item("story_description") {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Story Overview",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            val descText = current.description.ifBlank {
                                "No story overview provided for this title. Tap Play Game to start."
                            }
                            Text(
                                text = descText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 22.sp
                            )
                        }
                    }

                    // Preview Screenshots Gallery
                    item("preview_screenshots") {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = if (current.screenshots.isNotEmpty()) {
                                    "Preview Gallery (${current.screenshots.size})"
                                } else {
                                    "Preview Gallery"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 24.dp)
                            )

                            if (current.screenshots.isNotEmpty()) {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 24.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(current.screenshots) { imgUrl ->
                                        Box(
                                            Modifier
                                                .width(220.dp)
                                                .height(124.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                                .clickable { previewImage = imgUrl }
                                        ) {
                                            CeliumArtwork(
                                                url = imgUrl,
                                                headers = mapOf("Referer" to refererUrl),
                                                crop = true,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = "No preview screenshots available for this title.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 24.dp)
                                )
                            }
                        }
                    }
                }

            // Fullscreen Screenshot Preview Dialog
            previewImage?.let { imgUrl ->
                Dialog(
                    onDismissRequest = { previewImage = null },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.94f))
                            .clickable { previewImage = null },
                        contentAlignment = Alignment.Center
                    ) {
                        CeliumArtwork(
                            url = imgUrl,
                            headers = mapOf("Referer" to refererUrl),
                            crop = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        IconButton(
                            onClick = { previewImage = null },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(16.dp)
                                .size(40.dp)
                                .background(Color(0x88000000), CircleShape)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_baseline_close_24),
                                contentDescription = "Close preview",
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            // Import Save Dialog
            if (importDialogOpen) {
                AlertDialog(
                    onDismissRequest = { importDialogOpen = false },
                    title = { Text("Import Save Progress") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "Paste your exported Base64 save code below to restore progress:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedTextField(
                                value = importInputText,
                                onValueChange = { importInputText = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(130.dp),
                                placeholder = { Text("Paste save code here...") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (importInputText.isNotBlank()) {
                                    val success = CeliumGameSaveStore.importFromCode(gameUrl, importInputText)
                                    if (success) {
                                        refreshSaveState()
                                        Toast.makeText(this@CeliumGameDetailActivity, "Save progress restored successfully", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(this@CeliumGameDetailActivity, "Invalid save code format", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                importDialogOpen = false
                            }
                        ) {
                            Text("Restore Progress")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { importDialogOpen = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}
