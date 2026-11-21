package com.instadown.assist.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.download.RedownloadProgressState
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.util.Locale

sealed class CardProgressState {
    object Idle : CardProgressState()
    data class Queued(val queuePosition: Int) : CardProgressState()
    data class InProgress(val percent: Int, val message: String) : CardProgressState()
}

class DownloadHistoryAdapter(
    private val scope: CoroutineScope,
    private val onViewClick: (DownloadRecord) -> Unit,
    private val onRedownloadClick: (DownloadRecord) -> Unit,
    private val onOpenInstagramClick: (DownloadRecord) -> Unit,
    private val onItemLongClick: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadHistoryAdapter.HistoryViewHolder>() {

    companion object {
        private const val PAYLOAD_PROGRESS = "payload_progress"
    }

    private val items = mutableListOf<DownloadRecord>()
    private val progressStates = mutableMapOf<Long, CardProgressState>()

    fun submitList(newItems: List<DownloadRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun updateProgressStates(newStates: Map<Long, RedownloadProgressState>) {
        val affectedIds = (progressStates.keys + newStates.keys).toSet()
        progressStates.clear()
        for ((id, s) in newStates) {
            progressStates[id] = when {
                s.isQueued -> CardProgressState.Queued(s.queuePosition)
                else -> CardProgressState.InProgress(s.percent, s.message)
            }
        }
        for (id in affectedIds) {
            val index = items.indexOfFirst { it.id == id }
            if (index != -1) {
                notifyItemChanged(index, PAYLOAD_PROGRESS)
            }
        }
    }

    fun setDownloadQueued(downloadId: Long, queuePosition: Int) {
        progressStates[downloadId] = CardProgressState.Queued(queuePosition)
        notifyItemChangedById(downloadId)
    }

    fun setDownloadProgress(downloadId: Long, percent: Int, message: String) {
        progressStates[downloadId] = CardProgressState.InProgress(percent, message)
        notifyItemChangedById(downloadId)
    }

    fun clearDownloadState(downloadId: Long) {
        progressStates.remove(downloadId)
        notifyItemChangedById(downloadId)
    }

    private fun notifyItemChangedById(downloadId: Long) {
        val index = items.indexOfFirst { it.id == downloadId }
        if (index != -1) {
            notifyItemChanged(index, PAYLOAD_PROGRESS)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_card, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            val record = items[position]
            val isDeleted = record.isDeletedFromDevice
            val state = progressStates[record.id] ?: CardProgressState.Idle
            bindProgressAndStatus(holder, record, isDeleted, state)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val record = items[position]
        val isVideo = record.items.firstOrNull()?.mediaType == "VIDEO" || record.postType.equals("REEL", ignoreCase = true)
        val isDeleted = record.isDeletedFromDevice
        val state = progressStates[record.id] ?: CardProgressState.Idle

        // Title: Prioritize @username creator handle
        val pathType = if (isVideo) "reel" else "p"
        val displayShortcode = if (record.shortcode.isNotBlank()) record.shortcode else "post"
        if (!record.authorUsername.isNullOrBlank()) {
            val handle = record.authorUsername.removePrefix("@")
            holder.tvTitle.text = "@$handle"
            holder.tvTitle.setTextColor(Color.parseColor("#FFFFFF"))
        } else {
            holder.tvTitle.text = "instagram.com/$pathType/$displayShortcode"
            holder.tvTitle.setTextColor(Color.parseColor("#E0E0E0"))
        }

        // Subtitle: Type, item count & total storage size
        val typeDescription = when {
            record.isCarousel -> "Carousel • ${record.totalItemCount} items"
            isVideo -> "Instagram Reel (Video)"
            else -> "Instagram Photo"
        }
        val sizeFormatted = formatBytes(record.totalBytes)
        holder.tvSubtitle.text = if (sizeFormatted != "0 KB") "$typeDescription • $sizeFormatted" else typeDescription

        // Date Line: Includes original save date & shortcode
        holder.tvDate.text = "Saved ${record.formattedDate}"

        // Type Icon
        if (record.isCarousel) {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_carousel)
        } else if (isVideo) {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_video)
        } else {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_image)
        }

        // Bind status and progress
        bindProgressAndStatus(holder, record, isDeleted, state)

        // Thumbnail Loader: Load persistent internal thumbnail, DB thumbnail, or valid local media
        val context = holder.itemView.context
        val shortcode = record.shortcode
        val localThumbFile = if (shortcode.isNotBlank()) File(context.filesDir, "thumbnails/$shortcode.jpg") else null
        val legacyThumbFile = if (shortcode.isNotBlank()) File(context.cacheDir, "thumbnails/$shortcode.jpg") else null

        val uri = when {
            localThumbFile != null && localThumbFile.exists() -> localThumbFile.absolutePath
            legacyThumbFile != null && legacyThumbFile.exists() -> legacyThumbFile.absolutePath
            !record.thumbnailPath.isNullOrBlank() -> record.thumbnailPath
            !isDeleted && !record.representativeUri.isNullOrBlank() -> record.representativeUri
            else -> null
        }

        if (uri != null) {
            try {
                MediaThumbnailLoader.loadInto(
                    context = context,
                    uriString = uri,
                    imageView = holder.ivThumbnail,
                    isVideo = isVideo,
                    targetWidth = 240,
                    targetHeight = 240,
                    scope = scope
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                holder.ivThumbnail.setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
            }
        } else {
            holder.ivThumbnail.setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
        }

        holder.btnOpenInstagram.setOnClickListener { onOpenInstagramClick(record) }
        holder.btnViewMedia.setOnClickListener { onViewClick(record) }
        holder.btnRedownload.setOnClickListener { onRedownloadClick(record) }
        holder.btnMoreOptions.setOnClickListener { onItemLongClick(record) }
        holder.itemView.setOnClickListener {
            if (isDeleted) {
                onRedownloadClick(record)
            } else {
                onViewClick(record)
            }
        }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(record)
            true
        }
    }

    private fun bindProgressAndStatus(
        holder: HistoryViewHolder,
        record: DownloadRecord,
        isDeleted: Boolean,
        state: CardProgressState
    ) {
        when (state) {
            is CardProgressState.InProgress -> {
                val progressView = holder.ensureProgressInflated()
                progressView?.visibility = View.VISIBLE
                holder.tvProgressMessage?.text = state.message
                holder.tvProgressPercent?.text = "${state.percent}%"
                holder.pbHistoryHorizontal?.isIndeterminate = false
                holder.pbHistoryHorizontal?.progress = state.percent

                holder.pbRedownload.visibility = View.VISIBLE
                holder.btnRedownload.visibility = View.GONE
                holder.btnViewMedia.visibility = View.GONE

                holder.tvStatusText.text = "Re-downloading (${state.percent}%)..."
                holder.tvStatusText.setTextColor(Color.parseColor("#E1306C"))
                holder.dotStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E1306C"))
                holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#2E1220"))
                holder.ivDeletedBadge.visibility = View.GONE
            }
            is CardProgressState.Queued -> {
                val progressView = holder.ensureProgressInflated()
                progressView?.visibility = View.VISIBLE
                val qMsg = if (state.queuePosition > 1) "In queue (#${state.queuePosition})..." else "Waiting in queue..."
                holder.tvProgressMessage?.text = qMsg
                holder.tvProgressPercent?.text = "Queued"
                holder.pbHistoryHorizontal?.isIndeterminate = true

                holder.pbRedownload.visibility = View.VISIBLE
                holder.btnRedownload.visibility = View.GONE
                holder.btnViewMedia.visibility = View.GONE

                holder.tvStatusText.text = "In Download Queue"
                holder.tvStatusText.setTextColor(Color.parseColor("#38BDF8"))
                holder.dotStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#38BDF8"))
                holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#0C2A40"))
                holder.ivDeletedBadge.visibility = View.VISIBLE
            }
            is CardProgressState.Idle -> {
                holder.layoutProgress?.visibility = View.GONE
                holder.pbRedownload.visibility = View.GONE

                if (isDeleted) {
                    holder.btnRedownload.visibility = View.VISIBLE
                    holder.btnViewMedia.visibility = View.GONE
                    holder.ivDeletedBadge.visibility = View.VISIBLE

                    holder.dotStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
                    holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#2E2010"))
                    holder.tvStatusText.setTextColor(Color.parseColor("#F59E0B"))
                    holder.tvStatusText.text = "Removed from device"
                } else {
                    holder.btnRedownload.visibility = View.GONE
                    holder.btnViewMedia.visibility = View.VISIBLE
                    holder.ivDeletedBadge.visibility = View.GONE

                    holder.dotStatus.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#10B981"))
                    holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#1B2B20"))
                    holder.tvStatusText.setTextColor(Color.parseColor("#10B981"))
                    holder.tvStatusText.text = "On device"
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(Locale.US, "%.1f MB", mb)
        } else {
            String.format(Locale.US, "%.0f KB", kb)
        }
    }

    class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivHistoryThumbnail)
        val ivTypeIcon: ImageView = view.findViewById(R.id.ivHistoryTypeIcon)
        val ivDeletedBadge: ImageView = view.findViewById(R.id.ivDeletedBadge)
        val tvTitle: TextView = view.findViewById(R.id.tvHistoryTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvHistorySubtitle)
        val tvDate: TextView = view.findViewById(R.id.tvHistoryDate)
        val layoutStatusBadge: LinearLayout = view.findViewById(R.id.layoutStatusBadge)
        val dotStatus: View = view.findViewById(R.id.dotStatus)
        val tvStatusText: TextView = view.findViewById(R.id.tvStatusText)
        val btnOpenInstagram: Button = view.findViewById(R.id.btnOpenInstagram)
        val btnRedownload: Button = view.findViewById(R.id.btnRedownload)
        val btnViewMedia: Button = view.findViewById(R.id.btnViewMedia)
        val btnMoreOptions: ImageButton = view.findViewById(R.id.btnMoreOptions)
        val pbRedownload: ProgressBar = view.findViewById(R.id.pbRedownload)

        // ViewStub for progress
        val stubProgress: ViewStub? = view.findViewById(R.id.stubDownloadProgress)
        var layoutProgress: View? = view.findViewById(R.id.layoutDownloadProgress)
        var tvProgressMessage: TextView? = layoutProgress?.findViewById(R.id.tvProgressMessage)
        var tvProgressPercent: TextView? = layoutProgress?.findViewById(R.id.tvProgressPercent)
        var pbHistoryHorizontal: ProgressBar? = layoutProgress?.findViewById(R.id.pbHistoryHorizontal)

        fun ensureProgressInflated(): View? {
            if (layoutProgress == null && stubProgress != null && stubProgress.parent != null) {
                layoutProgress = stubProgress.inflate()
                tvProgressMessage = layoutProgress?.findViewById(R.id.tvProgressMessage)
                tvProgressPercent = layoutProgress?.findViewById(R.id.tvProgressPercent)
                pbHistoryHorizontal = layoutProgress?.findViewById(R.id.pbHistoryHorizontal)
            }
            return layoutProgress
        }
    }
}
