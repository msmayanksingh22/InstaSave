package com.instadown.assist.ui

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.databinding.FragmentHistoryBinding
import com.instadown.assist.download.RedownloadQueueManager
import com.instadown.assist.util.InstagramMetadataFetcher
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class HistoryFragment : Fragment() {

    private enum class HistoryFilter { ALL, ON_DEVICE, DELETED }

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private var currentFilter = HistoryFilter.ALL
    private var allHistoryRecords: List<DownloadRecord> = emptyList()

    private val repo by lazy { DownloadRepository.getInstance(requireContext().applicationContext) }
    private lateinit var adapter: DownloadHistoryAdapter

    private var loadJob: Job? = null
    private var backgroundEnrichJob: Job? = null

    private var pendingConsentDownloadId: Long? = null
    private var pendingConsentItemIds: List<Long>? = null

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val dlId = pendingConsentDownloadId
        val itemIds = pendingConsentItemIds
        pendingConsentDownloadId = null
        pendingConsentItemIds = null

        if (result.resultCode == Activity.RESULT_OK && dlId != null && itemIds != null) {
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DownloadRepository.getInstance(appCtx).finalizeConsentedDeletion(dlId, itemIds)
                    StorageAccountant.invalidateCache()
                }
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "✓ Media deleted from device. Post preserved in History!", Toast.LENGTH_SHORT).show()
                    loadHistory(forceReconcile = true)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            pendingConsentDownloadId = savedInstanceState.getLong(KEY_PENDING_DL_ID, -1L).takeIf { it != -1L }
            pendingConsentItemIds = savedInstanceState.getLongArray(KEY_PENDING_ITEM_IDS)?.toList()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingConsentDownloadId?.let { outState.putLong(KEY_PENDING_DL_ID, it) }
        pendingConsentItemIds?.let { outState.putLongArray(KEY_PENDING_ITEM_IDS, it.toLongArray()) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        setupFragmentResultListeners()

        // Collect RedownloadQueueManager state live across the application
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                RedownloadQueueManager.states.collect { states ->
                    adapter.updateProgressStates(states)
                }
            }
        }

        // Reactively observe repository transaction events with debouncing
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.transactionEvents
                    .conflate()
                    .collectLatest { event ->
                        if (event.transactionId == "RECONCILED") return@collectLatest
                        delay(150)
                        loadHistory(forceReconcile = false)
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Single authoritative entry point for history loading and reconciliation
        loadHistory(forceReconcile = true)
    }

    private fun setupRecyclerView() {
        adapter = DownloadHistoryAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onViewClick = { record ->
                if (record.isDeletedFromDevice) {
                    showReDownloadDialogOrSheet(record)
                } else {
                    MediaViewerActivity.start(requireContext(), record.id, 0)
                }
            },
            onRedownloadClick = { record ->
                showReDownloadDialogOrSheet(record)
            },
            onOpenInstagramClick = { record ->
                openInInstagram(record)
            },
            onItemLongClick = { record ->
                showItemOptionsDialog(record)
            }
        )

        binding.rvHistoryList.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistoryList.setHasFixedSize(true)
        (binding.rvHistoryList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.rvHistoryList.adapter = adapter
        setupSwipeGestures()
    }

    private fun setupSwipeGestures() {
        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f * resources.displayMetrics.density
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            private val shareIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_share)
            private val optionsIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_more_vert)
            private val cornerRadius = 16f * resources.displayMetrics.density

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.35f

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                getDefaultUIUtil().clearView(viewHolder.itemView)
                viewHolder.itemView.translationX = 0f
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val record = adapter.getItem(position)
                getDefaultUIUtil().clearView(viewHolder.itemView)
                viewHolder.itemView.translationX = 0f
                if (record == null) return
                adapter.notifyItemChanged(position)
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                if (direction == ItemTouchHelper.RIGHT) {
                    try {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, record.sourceUrl)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Link"))
                    } catch (_: Exception) {
                        Toast.makeText(requireContext(), "Failed to share link", Toast.LENGTH_SHORT).show()
                    }
                } else if (direction == ItemTouchHelper.LEFT) {
                    showItemOptionsDialog(record)
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val itemTop = itemView.top.toFloat()
                val itemBottom = itemView.bottom.toFloat()
                val itemHeight = itemBottom - itemTop

                if (dX > 0) {
                    bgPaint.color = Color.parseColor("#16233B")
                    val bgRect = RectF(
                        itemView.left.toFloat(),
                        itemTop + 8f,
                        itemView.left + dX,
                        itemBottom - 8f
                    )
                    c.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

                    shareIcon?.let { icon ->
                        val iconSize = (24f * resources.displayMetrics.density).toInt()
                        val iconTop = (itemTop + (itemHeight - iconSize) / 2f).toInt()
                        val iconLeft = (itemView.left + 24f * resources.displayMetrics.density).toInt()
                        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
                        icon.setTint(Color.parseColor("#3897F0"))
                        icon.draw(c)

                        c.drawText(
                            "Share",
                            iconLeft + iconSize / 2f,
                            iconTop + iconSize + 14f * resources.displayMetrics.density,
                            textPaint
                        )
                    }
                } else if (dX < 0) {
                    bgPaint.color = Color.parseColor("#2A1D34")
                    val bgRect = RectF(
                        itemView.right + dX,
                        itemTop + 8f,
                        itemView.right.toFloat(),
                        itemBottom - 8f
                    )
                    c.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)

                    optionsIcon?.let { icon ->
                        val iconSize = (24f * resources.displayMetrics.density).toInt()
                        val iconTop = (itemTop + (itemHeight - iconSize) / 2f).toInt()
                        val iconRight = (itemView.right - 24f * resources.displayMetrics.density).toInt()
                        icon.setBounds(iconRight - iconSize, iconTop, iconRight, iconTop + iconSize)
                        icon.setTint(ContextCompat.getColor(requireContext(), R.color.insta_pink))
                        icon.draw(c)

                        c.drawText(
                            "Options",
                            iconRight - iconSize / 2f,
                            iconTop + iconSize + 14f * resources.displayMetrics.density,
                            textPaint
                        )
                    }
                }

                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvHistoryList)
    }

    private fun setupListeners() {
        binding.swipeRefreshHistory.setColorSchemeResources(R.color.insta_pink, R.color.insta_purple, R.color.insta_amber)
        binding.swipeRefreshHistory.setProgressBackgroundColorSchemeResource(R.color.card_bg)
        binding.swipeRefreshHistory.setOnRefreshListener {
            loadHistory(forceReconcile = true)
        }

        binding.btnHistoryRefresh.setOnClickListener {
            loadHistory(forceReconcile = true)
        }

        binding.btnAddLink.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_home)
        }
    }

    private fun setupFilterChips() {
        binding.chipFilterAll.setOnClickListener { setFilter(HistoryFilter.ALL) }
        binding.chipFilterOnDevice.setOnClickListener { setFilter(HistoryFilter.ON_DEVICE) }
        binding.chipFilterDeleted.setOnClickListener { setFilter(HistoryFilter.DELETED) }
        updateFilterChipStyles()
    }

    private fun setupFragmentResultListeners() {
        // Rotation-proof listener for carousel differential restore confirmation
        childFragmentManager.setFragmentResultListener(
            ReDownloadSelectionBottomSheet.REQUEST_KEY_RESTORE,
            viewLifecycleOwner
        ) { _, bundle ->
            val dlId = bundle.getLong(ReDownloadSelectionBottomSheet.EXTRA_DOWNLOAD_ID, -1L)
            val indices = bundle.getIntArray(ReDownloadSelectionBottomSheet.EXTRA_INDICES)?.toSet()
            if (dlId != -1L) {
                val record = allHistoryRecords.firstOrNull { it.id == dlId }
                if (record != null) {
                    enqueueRedownload(record, indices)
                }
            }
        }

        // Rotation-proof listener for options sheet actions
        childFragmentManager.setFragmentResultListener(
            HistoryItemOptionsBottomSheet.REQUEST_KEY_OPTIONS,
            viewLifecycleOwner
        ) { _, bundle ->
            when (bundle.getString(HistoryItemOptionsBottomSheet.ACTION_TYPE)) {
                HistoryItemOptionsBottomSheet.ACTION_HISTORY_CHANGED -> {
                    loadHistory(forceReconcile = true)
                }
                HistoryItemOptionsBottomSheet.ACTION_REDOWNLOAD -> {
                    val recId = bundle.getLong(HistoryItemOptionsBottomSheet.EXTRA_RECORD_ID, -1L)
                    val record = allHistoryRecords.firstOrNull { it.id == recId }
                    if (record != null) {
                        showReDownloadDialogOrSheet(record)
                    }
                }
                HistoryItemOptionsBottomSheet.ACTION_CONSENT_REQUIRED -> {
                    val dlId = bundle.getLong(HistoryItemOptionsBottomSheet.EXTRA_RECORD_ID, -1L)
                    val sender = androidx.core.os.BundleCompat.getParcelable(
                        bundle,
                        HistoryItemOptionsBottomSheet.EXTRA_INTENT_SENDER,
                        IntentSender::class.java
                    )
                    val itemIds = bundle.getLongArray(HistoryItemOptionsBottomSheet.EXTRA_ITEM_IDS)?.toList()
                    if (dlId != -1L && sender != null && itemIds != null) {
                        launchDeleteConsent(dlId, sender, itemIds)
                    }
                }
            }
        }
    }

    private fun setFilter(filter: HistoryFilter) {
        currentFilter = filter
        updateFilterChipStyles()
        applyFilter()
    }

    private fun updateFilterChipStyles() {
        val pink = ContextCompat.getColor(requireContext(), R.color.insta_pink)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val activeText = ContextCompat.getColor(requireContext(), R.color.bg_dark)
        val cardBg = ContextCompat.getColor(requireContext(), R.color.card_bg)

        fun styleChip(btn: MaterialButton, isActive: Boolean) {
            btn.strokeColor = ContextCompat.getColorStateList(requireContext(), if (isActive) R.color.insta_pink else R.color.card_stroke)
            btn.isSelected = isActive
            btn.setTextColor(if (isActive) activeText else muted)
            btn.backgroundTintList = ColorStateList.valueOf(if (isActive) pink else cardBg)
        }

        styleChip(binding.chipFilterAll, currentFilter == HistoryFilter.ALL)
        styleChip(binding.chipFilterOnDevice, currentFilter == HistoryFilter.ON_DEVICE)
        styleChip(binding.chipFilterDeleted, currentFilter == HistoryFilter.DELETED)
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            HistoryFilter.ALL -> allHistoryRecords
            HistoryFilter.ON_DEVICE -> allHistoryRecords.filter { !it.isDeletedFromDevice }
            HistoryFilter.DELETED -> allHistoryRecords.filter { it.isDeletedFromDevice }
        }

        if (filtered.isEmpty()) {
            binding.swipeRefreshHistory.visibility = View.GONE
            binding.layoutEmptyHistory.visibility = View.VISIBLE
            binding.tvEmptyHistoryDescription.text = when (currentFilter) {
                HistoryFilter.ALL -> "No saved Instagram media in your history yet."
                HistoryFilter.ON_DEVICE -> "No media currently present on local storage."
                HistoryFilter.DELETED -> "No deleted media! All your saves are safe on your device."
            }
        } else {
            binding.layoutEmptyHistory.visibility = View.GONE
            binding.swipeRefreshHistory.visibility = View.VISIBLE
            adapter.submitList(filtered)
        }
    }

    private fun loadHistory(forceReconcile: Boolean) {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            // 1. Fast immediate display from SQLite: 0ms UI delay!
            val records = withContext(Dispatchers.IO) { repo.getFullHistory() }
            if (_binding == null || !isAdded) return@launch

            allHistoryRecords = records
            val totalCount = records.size
            val onDeviceCount = records.count { !it.isDeletedFromDevice }
            val deletedCount = records.count { it.isDeletedFromDevice }

            binding.chipFilterAll.text = "All ($totalCount)"
            binding.chipFilterOnDevice.text = "On Device ($onDeviceCount)"
            binding.chipFilterDeleted.text = "Deleted ($deletedCount)"

            binding.tvHistoryHeaderSummary.text = historySummary(totalCount, deletedCount)
            binding.swipeRefreshHistory.isRefreshing = false
            applyFilter()

            // 2. Background MediaStore reconciliation
            if (forceReconcile) {
                launch(Dispatchers.IO) {
                    val changed = repo.reconcileWithMediaStore()
                    if (changed > 0) {
                        val refreshed = repo.getFullHistory()
                        withContext(Dispatchers.Main) {
                            if (_binding == null || !isAdded) return@withContext
                            allHistoryRecords = refreshed
                            val newTotal = refreshed.size
                            val newOnDevice = refreshed.count { !it.isDeletedFromDevice }
                            val newDeleted = refreshed.count { it.isDeletedFromDevice }
                            binding.chipFilterAll.text = "All ($newTotal)"
                            binding.chipFilterOnDevice.text = "On Device ($newOnDevice)"
                            binding.chipFilterDeleted.text = "Deleted ($newDeleted)"
                            binding.tvHistoryHeaderSummary.text = historySummary(newTotal, newDeleted)
                            applyFilter()
                        }
                    }
                }
            }

            // 3. Unified, non-overlapping background pass for thumbnails & creator handles
            startUnifiedBackgroundEnrichment(records)
        }
    }

    private fun startUnifiedBackgroundEnrichment(records: List<DownloadRecord>) {
        backgroundEnrichJob?.cancel()
        val appCtx = requireContext().applicationContext

        backgroundEnrichJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val thumbDir = File(appCtx.filesDir, "thumbnails")
            if (!thumbDir.exists()) thumbDir.mkdirs()

            val targets = records.filter { rec ->
                rec.shortcode.isNotBlank() && (
                    rec.thumbnailPath.isNullOrBlank() ||
                    rec.authorUsername.isNullOrBlank() ||
                    !File(thumbDir, "${rec.shortcode}.jpg").exists()
                )
            }
            if (targets.isEmpty()) return@launch

            val coordinator = com.instadown.assist.download.InstagramDownloadCoordinator(appCtx)
            val semaphore = Semaphore(3)
            val updatedRecords = mutableMapOf<String, Triple<String?, String?, String?>>() // shortcode -> (author, thumb, caption)

            targets.forEach { rec ->
                launch {
                    semaphore.acquire()
                    try {
                        val thumbFile = File(thumbDir, "${rec.shortcode}.jpg")
                        val legacyFile = File(appCtx.cacheDir, "thumbnails/${rec.shortcode}.jpg")
                        var foundThumbPath: String? = null

                        if (thumbFile.exists() && thumbFile.length() > 0) {
                            foundThumbPath = thumbFile.absolutePath
                        } else if (legacyFile.exists() && legacyFile.length() > 0) {
                            try {
                                legacyFile.copyTo(thumbFile, overwrite = true)
                                foundThumbPath = thumbFile.absolutePath
                            } catch (_: Exception) {
                                foundThumbPath = legacyFile.absolutePath
                            }
                        } else {
                            val repUri = rec.representativeUri
                            if (!repUri.isNullOrBlank() && !repUri.startsWith("http")) {
                                val isVid = rec.items.firstOrNull()?.mediaType == "VIDEO" || rec.postType.equals("REEL", ignoreCase = true)
                                foundThumbPath = repo.extractAndSaveThumbnail(rec.shortcode, repUri, isVid)
                            }
                        }

                        var author = rec.authorUsername
                        var caption = rec.caption

                        // If still missing handle or thumbnail, fetch from Instagram metadata
                        if (foundThumbPath == null || author.isNullOrBlank()) {
                            val meta = InstagramMetadataFetcher.fetchAndStoreMetadata(appCtx, rec.shortcode)
                            if (meta != null) {
                                foundThumbPath = meta.thumbnailPath ?: foundThumbPath
                                author = meta.authorUsername ?: author
                                caption = meta.caption ?: caption
                            }
                        }

                        // Last resort for deleted post thumbnail: fast probe via resolver
                        if (foundThumbPath == null) {
                            val targetUrl = rec.targetDownloadUrl.ifBlank { "https://www.instagram.com/p/${rec.shortcode}/" }
                            val res = coordinator.resolver.resolve(targetUrl, validateCdn = false)
                            if (res.success && res.items.isNotEmpty()) {
                                val item = res.items.first()
                                val thumbUrl = item.thumbnailUrl ?: item.mediaUrl
                                if (!thumbUrl.isNullOrBlank()) {
                                    val saved = repo.extractAndSaveThumbnail(
                                        shortcode = rec.shortcode,
                                        uriStr = thumbUrl,
                                        isVideo = item.mediaType == com.instadown.assist.resolver.model.MediaType.VIDEO
                                    )
                                    if (saved != null) {
                                        foundThumbPath = saved
                                    }
                                }
                            }
                        }

                        if (foundThumbPath != null || author != rec.authorUsername || caption != rec.caption) {
                            repo.updateMetadata(rec.shortcode, authorUsername = author, thumbnailPath = foundThumbPath, caption = caption)
                            synchronized(updatedRecords) {
                                updatedRecords[rec.shortcode] = Triple(author, foundThumbPath, caption)
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        Log.d("HistoryFragment", "Background enrich notice for ${rec.shortcode}: ${e.message}")
                    } finally {
                        semaphore.release()
                    }
                }
            }

            // Batch UI update at completion
            delay(300)
            if (updatedRecords.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    if (_binding == null || !isAdded) return@withContext
                    allHistoryRecords = allHistoryRecords.map { r ->
                        val update = updatedRecords[r.shortcode]
                        if (update != null) {
                            r.copy(
                                authorUsername = update.first ?: r.authorUsername,
                                thumbnailPath = update.second ?: r.thumbnailPath,
                                caption = update.third ?: r.caption
                            )
                        } else r
                    }
                    applyFilter()
                }
            }
        }
    }

    private fun showReDownloadDialogOrSheet(record: DownloadRecord) {
        if (record.items.size > 1) {
            val sheet = ReDownloadSelectionBottomSheet.newInstance(record.id)
            sheet.onConfirmRestore = { targetRecord, targetIndices ->
                enqueueRedownload(targetRecord, targetIndices)
            }
            sheet.show(childFragmentManager, "ReDownloadSelectionBottomSheet")
        } else {
            enqueueRedownload(record, record.items.singleOrNull()?.let { setOf(it.itemIndex) })
        }
    }

    private fun enqueueRedownload(record: DownloadRecord, targetIndices: Set<Int>? = null) {
        val targetUrl = record.targetDownloadUrl
        if (targetUrl.isBlank()) {
            Toast.makeText(requireContext(), "Cannot re-download: Instagram URL missing", Toast.LENGTH_SHORT).show()
            return
        }

        val enqueued = RedownloadQueueManager.enqueue(
            appContext = requireContext(),
            record = record,
            targetIndices = targetIndices
        )

        if (enqueued) {
            Toast.makeText(requireContext(), "Queued ${record.userFacingTitle} for download", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "Item is already queued or downloading...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openInInstagram(record: DownloadRecord) {
        val url = record.targetDownloadUrl
        if (url.isBlank()) {
            Toast.makeText(requireContext(), "Instagram URL not available", Toast.LENGTH_SHORT).show()
            return
        }
        ProfileExplorerActivity.start(requireContext(), url)
    }

    private fun showItemOptionsDialog(record: DownloadRecord) {
        val sheet = HistoryItemOptionsBottomSheet.newInstance(record.id)
        sheet.setListener(object : HistoryItemOptionsBottomSheet.Listener {
            override fun onHistoryChanged() {
                loadHistory(forceReconcile = true)
            }
            override fun onReDownloadRequested(rec: DownloadRecord) {
                showReDownloadDialogOrSheet(rec)
            }
            override fun onConsentRequired(downloadId: Long, intentSender: IntentSender, itemIds: List<Long>) {
                launchDeleteConsent(downloadId, intentSender, itemIds)
            }
        })
        sheet.show(childFragmentManager, HistoryItemOptionsBottomSheet.TAG)
    }

    private fun launchDeleteConsent(downloadId: Long, intentSender: IntentSender, itemIds: List<Long>) {
        pendingConsentDownloadId = downloadId
        pendingConsentItemIds = itemIds
        try {
            deleteConsentLauncher.launch(
                IntentSenderRequest.Builder(intentSender).build()
            )
        } catch (e: Exception) {
            Log.e("HistoryFragment", "Failed to launch delete consent dialog: ${e.message}", e)
            pendingConsentDownloadId = null
            pendingConsentItemIds = null
            Toast.makeText(requireContext(), "Failed to request permission to delete media", Toast.LENGTH_SHORT).show()
        }
    }

    private fun historySummary(posts: Int, deleted: Int): String {
        val count = resources.getQuantityString(R.plurals.saved_post_count, posts, posts)
        return if (deleted == 0) count else getString(R.string.history_restore_summary, count, deleted)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        loadJob?.cancel()
        backgroundEnrichJob?.cancel()
        binding.rvHistoryList.adapter = null
        _binding = null
    }

    companion object {
        private const val KEY_PENDING_DL_ID = "key_pending_consent_dl_id"
        private const val KEY_PENDING_ITEM_IDS = "key_pending_consent_item_ids"
    }
}
