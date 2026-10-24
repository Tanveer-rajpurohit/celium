package com.lagradost.cloudstream3

import com.lagradost.cloudstream3.ui.celium.CeliumSearchEngine
import com.lagradost.cloudstream3.ui.celium.CeliumTile
import com.lagradost.cloudstream3.ui.celium.FetchResult
import com.lagradost.cloudstream3.ui.celium.SearchConfig
import com.lagradost.cloudstream3.ui.celium.SearchSession
import com.lagradost.cloudstream3.ui.celium.SearchTarget
import com.lagradost.cloudstream3.ui.celium.SourceOutcome
import com.lagradost.cloudstream3.ui.celium.SourceState
import com.lagradost.cloudstream3.ui.search.SearchQuery
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 1, steps 6 and 7: fan-out, isolation, timeout, cache, health, retry, paging and cancellation. */
class CeliumSearchEngineTest {
    private val a = SearchTarget("a", "A", 0)
    private val b = SearchTarget("b", "B", 1)
    private val c = SearchTarget("c", "C", 2)

    private fun item(title: String, source: String) = CeliumTile(title, "https://$source/${title.hashCode()}", source.uppercase(), TvType.Movie)
    private fun ok(vararg titles: String, source: String = "x", hasNext: Boolean = false) =
        FetchResult.Ok(titles.map { item(it, source) }, hasNext)

    private class Clock { @Volatile var now = 0L; fun read() = now }

    private fun engine(clock: Clock = Clock(), config: SearchConfig = SearchConfig(sourceTimeoutMs = 400),
        fetch: suspend (SearchTarget, String, Int) -> FetchResult) = CeliumSearchEngine(fetch, clock::read, config)

    private fun parse(q: String) = SearchQuery.parse(q, 2027)

    @Test fun `every target is asked once and reports once`() = runBlocking {
        val calls = AtomicInteger()
        val e = engine { t, _, _ -> calls.incrementAndGet(); ok("Dune", source = t.id) }
        val out = e.run(listOf(a, b, c), parse("dune")).toList()
        assertEquals(3, out.size)
        assertEquals(3, calls.get())
        assertTrue(out.all { it.state == SourceState.Done && it.items.size == 1 })
        assertEquals(setOf("a", "b", "c"), out.map { it.target.id }.toSet())
    }

    @Test fun `a slow source times out and never blocks the others`() = runBlocking {
        val e = engine { t, _, _ -> if (t.id == "b") { delay(5_000); ok("Late", source = "b") } else ok("Dune", source = t.id) }
        val started = System.currentTimeMillis()
        val out = e.run(listOf(a, b, c), parse("dune")).toList().associateBy { it.target.id }
        val took = System.currentTimeMillis() - started
        assertEquals(SourceState.TimedOut, out.getValue("b").state)
        assertEquals(SourceState.Done, out.getValue("a").state)
        assertEquals(SourceState.Done, out.getValue("c").state)
        assertTrue("took $took ms", took < 2_000)
    }

    @Test fun `a source that throws or fails is marked and the rest still arrive`() = runBlocking {
        val e = engine { t, _, _ ->
            when (t.id) {
                "a" -> throw IllegalStateException("boom")
                "b" -> FetchResult.Failed
                else -> ok("Dune", source = "c")
            }
        }
        val out = e.run(listOf(a, b, c), parse("dune")).toList().associateBy { it.target.id }
        assertEquals(SourceState.Failed, out.getValue("a").state)
        assertEquals(SourceState.Failed, out.getValue("b").state)
        assertEquals(SourceState.Done, out.getValue("c").state)
    }

    @Test fun `a repeated search is answered from the cache without asking again`() = runBlocking {
        val calls = AtomicInteger()
        val e = engine { t, _, _ -> calls.incrementAndGet(); ok("Dune", source = t.id) }
        e.run(listOf(a, b), parse("dune")).toList()
        val second = e.run(listOf(a, b), parse("  DUNE ")).toList()
        assertEquals(2, calls.get())
        assertTrue(second.all { it.fromCache })
    }

    @Test fun `the cache expires and empty answers expire sooner`() = runBlocking {
        val clock = Clock()
        val calls = AtomicInteger()
        val e = engine(clock, SearchConfig(sourceTimeoutMs = 400, cacheTtlMs = 1_000, emptyTtlMs = 100)) { t, q, _ ->
            calls.incrementAndGet()
            if (q == "nothing") FetchResult.Ok(emptyList(), false) else ok("Dune", source = t.id)
        }
        e.run(listOf(a), parse("nothing")).toList()
        e.run(listOf(a), parse("dune")).toList()
        assertEquals(2, calls.get())
        clock.now = 200 // past the empty TTL, inside the normal one
        e.run(listOf(a), parse("nothing")).toList()
        e.run(listOf(a), parse("dune")).toList()
        assertEquals(3, calls.get())
        clock.now = 5_000
        e.run(listOf(a), parse("dune")).toList()
        assertEquals(4, calls.get())
    }

