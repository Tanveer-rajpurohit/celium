package com.lagradost.cloudstream3.ui.celium.game

import android.webkit.JavascriptInterface
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CeliumGameBridge(
    private val gameId: String,
    private val onSaveUpdated: (Long) -> Unit
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
    }
}
