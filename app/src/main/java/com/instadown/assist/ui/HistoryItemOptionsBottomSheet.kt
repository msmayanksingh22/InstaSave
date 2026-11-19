package com.instadown.assist.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HistoryItemOptionsBottomSheet : DialogFragment() {

    interface Listener {
        fun onHistoryChanged()
        fun onReDownloadRequested(record: DownloadRecord)
        fun onConsentRequired(downloadId: Long, intentSender: android.content.IntentSender, itemIds: List<Long>)
    }

    private var listener: Listener? = null
    private var recordId: Long = -1L
    private var record: DownloadRecord? = null

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordId = arguments?.getLong(ARG_RECORD_ID, -1L) ?: -1L
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_history_item_options, container, false)
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ivThumbnail = view.findViewById<ImageView>(R.id.ivOptionsThumbnail)
        val tvTitle = view.findViewById<TextView>(R.id.tvOptionsTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvOptionsSubtitle)
        val layoutStatusBadge = view.findViewById<LinearLayout>(R.id.layoutOptionsStatusBadge)
        val dotStatus = view.findViewById<View>(R.id.dotOptionsStatus)
        val tvStatus = view.findViewById<TextView>(R.id.tvOptionsStatus)

        val cardStorageAction = view.findViewById<LinearLayout>(R.id.cardStorageAction)
        val ivStorageActionIcon = view.findViewById<ImageView>(R.id.ivStorageActionIcon)
        val tvStorageActionTitle = view.findViewById<TextView>(R.id.tvStorageActionTitle)
        val tvStorageActionSubtitle = view.findViewById<TextView>(R.id.tvStorageActionSubtitle)
        val tvStorageActionPill = view.findViewById<TextView>(R.id.tvStorageActionPill)

        val cardOpenInstagram = view.findViewById<LinearLayout>(R.id.cardOpenInstagram)
        val cardCopyLink = view.findViewById<LinearLayout>(R.id.cardCopyLink)

        val layoutDangerHeader = view.findViewById<LinearLayout>(R.id.layoutDangerHeader)
        val tvDangerChevron = view.findViewById<TextView>(R.id.tvDangerChevron)
        val layoutDangerContent = view.findViewById<LinearLayout>(R.id.layoutDangerContent)
        val btnPermanentDelete = view.findViewById<Button>(R.id.btnPermanentDelete)
        val btnClose = view.findViewById<Button>(R.id.btnCloseOptions)

        btnClose.setOnClickListener { dismiss() }

        val repo = DownloadRepository.getInstance(requireContext())

        lifecycleScope.launch {
            val loadedRecord = withContext(Dispatchers.IO) { repo.getTransactionById(recordId) }
            if (loadedRecord == null || !isAdded) {
                dismiss()
                return@launch
            }
            record = loadedRecord

            val isVideo = loadedRecord.items.firstOrNull()?.mediaType == "VIDEO" ||
                    loadedRecord.postType.equals("REEL", ignoreCase = true)
            val isDeleted = loadedRecord.isDeletedFromDevice

            // Title
            if (!loadedRecord.authorUsername.isNullOrBlank()) {
                val handle = loadedRecord.authorUsername.removePrefix("@")
                tvTitle.text = "@$handle"
            } else {
                tvTitle.text = if (loadedRecord.shortcode.isNotBlank()) loadedRecord.shortcode else "Instagram Post"
            }

            // Subtitle
            val typeDesc = when {
                loadedRecord.isCarousel -> "Carousel • ${loadedRecord.totalItemCount} items"
                isVideo -> "Instagram Reel"
                else -> "Instagram Photo"
            }
            val sizeStr = formatBytes(loadedRecord.totalBytes)
            tvSubtitle.text = if (sizeStr != "0 KB") "$typeDesc • $sizeStr" else typeDesc

            // Status Badge
            if (isDeleted) {
                dotStatus.setBackgroundColor(Color.parseColor("#F59E0B"))
                layoutStatusBadge.setBackgroundColor(Color.parseColor("#2E2010"))
                tvStatus.setTextColor(Color.parseColor("#F59E0B"))
                tvStatus.text = "Deleted from Device • In Archive"
            } else {
                dotStatus.setBackgroundColor(Color.parseColor("#10B981"))
                layoutStatusBadge.setBackgroundColor(Color.parseColor("#1B2B20"))
                tvStatus.setTextColor(Color.parseColor("#10B981"))
                tvStatus.text = "On Device • Ready in Gallery"
            }

            // Thumbnail Loading
            val context = requireContext()
            val shortcode = loadedRecord.shortcode
            val localThumbFile = if (shortcode.isNotBlank()) File(context.filesDir, "thumbnails/$shortcode.jpg") else null
            val legacyThumbFile = if (shortcode.isNotBlank()) File(context.cacheDir, "thumbnails/$shortcode.jpg") else null

            val uri = when {
                localThumbFile != null && localThumbFile.exists() -> localThumbFile.absolutePath
                legacyThumbFile != null && legacyThumbFile.exists() -> legacyThumbFile.absolutePath
                !loadedRecord.thumbnailPath.isNullOrBlank() -> loadedRecord.thumbnailPath
                !isDeleted && !loadedRecord.representativeUri.isNullOrBlank() -> loadedRecord.representativeUri
                else -> null
            }

            if (uri != null) {
                MediaThumbnailLoader.loadInto(
                    context = context,
                    uriString = uri,
                    imageView = ivThumbnail,
                    isVideo = isVideo,
                    targetWidth = 140,
                    targetHeight = 140,
                    scope = lifecycleScope
                )
            } else {
                ivThumbnail.setImageResource(if (isVideo) R.drawable.ic_video else R.drawable.ic_image)
            }

            // Configure Primary Storage Card
            if (isDeleted) {
                ivStorageActionIcon.setImageResource(R.drawable.ic_cloud_download)
                ivStorageActionIcon.setColorFilter(Color.parseColor("#E1306C"))
                tvStorageActionTitle.text = "Re-download to Device"
                tvStorageActionSubtitle.text = "Restore all or selected media files back into your device Gallery."
                tvStorageActionPill.text = "Restore"
                tvStorageActionPill.setTextColor(Color.parseColor("#E1306C"))
                tvStorageActionPill.setBackgroundColor(Color.parseColor("#3A1726"))

                cardStorageAction.setOnClickListener {
                    dismiss()
                    listener?.onReDownloadRequested(loadedRecord)
                }
            } else {
                ivStorageActionIcon.setImageResource(R.drawable.ic_delete)
                ivStorageActionIcon.setColorFilter(Color.parseColor("#EF4444"))
                tvStorageActionTitle.text = "Free Up Phone Storage"
                tvStorageActionSubtitle.text = "Deletes media files from device storage to save space. Keeps post & cover thumbnail in History so you can re-download anytime."
                tvStorageActionPill.text = "Free Up"
                tvStorageActionPill.setTextColor(Color.parseColor("#EF4444"))
                tvStorageActionPill.setBackgroundColor(Color.parseColor("#3A1C20"))

                cardStorageAction.setOnClickListener {
                    lifecycleScope.launch {
                        when (val result = repo.deleteDeviceStorageOnly(loadedRecord.id)) {
                            is com.instadown.assist.data.db.DeleteStorageResult.Completed -> {
                                Toast.makeText(
                                    requireContext(),
                                    "✓ Freed storage (${result.deletedCount} files deleted). Post preserved in History!",
                                    Toast.LENGTH_LONG
                                ).show()
                                listener?.onHistoryChanged()
                                dismiss()
                            }
                            is com.instadown.assist.data.db.DeleteStorageResult.RequiresUserConsent -> {
                                listener?.onConsentRequired(result.downloadId, result.intentSender, result.pendingItemIds)
                                dismiss()
                            }
                            is com.instadown.assist.data.db.DeleteStorageResult.Failed -> {
                                Toast.makeText(requireContext(), "Failed: ${result.reason}", Toast.LENGTH_SHORT).show()
                                dismiss()
                            }
                        }
                    }
                }
            }

            // Card 2: Open in Instagram
            cardOpenInstagram.setOnClickListener {
                val url = loadedRecord.targetDownloadUrl
                if (url.isNotBlank()) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Cannot open link", Toast.LENGTH_SHORT).show()
                    }
                }
                dismiss()
            }

            // Card 3: Copy Link
            cardCopyLink.setOnClickListener {
                val url = loadedRecord.targetDownloadUrl
                if (url.isNotBlank()) {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("instagram_url", url))
                    Toast.makeText(requireContext(), "Link copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                dismiss()
            }

            // Danger Zone Expand/Collapse
            var isDangerExpanded = false
            layoutDangerHeader.setOnClickListener {
                isDangerExpanded = !isDangerExpanded
                layoutDangerContent.visibility = if (isDangerExpanded) View.VISIBLE else View.GONE
                tvDangerChevron.text = if (isDangerExpanded) "▴" else "▾"
            }

            // Deep Permanent Deletion
            btnPermanentDelete.setOnClickListener {
                lifecycleScope.launch {
                    repo.deleteDownload(loadedRecord.id, deleteFiles = true)
                    if (loadedRecord.shortcode.isNotBlank()) {
                        try {
                            File(requireContext().filesDir, "thumbnails/${loadedRecord.shortcode}.jpg").delete()
                        } catch (_: Exception) {}
                    }
                    StorageAccountant.invalidateCache()
                    Toast.makeText(requireContext(), "Post permanently removed from History", Toast.LENGTH_SHORT).show()
                    listener?.onHistoryChanged()
                    dismiss()
                }
            }
        }
    }

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

    companion object {
        const val TAG = "HistoryItemOptionsBottomSheet"
        private const val ARG_RECORD_ID = "arg_record_id"

        fun newInstance(recordId: Long): HistoryItemOptionsBottomSheet {
            return HistoryItemOptionsBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_RECORD_ID, recordId)
                }
            }
        }
    }
}
