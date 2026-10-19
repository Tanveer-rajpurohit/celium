package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.celium.CeliumSection
import com.lagradost.cloudstream3.ui.celium.CeliumShelf
import com.lagradost.cloudstream3.ui.celium.CeliumSnapshot
import com.lagradost.cloudstream3.ui.celium.CeliumSources
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

class CeliumPolicyTest {
    private var previousAdult = false
    @Before fun enableAdultForCompatibilityTests() {
        previousAdult = MainAPI.settingsForProvider.enableAdult
        MainAPI.settingsForProvider.enableAdult = true
    }
    @After fun restoreAdultPolicy() { MainAPI.settingsForProvider.enableAdult = previousAdult }

    @Test fun `adult opt out rejects artifacts even when already installed`() {
        MainAPI.settingsForProvider.enableAdult = false
        val source = CeliumSources.all.first { it.id == "beeg" }
        val api = object : MainAPI() {
            override var name = "Beeg"
            override val supportedTypes = setOf(TvType.NSFW)
        }.apply { sourcePlugin = "/private/Beeg.cs3" }
        assertFalse(CeliumSources.allowsPlugin(CeliumSources.GIZLI, "Beeg"))
        assertNull(CeliumSources.resolve(source, listOf(api)))
        assertFalse(CeliumSources.all.any { it.plugin == "AnimePahe" })
    }
    @Test
    fun `adult and anime choices are isolated from movie and series sources`() {
        assertTrue(CeliumSources.choices(CeliumSection.Adult).all { it.adult && !it.anime })
        assertTrue(CeliumSources.choices(CeliumSection.Anime).all { it.anime && !it.adult })
        assertTrue(CeliumSources.choices(CeliumSection.Movies).none { it.adult || it.anime })
        assertEquals(CeliumSources.choices(CeliumSection.Movies), CeliumSources.choices(CeliumSection.Series))
    }

    @Test fun `curated feed collapses confirmed same year titles but preserves remakes and unknowns`() {
        val a = CeliumTile("Dune", "https://a/title", "A", TvType.Movie, year = 2021)
        val b = CeliumTile("Dune", "https://b/title", "B", TvType.Movie, year = 2021)
        assertEquals(CeliumSources.catalogIdentity(a), CeliumSources.catalogIdentity(b))
        assertNotEquals(CeliumSources.catalogIdentity(a), CeliumSources.catalogIdentity(b.copy(year = 1984)))
        assertNotEquals(CeliumSources.catalogIdentity(a.copy(year = null)), CeliumSources.catalogIdentity(b.copy(year = null)))
    }

    @Test
    fun `catalog lists AniKoto and HiAnime first and the anime pool keeps catalog order`() {
        assertEquals(24, CeliumSources.all.size)
        val anime = CeliumSources.choices(CeliumSection.Anime)
        assertEquals(listOf("celium", "anikoto", "hianime", "anichi", "animedekho"), anime.map { it.id })
        assertEquals(listOf("anikoto", "hianime", "anichi"),
            CeliumSources.orderedPool(anime, setOf("animedekho", "anichi", "hianime", "anikoto")).map { it.id })
        assertEquals(listOf("hianime", "anichi", "animedekho"),
            CeliumSources.orderedPool(anime, setOf("animedekho", "anichi", "hianime")).map { it.id })
        assertTrue(CeliumSources.allowsPlugin(CeliumSources.CSX, "Moviesmod"))
        assertTrue(CeliumSources.allowsPlugin(CeliumSources.CXXX, "XNXX"))
    }

    @Test
    fun `defaults and invalid saved preferences resolve inside their own section`() {
        assertEquals("celium", CeliumSources.selected(CeliumSection.Movies, null).id)
        assertEquals("celium", CeliumSources.selected(CeliumSection.Adult, "multi").id)
        assertEquals("celium", CeliumSources.selected(CeliumSection.Anime, "unknown").id)
        assertEquals("pornhat", CeliumSources.selected(CeliumSection.Adult, "pornhat").id)
    }

    @Test
    fun `plugin allowlist requires the exact repository and artifact identity`() {
        assertTrue(CeliumSources.allowsPlugin(CeliumSources.GIZLI, "Beeg"))
        assertTrue(CeliumSources.allowsPlugin(CeliumSources.CXXX, "FXPrnHD"))
        assertTrue(CeliumSources.allowsPlugin(CeliumSources.CNC, "CNC Verse"))
        assertFalse(CeliumSources.allowsPlugin(CeliumSources.CNC, "CNC Verse Mobile"))
        assertFalse(CeliumSources.allowsPlugin(CeliumSources.CXXX, "Beeg"))
        assertFalse(CeliumSources.allowsPlugin("https://untrusted.example/repo.json", "Beeg"))
        assertFalse(CeliumSources.allowsPlugin(CeliumSources.PHISHER, "Ultima"))
        assertFalse(CeliumSources.allowsPlugin(CeliumSources.GIZLI, "Porn36"))
        assertFalse(CeliumSources.all.any { it.id == "porn36" })
    }

