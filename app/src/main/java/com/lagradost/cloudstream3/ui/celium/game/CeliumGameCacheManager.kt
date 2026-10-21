package com.lagradost.cloudstream3.ui.celium.game

import android.content.Context
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Intelligent asset cache & lifecycle manager for adult story games.
 * 
 * Rules:
 * 1. Narrative Save States (localStorageDump, narrative variables, choices) are stored in
 *    CeliumGameSaveStore and are NEVER deleted.
 * 2. Heavy downloaded frames, images, and WebAssembly chunks in WebView's HTTP cache are
 *    pruned if a game has not been resumed in [MAX_INACTIVE_DAYS] (default 14 days),
 *    freeing up phone storage automatically without losing story progress.
 */
object CeliumGameCacheManager {
    private const val PREFS_NAME = "celium_game_lifecycle_prefs"
    private const val KEY_PREFIX_LAST_PLAYED = "last_played_"
    const val MAX_INACTIVE_DAYS = 14

    fun recordGamePlayed(context: Context, gameId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong("$KEY_PREFIX_LAST_PLAYED$gameId", System.currentTimeMillis()).apply()
        } catch (e: Exception) {
            logError(e)
        }
    }

    fun getLastPlayed(context: Context, gameId: String): Long {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getLong("$KEY_PREFIX_LAST_PLAYED$gameId", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Cleans up stale HTTP disk cache files if not accessed for [maxDays] days.
     * Preserves all save states, bookmarks, and user configurations.
     */
    suspend fun pruneInactiveGameAssets(context: Context, maxDays: Int = MAX_INACTIVE_DAYS) = withContext(Dispatchers.IO) {
        try {
            val cutoff = System.currentTimeMillis() - (maxDays * 24L * 60L * 60L * 1000L)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val allEntries = prefs.all

            val inactiveGames = mutableListOf<String>()
            for ((key, value) in allEntries) {
                if (key.startsWith(KEY_PREFIX_LAST_PLAYED) && value is Long) {
                    if (value < cutoff) {
                        inactiveGames.add(key.removePrefix(KEY_PREFIX_LAST_PLAYED))
                    }
                }
            }

            // Prune old web cache directories if inactive games exist
            if (inactiveGames.isNotEmpty()) {
                val cacheDir = context.cacheDir
                val webViewCache = File(cacheDir, "WebView")
                val httpCache = File(webViewCache, "Default/HTTP Cache")
                if (httpCache.exists() && httpCache.isDirectory) {
                    httpCache.listFiles()?.forEach { file ->
                        if (file.lastModified() < cutoff) {
                            file.delete()
                        }
                    }
                }

                // Remove stale tracking keys
                val editor = prefs.edit()
                for (id in inactiveGames) {
                    editor.remove("$KEY_PREFIX_LAST_PLAYED$id")
                }
                editor.apply()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }
}
