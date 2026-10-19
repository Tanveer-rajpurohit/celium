package com.lagradost.cloudstream3.ui.celium

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.downloader.DownloadQueueManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadActionType
import com.lagradost.cloudstream3.utils.downloader.VideoDownloadManager.DownloadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Shared download actions so the Downloads page and the Details control behave the same (Requirement 19.9). */
object CeliumDownloadActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun label(state: DlState?, percent: Int): String? = when (state) {
        null -> null
        DlState.Queued -> "Queued"
        DlState.Downloading -> "Downloading $percent%"
        DlState.Paused -> "Paused"
        DlState.Completed -> "Completed"
        DlState.Failed -> "Failed"
    }

    fun text(action: DlAction): String = when (action) {
        DlAction.Download -> "Download"
        DlAction.Cancel -> "Cancel"
        DlAction.Pause -> "Pause"
        DlAction.Resume -> "Resume"
        DlAction.Retry -> "Retry"
        DlAction.Play -> "Play"
        DlAction.Delete -> "Delete"
    }

    fun pause(id: Int) {
        VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Pause)
    }

    /** Same path as `DownloadButtonSetup` resume: live paused instance, else re-queue the resume package (keeps saved bytes). */
    fun resume(context: Context, id: Int) {
        try {
            if (VideoDownloadManager.downloadStatus[id] == DownloadType.IsPaused) {
                VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Resume)
                return
            }
            val pkg = VideoDownloadManager.getDownloadResumePackage(context, id)
            if (pkg != null) DownloadQueueManager.addToQueue(pkg.toWrapper())
            else VideoDownloadManager.downloadEvent.invoke(id to DownloadActionType.Resume)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun delete(context: Context, id: Int, onDone: () -> Unit = {}) {
        VideoDownloadManager.deleteFilesAndUpdateSettings(context.applicationContext, setOf(id), scope) { onDone() }
    }

    /** Runs a queue action; Download and Play are screen-specific and handled by the caller. */
    fun perform(context: Context, id: Int, action: DlAction) {
        when (action) {
            DlAction.Cancel -> CeliumDownloads.cancel(context, id)
            DlAction.Pause -> pause(id)
            DlAction.Resume -> resume(context, id)
            DlAction.Retry -> CeliumDownloads.retry(context, id)
            DlAction.Delete -> delete(context, id)
            DlAction.Download, DlAction.Play -> Unit
        }
    }
}

/**
 * Details_Download_Control: replaces the plain Download button with the item's live state and actions.
 * Reads the same `CeliumDownloads.items` flow as the Downloads page.
 */
@Composable
fun CeliumDownloadControl(id: Int, onDownload: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by CeliumDownloads.items.collectAsState()
    val item = items[id]
    val deletions = remember(id) { mutableIntStateOf(0) }
    // A finished file from an earlier session is not in the queue; check the disk once per state change.
    val onDisk by produceState<DlState?>(null, id, item?.state, deletions.intValue) {
        value = withContext(Dispatchers.IO) {
            runCatching { VideoDownloadManager.getDownloadFileInfo(context, id) }.getOrNull()
                ?.let { DownloadBook.of(null, inQueue = false, active = false, it.fileLength, it.totalBytes) }
        }
    }
    val state = item?.state ?: CeliumDownloads.stateOf(id) ?: onDisk
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        CeliumDownloadActions.label(state, item?.percent ?: 0)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DownloadBook.actions(state).forEach { action ->
            TextButton(onClick = {
                when (action) {
                    DlAction.Download -> onDownload()
                    DlAction.Play -> onPlay()
                    DlAction.Delete -> CeliumDownloadActions.delete(context, id) { deletions.intValue++ }
                    else -> CeliumDownloadActions.perform(context, id, action)
                }
            }) { Text(CeliumDownloadActions.text(action)) }
        }
    }
}