    @Test
    fun `artifact path identifies a provider even when its display name changes`() {
        val source = CeliumSources.selected(CeliumSection.Movies, "multi")
        val api = object : MainAPI() {
            override var name = "Renamed provider"
            override val supportedTypes = setOf(TvType.Movie)
        }.apply { sourcePlugin = "/private/repo/MultiMoviesProvider.${"MultiMoviesProvider".hashCode()}.cs3" }
        assertSame(api, CeliumSources.resolve(source, listOf(api)))
    }

    @Test
    fun `source name alias does not cross adult capability boundaries`() {
        val source = CeliumSources.selected(CeliumSection.Adult, "beeg")
        val wrong = object : MainAPI() {
            override var name = "Beeg"
            override val supportedTypes = setOf(TvType.Movie)
        }
        assertNull(CeliumSources.resolve(source, listOf(wrong)))
    }

    @Test
    fun `a different plugin cannot impersonate a selected source by its display name`() {
        val source = CeliumSources.selected(CeliumSection.Adult, "beeg")
        val wrong = object : MainAPI() {
            override var name = "Beeg"
            override val supportedTypes = setOf(TvType.NSFW)
        }.apply { sourcePlugin = "/other/UnselectedPlugin.cs3" }
        assertNull(CeliumSources.resolve(source, listOf(wrong)))
    }

    @Test
    fun `selected legacy local artifacts still resolve`() {
        val source = CeliumSources.selected(CeliumSection.Adult, "beeg")
        val local = object : MainAPI() {
            override var name = "An older Beeg display name"
            override val supportedTypes = setOf(TvType.NSFW)
        }.apply { sourcePlugin = "C:\\private\\plugins\\Beeg.cs3" }
        assertSame(local, CeliumSources.resolve(source, listOf(local)))
    }

    @Test
    fun `category filtering removes other media and repeated URLs`() {
        val movie = CeliumTile("Movie", "https://source/movie", "Source", TvType.Movie)
        val show = CeliumTile("Series", "https://source/series", "Source", TvType.TvSeries)
        val adult = CeliumTile("Adult", "https://source/adult", "Source", TvType.NSFW)
        val anime = CeliumTile("Anime", "https://source/anime", "Source", TvType.Anime)
        val cards = listOf(movie, movie, show, adult, anime)
        assertEquals(listOf(movie), CeliumSources.filter(CeliumSection.Movies, cards))
        assertEquals(listOf(show), CeliumSources.filter(CeliumSection.Series, cards))
        assertEquals(listOf(adult), CeliumSources.filter(CeliumSection.Adult, cards))
        assertEquals(listOf(anime), CeliumSources.filter(CeliumSection.Anime, cards))
    }

    @Test
    fun `unknown item type stays usable only after its source has been selected`() {
        assertTrue(CeliumSources.accepts(CeliumSection.Movies, null))
        assertFalse(CeliumSources.accepts(CeliumSection.Adult, TvType.Movie))
        assertFalse(CeliumSources.accepts(CeliumSection.Movies, TvType.NSFW))
    }

    @Test
    fun `feed snapshot preserves provider URLs headers and pagination across serialization`() {
        val card = CeliumTile("千と千尋の神隠し", "https://source/title?id=4", "Source", TvType.AnimeMovie,
            "https://source/poster", mapOf("Referer" to "https://source/"))
        val snapshot = CeliumSnapshot(1_000L, listOf(CeliumShelf("0:0", "Latest", listOf(card), 0, 3, true)))
        val encoded = Json.encodeToString(CeliumSnapshot.serializer(), snapshot)
        assertEquals(snapshot, Json.decodeFromString(CeliumSnapshot.serializer(), encoded))
    }

    @Test
    fun `cache freshness expires independently from stale fallback and rejects backwards clocks`() {
        val snapshot = CeliumSnapshot(10_000L, emptyList())
        assertTrue(snapshot.fresh(10_001L))
        assertFalse(snapshot.fresh(910_000L))
        assertTrue(snapshot.usable(910_000L))
        assertFalse(snapshot.usable(86_410_000L))
        assertFalse(snapshot.usable(9_999L))
    }
}
