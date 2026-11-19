package com.instadown.assist.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DeleteStorageResult
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.data.db.TransactionStatus
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryActivity : AppCompatActivity() {

    private enum class FilterType { ALL, PHOTOS, REELS, CAROUSELS }

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var chipAll: Button
    private lateinit var chipPhotos: Button
    private lateinit var chipReels: Button
    private lateinit var chipCarousels: Button

    private lateinit var galleryTopBar: LinearLayout
    private lateinit var gallerySelectionBar: LinearLayout
    private lateinit var btnStartSelection: Button
    private lateinit var btnCloseSelection: ImageButton
    private lateinit var tvSelectionCount: TextView
    private lateinit var btnSelectAllToggle: Button
    private lateinit var btnBatchShare: ImageButton
    private lateinit var btnBatchDelete: ImageButton

    private var currentFilter = FilterType.ALL
    private var allTransactions: List<DownloadRecord> = emptyList()
    private var lastReturnedPosition = -1

    override fun onActivityReenter(resultCode: Int, data: Intent?) {
        super.onActivityReenter(resultCode, data)
        val returnPos = data?.getIntExtra(SharedMediaTransition.EXTRA_RETURN_POSITION, -1) ?: -1
        if (returnPos >= 0) {
            lastReturnedPosition = returnPos
            rvGallery.scrollToPosition(returnPos)
        }
    }

    private val repo by lazy { DownloadRepository.getInstance(this) }
    private val mediaStoreObserver by lazy {
        com.instadown.assist.data.MediaStoreSyncObserver(this, lifecycleScope)
    }
    private lateinit var adapter: GalleryAdapter

    private var pendingConsentDownloadId: Long? = null
    private var pendingConsentItemIds: List<Long>? = null

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val dlId = pendingConsentDownloadId
        val itemIds = pendingConsentItemIds
        pendingConsentDownloadId = null
        pendingConsentItemIds = null
        if (result.resultCode == RESULT_OK && dlId != null && itemIds != null) {
            lifecycleScope.launch {
                repo.finalizeConsentedDeletion(dlId, itemIds)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@GalleryActivity,
                        "✓ Media deleted from device. Post preserved in History!",
                        Toast.LENGTH_SHORT
                    ).show()
                    loadGallery()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_gallery)

        galleryTopBar = findViewById(R.id.galleryTopBar)
        gallerySelectionBar = findViewById(R.id.gallerySelectionBar)
        btnStartSelection = findViewById(R.id.btnStartSelection)
        btnCloseSelection = findViewById(R.id.btnCloseSelection)
        tvSelectionCount = findViewById(R.id.tvSelectionCount)
        btnSelectAllToggle = findViewById(R.id.btnSelectAllToggle)
        btnBatchShare = findViewById(R.id.btnBatchShare)
        btnBatchDelete = findViewById(R.id.btnBatchDelete)

        rvGallery = findViewById(R.id.rvGallery)
        tvEmpty = findViewById(R.id.tvEmptyGallery)
        chipAll = findViewById(R.id.chipAll)
        chipPhotos = findViewById(R.id.chipPhotos)
        chipReels = findViewById(R.id.chipReels)
        chipCarousels = findViewById(R.id.chipCarousels)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootGalleryLayout)) { _, windowInsets ->
            val statusBar = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            galleryTopBar.setPadding(galleryTopBar.paddingLeft, statusBar.top, galleryTopBar.paddingRight, galleryTopBar.paddingBottom)
            gallerySelectionBar.setPadding(gallerySelectionBar.paddingLeft, statusBar.top, gallerySelectionBar.paddingRight, gallerySelectionBar.paddingBottom)
            rvGallery.setPadding(rvGallery.paddingLeft, rvGallery.paddingTop, rvGallery.paddingRight, navBar.bottom)
            windowInsets
        }

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnAddLink).setOnClickListener {
            DialogManualDownload.show(this)
        }

        registerPredictiveBackMotion(
            rootContainer = findViewById(R.id.rootGalleryLayout),
            shouldInterceptBack = { adapter.isSelectionMode },
            onInterceptedCommit = {
                exitSelectionMode()
            }
        )

        adapter = GalleryAdapter(
            onItemClick = { record, thumbView, position ->
                if (adapter.isSelectionMode) {
                    toggleSelection(record.id)
                } else {
                    lastReturnedPosition = position
                    SharedMediaTransition.launchViewer(this, thumbView, record.id, position)
                }
            },
            onItemLongClick = { record ->
                if (!adapter.isSelectionMode) {
                    enterSelectionMode(record.id)
                } else {
                    toggleSelection(record.id)
                }
            }
        )

        rvGallery.layoutManager = GridLayoutManager(this, 2)
        rvGallery.adapter = adapter

        SharedMediaTransition.bindGalleryExitRemapper(
            activity = this,
            recyclerView = rvGallery,
            getCurrentReturnPosition = { lastReturnedPosition },
            getMediaIdAt = { pos -> adapter.getRecordAt(pos)?.id ?: -1L }
        )

        btnStartSelection.setOnClickListener {
            if (adapter.itemCount > 0) {
                enterSelectionMode(null)
            }
        }

        btnCloseSelection.setOnClickListener {
            exitSelectionMode()
        }

        btnSelectAllToggle.setOnClickListener {
            val displayedRecords = getFilteredList()
            if (adapter.selectedIds.size == displayedRecords.size) {
                adapter.selectedIds.clear()
            } else {
                adapter.selectedIds.clear()
                adapter.selectedIds.addAll(displayedRecords.map { it.id })
            }
            updateSelectionBar()
            adapter.notifyDataSetChanged()
        }

        btnBatchShare.setOnClickListener {
            executeBatchShare()
        }

        btnBatchDelete.setOnClickListener {
            executeBatchDelete()
        }

        chipAll.setOnClickListener { setFilter(FilterType.ALL) }
        chipPhotos.setOnClickListener { setFilter(FilterType.PHOTOS) }
        chipReels.setOnClickListener { setFilter(FilterType.REELS) }
        chipCarousels.setOnClickListener { setFilter(FilterType.CAROUSELS) }

        lifecycleScope.launch {
            repo.transactionEvents.collect {
                loadGallery()
            }
        }

        loadGallery()
    }

    override fun onStart() {
        super.onStart()
        mediaStoreObserver.register()
    }

    override fun onStop() {
        super.onStop()
        mediaStoreObserver.unregister()
    }

    override fun onResume() {
        super.onResume()
        loadGallery()
        lifecycleScope.launch(Dispatchers.IO) {
            repo.reconcileWithMediaStore()
        }
    }

    private fun enterSelectionMode(initialSelectedId: Long?) {
        adapter.isSelectionMode = true
        adapter.selectedIds.clear()
        if (initialSelectedId != null) {
            adapter.selectedIds.add(initialSelectedId)
        }
        galleryTopBar.visibility = View.GONE
        gallerySelectionBar.visibility = View.VISIBLE
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        adapter.isSelectionMode = false
        adapter.selectedIds.clear()
        gallerySelectionBar.visibility = View.GONE
        galleryTopBar.visibility = View.VISIBLE
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(id: Long) {
        if (adapter.selectedIds.contains(id)) {
            adapter.selectedIds.remove(id)
        } else {
            adapter.selectedIds.add(id)
        }
        if (adapter.selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionBar()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedIds.size
        tvSelectionCount.text = "$count selected"
        val displayedRecords = getFilteredList()
        btnSelectAllToggle.text = if (count == displayedRecords.size) "Clear" else "Select All"
    }

    private fun executeBatchShare() {
        val selectedRecords = allTransactions.filter { adapter.selectedIds.contains(it.id) }
        val allUris = selectedRecords.flatMap { record ->
            record.items.filter { it.status == com.instadown.assist.data.db.ItemStatus.AVAILABLE && it.mediaUri != null }
                .mapNotNull { try { Uri.parse(it.mediaUri) } catch (e: Exception) { null } }
        }

        if (allUris.isEmpty()) {
            Toast.makeText(this, "No media files available to share", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = if (allUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, allUris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(allUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(shareIntent, "Share ${allUris.size} Media Item(s)"))
    }

    private fun executeBatchDelete() {
        val count = adapter.selectedIds.size
        if (count == 0) return

        val targetIds = adapter.selectedIds.toList()

        val sheet = GalleryBatchOptionsBottomSheet.newInstance(targetIds)
        sheet.setListener(object : GalleryBatchOptionsBottomSheet.Listener {
            override fun onFreeUpStorage(selectedIds: List<Long>) {
                lifecycleScope.launch {
                    var freedCount = 0
                    for (id in selectedIds) {
                        when (val res = repo.deleteDeviceStorageOnly(id)) {
                            is DeleteStorageResult.Completed -> {
                                freedCount += res.deletedCount
                            }
                            is DeleteStorageResult.RequiresUserConsent -> {
                                pendingConsentDownloadId = res.downloadId
                                pendingConsentItemIds = res.pendingItemIds
                                try {
                                    deleteConsentLauncher.launch(
                                        IntentSenderRequest.Builder(res.intentSender).build()
                                    )
                                } catch (e: Exception) {
                                    Log.e("GalleryActivity", "Failed to launch delete consent: ${e.message}")
                                }
                            }
                            is DeleteStorageResult.Failed -> {
                                Log.w("GalleryActivity", "deleteDeviceStorageOnly failed for $id: ${res.reason}")
                            }
                        }
                    }
                    StorageAccountant.invalidateCache()
                    Toast.makeText(
                        this@GalleryActivity,
                        "✓ Freed up storage for $count item(s). Posts preserved in History!",
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                    loadGallery()
                }
            }

            override fun onPurgePermanently(selectedIds: List<Long>) {
                lifecycleScope.launch {
                    var totalDeleted = 0
                    for (id in selectedIds) {
                        val res = repo.deleteDownload(id, deleteFiles = true)
                        totalDeleted += res.filesDeleted
                    }
                    StorageAccountant.invalidateCache()
                    Toast.makeText(
                        this@GalleryActivity,
                        "Permanently erased $count item(s) from history and storage",
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                    loadGallery()
                }
            }
        })
        sheet.show(supportFragmentManager, GalleryBatchOptionsBottomSheet.TAG)
    }

    private fun setFilter(filter: FilterType) {
        currentFilter = filter
        val pink = getColor(R.color.insta_pink)
        val muted = getColor(R.color.text_secondary)
        val white = getColor(R.color.white)

        fun styleChip(btn: Button, isActive: Boolean) {
            if (isActive) {
                btn.setBackgroundResource(R.drawable.card_background)
                btn.backgroundTintList = android.content.res.ColorStateList.valueOf(pink)
                btn.setTextColor(white)
            } else {
                btn.setBackgroundResource(R.drawable.card_background)
                btn.backgroundTintList = null
                btn.setTextColor(muted)
            }
        }

        styleChip(chipAll, currentFilter == FilterType.ALL)
        styleChip(chipPhotos, currentFilter == FilterType.PHOTOS)
        styleChip(chipReels, currentFilter == FilterType.REELS)
        styleChip(chipCarousels, currentFilter == FilterType.CAROUSELS)

        applyFilter()
    }

    private fun getFilteredList(): List<DownloadRecord> {
        return when (currentFilter) {
            FilterType.ALL -> allTransactions
            FilterType.PHOTOS -> allTransactions.filter { !it.isCarousel && !it.postType.equals("REEL", ignoreCase = true) && it.items.firstOrNull()?.mediaType != "VIDEO" }
            FilterType.REELS -> allTransactions.filter { it.postType.equals("REEL", ignoreCase = true) || it.items.firstOrNull()?.mediaType == "VIDEO" }
            FilterType.CAROUSELS -> allTransactions.filter { it.isCarousel }
        }
    }

    private fun applyFilter() {
        val filtered = getFilteredList()
        if (filtered.isEmpty()) {
            rvGallery.visibility = View.GONE
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            rvGallery.visibility = View.VISIBLE
            adapter.submitList(filtered)
        }
    }

    private fun loadGallery() {
        lifecycleScope.launch {
            allTransactions = repo.getAllTransactions()
            btnStartSelection.visibility = if (allTransactions.isEmpty()) View.GONE else View.VISIBLE
            applyFilter()
        }
    }

    private inner class GalleryAdapter(
        private val onItemClick: (DownloadRecord, ImageView, Int) -> Unit,
        private val onItemLongClick: (DownloadRecord) -> Unit
    ) : RecyclerView.Adapter<GalleryViewHolder>() {

        private val items = mutableListOf<DownloadRecord>()
        var isSelectionMode = false
        val selectedIds = mutableSetOf<Long>()

        fun submitList(newItems: List<DownloadRecord>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getRecordAt(position: Int): DownloadRecord? = items.getOrNull(position)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_card, parent, false)
            return GalleryViewHolder(view)
        }

        override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
            val record = items[position]
            holder.tvDate.text = record.formattedDate
            holder.tvTitle.text = record.userFacingTitle
            holder.tvStatus.text = record.userFacingStatus

            val statusColor = when {
                record.isPartiallyDeleted -> R.color.status_amber
                record.status == TransactionStatus.PARTIAL_SUCCESS -> R.color.status_amber
                else -> R.color.status_green
            }
            holder.tvStatus.setTextColor(getColor(statusColor))

            val isVideo = record.items.firstOrNull()?.mediaType == "VIDEO" || record.postType.equals("REEL", ignoreCase = true)

            if (record.isCarousel) {
                holder.tvTypeBadge.text = "CAROUSEL"
                holder.ivTypeIcon.setImageResource(R.drawable.ic_carousel)
                holder.tvCarouselBadge.text = "${record.availableItemCount} items"
                holder.tvCarouselBadge.visibility = View.VISIBLE
                holder.ivPlayIndicator.visibility = View.GONE
            } else if (isVideo) {
                holder.tvTypeBadge.text = "REEL"
                holder.ivTypeIcon.setImageResource(R.drawable.ic_video)
                holder.tvCarouselBadge.visibility = View.GONE
                holder.ivPlayIndicator.visibility = View.VISIBLE
            } else {
                holder.tvTypeBadge.text = "PHOTO"
                holder.ivTypeIcon.setImageResource(R.drawable.ic_image)
                holder.tvCarouselBadge.visibility = View.GONE
                holder.ivPlayIndicator.visibility = View.GONE
            }

            // Selection Mode Visuals
            if (isSelectionMode) {
                holder.ivSelectBadge.visibility = View.VISIBLE
                val isSelected = selectedIds.contains(record.id)
                if (isSelected) {
                    holder.ivSelectBadge.setImageResource(R.drawable.ic_check_circle)
                    holder.viewSelectedOverlay.visibility = View.VISIBLE
                } else {
                    holder.ivSelectBadge.setImageResource(R.drawable.ic_circle_outline)
                    holder.viewSelectedOverlay.visibility = View.GONE
                }
            } else {
                holder.ivSelectBadge.visibility = View.GONE
                holder.viewSelectedOverlay.visibility = View.GONE
            }

            MediaThumbnailLoader.loadInto(
                context = this@GalleryActivity,
                uriString = record.representativeUri,
                imageView = holder.ivThumbnail,
                isVideo = isVideo,
                scope = lifecycleScope
            )

            holder.itemView.setOnClickListener { onItemClick(record, holder.ivThumbnail, position) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(record)
                true
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivGalleryThumbnail)
        val ivTypeIcon: ImageView = view.findViewById(R.id.ivGalleryTypeIcon)
        val tvTypeBadge: TextView = view.findViewById(R.id.tvGalleryTypeBadge)
        val tvCarouselBadge: TextView = view.findViewById(R.id.tvCarouselCountBadge)
        val ivPlayIndicator: ImageView = view.findViewById(R.id.ivPlayIndicator)
        val viewSelectedOverlay: View = view.findViewById(R.id.viewSelectedOverlay)
        val ivSelectBadge: ImageView = view.findViewById(R.id.ivSelectBadge)
        val tvTitle: TextView = view.findViewById(R.id.tvGalleryTitle)
        val tvDate: TextView = view.findViewById(R.id.tvGalleryDate)
        val tvStatus: TextView = view.findViewById(R.id.tvGalleryStatus)
    }
}
