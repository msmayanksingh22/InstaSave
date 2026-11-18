package com.instadown.assist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.download.InstagramDownloadCoordinator
import com.instadown.assist.util.InstagramMetadataFetcher
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class DownloadHistoryActivity : AppCompatActivity() {

    private enum class HistoryFilter { ALL, ON_DEVICE, DELETED }

    private lateinit var rvHistoryList: RecyclerView
    private lateinit var layoutEmptyHistory: LinearLayout
    private lateinit var tvEmptyHistoryDescription: TextView
    private lateinit var pbHistoryLoading: ProgressBar
    private lateinit var tvHeaderSummary: TextView

    private lateinit var chipFilterAll: Button
    private lateinit var chipFilterOnDevice: Button
    private lateinit var chipFilterDeleted: Button

    private var currentFilter = HistoryFilter.ALL
    private var allHistoryRecords: List<DownloadRecord> = emptyList()

    private val repo by lazy { DownloadRepository.getInstance(this) }
    private val coordinator by lazy { InstagramDownloadCoordinator(this) }
    private lateinit var adapter: DownloadHistoryAdapter

    // Sequential Re-download Queue Management
    private data class QueuedDownloadItem(
        val record: DownloadRecord,
        val targetUrl: String
    )
    private val downloadQueue = ConcurrentLinkedQueue<QueuedDownloadItem>()
    private val queuedOrActiveIds = ConcurrentHashMap.newKeySet<Long>()
    private var isQueueProcessing = false
    private var metadataFetchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_history)

        val topBar = findViewById<View>(R.id.historyTopBar)
        rvHistoryList = findViewById(R.id.rvHistoryList)
        layoutEmptyHistory = findViewById(R.id.layoutEmptyHistory)
        tvEmptyHistoryDescription = findViewById(R.id.tvEmptyHistoryDescription)
        pbHistoryLoading = findViewById(R.id.pbHistoryLoading)
        tvHeaderSummary = findViewById(R.id.tvHistoryHeaderSummary)

        chipFilterAll = findViewById(R.id.chipFilterAll)
        chipFilterOnDevice = findViewById(R.id.chipFilterOnDevice)
        chipFilterDeleted = findViewById(R.id.chipFilterDeleted)

        // Handle edge-to-edge system window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootHistoryLayout)) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            topBar.setPadding(topBar.paddingLeft, statusBar.top, topBar.paddingRight, topBar.paddingBottom)
            rvHistoryList.setPadding(rvHistoryList.paddingLeft, rvHistoryList.paddingTop, rvHistoryList.paddingRight, navBar.bottom)
            insets
        }

        findViewById<ImageButton>(R.id.btnHistoryBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnHistoryRefresh).setOnClickListener {
            loadHistory(forceReconcile = true)
        }

        adapter = DownloadHistoryAdapter(
            scope = lifecycleScope,
            onViewClick = { record ->
                if (record.isDeletedFromDevice) {
                    enqueueRedownload(record)
                } else {
                    MediaViewerActivity.start(this, record.id, 0)
                }
            },
            onRedownloadClick = { record ->
                enqueueRedownload(record)
            },
            onOpenInstagramClick = { record ->
                openInInstagram(record)
            },
            onItemLongClick = { record ->
                showItemOptionsDialog(record)
            }
        )

        rvHistoryList.layoutManager = LinearLayoutManager(this)
        rvHistoryList.adapter = adapter

        chipFilterAll.setOnClickListener { setFilter(HistoryFilter.ALL) }
        chipFilterOnDevice.setOnClickListener { setFilter(HistoryFilter.ON_DEVICE) }
        chipFilterDeleted.setOnClickListener { setFilter(HistoryFilter.DELETED) }

        // Reactively observe repository transaction events
        lifecycleScope.launch {
            repo.transactionEvents.collect {
                loadHistory(forceReconcile = false)
            }
        }

        loadHistory(forceReconcile = true)
    }

    override fun onResume() {
        super.onResume()
        loadHistory(forceReconcile = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        metadataFetchJob?.cancel()
    }

    private fun setFilter(filter: HistoryFilter) {
        currentFilter = filter
        updateFilterChipStyles()
        applyFilter()
    }

    private fun updateFilterChipStyles() {
        val pink = getColor(R.color.insta_pink)
        val textSecondary = getColor(R.color.text_secondary)
        val white = getColor(R.color.white)

        fun styleChip(btn: Button, isActive: Boolean) {
            if (isActive) {
                btn.setBackgroundResource(R.drawable.card_background)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(pink)
                btn.setTextColor(white)
            } else {
                btn.setBackgroundResource(R.drawable.card_background)
                btn.backgroundTintList = null
                btn.setTextColor(textSecondary)
            }
        }

        styleChip(chipFilterAll, currentFilter == HistoryFilter.ALL)
        styleChip(chipFilterOnDevice, currentFilter == HistoryFilter.ON_DEVICE)
        styleChip(chipFilterDeleted, currentFilter == HistoryFilter.DELETED)
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            HistoryFilter.ALL -> allHistoryRecords
            HistoryFilter.ON_DEVICE -> allHistoryRecords.filter { !it.isDeletedFromDevice }
            HistoryFilter.DELETED -> allHistoryRecords.filter { it.isDeletedFromDevice }
        }

        if (filtered.isEmpty()) {
            rvHistoryList.visibility = View.GONE
            layoutEmptyHistory.visibility = View.VISIBLE
            tvEmptyHistoryDescription.text = when (currentFilter) {
                HistoryFilter.ALL -> "No saved Instagram media in your history yet."
                HistoryFilter.ON_DEVICE -> "No media currently present on local storage."
                HistoryFilter.DELETED -> "No deleted media! All your saves are safe on your device."
            }
        } else {
            layoutEmptyHistory.visibility = View.GONE
            rvHistoryList.visibility = View.VISIBLE
            adapter.submitList(filtered)
        }
    }

    private fun loadHistory(forceReconcile: Boolean) {
        lifecycleScope.launch {
            if (forceReconcile) {
                withContext(Dispatchers.IO) {
                    repo.reconcileWithMediaStore()
                }
            }

            allHistoryRecords = repo.getFullHistory()

            val totalCount = allHistoryRecords.size
            val onDeviceCount = allHistoryRecords.count { !it.isDeletedFromDevice }
            val deletedCount = allHistoryRecords.count { it.isDeletedFromDevice }

            chipFilterAll.text = "All ($totalCount)"
            chipFilterOnDevice.text = "On Device ($onDeviceCount)"
            chipFilterDeleted.text = "Deleted ($deletedCount)"

            tvHeaderSummary.text = "$totalCount items recorded • $deletedCount available to re-download"
            applyFilter()

            // Enrich creator handle & thumbnails in background for any unpopulated records
            enrichMetadataInBackground(allHistoryRecords)
        }
    }

    private fun enrichMetadataInBackground(records: List<DownloadRecord>) {
        val missing = records.filter { it.shortcode.isNotBlank() && (it.authorUsername.isNullOrBlank() || it.thumbnailPath.isNullOrBlank()) }
        if (missing.isEmpty()) return

        metadataFetchJob?.cancel()
        metadataFetchJob = lifecycleScope.launch(Dispatchers.IO) {
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)
            missing.map { rec ->
                launch {
                    semaphore.acquire()
                    try {
                        // 1. Fast local check: if a thumbnail file already exists on disk
                        val localThumb = java.io.File(filesDir, "thumbnails/${rec.shortcode}.jpg")
                        val legacyThumb = java.io.File(cacheDir, "thumbnails/${rec.shortcode}.jpg")
                        var foundLocalPath: String? = null
                        if (localThumb.exists() && localThumb.length() > 0) {
                            foundLocalPath = localThumb.absolutePath
                        } else if (legacyThumb.exists() && legacyThumb.length() > 0) {
                            try {
                                if (!localThumb.parentFile!!.exists()) localThumb.parentFile!!.mkdirs()
                                legacyThumb.copyTo(localThumb, overwrite = true)
                                foundLocalPath = localThumb.absolutePath
                            } catch (ignored: Exception) {
                                foundLocalPath = legacyThumb.absolutePath
                            }
                        }

                        // 1b. If not on disk, but the item has an available local file in Gallery, generate permanent thumbnail!
                        if (foundLocalPath == null && rec.representativeUri != null && !rec.representativeUri!!.startsWith("http")) {
                            try {
                                val isVid = rec.items.firstOrNull()?.mediaType == "VIDEO" || rec.postType.equals("REEL", ignoreCase = true)
                                val bmp = MediaThumbnailLoader.loadThumbnail(applicationContext, rec.representativeUri, isVid, 400, 400)
                                if (bmp != null) {
                                    if (!localThumb.parentFile!!.exists()) localThumb.parentFile!!.mkdirs()
                                    java.io.FileOutputStream(localThumb).use { fos ->
                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, fos)
                                    }
                                    foundLocalPath = localThumb.absolutePath
                                }
                            } catch (e: Exception) {
                                Log.d("DownloadHistory", "Local thumbnail gen notice: ${e.message}")
                            }
                        }

                        if (foundLocalPath != null && !rec.authorUsername.isNullOrBlank()) {
                            // If we already have username and thumbnail, persist and finish
                            repo.updateMetadata(rec.shortcode, thumbnailPath = foundLocalPath)
                            withContext(Dispatchers.Main) {
                                updateRecordInMemory(rec.shortcode, rec.authorUsername, foundLocalPath, rec.caption)
                            }
                            return@launch
                        }

                        // 2. Network fetch for creator handle & thumbnail
                        val meta = InstagramMetadataFetcher.fetchAndStoreMetadata(applicationContext, rec.shortcode)
                        if (meta != null) {
                            val resolvedThumb = meta.thumbnailPath ?: foundLocalPath ?: rec.thumbnailPath
                            val resolvedAuthor = meta.authorUsername ?: rec.authorUsername
                            withContext(Dispatchers.Main) {
                                updateRecordInMemory(
                                    rec.shortcode,
                                    resolvedAuthor,
                                    resolvedThumb,
                                    meta.caption ?: rec.caption
                                )
                            }
                        } else if (foundLocalPath != null) {
                            repo.updateMetadata(rec.shortcode, thumbnailPath = foundLocalPath)
                            withContext(Dispatchers.Main) {
                                updateRecordInMemory(rec.shortcode, rec.authorUsername, foundLocalPath, rec.caption)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("DownloadHistory", "enrichMetadata failed for ${rec.shortcode}: ${e.message}")
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

    private fun updateRecordInMemory(shortcode: String, author: String?, thumb: String?, caption: String?) {
        allHistoryRecords = allHistoryRecords.map {
            if (it.shortcode == shortcode) {
                it.copy(
                    authorUsername = author ?: it.authorUsername,
                    thumbnailPath = thumb ?: it.thumbnailPath,
                    caption = caption ?: it.caption
                )
            } else it
        }
        applyFilter()
    }

    private fun enqueueRedownload(record: DownloadRecord) {
        val targetUrl = record.targetDownloadUrl
        if (targetUrl.isBlank()) {
            Toast.makeText(this, "Cannot re-download: Instagram URL missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (queuedOrActiveIds.contains(record.id)) {
            Toast.makeText(this, "Item is already queued or downloading...", Toast.LENGTH_SHORT).show()
            return
        }

        queuedOrActiveIds.add(record.id)
        downloadQueue.add(QueuedDownloadItem(record, targetUrl))

        val position = downloadQueue.size
        adapter.setDownloadQueued(record.id, position)

        if (isQueueProcessing) {
            Toast.makeText(this, "Queued ${record.userFacingTitle} (Position #$position)", Toast.LENGTH_SHORT).show()
        }

        processNextInQueue()
    }

    private fun processNextInQueue() {
        if (isQueueProcessing) return
        val nextItem = downloadQueue.poll() ?: return
        isQueueProcessing = true

        val record = nextItem.record
        val targetUrl = nextItem.targetUrl

        lifecycleScope.launch {
            try {
                adapter.setDownloadProgress(record.id, percent = 0, message = "Connecting...")

                val result = coordinator.executeDirectDownload(
                    url = targetUrl,
                    recordInDb = true,
                    onStatusUpdate = { status ->
                        adapter.setDownloadProgress(record.id, percent = 0, message = status)
                    },
                    onProgress = { progress ->
                        adapter.setDownloadProgress(
                            record.id,
                            percent = progress.overallPercent,
                            message = progress.statusMessage
                        )
                    }
                )

                if (result.success || (result.skippedCount > 0 && result.completedCount == 0)) {
                    StorageAccountant.invalidateCache()
                    withContext(Dispatchers.IO) {
                        repo.reconcileWithMediaStore()
                    }
                    Toast.makeText(this@DownloadHistoryActivity, "✓ Saved ${record.userFacingTitle} to device!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@DownloadHistoryActivity, "Download failed: ${result.error ?: "Network error"}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("DownloadHistory", "Queue execution error for ${record.shortcode}", e)
                Toast.makeText(this@DownloadHistoryActivity, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                queuedOrActiveIds.remove(record.id)
                adapter.clearDownloadState(record.id)
                isQueueProcessing = false
                loadHistory(forceReconcile = true)
                // Automatically proceed to the next item in the queue
                processNextInQueue()
            }
        }
    }

    private fun openInInstagram(record: DownloadRecord) {
        val url = record.targetDownloadUrl
        if (url.isBlank()) {
            Toast.makeText(this, "Instagram URL not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Cannot open link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showItemOptionsDialog(record: DownloadRecord) {
        val options = mutableListOf<String>()
        options.add("Copy Instagram Link")
        options.add("Open in Instagram")
        options.add("Remove from History")
        if (!record.isDeletedFromDevice) {
            options.add("Delete from Storage & History")
        } else {
            options.add("Re-download to Device")
        }

        AlertDialog.Builder(this)
            .setTitle(record.userFacingTitle)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Copy Instagram Link" -> {
                        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("instagram_url", record.targetDownloadUrl))
                        Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    "Open in Instagram" -> {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(record.targetDownloadUrl))
                            startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(this, "Cannot open link", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Remove from History" -> {
                        lifecycleScope.launch {
                            repo.deleteDownload(record.id, deleteFiles = false)
                            loadHistory(forceReconcile = false)
                            Toast.makeText(this@DownloadHistoryActivity, "Removed from history", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Delete from Storage & History" -> {
                        lifecycleScope.launch {
                            repo.deleteDownload(record.id, deleteFiles = true)
                            StorageAccountant.invalidateCache()
                            loadHistory(forceReconcile = true)
                            Toast.makeText(this@DownloadHistoryActivity, "Deleted from storage", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Re-download to Device" -> {
                        enqueueRedownload(record)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
