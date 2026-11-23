package com.lagradost.cloudstream3.cast

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Threads that source plugins find links on. Some hosts make the phone solve a proof-of-work puzzle
 * per link; dozens of those in parallel took 5+ cores for minutes and got Celium killed. Plugins'
 * parallel helpers (amap) run on the caller's threads, so these limits apply to them too:
 * - fast (someone is waiting for a video to start): [fastThreads] threads at normal priority;
 * - light (the video is playing): [slowThreads] threads at background priority.
 * The pool resizes in place: threads above a lowered limit stop after their current task.
 *
 * @param setPriority applies the thread priority for the current mode (true = fast) to the calling thread.
 */
class LinkPool(
    private val fastThreads: Int = 5,
    private val slowThreads: Int = 2,
    private val setPriority: (fast: Boolean) -> Unit = {},
) {
    private val pool = ThreadPoolExecutor(fastThreads, fastThreads, 10, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "CastLinks").apply { isDaemon = true }
    }

    @Volatile var fast = true
        private set

    val dispatcher: CoroutineDispatcher = Executor { task ->
        pool.execute {
            setPriority(fast)
            task.run()
        }
    }.asCoroutineDispatcher()

    /** Most threads that can run at once in the current mode. */
    val limit: Int get() = pool.maximumPoolSize

    fun speed(fast: Boolean) = synchronized(pool) {
        this.fast = fast
        val size = if (fast) fastThreads else slowThreads
        // The maximum can't go below the core size, so the order depends on the direction.
        if (fast) { pool.maximumPoolSize = size; pool.corePoolSize = size }
        else { pool.corePoolSize = size; pool.maximumPoolSize = size }
    }
}
