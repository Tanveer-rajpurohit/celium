package com.lagradost.cloudstream3.ui.celium.game

import android.webkit.JavascriptInterface
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CeliumGameBridge(
    private val gameId: String,
    private val onSaveUpdated: (Long) -> Unit,
    private val onEngineProgress: (Int, String) -> Unit,
    private val onEngineReady: () -> Unit
) {
    private val pendingDump = mutableMapOf<String, String>()

    init {
        CeliumGameSaveStore.load(gameId)?.localStorageDump?.let {
            pendingDump.putAll(it)
        }
    }

    @JavascriptInterface
    fun onStateUpdated(key: String, value: String) {
        synchronized(pendingDump) {
            pendingDump[key] = value
            val snapshot = CeliumGameSaveStore.save(gameId, pendingDump.toMap())
            onSaveUpdated(snapshot.timestamp)
        }
    }

    @JavascriptInterface
    fun onStateRemoved(key: String) {
        synchronized(pendingDump) {
            pendingDump.remove(key)
            val snapshot = CeliumGameSaveStore.save(gameId, pendingDump.toMap())
            onSaveUpdated(snapshot.timestamp)
        }
    }

    @JavascriptInterface
    fun onProgressUpdate(percent: Int, status: String) {
        onEngineProgress(percent.coerceIn(0, 100), status)
    }

    @JavascriptInterface
    fun onEngineStarted() {
        onEngineReady()
    }

    companion object {
        const val JS_INTERFACE_NAME = "CeliumNative"
        private val json = Json { ignoreUnknownKeys = true }

        fun getPreloadScript(snapshot: CeliumGameSaveSnapshot?): String {
            val dump = snapshot?.localStorageDump ?: emptyMap()
            val rawJson = json.encodeToString(dump).replace("\\", "\\\\").replace("'", "\\'")
            return """
                (function() {
                    try {
                        const dump = JSON.parse('$rawJson');
                        for (const [k, v] of Object.entries(dump)) {
                            try { window.localStorage.setItem(k, v); } catch(e) {}
                        }
                    } catch(e) {}

                    try {
                        const originalSetItem = window.localStorage.setItem;
                        window.localStorage.setItem = function(key, val) {
                            originalSetItem.apply(this, arguments);
                            try {
                                if (window.$JS_INTERFACE_NAME && window.$JS_INTERFACE_NAME.onStateUpdated) {
                                    window.$JS_INTERFACE_NAME.onStateUpdated(String(key), String(val));
                                }
                            } catch(e) {}
                        };

                        const originalRemoveItem = window.localStorage.removeItem;
                        window.localStorage.removeItem = function(key) {
                            originalRemoveItem.apply(this, arguments);
                            try {
                                if (window.$JS_INTERFACE_NAME && window.$JS_INTERFACE_NAME.onStateRemoved) {
                                    window.$JS_INTERFACE_NAME.onStateRemoved(String(key));
                                }
                            } catch(e) {}
                        };
                    } catch(e) {}
                })();
            """.trimIndent()
        }

        fun getRuntimeEnhancementScript(): String {
            return """
                (function() {
                    try {
                        // 1. Anti-Popup & Fullscreen Canvas Styles
                        if (!document.getElementById('celium-runtime-styles')) {
                            const style = document.createElement('style');
                            style.id = 'celium-runtime-styles';
                            style.textContent = `
                                .custom-popup, #report-form-popup, .mfp-hide, .modal, .overlay, 
                                div[class*="popup"], div[class*="modal"], div[class*="overlay"],
                                div[class*="ad-"], div[id*="ad-"], div[data-v-23763688],
                                .ts-order-game, .preview-block {
                                    display: none !important;
                                    visibility: hidden !important;
                                    opacity: 0 !important;
                                    pointer-events: none !important;
                                }
                                html, body {
                                    width: 100% !important;
                                    height: 100% !important;
                                    margin: 0 !important;
                                    padding: 0 !important;
                                    overflow: hidden !important;
                                    background: #000000 !important;
                                }
                                #canvas {
                                    position: absolute !important;
                                    top: 0 !important;
                                    left: 0 !important;
                                    width: 100% !important;
                                    height: 100% !important;
                                    object-fit: contain !important;
                                    background: #000000 !important;
                                }
                            `;
                            document.head.appendChild(style);
                        }

                        // Remove any overlay popups immediately
                        document.querySelectorAll('.custom-popup, #report-form-popup, .mfp-bg, .mfp-wrap').forEach(el => el.remove());

                        // 2. Ren'Py Progress & Readiness Observer
                        const statusProgress = document.getElementById('statusProgress');
                        const statusDiv = document.getElementById('statusDiv');
                        const canvas = document.getElementById('canvas');

                        function reportProgress() {
                            try {
                                if (statusProgress) {
                                    const val = parseInt(statusProgress.getAttribute('value') || '0', 10);
                                    const max = parseInt(statusProgress.getAttribute('max') || '100', 10);
                                    const pct = max > 0 ? Math.round((val / max) * 100) : val;
                                    const txt = document.getElementById('statusTextDiv')?.textContent || 'Loading Game Assets...';
                                    if (window.$JS_INTERFACE_NAME && window.$JS_INTERFACE_NAME.onProgressUpdate) {
                                        window.$JS_INTERFACE_NAME.onProgressUpdate(pct, txt);
                                    }
                                }
                            } catch(e) {}
                        }

                        if (statusProgress) {
                            const observer = new MutationObserver(() => reportProgress());
                            observer.observe(statusProgress, { attributes: true, attributeFilter: ['value', 'max'] });
                        }

                        // Check when game canvas becomes active
                        function checkReady() {
                            if (canvas && (statusDiv?.classList.contains('hidden') || !statusDiv)) {
                                if (window.$JS_INTERFACE_NAME && window.$JS_INTERFACE_NAME.onEngineStarted) {
                                    window.$JS_INTERFACE_NAME.onEngineStarted();
                                }
                            } else {
                                setTimeout(checkReady, 500);
                            }
                        }
                        setTimeout(checkReady, 1000);

                        // 3. Balanced Frame & Asset Preloader (Preload upcoming 15-20 frames into HTTP cache)
                        try {
                            const prefetchUrls = [];
                            document.querySelectorAll('img[src], link[rel="preload"]').forEach(el => {
                                const src = el.getAttribute('src') || el.getAttribute('href');
                                if (src && !prefetchUrls.includes(src) && prefetchUrls.length < 20) {
                                    prefetchUrls.push(src);
                                }
                            });
                            prefetchUrls.forEach(url => {
                                const img = new Image();
                                img.src = url;
                            });
                        } catch(e) {}

                    } catch(e) {}
                })();
            """.trimIndent()
        }
    }
}
