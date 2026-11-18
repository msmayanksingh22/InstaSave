package com.instadown.assist.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.util.MediaThumbnailLoader

class RecentDownloadsAdapter(
    private val scope: LifecycleCoroutineScope,
    private val onItemClick: (DownloadRecord) -> Unit
) : RecyclerView.Adapter<RecentDownloadsAdapter.RecentViewHolder>() {

    private val items = mutableListOf<DownloadRecord>()

    fun submitList(newItems: List<DownloadRecord>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecentViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_recent_compact, parent, false)
        return RecentViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecentViewHolder, position: Int) {
        val record = items[position]
        holder.tvDate.text = record.formattedDate

        val isVideo = record.items.any { it.mediaType.equals("VIDEO", ignoreCase = true) }

        if (record.isCarousel) {
            holder.layoutBadge.visibility = View.VISIBLE
            holder.ivTypeIcon.setImageResource(R.drawable.ic_carousel)
            holder.tvCarouselCount.visibility = View.VISIBLE
            holder.tvCarouselCount.text = "${record.availableItemCount}"
        } else if (isVideo) {
            holder.layoutBadge.visibility = View.VISIBLE
            holder.ivTypeIcon.setImageResource(R.drawable.ic_video)
            holder.tvCarouselCount.visibility = View.GONE
        } else {
            holder.layoutBadge.visibility = View.GONE
        }

        MediaThumbnailLoader.loadInto(
            context = holder.itemView.context,
            uriString = record.representativeUri,
            imageView = holder.ivThumbnail,
            isVideo = isVideo,
            scope = scope
        )

        holder.itemView.setOnClickListener {
            onItemClick(record)
        }
    }

    override fun getItemCount(): Int = items.size

    class RecentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumbnail: ImageView = view.findViewById(R.id.ivRecentThumbnail)
        val layoutBadge: FrameLayout = view.findViewById(R.id.layoutRecentBadge)
        val ivTypeIcon: ImageView = view.findViewById(R.id.ivRecentTypeIcon)
        val tvCarouselCount: TextView = view.findViewById(R.id.tvRecentCarouselCount)
        val tvDate: TextView = view.findViewById(R.id.tvRecentDate)
    }
}
