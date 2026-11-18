package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.download.InstagramDownloadCoordinator
import com.instadown.assist.resolver.CompositeInstagramResolver
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.PostType
import com.instadown.assist.resolver.model.ResolvedMedia
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaSelectionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "extra_instagram_url"

        fun start(context: Context, url: String) {
            val intent = Intent(context, MediaSelectionActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnToggleSelectAll: Button
    private lateinit var rvMediaTiles: RecyclerView
    private lateinit var btnDownloadSelected: Button
    private lateinit var pbDownloadProgress: ProgressBar
    private lateinit var tvDownloadStatus: TextView

    private val resolver by lazy { CompositeInstagramResolver(this) }
    private val coordinator by lazy { InstagramDownloadCoordinator(this, resolver = resolver) }

    private var targetUrl: String = ""
    private var resolvedItems: List<ResolvedMedia> = emptyList()
    private val selectedIndices = mutableSetOf<Int>()
    private var isDownloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_selection)

        targetUrl = intent.getStringExtra(EXTRA_URL)
            ?: intent.getStringExtra("EXTRA_INSTAGRAM_URL")
            ?: ""
        if (targetUrl.isBlank()) {
            Toast.makeText(this, "No Instagram link provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val topBar = findViewById<View>(R.id.selectionTopBar)
        val bottomBar = findViewById<View>(R.id.selectionBottomBar)
        tvTitle = findViewById(R.id.tvSelectionTitle)
        tvSubtitle = findViewById(R.id.tvSelectionSubtitle)
        btnToggleSelectAll = findViewById(R.id.btnToggleSelectAll)
        rvMediaTiles = findViewById(R.id.rvMediaTiles)
        btnDownloadSelected = findViewById(R.id.btnDownloadSelected)
        pbDownloadProgress = findViewById(R.id.pbDownloadProgress)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)

        // Handle edge-to-edge window insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootMediaSelection)) { _, windowInsets ->
            val statusBar = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            topBar.setPadding(topBar.paddingLeft, statusBar.top + 8, topBar.paddingRight, topBar.paddingBottom)
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, navBar.bottom + 8)
            windowInsets
        }

        findViewById<ImageButton>(R.id.btnBackSelection).setOnClickListener {
            if (!isDownloading) finish()
        }

        rvMediaTiles.layoutManager = GridLayoutManager(this, 2)

        btnToggleSelectAll.setOnClickListener {
            if (selectedIndices.size == resolvedItems.size) {
                selectedIndices.clear()
            } else {
                selectedIndices.clear()
                for (i in resolvedItems.indices) selectedIndices.add(i)
            }
            updateUiState()
            rvMediaTiles.adapter?.notifyDataSetChanged()
        }

        btnDownloadSelected.setOnClickListener {
            startDownload()
        }

        loadMediaMetadata()
    }

    private fun loadMediaMetadata() {
        tvSubtitle.text = "Resolving Instagram post..."
        pbDownloadProgress.isIndeterminate = true
        pbDownloadProgress.visibility = View.VISIBLE
        btnDownloadSelected.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                resolver.resolve(targetUrl, validateCdn = false)
            }

            pbDownloadProgress.visibility = View.GONE

            if (!result.success || result.items.isEmpty()) {
                val errorMsg = result.error?.message ?: "Failed to resolve post media"
                tvSubtitle.text = "Error: $errorMsg"
                Toast.makeText(this@MediaSelectionActivity, errorMsg, Toast.LENGTH_LONG).show()
                return@launch
            }

            resolvedItems = result.items
            // Default: Select all items
            selectedIndices.clear()
            for (i in resolvedItems.indices) selectedIndices.add(i)

            // High-throughput parallel thumbnail prefetch
            lifecycleScope.launch(Dispatchers.IO) {
                resolvedItems.map { item ->
                    launch {
                        val thumb = if (item.mediaType == MediaType.VIDEO) item.thumbnailUrl else (item.thumbnailUrl ?: item.mediaUrl)
                        if (!thumb.isNullOrBlank() && (thumb.startsWith("http://") || thumb.startsWith("https://"))) {
                            try {
                                MediaThumbnailLoader.loadThumbnail(
                                    this@MediaSelectionActivity,
                                    thumb,
                                    isVideo = item.mediaType == MediaType.VIDEO,
                                    targetWidth = 240,
                                    targetHeight = 240
                                )
                            } catch (ignored: Exception) {}
                        }
                    }
                }
            }

            val postType = result.postType ?: when {
                resolvedItems.size > 1 -> PostType.CAROUSEL
                resolvedItems.any { it.mediaType == MediaType.VIDEO } -> PostType.REEL
                else -> PostType.PHOTO
            }

            tvTitle.text = when (postType) {
                PostType.CAROUSEL -> "Carousel • ${resolvedItems.size} items"
                PostType.REEL -> "Instagram Reel"
                else -> "Instagram Photo"
            }

            rvMediaTiles.adapter = MediaTileAdapter()
            updateUiState()
        }
    }

    private fun updateUiState() {
        val total = resolvedItems.size
        val selected = selectedIndices.size

        btnDownloadSelected.isEnabled = selected > 0 && !isDownloading
        btnDownloadSelected.text = "Download Selected ($selected)"

        if (selected == total && total > 0) {
            btnToggleSelectAll.text = "Clear All"
        } else {
            btnToggleSelectAll.text = "Select All"
        }

        tvSubtitle.text = if (total > 1) {
            "$selected of $total items selected"
        } else {
            "Tap to toggle selection"
        }
    }

    private fun startDownload() {
        if (selectedIndices.isEmpty() || isDownloading) return

        isDownloading = true
        btnDownloadSelected.isEnabled = false
        btnToggleSelectAll.isEnabled = false
        pbDownloadProgress.visibility = View.VISIBLE
        pbDownloadProgress.isIndeterminate = false
        pbDownloadProgress.progress = 0
        tvDownloadStatus.visibility = View.VISIBLE
        tvDownloadStatus.text = "Starting download..."

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                coordinator.executeDirectDownload(
                    url = targetUrl,
                    selectedIndices = selectedIndices.toSet(),
                    recordInDb = true,
                    onStatusUpdate = { status ->
                        runOnUiThread {
                            tvDownloadStatus.text = status
                        }
                    },
                    onProgress = { progress ->
                        runOnUiThread {
                            pbDownloadProgress.progress = progress.overallPercent
                            tvDownloadStatus.text = progress.statusMessage
                        }
                    }
                )
            }

            isDownloading = false
            pbDownloadProgress.visibility = View.GONE
            tvDownloadStatus.visibility = View.GONE

            if (result.success || result.completedCount > 0 || (result.skippedCount > 0 && result.failedCount == 0)) {
                val count = result.completedCount
                val msg = if (count > 0) "Successfully saved $count items to Gallery!" else "Media already saved in Gallery"
                Toast.makeText(this@MediaSelectionActivity, msg, Toast.LENGTH_LONG).show()
                finish()
            } else {
                val errorMsg = result.error ?: "Download failed"
                Toast.makeText(this@MediaSelectionActivity, errorMsg, Toast.LENGTH_LONG).show()
                btnDownloadSelected.isEnabled = true
                btnToggleSelectAll.isEnabled = true
            }
        }
    }

    private inner class MediaTileAdapter : RecyclerView.Adapter<MediaTileViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaTileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selection_tile, parent, false)
            return MediaTileViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaTileViewHolder, position: Int) {
            val item = resolvedItems[position]
            val isSelected = selectedIndices.contains(position)

            holder.tvIndexBadge.text = "#${position + 1}"
            holder.ivVideoIndicator.visibility = if (item.mediaType == MediaType.VIDEO) View.VISIBLE else View.GONE
            holder.viewDimOverlay.visibility = if (isSelected) View.GONE else View.VISIBLE

            if (isSelected) {
                holder.ivCheckmark.setImageResource(R.drawable.ic_check_circle)
                holder.ivCheckmark.setColorFilter(getColor(R.color.status_green))
            } else {
                holder.ivCheckmark.setImageResource(R.drawable.dot_indicator)
                holder.ivCheckmark.setColorFilter(getColor(R.color.text_muted))
            }

            // Load preview thumbnail via MediaThumbnailLoader safely
            val isVideo = item.mediaType == MediaType.VIDEO
            val previewUrl = if (isVideo) {
                item.thumbnailUrl
            } else {
                item.thumbnailUrl ?: item.mediaUrl
            }

            if (!previewUrl.isNullOrBlank()) {
                MediaThumbnailLoader.loadInto(
                    context = this@MediaSelectionActivity,
                    uriString = previewUrl,
                    imageView = holder.ivThumbnail,
                    isVideo = isVideo,
                    targetWidth = 240,
                    targetHeight = 240,
                    scope = lifecycleScope
                )
            } else {
                holder.ivThumbnail.setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
            }

            holder.itemView.setOnClickListener {
                if (isDownloading) return@setOnClickListener
                if (selectedIndices.contains(position)) {
                    selectedIndices.remove(position)
                } else {
                    selectedIndices.add(position)
                }
                notifyItemChanged(position)
                updateUiState()
            }
        }

        override fun getItemCount(): Int = resolvedItems.size
    }

    private class MediaTileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivTileThumbnail)
        val viewDimOverlay: View = view.findViewById(R.id.viewDimOverlay)
        val tvIndexBadge: TextView = view.findViewById(R.id.tvTileIndexBadge)
        val ivVideoIndicator: ImageView = view.findViewById(R.id.ivTileVideoIndicator)
        val ivCheckmark: ImageView = view.findViewById(R.id.ivTileCheckmark)
    }
}
