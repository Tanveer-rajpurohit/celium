package com.lagradost.cloudstream3.utils.downloader

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity.Companion.lastError
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.services.DownloadQueueService
import com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects.DownloadQueueWrapper
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_IN_QUEUE
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.KEY_RESUME_PACKAGES
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatus
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.downloadStatusEvent
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadFileInfo
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadQueuePackage
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.getDownloadResumePackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet

// 1. Put a download on the queue
// 2. The queue manager starts a foreground service to handle the queue
// 3. The service starts work manager jobs to handle the downloads?
object DownloadQueueManager {
    private const val TAG = "DownloadQueueManager"
    const val QUEUE_KEY = "download_queue_key"

    /** Flow of all active queued download, no active downloads.
     * This flow may see many changes, do not place expensive observers.
     * downloadInstances is the flow keeping track of active downloads.
     * @see com.lagradost.cloudstream3.services.DownloadQueueService.Companion.downloadInstances
     */
    private val _queue: MutableStateFlow<Array<DownloadQueueWrapper>> by lazy {
        /** Persistent queue */
        // A corrupt persisted queue must never crash startup (Requirement 7.7)
        val currentValue = runCatching {
            getKey<Array<DownloadQueueWrapper>>(QUEUE_KEY)
        }.getOrNull() ?: emptyArray()
        MutableStateFlow(currentValue)
    }

    val queue: StateFlow<Array<DownloadQueueWrapper>> by lazy { _queue }

    /** Start the queue, marks all queue objects as in progress.
     * Note that this may run twice without the service restarting
     * because MainActivity may be recreated. */
    fun init(context: Context) {
        ioSafe {
            _queue.collect { queue ->
                setKey(QUEUE_KEY, queue)
            }
        }

        ioSafe startQueue@{
            // Do not automatically start the queue if safe mode is activated.
            if (PluginManager.isSafeMode()) {
                // Prevent misleading UI
                VideoDownloadManager.cancelAllDownloadNotifications(context)
                return@startQueue
            }

            val active = VideoDownloadManager.currentDownloads.value.toSet()

            // Each resume id becomes a candidate; unrestorable entries get a null wrapper (Requirement 7.7)
            val resumeCandidates = getPreResumeIds().map { id ->
                val wrapper = runCatching {
                    getDownloadResumePackage(context, id)?.toWrapper()
                        ?: getDownloadQueuePackage(context, id)
                }.getOrNull()
                RestoreCandidate(id, wrapper, isDownloadComplete(context, id))
            }

            // Re-validate the persisted queue, completed entries are dropped
            val queuedComplete = _queue.value.associate { wrapper ->
                wrapper.id to isDownloadComplete(context, wrapper.id)
            }

            var restored: List<DownloadQueueWrapper> = emptyList()
            _queue.update { localQueue ->
                val queuedCandidates = localQueue.map { wrapper ->
                    // Items added after the snapshot were already validated by addToQueue
                    RestoreCandidate(wrapper.id, wrapper, queuedComplete[wrapper.id] ?: false)
                }
                val planned = DownloadRestore.plan(resumeCandidates, queuedCandidates, active)
                // Items currently downloading stay out of the queue, as before
                restored = planned
                planned.toTypedArray()
            }

            val restoredIds = restored.mapTo(HashSet()) { it.id }
            val droppedIds = (resumeCandidates.map { it.id } + queuedComplete.keys)
                .filter { it !in restoredIds && it !in active }
                .toSet()

            // Once added to the queue they can be safely removed
            removeKeys(KEY_RESUME_IN_QUEUE)

            // Discarded entries must not resume later and should not look pending
            droppedIds.forEach { id ->
                safe { removeKey(KEY_RESUME_PACKAGES, id.toString()) }
                setQueueStatus(id, VideoDownloadManager.DownloadType.IsStopped)
            }

            // Make sure the download buttons display a pending status
            restored.forEach { obj ->
                setQueueStatus(obj.id, VideoDownloadManager.DownloadType.IsPending)
            }

            // Only start the foreground service for real work (Requirements 7.1, 7.2)
            if (DownloadRestore.shouldStartService(restored)) {
                startQueueService(context)
            }
        }
    }

    /** True when more than 98% of the file is on disk. Shared by restore and [addToQueue]. */
    private fun isDownloadComplete(context: Context, id: Int): Boolean {
        val fileInfo = runCatching { getDownloadFileInfo(context, id) }.getOrNull()
        return fileInfo != null &&
                // Assure no division by 0
                fileInfo.totalBytes > 0 &&
                (fileInfo.fileLength.toFloat() / fileInfo.totalBytes.toFloat()) > 0.98f
    }

