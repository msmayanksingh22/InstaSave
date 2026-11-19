package com.instadown.assist.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class GalleryBatchOptionsBottomSheet : DialogFragment() {

    interface Listener {
        fun onFreeUpStorage(selectedIds: List<Long>)
        fun onPurgePermanently(selectedIds: List<Long>)
    }

    private var listener: Listener? = null
    private var selectedIds: List<Long> = emptyList()
    private var isDangerArmed = false

    fun setListener(listener: Listener) {
        this.listener = listener
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val longArray = arguments?.getLongArray(ARG_SELECTED_IDS)
        selectedIds = longArray?.toList() ?: emptyList()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_gallery_batch_options, container, false)
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

        val tvTitle = view.findViewById<TextView>(R.id.tvBatchTitle)
        val tvSubtitle = view.findViewById<TextView>(R.id.tvBatchSubtitle)
        val cardFreeUp = view.findViewById<LinearLayout>(R.id.cardFreeUpStorage)
        val tvFreeUpPill = view.findViewById<TextView>(R.id.tvFreeUpPill)

        val layoutDangerHeader = view.findViewById<LinearLayout>(R.id.layoutBatchDangerHeader)
        val tvDangerChevron = view.findViewById<TextView>(R.id.tvBatchDangerChevron)
        val layoutDangerContent = view.findViewById<LinearLayout>(R.id.layoutBatchDangerContent)
        val btnPermanentDelete = view.findViewById<Button>(R.id.btnBatchPermanentDelete)
        val btnCancel = view.findViewById<Button>(R.id.btnBatchCancel)

        val count = selectedIds.size
        tvTitle.text = "Selected Media ($count item${if (count != 1) "s" else ""})"

        btnCancel.setOnClickListener { dismiss() }

        // Load estimated file sizes asynchronously
        val repo = DownloadRepository.getInstance(requireContext())
        lifecycleScope.launch {
            val totalBytes = withContext(Dispatchers.IO) {
                var sum = 0L
                for (id in selectedIds) {
                    val rec = repo.getTransactionById(id) ?: continue
                    sum += if (rec.totalBytes > 0) rec.totalBytes else rec.items.sumOf { it.sizeBytes }
                }
                sum
            }

            val sizeStr = formatBytes(totalBytes)
            tvSubtitle.text = "Reclaimable Storage: ~$sizeStr"
            tvFreeUpPill.text = "Free Up ($sizeStr)"
        }

        // Primary action: Free up storage (preserve history & cover thumbnails)
        cardFreeUp.setOnClickListener {
            listener?.onFreeUpStorage(selectedIds)
            dismiss()
        }

        // Accordion toggle for Danger Zone
        layoutDangerHeader.setOnClickListener {
            val isCurrentlyVisible = layoutDangerContent.visibility == View.VISIBLE
            layoutDangerContent.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            tvDangerChevron.text = if (isCurrentlyVisible) "▾" else "▴"
        }

        // Deep permanent purge with two-tap safety arming
        btnPermanentDelete.setOnClickListener {
            if (!isDangerArmed) {
                isDangerArmed = true
                btnPermanentDelete.text = "⚠️ Tap again to confirm permanent erase"
                btnPermanentDelete.setBackgroundColor(Color.parseColor("#5A1A1E"))
            } else {
                listener?.onPurgePermanently(selectedIds)
                dismiss()
            }
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb < 0.1) "<0.1 MB" else String.format(Locale.US, "%.1f MB", mb)
    }

    companion object {
        const val TAG = "GalleryBatchOptions"
        private const val ARG_SELECTED_IDS = "arg_selected_ids"

        fun newInstance(selectedIds: List<Long>): GalleryBatchOptionsBottomSheet {
            return GalleryBatchOptionsBottomSheet().apply {
                arguments = Bundle().apply {
                    putLongArray(ARG_SELECTED_IDS, selectedIds.toLongArray())
                }
            }
        }
    }
}
