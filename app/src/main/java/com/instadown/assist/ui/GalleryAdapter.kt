package com.instadown.assist.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.TransactionStatus
import com.instadown.assist.util.MediaThumbnailLoader

class GalleryAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val onItemClick: (DownloadRecord, ImageView, Int) -> Unit,
    private val onItemLongClick: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<GalleryAdapter.GalleryViewHolder>() {

    private val items = mutableListOf<DownloadRecord>()
    var isSelectionMode = false
    val selectedIds = mutableSetOf<Long>()

    fun submitList(newItems: List<DownloadRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getRecordAt(position: Int): DownloadRecord? = items.getOrNull(position)

    fun getPositionForId(id: Long): Int = items.indexOfFirst { it.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GalleryViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_card, parent, false)
        return GalleryViewHolder(view)
    }

    override fun onBindViewHolder(holder: GalleryViewHolder, position: Int) {
        val record = items[position]
        val context = holder.itemView.context

        holder.tvDate.text = record.formattedDate
        holder.tvTitle.text = record.userFacingTitle
        holder.tvStatus.text = record.userFacingStatus

        val statusColor = when {
            record.isPartiallyDeleted -> R.color.status_amber
            record.status == TransactionStatus.PARTIAL_SUCCESS -> R.color.status_amber
            else -> R.color.status_green
        }
        holder.tvStatus.setTextColor(ContextCompat.getColor(context, statusColor))

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
            context = context,
            uriString = record.representativeUri,
            imageView = holder.ivThumbnail,
            isVideo = isVideo,
            scope = scope
        )

        holder.itemView.setOnClickListener { onItemClick(record, holder.ivThumbnail, position) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(record)
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class GalleryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
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