    /** Downloads not yet started or in progress. */
    private fun getPreResumeIds(): Set<Int> {
        return getKeys(KEY_RESUME_IN_QUEUE)?.mapNotNull {
            it.substringAfter("$KEY_RESUME_IN_QUEUE/").toIntOrNull()
        }?.toSet()
            ?: emptySet()
    }

    /** Adds an object to the internal persistent queue. It does not re-add an existing item. @return true if successfully added  */
    private fun add(downloadQueueWrapper: DownloadQueueWrapper): Boolean {
        Log.d(TAG, "Download added to queue: $downloadQueueWrapper")
        val newQueue = _queue.updateAndGet { localQueue ->
            // Do not add the same episode twice
            if (downloadQueueWrapper.isCurrentlyDownloading() || localQueue.any { it.id == downloadQueueWrapper.id }) {
                return@updateAndGet localQueue
            }
            localQueue + downloadQueueWrapper
        }
        return newQueue.any { it.id == downloadQueueWrapper.id }
    }

    /** Removes all objects with the same id from the internal persistent queue */
    private fun remove(id: Int) {
        Log.d(TAG, "Download removed from the queue: $id")
        _queue.update { localQueue ->
            // The check is to prevent unnecessary updates
            if (!localQueue.any { it.id == id }) {
                return@update localQueue
            }

            localQueue.filter { it.id != id }.toTypedArray()
        }
    }

    /** Removes all items and returns the previous queue */
    private fun removeAll(): Array<DownloadQueueWrapper> {
        Log.d(TAG, "Removed everything from queue")
        return _queue.getAndUpdate {
            emptyArray()
        }
    }

    private fun reorder(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        _queue.update { localQueue ->
            val newIndex = newPosition.coerceIn(0, localQueue.size)
            val id = downloadQueueWrapper.id

            val newQueue = localQueue.filter { it.id != id }.toMutableList().apply {
                this.add(newIndex, downloadQueueWrapper)
            }.toTypedArray()

            newQueue
        }
    }

    /** Start a real download from the first item in the queue */
    fun popQueue(context: Context): VideoDownloadManager.EpisodeDownloadInstance? {
        val first = queue.value.firstOrNull() ?: return null

        remove(first.id)

        val downloadInstance = VideoDownloadManager.EpisodeDownloadInstance(context, first)

        return downloadInstance
    }

    /** Marks the item as in queue for the download button */
    private fun setQueueStatus(id: Int, status: VideoDownloadManager.DownloadType) {
        downloadStatusEvent.invoke(
            Pair(
                id,
                status
            )
        )
        downloadStatus[id] = status
    }

    private fun startQueueService(context: Context?) {
        if (context == null) {
            Log.d(TAG, "Cannot start download queue service, null context.")
            return
        }
        // Do not restart the download queue service
        if (DownloadQueueService.isRunning) {
            return
        }
        ioSafe {
            val intent = DownloadQueueService.getIntent(context)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    /** Cancels an active download or removes it from queue depending on where it is. */
    fun cancelDownload(id: Int) {
        Log.d(TAG, "Cancelling download: $id")

        val currentInstance = downloadInstances.value.find { it.downloadQueueWrapper.id == id }

        if (currentInstance != null) {
            currentInstance.cancelDownload()
        } else {
            removeFromQueue(id)
        }
    }

    /** Removes all queued items */
    fun removeAllFromQueue() {
        removeAll().forEach { wrapper ->
            setQueueStatus(wrapper.id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    /** Removes all objects with the same id from the internal persistent queue  */
    fun removeFromQueue(id: Int) {
        ioSafe {
            remove(id)
            setQueueStatus(id, VideoDownloadManager.DownloadType.IsStopped)
        }
    }

    /** Will move the download queue wrapper to a new position in the queue.
     * If the item does not exist it will also insert it. */
    fun reorderItem(downloadQueueWrapper: DownloadQueueWrapper, newPosition: Int) {
        ioSafe {
            reorder(downloadQueueWrapper, newPosition)
        }
    }

    /** Add a new object to the queue. Will not queue completed downloads or current downloads. */
    fun addToQueue(downloadQueueWrapper: DownloadQueueWrapper) = safe {
        val context = CloudStreamApp.context ?: return@safe
        // Do not queue completed files!
        if (isDownloadComplete(context, downloadQueueWrapper.id)) return@safe

        // List the item as Queued before link extraction starts (Requirement 19.1).
        com.lagradost.cloudstream3.ui.celium.CeliumDownloads.record(downloadQueueWrapper)
        if (add(downloadQueueWrapper)) {
            setQueueStatus(downloadQueueWrapper.id, VideoDownloadManager.DownloadType.IsPending)
            startQueueService(context)
        }
    }


    /** Refreshes the queue flow with the same value, but copied.
     * Good to run if the downloads are affected by some outside value change. */
    fun forceRefreshQueue() {
        _queue.update { localQueue ->
            localQueue.copyOf()
        }
    }
}