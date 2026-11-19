package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadItemRecord
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.ui.view.ZoomableImageView
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MediaViewerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_INITIAL_INDEX = "extra_initial_index"

        fun start(context: Context, downloadId: Long, initialIndex: Int = 0) {
            val intent = Intent(context, MediaViewerActivity::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
                putExtra(EXTRA_INITIAL_INDEX, initialIndex)
            }
            context.startActivity(intent)
        }
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var topBar: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnBack: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnDelete: ImageButton

    private var currentRecord: DownloadRecord? = null
    private var mediaItems: List<DownloadItemRecord> = emptyList()
    private val repo by lazy { DownloadRepository.getInstance(this) }
    private var isControlsVisible = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_media_viewer)

        viewPager = findViewById(R.id.viewPager)
        topBar = findViewById(R.id.topBar)
        tvTitle = findViewById(R.id.tvViewerTitle)
        tvSubtitle = findViewById(R.id.tvViewerSubtitle)
        btnBack = findViewById(R.id.btnBack)
        btnShare = findViewById(R.id.btnShare)
        btnDelete = findViewById(R.id.btnDelete)

        // Handle edge-to-edge system insets
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top + 16, view.paddingRight, view.paddingBottom)
            insets
        }

        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        val initialIndex = intent.getIntExtra(EXTRA_INITIAL_INDEX, 0)
        val startPosition = intent.getIntExtra(SharedMediaTransition.EXTRA_START_POSITION, 0)

        SharedMediaTransition.bindViewerEnterRemapper(
            activity = this,
            viewPager = viewPager,
            getMediaIdAt = { downloadId },
            findActiveImageView = { pos ->
                val holder = (viewPager.getChildAt(0) as? RecyclerView)?.findViewHolderForAdapterPosition(pos)
                holder?.itemView?.findViewById(R.id.ivPhoto)
            }
        )

        val handleExit = {
            SharedMediaTransition.setReturnPositionResult(this, startPosition)
            finishAfterTransition()
        }

        btnBack.setOnClickListener { handleExit() }
        btnShare.setOnClickListener { shareCurrentMedia() }
        btnDelete.setOnClickListener { confirmDeleteCurrentDownload() }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleExit()
            }
        })

        if (downloadId != -1L) {
            loadTransaction(downloadId, initialIndex)
        } else {
            Toast.makeText(this, "Media not found", Toast.LENGTH_SHORT).show()
            finish()
        }

        lifecycleScope.launch {
            repo.transactionEvents.collect {
                val currentDlId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
                if (currentDlId != -1L) {
                    val currentPos = viewPager.currentItem
                    loadTransaction(currentDlId, currentPos)
                }
            }
        }
    }

    private val mediaStoreObserver by lazy {
        com.instadown.assist.data.MediaStoreSyncObserver(this, lifecycleScope)
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
        val downloadId = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId != -1L) {
            lifecycleScope.launch(Dispatchers.IO) {
                repo.reconcileWithMediaStore()
            }
        }
    }

    private fun loadTransaction(downloadId: Long, initialIndex: Int) {
        lifecycleScope.launch {
            val record = repo.getTransactionById(downloadId)
            if (record == null || record.items.isEmpty()) {
                Toast.makeText(this@MediaViewerActivity, "Media record unavailable", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            currentRecord = record
            // Filter strictly AVAILABLE items with valid URI
            mediaItems = record.items.filter { it.mediaUri != null && it.status == com.instadown.assist.data.db.ItemStatus.AVAILABLE }
            if (mediaItems.isEmpty()) {
                Toast.makeText(this@MediaViewerActivity, "Media files have been removed from device", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            setupViewPager(initialIndex)
        }
    }

    private fun setupViewPager(initialIndex: Int) {
        val adapter = MediaPagerAdapter(mediaItems) {
            toggleControlsVisibility()
        }
        viewPager.adapter = adapter
        viewPager.orientation = ViewPager2.ORIENTATION_HORIZONTAL

        val safeInitial = initialIndex.coerceIn(0, mediaItems.size - 1)
        viewPager.setCurrentItem(safeInitial, false)
        updateHeader(safeInitial)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateHeader(position)
            }
        })
    }

    private fun updateHeader(position: Int) {
        val record = currentRecord ?: return
        val currentItem = mediaItems.getOrNull(position) ?: return
        val isVideo = currentItem.mediaType == "VIDEO" || record.postType.equals("REEL", ignoreCase = true)

        if (record.isCarousel) {
            tvTitle.text = "Instagram Carousel"
            tvSubtitle.text = "Item ${position + 1} of ${mediaItems.size}"
        } else if (isVideo) {
            tvTitle.text = "Instagram Reel"
            tvSubtitle.text = record.shortcode.ifBlank { "Reel" }
        } else {
            tvTitle.text = "Instagram Photo"
            tvSubtitle.text = record.shortcode.ifBlank { "Photo" }
        }
    }

    private fun toggleControlsVisibility() {
        isControlsVisible = !isControlsVisible
        val targetAlpha = if (isControlsVisible) 1f else 0f
        topBar.animate()
            .alpha(targetAlpha)
            .setDuration(200)
            .withEndAction {
                topBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
            }
            .start()
        if (isControlsVisible) topBar.visibility = View.VISIBLE
    }

    private fun shareCurrentMedia() {
        val currentIndex = viewPager.currentItem
        val currentItem = mediaItems.getOrNull(currentIndex) ?: return
        val uriStr = currentItem.mediaUri ?: return
        val uri = Uri.parse(uriStr)

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (currentItem.mediaType == "VIDEO") "video/*" else "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(Intent.createChooser(intent, "Share Media"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share media", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteCurrentDownload() {
        val record = currentRecord ?: return
        AlertDialog.Builder(this)
            .setTitle("Delete Media?")
            .setMessage("This will remove this item from history and delete its downloaded files from your device.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    repo.deleteDownload(record.id, deleteFiles = true)
                    StorageAccountant.invalidateCache()
                    Toast.makeText(this@MediaViewerActivity, "Media deleted", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ViewPager2 Adapter
    inner class MediaPagerAdapter(
        private val items: List<DownloadItemRecord>,
        private val onItemClick: () -> Unit
    ) : RecyclerView.Adapter<MediaPageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaPageViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_media_page, parent, false)
            return MediaPageViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaPageViewHolder, position: Int) {
            holder.bind(items[position], onItemClick)
        }

        override fun onViewRecycled(holder: MediaPageViewHolder) {
            super.onViewRecycled(holder)
            holder.cleanup()
        }

        override fun getItemCount(): Int = items.size
    }

    inner class MediaPageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPhoto: ZoomableImageView = itemView.findViewById(R.id.ivPhoto)
        private val videoContainer: FrameLayout = itemView.findViewById(R.id.videoContainer)
        private val videoView: VideoView = itemView.findViewById(R.id.videoView)
        private val btnPlayPauseOverlay: FrameLayout = itemView.findViewById(R.id.btnPlayPauseOverlay)
        private val ivPlayPauseIcon: ImageView = itemView.findViewById(R.id.ivPlayPauseIcon)
        private val videoControlsBar: LinearLayout = itemView.findViewById(R.id.videoControlsBar)
        private val videoSeekBar: SeekBar = itemView.findViewById(R.id.videoSeekBar)
        private val tvVideoTime: TextView = itemView.findViewById(R.id.tvVideoTime)
        private val tvVideoDuration: TextView = itemView.findViewById(R.id.tvVideoDuration)
        private val pageLoading: ProgressBar = itemView.findViewById(R.id.pageLoading)

        private val handler = Handler(Looper.getMainLooper())
        private var updateProgressRunnable: Runnable? = null
        private var isVideoPrepared = false

        fun bind(item: DownloadItemRecord, onItemClick: () -> Unit) {
            cleanup()
            val uriStr = item.mediaUri ?: return
            val uri = Uri.parse(uriStr)
            val isVideo = item.mediaType == "VIDEO"

            if (isVideo) {
                ivPhoto.visibility = View.GONE
                videoContainer.visibility = View.VISIBLE
                pageLoading.visibility = View.VISIBLE
                btnPlayPauseOverlay.visibility = View.GONE

                setupVideoPlayer(uri, onItemClick)
            } else {
                videoContainer.visibility = View.GONE
                ivPhoto.visibility = View.VISIBLE
                pageLoading.visibility = View.VISIBLE
                ivPhoto.resetZoom()

                loadHighResPhoto(uri)
                ivPhoto.setOnClickListener { onItemClick() }
            }
        }

        private fun loadHighResPhoto(uri: Uri) {
            lifecycleScope.launch(Dispatchers.IO) {
                val bmp = try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                } catch (e: Exception) {
                    null
                }

                withContext(Dispatchers.Main) {
                    pageLoading.visibility = View.GONE
                    if (bmp != null) {
                        ivPhoto.setImageBitmap(bmp)
                    } else {
                        ivPhoto.setImageResource(R.drawable.ic_image)
                    }
                    supportStartPostponedEnterTransition()
                }
            }
        }

        private fun setupVideoPlayer(uri: Uri, onItemClick: () -> Unit) {
            videoView.setVideoURI(uri)

            videoView.setOnPreparedListener { mp ->
                isVideoPrepared = true
                pageLoading.visibility = View.GONE
                btnPlayPauseOverlay.visibility = View.VISIBLE
                ivPlayPauseIcon.setImageResource(R.drawable.ic_pause)

                mp.isLooping = true
                val durationMs = videoView.duration
                tvVideoDuration.text = formatDuration(durationMs)
                videoSeekBar.max = durationMs

                videoView.start()
                startProgressUpdater()
                supportStartPostponedEnterTransition()
            }

            videoView.setOnErrorListener { _, _, _ ->
                pageLoading.visibility = View.GONE
                btnPlayPauseOverlay.visibility = View.VISIBLE
                ivPlayPauseIcon.setImageResource(R.drawable.ic_play_arrow)
                Toast.makeText(itemView.context, "Cannot play video", Toast.LENGTH_SHORT).show()
                true
            }

            btnPlayPauseOverlay.setOnClickListener {
                if (!isVideoPrepared) return@setOnClickListener
                if (videoView.isPlaying) {
                    videoView.pause()
                    ivPlayPauseIcon.setImageResource(R.drawable.ic_play_arrow)
                    btnPlayPauseOverlay.visibility = View.VISIBLE
                } else {
                    videoView.start()
                    ivPlayPauseIcon.setImageResource(R.drawable.ic_pause)
                    startProgressUpdater()
                }
            }

            videoContainer.setOnClickListener {
                onItemClick()
                if (videoView.isPlaying) {
                    // Temporarily toggle overlay visibility
                    btnPlayPauseOverlay.visibility = if (btnPlayPauseOverlay.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }

            videoSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        videoView.seekTo(progress)
                        tvVideoTime.text = formatDuration(progress)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }

        private fun startProgressUpdater() {
            updateProgressRunnable = object : Runnable {
                override fun run() {
                    if (videoView.isPlaying) {
                        val current = videoView.currentPosition
                        videoSeekBar.progress = current
                        tvVideoTime.text = formatDuration(current)
                        handler.postDelayed(this, 250)
                    }
                }
            }
            handler.post(updateProgressRunnable!!)
        }

        fun cleanup() {
            updateProgressRunnable?.let { handler.removeCallbacks(it) }
            updateProgressRunnable = null
            isVideoPrepared = false
            if (videoView.isPlaying) {
                videoView.stopPlayback()
            }
        }

        private fun formatDuration(millis: Int): String {
            val totalSeconds = millis / 1000
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds)
        }
    }
}