    @Test fun `a source that fails three searches in a row is skipped until a retry or the skip ends`() = runBlocking {
        val clock = Clock()
        val calls = AtomicInteger()
        val e = engine(clock, SearchConfig(sourceTimeoutMs = 400, failureLimit = 3, skipMs = 1_000)) { _, _, _ -> calls.incrementAndGet(); FetchResult.Failed }
        repeat(3) { e.run(listOf(a), parse("dune $it")).toList() }
        assertEquals(3, calls.get())
        val skipped = e.run(listOf(a), parse("dune again")).toList().single()
        assertEquals(SourceState.Skipped, skipped.state)
        assertEquals(3, calls.get()) // no request was made
        e.health.reset()
        e.run(listOf(a), parse("dune retry")).toList()
        assertEquals(4, calls.get())
        // Time alone also ends a skip.
        repeat(2) { e.run(listOf(a), parse("x $it")).toList() }
        assertEquals(SourceState.Skipped, e.run(listOf(a), parse("x skip")).toList().single().state)
        clock.now = 5_000
        assertEquals(SourceState.Failed, e.run(listOf(a), parse("x later")).toList().single().state)
    }

    @Test fun `a success clears the failure streak`() = runBlocking {
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        val calls = AtomicInteger()
        val e = engine(config = SearchConfig(sourceTimeoutMs = 400, failureLimit = 3)) { _, _, _ ->
            calls.incrementAndGet()
            if (fail.get()) FetchResult.Failed else ok("Dune")
        }
        repeat(2) { e.run(listOf(a), parse("first $it")).toList() }
        fail.set(false)
        e.run(listOf(a), parse("good")).toList()
        fail.set(true)
        repeat(2) { e.run(listOf(a), parse("second $it")).toList() }
        // Four failures in all, but only two since the success: below the limit of three, so still asked.
        val last = e.run(listOf(a), parse("final")).toList().single()
        assertEquals(SourceState.Failed, last.state)
        assertEquals(6, calls.get())
    }

    @Test fun `an empty answer for the title retries once with the text as typed`() = runBlocking {
        val asked = java.util.Collections.synchronizedList(ArrayList<String>())
        val e = engine { _, q, _ -> asked.add(q); if (q == "dune 2021") ok("Dune (2021)") else FetchResult.Ok(emptyList(), false) }
        val out = e.run(listOf(a), parse("dune 2021")).toList().single()
        assertEquals(listOf("dune", "dune 2021"), asked.map { it.lowercase() })
        assertEquals(1, out.items.size)
    }

    @Test fun `no retry when the title is the text, or for later pages`() = runBlocking {
        val calls = AtomicInteger()
        val e = engine { _, _, _ -> calls.incrementAndGet(); FetchResult.Ok(emptyList(), false) }
        e.run(listOf(a), parse("dune")).toList()
        assertEquals(1, calls.get())
        e.run(listOf(a), parse("dune 2021")) { 2 }.toList()
        assertEquals(2, calls.get())
    }

    @Test fun `cancelling the collector cancels every request`() = runBlocking {
        val cancelled = AtomicInteger()
        val e = engine(config = SearchConfig(sourceTimeoutMs = 60_000)) { _, _, _ ->
            try { delay(30_000); ok("Never") } finally { cancelled.incrementAndGet() }
        }
        withTimeoutOrNull(150) { e.run(listOf(a, b, c), parse("dune")).toList() }
        delay(100)
        assertEquals(3, cancelled.get())
    }

    // ---------------------------------------------------------------- session

    private fun done(t: SearchTarget, page: Int, hasNext: Boolean, vararg titles: String) =
        SourceOutcome(t, page, SourceState.Done, titles.map { item(it, t.id) }, hasNext)

    @Test fun `the session counts answers and removes repeated hits`() {
        val s = SearchSession(listOf(a, b), parse("dune"))
        assertTrue(s.pending)
        assertEquals(2, s.apply(done(a, 1, true, "Dune", "Dune 2")))
        assertEquals(0, s.apply(done(a, 1, true, "Dune"))) // same source and url
        assertEquals(1, s.answered)
        s.apply(SourceOutcome(b, 1, SourceState.TimedOut))
        assertFalse(s.pending)
        assertEquals(2, s.hits.size)
        assertEquals(SourceState.TimedOut, s.statuses.last().state)
    }

    @Test fun `seeded shelf results are kept and not counted as a source`() {
        val s = SearchSession(listOf(a), parse("dune"))
        s.seed(listOf(item("Dune", "a")))
        assertEquals(1, s.hits.size)
        assertEquals(0, s.apply(done(a, 1, false, "Dune"))) // same url as the seeded one
        assertEquals(1, s.hits.size)
    }

    @Test fun `paging asks only sources with a next page and stops a source that adds nothing`() {
        val s = SearchSession(listOf(a, b), parse("dune"))
        s.apply(done(a, 1, true, "Dune"))
        s.apply(done(b, 1, false, "Dune B"))
        assertEquals(listOf(a), s.nextTargets())
        s.beginNextPage(s.nextTargets())
        assertTrue(s.pending)
        assertEquals(2, s.pageToLoad(a))
        assertEquals(0, s.apply(done(a, 2, true, "Dune"))) // nothing new: that source ends
        assertTrue(s.nextTargets().isEmpty())
        assertFalse(s.pending)
    }

    @Test fun `a failed later page keeps the first page and just ends that source`() {
        val s = SearchSession(listOf(a), parse("dune"))
        s.apply(done(a, 1, true, "Dune"))
        s.beginNextPage(s.nextTargets())
        s.apply(SourceOutcome(a, 2, SourceState.Failed))
        assertEquals(SourceState.Done, s.statuses.single().state)
        assertTrue(s.nextTargets().isEmpty())
        assertEquals(1, s.hits.size)
    }
}
