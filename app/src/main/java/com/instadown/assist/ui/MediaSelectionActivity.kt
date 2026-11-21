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
        private val pendingResults = android.util.LruCache<String, com.instadown.assist.resolver.model.ResolveResult>(8)

        fun start(context: Context, url: String, preloaded: com.instadown.assist.resolver.model.ResolveResult? = null) {
            val intent = Intent(context, MediaSelectionActivity::class.java).apply {
                if (preloaded != null) {
                    val token = java.util.UUID.randomUUID().toString()
                    pendingResults.put(token, preloaded)
                    putExtra("selectionRequest", token)
                }
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
    private lateinit var btnCopySelectedImage: Button
    private lateinit var pbDownloadProgress: ProgressBar
    private lateinit var tvDownloadStatus: TextView

    private val resolver by lazy { CompositeInstagramResolver(this) }
    private var restoredSelection: IntArray? = null

    class SelectionState : androidx.lifecycle.ViewModel() {
        var result: com.instadown.assist.resolver.model.ResolveResult? = null
    }

    private val selectionState by lazy { androidx.lifecycle.ViewModelProvider(this)[SelectionState::class.java] }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val selection = if (currentResolveResult != null) selectedIndices.toIntArray() else restoredSelection
        if (selection != null) outState.putIntArray("selection", selection)
    }

    private var targetUrl: String = ""
    private val currentResolveResult get() = selectionState.result
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
        btnCopySelectedImage = findViewById(R.id.btnCopySelectedImage)
        pbDownloadProgress = findViewById(R.id.pbDownloadProgress)
        tvDownloadStatus = findViewById(R.id.tvDownloadStatus)

        // Handle edge-to-edge window insets
        val topPadding = topBar.paddingTop
        val bottomPadding = bottomBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootMediaSelection)) { root, windowInsets ->
            val statusBar = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val safe = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.setPadding(safe.left, root.paddingTop, safe.right, root.paddingBottom)
            topBar.setPadding(topBar.paddingLeft, maxOf(statusBar.top, safe.top) + topPadding, topBar.paddingRight, topBar.paddingBottom)
            bottomBar.setPadding(bottomBar.paddingLeft, bottomBar.paddingTop, bottomBar.paddingRight, navBar.bottom + bottomPadding)
            windowInsets
        }

        findViewById<ImageButton>(R.id.btnBackSelection).setOnClickListener {
            if (!isDownloading) finish()
        }

        registerPredictiveBackMotion(
            rootContainer = findViewById(R.id.rootMediaSelection),
            shouldInterceptBack = { isDownloading },
            onInterceptedCommit = {
                Toast.makeText(this, "Download in progress...", Toast.LENGTH_SHORT).show()
            }
        )

        rvMediaTiles.layoutManager = GridLayoutManager(this, resources.getInteger(R.integer.media_grid_columns))
        // Selection changes should not cross-fade or flash already loaded previews.
        rvMediaTiles.itemAnimator = null

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
            if (currentResolveResult == null) loadMediaMetadata() else startDownload()
        }

        btnCopySelectedImage.setOnClickListener {
            copySelectedToClipboard()
        }

        restoredSelection = savedInstanceState?.getIntArray("selection")
        val preloaded = currentResolveResult
            ?: intent.getStringExtra("selectionRequest")?.let { pendingResults.remove(it) }

        if (preloaded != null && preloaded.success && preloaded.items.isNotEmpty()) {
            displayResolvedMedia(preloaded)
        } else {
            loadMediaMetadata()
        }
    }

    private fun displayResolvedMedia(result: com.instadown.assist.resolver.model.ResolveResult) {
        selectionState.result = result
        resolvedItems = result.items
        selectedIndices.clear()
        val previous = restoredSelection
        if (previous == null) selectedIndices.addAll(resolvedItems.indices)
        else previous.filterTo(selectedIndices) { it in resolvedItems.indices }
        restoredSelection = null
        btnToggleSelectAll.visibility = View.VISIBLE

        // Use the loader's bounded concurrency and shared in-flight requests.
        lifecycleScope.launch(Dispatchers.IO) {
            resolvedItems.forEach { item ->
                launch {
                    val thumb = if (item.mediaType == MediaType.VIDEO) item.thumbnailUrl else (item.thumbnailUrl ?: item.mediaUrl)
                    if (!thumb.isNullOrBlank() && (thumb.startsWith("http://") || thumb.startsWith("https://"))) {
                        try {
                            MediaThumbnailLoader.loadThumbnail(
                                applicationContext,
                                thumb,
                                isVideo = item.mediaType == MediaType.VIDEO,
                                targetWidth = 240,
                                targetHeight = 240
                            )
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        }
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

    private fun loadMediaMetadata() {
        tvSubtitle.text = "Resolving Instagram post..."
        btnToggleSelectAll.visibility = View.GONE
        btnDownloadSelected.setText(R.string.selection_loading)
        pbDownloadProgress.isIndeterminate = true
        pbDownloadProgress.visibility = View.VISIBLE
        btnDownloadSelected.isEnabled = false

        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { resolver.resolve(targetUrl, validateCdn = false) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                com.instadown.assist.util.AppLogger.w("MediaSelection", "Resolution failed: ${e.javaClass.simpleName}")
                null
            }

            pbDownloadProgress.visibility = View.GONE

            if (result == null || !result.success || result.items.isEmpty()) {
                val errorMsg = result?.error?.message ?: getString(R.string.selection_resolve_failed)
                tvSubtitle.text = "Error: $errorMsg"
                btnDownloadSelected.setText(R.string.selection_retry)
                btnDownloadSelected.isEnabled = true
                return@launch
            }

            displayResolvedMedia(result)
        }
    }

    private fun updateUiState() {
        val total = resolvedItems.size
        val selected = selectedIndices.size

        btnDownloadSelected.isEnabled = selected > 0 && !isDownloading
        btnDownloadSelected.text = "Download Selected ($selected)"

        btnCopySelectedImage.isEnabled = selected > 0 && !isDownloading
        btnCopySelectedImage.visibility = if (selected > 0) View.VISIBLE else View.GONE

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

    private fun copySelectedToClipboard() {
        if (selectedIndices.isEmpty() || isDownloading) return
        val targetIndex = selectedIndices.firstOrNull() ?: return
        val item = resolvedItems.getOrNull(targetIndex) ?: return
        copyItemToClipboard(item)
    }

    private fun copyItemToClipboard(item: ResolvedMedia) {
        val targetUrl = if (item.mediaType == MediaType.VIDEO) {
            item.thumbnailUrl ?: item.mediaUrl
        } else {
            item.mediaUrl
        }
        if (targetUrl.isNullOrBlank()) {
            Toast.makeText(this, "Image URL not available", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Copying image to clipboard...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val success = com.instadown.assist.util.ImageClipboardHelper.copyImageToClipboard(
                context = this@MediaSelectionActivity,
                remoteUrl = targetUrl,
                label = currentResolveResult?.caption
            )
            if (success) {
                // Done!
            }
        }
    }

    private fun startDownload() {
        val result = currentResolveResult ?: return
        if (selectedIndices.isEmpty() || isDownloading) return

        isDownloading = true
        val selectedCount = selectedIndices.size
        val selectedSet = selectedIndices.toSet()

        Toast.makeText(
            this,
            if (selectedCount > 1) "Saving $selectedCount items to Gallery in background..." else "Saving to Gallery in background...",
            Toast.LENGTH_SHORT
        ).show()

        val appContext = applicationContext
        val url = targetUrl
        com.instadown.assist.InstaSaveApp.applicationScope.launch {
            try {
                val appCoordinator = InstagramDownloadCoordinator(appContext)
                appCoordinator.executeDirectDownload(
                    url = url,
                    selectedIndices = selectedSet,
                    recordInDb = true,
                    preResolvedResult = result
                )
            } catch (e: Exception) {
                android.util.Log.e("MediaSelection", "Background download error", e)
            }
        }

        // Close selection sheet immediately so user does not wait!
        finish()
    }

    private inner class MediaTileAdapter : RecyclerView.Adapter<MediaTileViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaTileViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_selection_tile, parent, false)
            return MediaTileViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaTileViewHolder, position: Int) {
            val item = resolvedItems[position]
            val isSelected = selectedIndices.contains(position)
            holder.itemView.isSelected = isSelected
            holder.itemView.contentDescription = getString(
                if (item.mediaType == MediaType.VIDEO) R.string.selection_video else R.string.selection_photo,
                position + 1, resolvedItems.size
            )

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
                holder.ivThumbnail.tag = null
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

            holder.itemView.setOnLongClickListener {
                if (isDownloading) return@setOnLongClickListener false
                copyItemToClipboard(item)
                true
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
