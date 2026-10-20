package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.celium.game.CeliumGameSaveStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CeliumGameSaveStoreTest {

    @Before
    fun setUp() {
        CeliumGameSaveStore.clearMemory()
    }

    @After
    fun tearDown() {
        CeliumGameSaveStore.clearMemory()
    }

    @Test
    fun `game id normalization handles urls and special characters`() {
        assertEquals("pgh_corrupted_island_v1", CeliumGameSaveStore.normalizeGameId("https://www.porngamehub.com/game/corrupted-island-v1/"))
        assertEquals("summer_memories_episode_2", CeliumGameSaveStore.normalizeGameId("Summer Memories: Episode 2!"))
        assertEquals("simple_game", CeliumGameSaveStore.normalizeGameId("  simple_game  "))
    }

    @Test
    fun `save and load cycle preserves game variables in memory`() {
        val gameId = "visual_novel_sample"
        val state = mapOf(
            "sugarcube_slot_0" to "{\"story\":\"prologue\",\"chapter\":3,\"hp\":100}",
            "player_name" to "Alex",
            "flags" to "met_heroine_1,unlocked_scene_2"
        )

        val snapshot = CeliumGameSaveStore.save(gameId, state)
        assertNotNull(snapshot)
        assertEquals(state, snapshot.localStorageDump)
        assertTrue(snapshot.exportCode.isNotBlank())

        val loaded = CeliumGameSaveStore.load(gameId)
        assertNotNull(loaded)
        assertEquals(state, loaded?.localStorageDump)
        assertEquals(snapshot.exportCode, loaded?.exportCode)
    }

    @Test
    fun `export and import code restores full state accurately`() {
        val sourceId = "game_source"
        val targetId = "game_target"
        val state = mapOf(
            "save_slot_1" to "{\"gold\":500,\"level\":12}",
            "current_scene" to "castle_courtyard"
        )

        val sourceSnapshot = CeliumGameSaveStore.save(sourceId, state)
        val exportCode = sourceSnapshot.exportCode
        assertTrue("Export code must not be blank", exportCode.isNotBlank())

        val importSuccess = CeliumGameSaveStore.importFromCode(targetId, exportCode)
        assertTrue("Import must succeed with valid export code", importSuccess)

        val restored = CeliumGameSaveStore.load(targetId)
        assertNotNull(restored)
        assertEquals(state, restored?.localStorageDump)
    }

    @Test
    fun `corrupted or invalid import codes return false safely without crashing`() {
        val gameId = "game_error_test"
        assertFalse(CeliumGameSaveStore.importFromCode(gameId, "not_valid_base64!@@#$%%^&*()"))
        assertFalse(CeliumGameSaveStore.importFromCode(gameId, "SGVsbG8gV29ybGQ=")) // Valid base64, but not JSON map
        assertFalse(CeliumGameSaveStore.importFromCode(gameId, ""))
        assertNull(CeliumGameSaveStore.load(gameId))
    }
}
