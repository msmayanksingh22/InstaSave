package com.instadown.assist.download

import android.content.Context
import com.instadown.assist.InstaSaveApp
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RedownloadProgressState(
    val recordId: Long,
    val isQueued: Boolean,
    val queuePosition: Int = 0,
    val percent: Int = 0,
    val message: String = "Queued..."
)

object RedownloadQueueManager {

    private data class Request(
        val record: DownloadRecord,
        val targetUrl: String,
        val targetIndices: Set<Int>?
    )

    private val queueChannel = Channel<Request>(Channel.UNLIMITED)
    private val _states = MutableStateFlow<Map<Long, RedownloadProgressState>>(emptyMap())
    val states: StateFlow<Map<Long, RedownloadProgressState>> = _states.asStateFlow()

    @Volatile private var started = false

    fun enqueue(
        appContext: Context,
        record: DownloadRecord,
        targetIndices: Set<Int>? = null
    ): Boolean {
        if (record.targetDownloadUrl.isBlank()) return false
        if (_states.value.containsKey(record.id)) return false

        ensureConsumerStarted(appContext.applicationContext)

        val position = _states.value.size + 1
        _states.update { current ->
            current + (record.id to RedownloadProgressState(
                recordId = record.id,
                isQueued = true,
                queuePosition = position,
                message = "Queued (#$position)"
            ))
        }
        queueChannel.trySend(Request(record, record.targetDownloadUrl, targetIndices))
        return true
    }

    private fun ensureConsumerStarted(appCtx: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            InstaSaveApp.applicationScope.launch(Dispatchers.IO) {
                val repo = DownloadRepository.getInstance(appCtx)
                val coordinator = InstagramDownloadCoordinator(appCtx)
                for (req in queueChannel) {
                    runRequest(repo, coordinator, req)
                }
            }
        }
    }

    private suspend fun runRequest(
        repo: DownloadRepository,
        coordinator: InstagramDownloadCoordinator,
        req: Request
    ) {
        val id = req.record.id
        try {
            updateProgress(id, 0, "Connecting...")
            val result = coordinator.executeDirectDownload(
                url = req.targetUrl,
                selectedIndices = req.targetIndices,
                recordInDb = true,
                onStatusUpdate = { status -> updateProgress(id, 0, status) },
                onProgress = { p -> updateProgress(id, p.overallPercent, p.statusMessage) }
            )
            if (result.success || (result.skippedCount > 0 && result.completedCount == 0)) {
                StorageAccountant.invalidateCache()
                repo.reconcileWithMediaStore()
            }
        } finally {
            _states.update { it - id }
        }
    }

    private fun updateProgress(id: Long, percent: Int, message: String) {
        _states.update { map ->
            map + (id to RedownloadProgressState(
                recordId = id,
                isQueued = false,
                percent = percent,
                message = message
            ))
        }
    }
}
