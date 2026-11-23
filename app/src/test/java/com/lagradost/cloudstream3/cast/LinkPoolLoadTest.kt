package com.lagradost.cloudstream3.cast

import com.lagradost.cloudstream3.amap
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Load test for link finding, without a phone: plugins that solve a proof-of-work puzzle per link are
 * simulated by jobs that keep a core fully busy. It measures how many run at once and how many cores'
 * worth of CPU they use, in the fast mode (waiting for a video) and the light mode (video playing).
 */
class LinkPoolLoadTest {
    private val threads = ManagementFactory.getThreadMXBean()

    /** Counts jobs running at once, and adds up their CPU time. */
    private class Meter {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val cpuNanos = java.util.concurrent.atomic.AtomicLong()
        val threadNames: MutableSet<String> = Collections.synchronizedSet(HashSet())
    }

    /** One "puzzle": [ms] of pure CPU work on the calling thread. */
    private fun puzzle(meter: Meter, ms: Long) {
        val now = meter.running.incrementAndGet()
        meter.peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        // Coroutine debug mode (on in tests) appends " @coroutine#N" to the thread name.
        meter.threadNames += Thread.currentThread().name.substringBefore(" @")
        val cpuStart = threads.currentThreadCpuTime
        val until = System.nanoTime() + ms * 1_000_000
        var x = 0L
        while (System.nanoTime() < until) x += x xor 31
        meter.cpuNanos.addAndGet(threads.currentThreadCpuTime - cpuStart)
        meter.running.decrementAndGet()
        if (x == 42L) println() // keeps the loop from being optimised away
    }

    /** Runs [jobs] puzzles through the pool's dispatcher the way plugins do (amap), returning wall time in ms. */
    private fun load(pool: LinkPool, meter: Meter, jobs: Int, ms: Long): Long = runBlocking {
        val start = System.nanoTime()
        withContext(pool.dispatcher) { (1..jobs).toList().amap { puzzle(meter, ms) } }
        (System.nanoTime() - start) / 1_000_000
    }

    private fun report(name: String, meter: Meter, wallMs: Long) {
        val cores = meter.cpuNanos.get() / 1e6 / wallMs
        println("$name: peak ${meter.peak.get()} jobs at once, ${"%.1f".format(cores)} cores on average over ${wallMs} ms")
    }

    @Test
    fun fastModeUsesAtMostFiveThreads() {
        val pool = LinkPool()
        val meter = Meter()
        val wall = load(pool, meter, jobs = 30, ms = 40)
        report("Fast (starting a video)", meter, wall)
        assertEquals(5, meter.peak.get())
        assertEquals(setOf("CastLinks"), meter.threadNames)
        // 30 jobs × 40 ms on 5 threads can't finish faster than 240 ms.
        assertTrue("finished in $wall ms", wall >= 30 * 40 / 5)
    }

    @Test
    fun lightModeUsesAtMostTwoThreads() {
        val pool = LinkPool().apply { speed(fast = false) }
        val meter = Meter()
        val wall = load(pool, meter, jobs = 20, ms = 40)
        report("Light (video playing)", meter, wall)
        assertEquals(2, meter.peak.get())
        val cores = meter.cpuNanos.get() / 1e6 / wall
        assertTrue("used $cores cores", cores <= 2.1)
    }

    @Test
    fun switchingToLightTakesEffectAfterTheRunningJobs() = runBlocking {
        val pool = LinkPool()
        val meter = Meter()
        val afterSwitch = Meter()
        val switchedAt = AtomicInteger()
        val job = launch(pool.dispatcher) {
            (1..60).toList().amap {
                // Jobs that start once the old ones have had time to finish are measured separately.
                if (switchedAt.get() == 1) puzzle(afterSwitch, 40) else puzzle(meter, 40)
            }
        }
        Thread.sleep(100)
        pool.speed(fast = false)
        Thread.sleep(80) // longer than one job: the fast-mode jobs still running have finished
        switchedAt.set(1)
        job.join()
        report("Before the switch", meter, 100)
        assertEquals(5, meter.peak.get())
        assertTrue("peak after switch ${afterSwitch.peak.get()}", afterSwitch.peak.get() in 1..2)
    }

    @Test
    fun switchingBackToFastAllowsFiveAgain() {
        val pool = LinkPool()
        pool.speed(fast = false)
        pool.speed(fast = true)
        assertEquals(5, pool.limit)
        val meter = Meter()
        load(pool, meter, jobs = 20, ms = 30)
        assertEquals(5, meter.peak.get())
    }

    @Test
    fun priorityFollowsTheMode() {
        val seen = Collections.synchronizedList(mutableListOf<Boolean>())
        val pool = LinkPool(setPriority = { seen += it })
        runBlocking { withContext(pool.dispatcher) { } }
        pool.speed(fast = false)
        seen.clear()
        runBlocking { withContext(pool.dispatcher) { } }
        assertTrue(seen.isNotEmpty())
        assertTrue("light mode must ask for background priority", seen.none { it })
    }
}
