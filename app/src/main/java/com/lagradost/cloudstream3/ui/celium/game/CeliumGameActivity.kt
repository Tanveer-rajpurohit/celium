package com.lagradost.cloudstream3.ui.celium.game

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.ui.celium.CeliumTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class CeliumGameActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var gameId: String = ""
    private var gameTitle: String = ""
    private var rawPlayUrl: String = ""

    companion object {
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_GAME_TITLE = "extra_game_title"
        const val EXTRA_PLAY_URL = "extra_play_url"

        private val BLOCKED_HOSTS = listOf(
            "adsterra", "exoclick", "trafficjunky", "popads", "hilltopads",
            "tsyndicate", "juicyads", "ero-advertising", "realsrv", "bet",
            "google-analytics", "googletagmanager"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive sticky fullscreen for uninterrupted gaming
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        gameId = intent.getStringExtra(EXTRA_GAME_ID).orEmpty().ifBlank { "unknown_game" }
        gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE).orEmpty().ifBlank { "Story Game" }
        rawPlayUrl = intent.getStringExtra(EXTRA_PLAY_URL).orEmpty()

        if (rawPlayUrl.isBlank()) {
            Toast.makeText(this, "Game play URL is missing.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Record last played timestamp for intelligent frame cache lifecycle
        CeliumGameCacheManager.recordGamePlayed(this, gameId)

        setContent {
            CeliumTheme {
                GameScreenSurface()
            }
        }
    }

    @Composable
    private fun GameScreenSurface() {
        var menuOpen by remember { mutableStateOf(false) }
        var importOpen by remember { mutableStateOf(false) }
        var importText by remember { mutableStateOf("") }
        var lastSavedAt by remember { mutableStateOf(CeliumGameSaveStore.load(gameId)?.timestamp ?: 0L) }
        var isLandscape by remember { mutableStateOf(false) }

        // Dynamic Loading & Engine Readiness States
        var loadingProgress by remember { mutableIntStateOf(10) }
        var statusMessage by remember { mutableStateOf("Connecting to Game Runtime...") }
        var engineReady by remember { mutableStateOf(false) }

        val animatedProgress by animateFloatAsState(
            targetValue = (loadingProgress / 100f).coerceIn(0f, 1f),
            label = "GameProgress"
        )

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // Hardware-accelerated Game WebView
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    createGameWebView(
                        context = ctx,
                        onSaveUpdate = { ts -> runOnUiThread { lastSavedAt = ts } },
                        onProgress = { pct, status ->
                            runOnUiThread {
                                if (pct > loadingProgress) loadingProgress = pct
                                if (status.isNotBlank()) statusMessage = status
                                if (pct >= 100) engineReady = true
                            }
                        },
                        onReady = {
                            runOnUiThread {
                                loadingProgress = 100
                                engineReady = true
                            }
                        }
                    ).also {
                        webView = it
                        resolveAndLoadRuntime(it) { pct, msg ->
                            runOnUiThread {
                                loadingProgress = pct
                                statusMessage = msg
                            }
                        }
                    }
                }
            )

            // High-fidelity Native Loading Screen with Progress Bar
            AnimatedVisibility(
                visible = !engineReady,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F14)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxWidth(0.85f)
                            .padding(24.dp)
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp),
                            strokeWidth = 3.dp
                        )

                        Text(
                            text = gameTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1
                        )

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )

                        // Smooth Visual Progress Bar
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color(0xFF262630)
                        )

                        Text(
                            text = "$loadingProgress%",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF888888)
                        )
                    }
                }
            }

            // Discreet floating menu trigger
            Box(
                Modifier
                    .padding(16.dp)
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC1E1E1E))
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_outline_settings_24),
                    contentDescription = "Game Menu",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Quick Control Bottom Sheet / Dialog
            if (menuOpen) {
                AlertDialog(
                    onDismissRequest = { menuOpen = false },
                    title = { Text(gameTitle, maxLines = 1) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            val statusText = if (lastSavedAt > 0) {
                                val agoSeconds = maxOf(0L, (System.currentTimeMillis() - lastSavedAt) / 1000L)
                                if (agoSeconds < 60) "Auto-saved just now" else "Auto-saved ${agoSeconds / 60}m ago"
                            } else "No save recorded yet"

                            Text(statusText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            HorizontalDivider()

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val snapshot = CeliumGameSaveStore.load(gameId)
                                        if (snapshot != null && snapshot.exportCode.isNotBlank()) {
                                            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Celium Game Save", snapshot.exportCode))
                                            Toast.makeText(this@CeliumGameActivity, "Save code copied to clipboard", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(this@CeliumGameActivity, "No save data to export", Toast.LENGTH_SHORT).show()
                                        }
                                        menuOpen = false
                                    }
                                ) {
                                    Text("Export Save", fontSize = 13.sp)
                                }

                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        importText = ""
                                        importOpen = true
                                        menuOpen = false
                                    }
                                ) {
                                    Text("Import Save", fontSize = 13.sp)
                                }
                            }

                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        isLandscape = !isLandscape
                                        requestedOrientation = if (isLandscape) {
                                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        } else {
                                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                        }
                                        menuOpen = false
                                    }
                                ) {
                                    Text(if (isLandscape) "Portrait" else "Landscape")
                                }

                                TextButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        menuOpen = false
                                        webView?.reload()
                                    }
                                ) {
                                    Text("Reload")
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { menuOpen = false }) { Text("Close") }
                    },
                    dismissButton = {
                        TextButton(onClick = { finish() }) { Text("Exit Game", color = MaterialTheme.colorScheme.error) }
                    }
                )
            }

            // Import Save Dialog
            if (importOpen) {
                AlertDialog(
                    onDismissRequest = { importOpen = false },
                    title = { Text("Import Save Code") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Paste your exported base64 save string below:", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(
                                value = importText,
                                onValueChange = { importText = it },
                                modifier = Modifier.fillMaxWidth().height(120.dp),
                                placeholder = { Text("Paste save code here...") }
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (importText.isNotBlank()) {
                                    val success = CeliumGameSaveStore.importFromCode(gameId, importText)
                                    if (success) {
                                        Toast.makeText(this@CeliumGameActivity, "Save restored. Reloading game...", Toast.LENGTH_SHORT).show()
                                        webView?.reload()
                                    } else {
                                        Toast.makeText(this@CeliumGameActivity, "Invalid save code format", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                importOpen = false
                            }
                        ) {
                            Text("Apply & Reload")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { importOpen = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }

    private fun resolveAndLoadRuntime(targetWebView: WebView, onProgressUpdate: (Int, String) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            var finalUrl = rawPlayUrl
            // If the URL passed is a website article page rather than a direct storage runtime, resolve it
            if (rawPlayUrl.contains("porngameshub.com", ignoreCase = true) && !rawPlayUrl.contains("storage", ignoreCase = true)) {
                onProgressUpdate(20, "Resolving Direct Game Engine...")
                val loaded = PornGameHubProvider.loadGame(rawPlayUrl)
                if (loaded != null && !loaded.playUrl.isNullOrBlank() && loaded.playUrl != rawPlayUrl) {
                    finalUrl = loaded.playUrl
                }
            }

            onProgressUpdate(40, "Downloading Ren'Py & WebGL Core...")

            withContext(Dispatchers.Main) {
                val headers = mapOf(
                    "Referer" to "https://porngameshub.com/",
                    "Origin" to "https://porngameshub.com"
                )
                targetWebView.loadUrl(finalUrl, headers)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createGameWebView(
        context: Context,
        onSaveUpdate: (Long) -> Unit,
        onProgress: (Int, String) -> Unit,
        onReady: () -> Unit
    ): WebView {
        return WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                userAgentString = USER_AGENT
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                setSupportMultipleWindows(false)
                // Cache frames locally for instant repeat transitions and low mobile data consumption
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            val bridge = CeliumGameBridge(gameId, onSaveUpdate, onProgress, onReady)
            addJavascriptInterface(bridge, CeliumGameBridge.JS_INTERFACE_NAME)

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress in 40..95) {
                        onProgress(newProgress, "Preloading Story Frames & Assets ($newProgress%)...")
                    } else if (newProgress >= 100) {
                        onProgress(100, "Starting Story Engine...")
                    }
                }

                // Suppress disruptive JS popups from interrupting gameplay
                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    result?.confirm()
                    return true
                }

                override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
                    result?.confirm(defaultValue)
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // Filter out disruptive ad redirectors and popups
                    if (BLOCKED_HOSTS.any { url.contains(it, ignoreCase = true) }
                        || url.contains("/advertise", ignoreCase = true)) {
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return null
                    // Intercept and neutralize protection scripts, ad networks, and popups
                    if (url.contains("protection.js", ignoreCase = true)
                        || url.contains("combiner/protection", ignoreCase = true)
                        || BLOCKED_HOSTS.any { url.contains(it, ignoreCase = true) }) {
                        return WebResourceResponse("application/javascript", "UTF-8", ByteArrayInputStream("".toByteArray()))
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // Pre-inject saved localStorage state before game engines initialize
                    val snapshot = CeliumGameSaveStore.load(gameId)
                    val script = CeliumGameBridge.getPreloadScript(snapshot)
                    view?.evaluateJavascript(script, null)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Reinforce hooks on finished DOM load
                    val snapshot = CeliumGameSaveStore.load(gameId)
                    val script = CeliumGameBridge.getPreloadScript(snapshot)
                    view?.evaluateJavascript(script, null)

                    // Inject anti-popup styling and frame preloader
                    val runtimeScript = CeliumGameBridge.getRuntimeEnhancementScript()
                    view?.evaluateJavascript(runtimeScript, null)
                }
            }
        }
    }

    override fun onDestroy() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        webView = null

        // Prune stale game assets older than 14 days in background while preserving all narrative saves
        lifecycleScope.launch(Dispatchers.IO) {
            CeliumGameCacheManager.pruneInactiveGameAssets(applicationContext)
        }

        super.onDestroy()
    }
}
