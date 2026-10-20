package com.lagradost.cloudstream3.ui.celium.game

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object CeliumGameSaveStore {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val memoryCache = ConcurrentHashMap<String, CeliumGameSaveSnapshot>()

    fun normalizeGameId(raw: String): String {
        val trimmed = raw.trim()
        val slug = if (trimmed.contains("/game/")) {
            "pgh_" + trimmed.substringAfter("/game/").trim('/')
        } else trimmed
        return slug.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun save(gameId: String, dump: Map<String, String>): CeliumGameSaveSnapshot {
        val id = normalizeGameId(gameId)
        val serialized = json.encodeToString(dump)
        val exportCode = Base64.encode(serialized.encodeToByteArray())
        val snapshot = CeliumGameSaveSnapshot(
            gameId = id,
            timestamp = System.currentTimeMillis(),
            localStorageDump = dump,
            exportCode = exportCode
        )
        memoryCache[id] = snapshot
        try {
            setKey("celium_game_save_$id", snapshot)
        } catch (e: Exception) {
            logError(e)
        }
        return snapshot
    }

    fun load(gameId: String): CeliumGameSaveSnapshot? {
        val id = normalizeGameId(gameId)
        val persistent = try {
            getKey<CeliumGameSaveSnapshot>("celium_game_save_$id")
        } catch (e: Exception) {
            logError(e)
            null
        }
        return persistent ?: memoryCache[id]
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun importFromCode(gameId: String, code: String): Boolean {
        val id = normalizeGameId(gameId)
        return try {
            val decodedBytes = Base64.decode(code.trim().replace("\n", "").replace("\r", ""))
            val decodedString = decodedBytes.decodeToString()
            val dump = json.decodeFromString<Map<String, String>>(decodedString)
            save(id, dump)
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    fun clearMemory() {
        memoryCache.clear()
    }
}
