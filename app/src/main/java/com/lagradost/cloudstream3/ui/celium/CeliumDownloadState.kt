package com.lagradost.cloudstream3.ui.celium

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/** Download state shown identically on the Downloads page and the Details download control (Requirement 19). */
enum class DlState { Queued, Downloading, Paused, Completed, Failed }

enum class DlAction { Download, Cancel, Pause, Resume, Retry, Play, Delete }

data class DlItem(
    val id: Int,
    val title: String,
    val subtitle: String,
    val poster: String?,
    val state: DlState,
    val percent: Int,
    val addedSeq: Long,
)

/** User and downloader events that move an item through the download state machine. */
sealed interface DlEvent {
    val id: Int

    data class Queue(override val id: Int) : DlEvent
    data class Start(override val id: Int) : DlEvent
    data class Progress(override val id: Int, val bytes: Long, val total: Long) : DlEvent
    data class Pause(override val id: Int) : DlEvent
    data class Resume(override val id: Int) : DlEvent
    data class Fail(override val id: Int) : DlEvent
    data class Complete(override val id: Int) : DlEvent
    data class Cancel(override val id: Int) : DlEvent
    data class Retry(override val id: Int) : DlEvent
}

/** Pure model of the download list: one entry per id, ordered by the time it was first added. */
object DownloadBook {
    /** Same threshold `DownloadQueueManager.addToQueue` uses to treat a file as complete. */
    const val DONE_RATIO = 0.98

    data class Entry(val state: DlState, val bytes: Long, val total: Long, val addedSeq: Long)
    data class Book(val seq: Long = 0L, val entries: Map<Int, Entry> = emptyMap())

    val empty = Book()

    fun apply(book: Book, event: DlEvent): Book {
        val id = event.id
        val current = book.entries[id]
        fun put(entry: Entry) = book.copy(entries = book.entries + (id to entry))
        return when (event) {
            // Re-adding an item that is already listed keeps its place and state (no duplicates).
            is DlEvent.Queue -> if (current != null) book else Book(
                seq = book.seq + 1,
                entries = book.entries + (id to Entry(DlState.Queued, 0L, 0L, book.seq)),
            )

            is DlEvent.Start ->
                if (current?.state == DlState.Queued) put(current.copy(state = DlState.Downloading)) else book

            is DlEvent.Progress -> when (current?.state) {
                DlState.Queued, DlState.Downloading, DlState.Paused -> {
                    val next = current.copy(bytes = event.bytes.coerceAtLeast(0L), total = event.total)
                    put(if (isDone(next.bytes, next.total)) next.copy(state = DlState.Completed) else next)
                }

                else -> book
            }

            is DlEvent.Pause ->
                if (current?.state == DlState.Downloading) put(current.copy(state = DlState.Paused)) else book

            // Resume continues from the bytes already saved.
            is DlEvent.Resume ->
                if (current?.state == DlState.Paused) put(current.copy(state = DlState.Downloading)) else book

            is DlEvent.Fail ->
                if (current != null && current.state != DlState.Completed) put(current.copy(state = DlState.Failed)) else book

            is DlEvent.Complete ->
                if (current != null) put(current.copy(state = DlState.Completed, bytes = maxOf(current.bytes, current.total))) else book

            is DlEvent.Cancel ->
                if (current != null) book.copy(entries = book.entries - id) else book

            // Retry restarts from 0 bytes and keeps the original add order.
            is DlEvent.Retry ->
                if (current?.state == DlState.Failed) put(current.copy(state = DlState.Queued, bytes = 0L, total = 0L)) else book
        }
    }

    /** Rows in add order; ids are map keys, so each item appears once. */
    fun rows(book: Book): List<Pair<Int, DlState>> =
        book.entries.entries
            .sortedWith(compareBy<Map.Entry<Int, Entry>>({ it.value.addedSeq }, { it.key }))
            .map { it.key to it.value.state }

    /** Maps the downloader's live signals to a Celium state; null means "not queued, no file" (Download action). */
    fun of(status: DownloadType?, inQueue: Boolean, active: Boolean, bytes: Long, total: Long): DlState? = when {
        status == DownloadType.IsDone || isDone(bytes, total) -> DlState.Completed
        status == DownloadType.IsStopped -> null
        status == DownloadType.IsFailed -> DlState.Failed
        status == DownloadType.IsPaused -> DlState.Paused
        status == DownloadType.IsDownloading -> DlState.Downloading
        status == DownloadType.IsPending || inQueue || active -> DlState.Queued
        else -> null
    }

