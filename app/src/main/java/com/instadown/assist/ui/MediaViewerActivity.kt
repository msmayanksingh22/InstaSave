package com.instadown.assist.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.content.IntentSender
import android.widget.*
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlin.math.abs

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
    private lateinit var btnExtractAudio: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var viewerRoot: View

    private var downX = 0f
    private var downY = 0f
    private var isDismissDragging = false
    private var dismissVelocityTracker: android.view.VelocityTracker? = null
    private val touchSlop by lazy { android.view.ViewConfiguration.get(this).scaledTouchSlop }

    private lateinit var layoutCarouselBottomBar: LinearLayout
    private lateinit var tvCarouselIndicatorPill: TextView
    private lateinit var rvCarouselFilmstrip: RecyclerView
    private lateinit var btnPrevItem: ImageButton
    private lateinit var btnNextItem: ImageButton
    private var filmstripAdapter: CarouselFilmstripAdapter? = null

    private var currentRecord: DownloadRecord? = null
    private var mediaItems: List<DownloadItemRecord> = emptyList()
    private val repo by lazy { DownloadRepository.getInstance(this) }
    private var isControlsVisible = true

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
                        this@MediaViewerActivity,
                        "✓ Media deleted from device. Post preserved in History!",
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_media_viewer)
        viewerRoot = findViewById(R.id.viewerRoot)

        viewPager = findViewById(R.id.viewPager)
        topBar = findViewById(R.id.topBar)
        tvTitle = findViewById(R.id.tvViewerTitle)
        tvSubtitle = findViewById(R.id.tvViewerSubtitle)
        btnBack = findViewById(R.id.btnBack)
        btnExtractAudio = findViewById(R.id.btnExtractAudio)
        btnShare = findViewById(R.id.btnShare)
        btnDelete = findViewById(R.id.btnDelete)
        layoutCarouselBottomBar = findViewById(R.id.layoutCarouselBottomBar)
        tvCarouselIndicatorPill = findViewById(R.id.tvCarouselIndicatorPill)
        rvCarouselFilmstrip = findViewById(R.id.rvCarouselFilmstrip)
        btnPrevItem = findViewById(R.id.btnPrevItem)
        btnNextItem = findViewById(R.id.btnNextItem)

        btnPrevItem.setOnClickListener {
            val prev = viewPager.currentItem - 1
            if (prev >= 0) viewPager.setCurrentItem(prev, true)
        }
        btnNextItem.setOnClickListener {
            val next = viewPager.currentItem + 1
            if (next < mediaItems.size) viewPager.setCurrentItem(next, true)
        }

        // Handle edge-to-edge system insets
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(view.paddingLeft, statusBars.top + 16, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(layoutCarouselBottomBar) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, navBars.bottom + 16)
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
        btnExtractAudio.setOnClickListener { extractAudioForCurrentItem() }
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

    private fun isCurrentItemZoomed(): Boolean {
        val recyclerView = viewPager.getChildAt(0) as? RecyclerView ?: return false
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(viewPager.currentItem) as? MediaPageViewHolder ?: return false
        return viewHolder.isZoomed()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                isDismissDragging = false
                dismissVelocityTracker?.recycle()
                dismissVelocityTracker = android.view.VelocityTracker.obtain()
                dismissVelocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                dismissVelocityTracker?.addMovement(ev)
                val dy = ev.rawY - downY
                val dx = ev.rawX - downX

                if (!isDismissDragging) {
                    if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f && !isCurrentItemZoomed()) {
                        isDismissDragging = true
                        val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                    }
                }

                if (isDismissDragging) {
                    viewPager.translationY = dy
                    val dragFraction = (abs(dy) / (viewerRoot.height.toFloat().coerceAtLeast(1f))).coerceIn(0f, 1f)
                    val scale = (1f - dragFraction * 0.22f).coerceIn(0.78f, 1f)
                    viewPager.scaleX = scale
                    viewPager.scaleY = scale

                    val bgAlpha = ((1f - dragFraction * 1.25f).coerceIn(0f, 1f) * 255).toInt()
                    viewerRoot.setBackgroundColor(android.graphics.Color.argb(bgAlpha, 0, 0, 0))

                    val chromeAlpha = (1f - dragFraction * 2.5f).coerceIn(0f, 1f)
                    topBar.alpha = chromeAlpha
                    layoutCarouselBottomBar.alpha = chromeAlpha
                    btnPrevItem.alpha = chromeAlpha
                    btnNextItem.alpha = chromeAlpha
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDismissDragging) {
                    dismissVelocityTracker?.addMovement(ev)
                    dismissVelocityTracker?.computeCurrentVelocity(1000)
                    val vy = dismissVelocityTracker?.yVelocity ?: 0f
                    val dy = ev.rawY - downY
                    val shouldDismiss = abs(dy) > (viewerRoot.height * 0.16f) ||
                            (abs(vy) > 1100f && abs(dy) > touchSlop)

                    if (shouldDismiss) {
                        val targetY = if (dy >= 0) viewerRoot.height.toFloat() * 1.15f else -viewerRoot.height.toFloat() * 1.15f
                        viewPager.animate()
                            .translationY(targetY)
                            .scaleX(0.72f)
                            .scaleY(0.72f)
                            .alpha(0f)
                            .setDuration(220)
                            .setInterpolator(AccelerateInterpolator())
                            .withEndAction {
                                finish()
                                overridePendingTransition(0, 0)
                            }
                            .start()
                        viewerRoot.animate()
                            .alpha(0f)
                            .setDuration(180)
                            .start()
                    } else {
                        viewPager.animate()
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(240)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                        viewerRoot.setBackgroundColor(android.graphics.Color.BLACK)
                        topBar.animate().alpha(if (isControlsVisible) 1f else 0f).setDuration(200).start()
                        layoutCarouselBottomBar.animate().alpha(if (isControlsVisible) 1f else 0f).setDuration(200).start()
                        btnPrevItem.animate().alpha(if (isControlsVisible) 1f else 0f).setDuration(200).start()
                        btnNextItem.animate().alpha(if (isControlsVisible) 1f else 0f).setDuration(200).start()
                    }

                    dismissVelocityTracker?.recycle()
                    dismissVelocityTracker = null
                    isDismissDragging = false
                    return true
                }
                dismissVelocityTracker?.recycle()
                dismissVelocityTracker = null
                isDismissDragging = false
            }
        }
        return super.dispatchTouchEvent(ev)
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

        if (mediaItems.size > 1) {
            layoutCarouselBottomBar.visibility = View.VISIBLE
            rvCarouselFilmstrip.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
                this,
                androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL,
                false
            )
            filmstripAdapter = CarouselFilmstripAdapter(mediaItems, safeInitial) { clickedPos ->
                viewPager.setCurrentItem(clickedPos, true)
            }
            rvCarouselFilmstrip.adapter = filmstripAdapter
            updateCarouselNavButtons(safeInitial)
            tvCarouselIndicatorPill.text = "Slide ${safeInitial + 1} of ${mediaItems.size}"
        } else {
            layoutCarouselBottomBar.visibility = View.GONE
            btnPrevItem.visibility = View.GONE
            btnNextItem.visibility = View.GONE
        }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateHeader(position)
                updateCarouselNavButtons(position)
                filmstripAdapter?.setSelectedPosition(position)
                rvCarouselFilmstrip.smoothScrollToPosition(position)
                tvCarouselIndicatorPill.text = "Slide ${position + 1} of ${mediaItems.size}"
            }
        })
    }

    private fun updateCarouselNavButtons(position: Int) {
        if (mediaItems.size <= 1) {
            btnPrevItem.visibility = View.GONE
            btnNextItem.visibility = View.GONE
            return
        }
        if (isControlsVisible) {
            btnPrevItem.visibility = if (position > 0) View.VISIBLE else View.GONE
            btnNextItem.visibility = if (position < mediaItems.size - 1) View.VISIBLE else View.GONE
        }
    }

    private fun updateHeader(position: Int) {
        val record = currentRecord ?: return
        val currentItem = mediaItems.getOrNull(position) ?: return
        val isVideo = currentItem.mediaType == "VIDEO" || record.postType.equals("REEL", ignoreCase = true)

        btnExtractAudio.visibility = if (isVideo) View.VISIBLE else View.GONE

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

    private fun extractAudioForCurrentItem() {
        val record = currentRecord ?: return
        val currentItem = mediaItems.getOrNull(viewPager.currentItem) ?: return
        val uriStr = currentItem.mediaUri ?: return
        val videoUri = Uri.parse(uriStr)

        Toast.makeText(this, "Extracting lossless audio (.m4a)...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = com.instadown.assist.download.ReelAudioExtractor.extractAudio(
                context = this@MediaViewerActivity,
                videoUri = videoUri,
                shortcode = record.shortcode
            )
            if (result.success && result.audioUri != null) {
                Toast.makeText(
                    this@MediaViewerActivity,
                    "✓ Audio extracted: ${result.fileName}\nSaved in Music/InstaSave/ (${result.elapsedMs}ms)",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this@MediaViewerActivity,
                    "Audio extraction failed: ${result.error}",
                    Toast.LENGTH_SHORT
                ).show()
            }
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

        if (mediaItems.size > 1) {
            layoutCarouselBottomBar.animate()
                .alpha(targetAlpha)
                .setDuration(200)
                .withEndAction {
                    layoutCarouselBottomBar.visibility = if (isControlsVisible) View.VISIBLE else View.GONE
                }
                .start()
            val cur = viewPager.currentItem
            btnPrevItem.visibility = if (isControlsVisible && cur > 0) View.VISIBLE else View.GONE
            btnNextItem.visibility = if (isControlsVisible && cur < mediaItems.size - 1) View.VISIBLE else View.GONE
        }
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
        val sheet = HistoryItemOptionsBottomSheet.newInstance(record.id)
        sheet.setListener(object : HistoryItemOptionsBottomSheet.Listener {
            override fun onHistoryChanged() {
                finish()
            }
            override fun onReDownloadRequested(rec: DownloadRecord) {
                finish()
            }
            override fun onConsentRequired(downloadId: Long, intentSender: IntentSender, itemIds: List<Long>) {
                pendingConsentDownloadId = downloadId
                pendingConsentItemIds = itemIds
                try {
                    deleteConsentLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build()
                    )
                } catch (e: Exception) {
                    Toast.makeText(this@MediaViewerActivity, "Failed to request delete permission: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        })
        sheet.show(supportFragmentManager, HistoryItemOptionsBottomSheet.TAG)
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

        fun isZoomed(): Boolean = ivPhoto.visibility == View.VISIBLE && ivPhoto.isZoomed()

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

    private inner class CarouselFilmstripAdapter(
        private val items: List<DownloadItemRecord>,
        private var selectedPosition: Int,
        private val onItemClick: (Int) -> Unit
    ) : RecyclerView.Adapter<CarouselFilmstripAdapter.ThumbViewHolder>() {

        fun setSelectedPosition(pos: Int) {
            val prev = selectedPosition
            selectedPosition = pos
            notifyItemChanged(prev)
            notifyItemChanged(selectedPosition)
        }

        inner class ThumbViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val card: com.google.android.material.card.MaterialCardView = view.findViewById(R.id.cardViewerThumb)
            val ivThumb: ImageView = view.findViewById(R.id.ivCarouselItemThumb)
            val tvIndex: TextView = view.findViewById(R.id.tvCarouselItemIndex)
            val ivVideo: ImageView = view.findViewById(R.id.ivCarouselVideoBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ThumbViewHolder {
            val view = layoutInflater.inflate(R.layout.item_viewer_carousel_thumb, parent, false)
            return ThumbViewHolder(view)
        }

        override fun onBindViewHolder(holder: ThumbViewHolder, position: Int) {
            val item = items[position]
            holder.tvIndex.text = "#${position + 1}"
            val isVideo = item.mediaType.equals("VIDEO", ignoreCase = true)
            holder.ivVideo.visibility = if (isVideo) View.VISIBLE else View.GONE

            val isSelected = position == selectedPosition
            val density = resources.displayMetrics.density
            holder.card.strokeWidth = if (isSelected) (2 * density).toInt() else (1 * density).toInt()
            holder.card.strokeColor = if (isSelected) Color.parseColor("#8A67FF") else Color.parseColor("#33FFFFFF")
            holder.card.scaleX = if (isSelected) 1.08f else 1.0f
            holder.card.scaleY = if (isSelected) 1.08f else 1.0f

            MediaThumbnailLoader.loadInto(
                context = this@MediaViewerActivity,
                uriString = item.mediaUri,
                imageView = holder.ivThumb,
                isVideo = isVideo,
                targetWidth = 100,
                targetHeight = 100,
                scope = lifecycleScope
            )

            holder.itemView.setOnClickListener {
                onItemClick(position)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
