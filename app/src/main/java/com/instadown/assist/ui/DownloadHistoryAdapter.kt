package com.instadown.assist.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.util.MediaThumbnailLoader

sealed class CardProgressState {
    object Idle : CardProgressState()
    data class Queued(val queuePosition: Int) : CardProgressState()
    data class InProgress(val percent: Int, val message: String) : CardProgressState()
}

class DownloadHistoryAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onViewClick: (DownloadRecord) -> Unit,
    private val onRedownloadClick: (DownloadRecord) -> Unit,
    private val onOpenInstagramClick: (DownloadRecord) -> Unit,
    private val onItemLongClick: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<DownloadHistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<DownloadRecord>()
    private val progressStates = mutableMapOf<Long, CardProgressState>()

    fun submitList(newItems: List<DownloadRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
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
            notifyItemChanged(index)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history_card, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        val record = items[position]
        val isVideo = record.items.firstOrNull()?.mediaType == "VIDEO" || record.postType.equals("REEL", ignoreCase = true)
        val isDeleted = record.isDeletedFromDevice
        val state = progressStates[record.id] ?: CardProgressState.Idle

        // Title: Prioritize @username creator handle for clear instant recognition
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
        holder.tvDate.text = "Saved on ${record.formattedDate} • $displayShortcode"

        // Type Icon
        if (record.isCarousel) {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_carousel)
        } else if (isVideo) {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_video)
        } else {
            holder.ivTypeIcon.setImageResource(R.drawable.ic_image)
        }

        // Status badge & progress styling
        when (state) {
            is CardProgressState.InProgress -> {
                holder.layoutProgress.visibility = View.VISIBLE
                holder.tvProgressMessage.text = state.message
                holder.tvProgressPercent.text = "${state.percent}%"
                holder.pbHistoryHorizontal.isIndeterminate = false
                holder.pbHistoryHorizontal.progress = state.percent

                holder.pbRedownload.visibility = View.VISIBLE
                holder.btnRedownload.visibility = View.GONE
                holder.btnViewMedia.visibility = View.GONE

                holder.tvStatusText.text = "Re-downloading (${state.percent}%)..."
                holder.tvStatusText.setTextColor(Color.parseColor("#E1306C"))
                holder.dotStatus.setBackgroundColor(Color.parseColor("#E1306C"))
                holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#2E1220"))
                holder.ivDeletedBadge.visibility = View.GONE
            }
            is CardProgressState.Queued -> {
                holder.layoutProgress.visibility = View.VISIBLE
                val qMsg = if (state.queuePosition > 1) "In queue (#${state.queuePosition})..." else "Waiting in queue..."
                holder.tvProgressMessage.text = qMsg
                holder.tvProgressPercent.text = "Queued"
                holder.pbHistoryHorizontal.isIndeterminate = true

                holder.pbRedownload.visibility = View.VISIBLE
                holder.btnRedownload.visibility = View.GONE
                holder.btnViewMedia.visibility = View.GONE

                holder.tvStatusText.text = "In Download Queue"
                holder.tvStatusText.setTextColor(Color.parseColor("#38BDF8"))
                holder.dotStatus.setBackgroundColor(Color.parseColor("#38BDF8"))
                holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#0C2A40"))
                holder.ivDeletedBadge.visibility = View.VISIBLE
            }
            is CardProgressState.Idle -> {
                holder.layoutProgress.visibility = View.GONE
                holder.pbRedownload.visibility = View.GONE

                if (isDeleted) {
                    holder.btnRedownload.visibility = View.VISIBLE
                    holder.btnViewMedia.visibility = View.GONE
                    holder.ivDeletedBadge.visibility = View.VISIBLE

                    holder.dotStatus.setBackgroundColor(Color.parseColor("#F59E0B")) // Amber
                    holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#2E2010"))
                    holder.tvStatusText.setTextColor(Color.parseColor("#F59E0B"))
                    holder.tvStatusText.text = "Deleted from Device • Tap Re-download"
                } else {
                    holder.btnRedownload.visibility = View.GONE
                    holder.btnViewMedia.visibility = View.VISIBLE
                    holder.ivDeletedBadge.visibility = View.GONE

                    holder.dotStatus.setBackgroundColor(Color.parseColor("#10B981")) // Green
                    holder.layoutStatusBadge.setBackgroundColor(Color.parseColor("#1B2B20"))
                    holder.tvStatusText.setTextColor(Color.parseColor("#10B981"))
                    holder.tvStatusText.text = "On Device • Ready in Gallery"
                }
            }
        }

        // Thumbnail Loader: Load persistent internal thumbnail, DB thumbnail, or valid local media
        val context = holder.itemView.context
        val shortcode = record.shortcode
        val localThumbFile = if (shortcode.isNotBlank()) java.io.File(context.filesDir, "thumbnails/$shortcode.jpg") else null
        val legacyThumbFile = if (shortcode.isNotBlank()) java.io.File(context.cacheDir, "thumbnails/$shortcode.jpg") else null

        val uri = when {
            // 1. Persistent internal disk thumbnail (takes precedence for instant offline load even for deleted items)
            localThumbFile != null && localThumbFile.exists() -> localThumbFile.absolutePath
            legacyThumbFile != null && legacyThumbFile.exists() -> legacyThumbFile.absolutePath
            // 2. Stored DB thumbnailPath (e.g. cached path or CDN preview)
            !record.thumbnailPath.isNullOrBlank() -> record.thumbnailPath
            // 3. Local media file if item is still present on device
            !isDeleted && !record.representativeUri.isNullOrBlank() -> record.representativeUri
            // Do NOT fall back to dead https://www.instagram.com/p/$shortcode/media/?size=m which redirects to login!
            else -> null
        }

        if (uri != null) {
            MediaThumbnailLoader.loadInto(
                context = context,
                uriString = uri,
                imageView = holder.ivThumbnail,
                isVideo = isVideo,
                targetWidth = 240,
                targetHeight = 240,
                scope = scope
            )
        } else {
            holder.ivThumbnail.setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
        }

        holder.btnOpenInstagram.setOnClickListener { onOpenInstagramClick(record) }
        holder.btnViewMedia.setOnClickListener { onViewClick(record) }
        holder.btnRedownload.setOnClickListener { onRedownloadClick(record) }
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

    override fun getItemCount(): Int = items.size

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        return if (mb >= 1.0) {
            String.format(java.util.Locale.US, "%.1f MB", mb)
        } else {
            String.format(java.util.Locale.US, "%.0f KB", kb)
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
        val pbRedownload: ProgressBar = view.findViewById(R.id.pbRedownload)

        // Progress section
        val layoutProgress: LinearLayout = view.findViewById(R.id.layoutDownloadProgress)
        val tvProgressMessage: TextView = view.findViewById(R.id.tvProgressMessage)
        val tvProgressPercent: TextView = view.findViewById(R.id.tvProgressPercent)
        val pbHistoryHorizontal: ProgressBar = view.findViewById(R.id.pbHistoryHorizontal)
    }
}