    fun actions(state: DlState?): List<DlAction> = when (state) {
        null -> listOf(DlAction.Download)
        DlState.Queued -> listOf(DlAction.Cancel)
        DlState.Downloading -> listOf(DlAction.Pause, DlAction.Cancel)
        DlState.Paused -> listOf(DlAction.Resume, DlAction.Cancel)
        DlState.Failed -> listOf(DlAction.Retry, DlAction.Cancel)
        DlState.Completed -> listOf(DlAction.Play, DlAction.Delete)
    }

    fun percent(bytes: Long, total: Long): Int {
        if (total <= 0L) return 0
        return ((bytes.coerceAtLeast(0L).toDouble() * 100.0) / total.toDouble()).toInt().coerceIn(0, 100)
    }

    private fun isDone(bytes: Long, total: Long): Boolean =
        total > 0L && bytes.toDouble() / total.toDouble() > DONE_RATIO
}

/** Android adapter: one StateFlow over the queue, active instances and stored files, read by every screen. */
object CeliumDownloads {
    private const val KEY = "celium_download_records_v1"

    @Serializable
    data class Record(
        @JsonProperty("wrapper") @SerialName("wrapper") val wrapper: DownloadQueueWrapper,
        @JsonProperty("addedSeq") @SerialName("addedSeq") val addedSeq: Long,
        @JsonProperty("failed") @SerialName("failed") val failed: Boolean = false,
    )

    @Serializable
    data class Records(
        @JsonProperty("seq") @SerialName("seq") val seq: Long = 0L,
        @JsonProperty("items") @SerialName("items") val items: List<Record> = emptyList(),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val records: MutableStateFlow<Records> by lazy {
        MutableStateFlow(runCatching { getKey<Records>(KEY) }.getOrNull() ?: Records())
    }
    private val statusTick = MutableStateFlow(0L)
    private val statuses = ConcurrentHashMap<Int, DownloadType>()
    private val progress = ConcurrentHashMap<Int, Pair<Long, Long>>()
    private val fileSizes = ConcurrentHashMap<Int, Pair<Long, Long>>()
    private val retrying = ConcurrentHashMap.newKeySet<Int>()

    private fun bump() = statusTick.update { it + 1 }

    private val statusListener = { event: Pair<Int, DownloadType> ->
        val (id, status) = event
        statuses[id] = status
        fileSizes.remove(id)
        when (status) {
            DownloadType.IsFailed -> setFailed(id, true)
            DownloadType.IsPending, DownloadType.IsDownloading -> setFailed(id, false)
            // Cancelled items disappear; a retry re-inserts its own record.
            DownloadType.IsStopped -> if (id !in retrying) {
                progress.remove(id)
                drop(id)
            }

            else -> Unit
        }
        bump()
    }

    private val progressListener = { event: Triple<Int, Long, Long> ->
        progress[event.first] = event.second to event.third
        bump()
    }

    private val deleteListener = { id: Int ->
        progress.remove(id)
        fileSizes.remove(id)
        if (id !in retrying) drop(id)
        bump()
    }

    init {
        VideoDownloadManager.downloadStatusEvent += statusListener
        VideoDownloadManager.downloadProgressEvent += progressListener
        VideoDownloadManager.downloadDeleteEvent += deleteListener
    }

    /** Every recorded, queued or active item keyed by id, iterated in add order. */
    val items: StateFlow<Map<Int, DlItem>> by lazy {
        combine(
            DownloadQueueManager.queue,
            DownloadQueueService.downloadInstances,
            statusTick,
            records,
        ) { queue, instances, _, recs -> build(queue, instances, recs) }
            .flowOn(Dispatchers.IO)
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Current state for an id; null means not queued and no file, so the Download action applies. */
    fun stateOf(id: Int): DlState? {
        items.value[id]?.let { return it.state }
        val (bytes, total) = progress[id] ?: fileSizes[id] ?: (0L to 0L)
        return DownloadBook.of(statusOf(id), inQueue = false, active = false, bytes, total)
    }

    /** Parent (title) id of a recorded, queued or active item, so the library can hide titles that are only in progress. */
    fun parentOf(id: Int): Int? {
        val wrapper = records.value.items.firstOrNull { it.wrapper.id == id }?.wrapper
            ?: DownloadQueueManager.queue.value.firstOrNull { it.id == id }
            ?: DownloadQueueService.downloadInstances.value.firstOrNull { it.downloadQueueWrapper.id == id }?.downloadQueueWrapper
            ?: return null
        return runCatching { wrapper.parentId }.getOrNull()
    }

    /** Records an add so the item is listed as Queued before link extraction and keeps its add order across restarts. */
    fun record(wrapper: DownloadQueueWrapper) {
        val id = wrapper.id
        // Show Queued right away, even if an older Stopped/Failed status is still cached for this id.
        when (statusOf(id)) {
            DownloadType.IsDownloading, DownloadType.IsPaused, DownloadType.IsDone -> Unit
            else -> statuses[id] = DownloadType.IsPending
        }
        mutate { recs ->
            val existing = recs.items.firstOrNull { it.wrapper.id == id }
            if (existing != null) {
                if (!existing.failed) recs
                else recs.copy(items = recs.items.map { if (it.wrapper.id == id) it.copy(failed = false) else it })
            } else {
                Records(recs.seq + 1, recs.items + Record(wrapper, recs.seq))
            }
        }
        bump()
    }

    /** Cancels a queued or active item, deletes its partial data and removes it from the list. */
    fun cancel(context: Context, id: Int) {
        val appContext = context.applicationContext
        retrying.remove(id)
        val wasActive = DownloadQueueService.downloadInstances.value.any { it.downloadQueueWrapper.id == id }
        // Hide the row immediately; the queue removal itself is asynchronous.
        statuses[id] = DownloadType.IsStopped
        DownloadQueueManager.cancelDownload(id)
        removeKey(KEY_RESUME_PACKAGES, id.toString())
        removeKey(KEY_RESUME_IN_QUEUE, id.toString())
        progress.remove(id)
        fileSizes.remove(id)
        drop(id)
        bump()
        scope.launch {
            // An active download deletes its own partial file once it sees the stop; give it time to close the stream.
            if (wasActive) delay(1_500L)
            if (isLive(id) || records.value.items.any { it.wrapper.id == id }) return@launch
            deletePartial(appContext, id)
        }
    }

    /** Deletes any partial file and queues the recorded item again from 0 bytes, keeping its list position. */
    fun retry(context: Context, id: Int) {
        val record = records.value.items.firstOrNull { it.wrapper.id == id } ?: return
        val appContext = context.applicationContext
        retrying.add(id)
        bump()
        scope.launch {
            try {
                removeKey(KEY_RESUME_PACKAGES, id.toString())
                deletePartial(appContext, id)
                // Deletion emits stop/delete events; restore the record with its original order.
                mutate { recs ->
                    val others = recs.items.filterNot { it.wrapper.id == id }
                    recs.copy(items = others + record.copy(failed = false))
                }
                statuses.remove(id)
                progress.remove(id)
                fileSizes.remove(id)
                // The stop event's queue removal runs asynchronously; let it settle before re-adding.
                delay(500L)
                DownloadQueueManager.addToQueue(record.wrapper)
            } catch (t: Throwable) {
                logError(t)
            } finally {
                retrying.remove(id)
                bump()
            }
        }
    }

    private suspend fun deletePartial(context: Context, id: Int) {
        val done = CompletableDeferred<Unit>()
        VideoDownloadManager.deleteFilesAndUpdateSettings(context, setOf(id), scope) { done.complete(Unit) }
        withTimeoutOrNull(5_000L) { done.await() }
        fileSizes.remove(id)
    }

    private fun isLive(id: Int): Boolean =
        DownloadQueueManager.queue.value.any { it.id == id } ||
                DownloadQueueService.downloadInstances.value.any { it.downloadQueueWrapper.id == id }

    private fun statusOf(id: Int): DownloadType? =
        statuses[id] ?: runCatching { VideoDownloadManager.downloadStatus[id] }.getOrNull()

    private fun build(
        queue: Array<DownloadQueueWrapper>,
        instances: List<VideoDownloadManager.EpisodeDownloadInstance>,
        recs: Records,
    ): Map<Int, DlItem> {
        val context = CloudStreamApp.context
        val queued = queue.associateBy { it.id }
        val active = instances
            .filterNot { it.isCompleted || it.isFailed || it.isCancelled }
            .associateBy { it.downloadQueueWrapper.id }
        val recorded = recs.items.associateBy { it.wrapper.id }
        val ids = LinkedHashSet<Int>().apply {
            addAll(recorded.keys)
            addAll(queued.keys)
            addAll(active.keys)
        }
        // Items queued outside `record` (e.g. restored on startup) go after recorded ones, in queue order.
        var extraSeq = recs.seq
        val out = ids.mapNotNull { id ->
            val rec = recorded[id]
            val wrapper = rec?.wrapper ?: active[id]?.downloadQueueWrapper ?: queued[id] ?: return@mapNotNull null
            val (bytes, total) = sizeOf(context, id, live = id in active)
            val state: DlState = when {
                id in retrying -> DlState.Queued
                else -> DownloadBook.of(statusOf(id), id in queued, id in active, bytes, total)
                    ?: rec?.let { fallback(context, id, it) }
            } ?: return@mapNotNull null
            val seq = rec?.addedSeq ?: extraSeq++
            val (title, subtitle, poster) = describe(wrapper)
            DlItem(id, title, subtitle, poster, state, DownloadBook.percent(bytes, total), seq)
        }
        return out.sortedWith(compareBy<DlItem>({ it.addedSeq }, { it.id }))
            .associateByTo(LinkedHashMap()) { it.id }
    }

    /** A recorded item with no live signal (e.g. after a restart) keeps a resumable or failed state instead of vanishing. */
    private fun fallback(context: Context?, id: Int, rec: Record): DlState {
        if (rec.failed || context == null) return DlState.Failed
        val resumable = runCatching { VideoDownloadManager.getDownloadResumePackage(context, id) }.getOrNull() != null
        return if (resumable) DlState.Paused else DlState.Failed
    }

    private fun sizeOf(context: Context?, id: Int, live: Boolean): Pair<Long, Long> {
        progress[id]?.let { return it }
        if (live || context == null) return 0L to 0L
        fileSizes[id]?.let { return it }
        val info = runCatching { VideoDownloadManager.getDownloadFileInfo(context, id) }.getOrNull()
        val size = if (info != null) info.fileLength to info.totalBytes else 0L to 0L
        fileSizes[id] = size
        return size
    }

    private fun describe(wrapper: DownloadQueueWrapper): Triple<String, String, String?> {
        wrapper.downloadItem?.let { item ->
            val ep = item.episode
            val subtitle = if (item.isMovie) "" else label(ep.season, ep.episode, ep.name)
            return Triple(item.resultName, subtitle, item.resultPoster ?: ep.poster)
        }
        val ep = wrapper.resumePackage?.item?.ep ?: return Triple("", "", null)
        return Triple(ep.mainName, label(ep.season, ep.episode, ep.name), ep.poster)
    }

    private fun label(season: Int?, episode: Int?, name: String?): String {
        val number = when {
            season != null && episode != null -> "S$season E$episode"
            episode != null -> "E$episode"
            else -> null
        }
        val title = name?.takeIf { it.isNotBlank() }
        return listOfNotNull(number, title).joinToString(" · ")
    }

    private fun setFailed(id: Int, failed: Boolean) = mutate { recs ->
        if (recs.items.none { it.wrapper.id == id && it.failed != failed }) recs
        else recs.copy(items = recs.items.map { if (it.wrapper.id == id) it.copy(failed = failed) else it })
    }

    private fun drop(id: Int) = mutate { recs ->
        if (recs.items.none { it.wrapper.id == id }) recs
        else recs.copy(items = recs.items.filterNot { it.wrapper.id == id })
    }

    private fun mutate(change: (Records) -> Records) {
        synchronized(lock) {
            val before = records.value
            val after = change(before)
            if (after == before) return
            records.value = after
            runCatching { setKey(KEY, after) }.onFailure { logError(it) }
        }
    }
}
