package com.instadown.assist.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.instadown.assist.R
import com.instadown.assist.data.db.DownloadItemRecord
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.util.MediaThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Smart Differential Re-Download Bottom Sheet.
 * Verifies local MediaStore availability, identifies missing or corrupted files,
 * and allows users to restore exactly the items they need with 1 tap.
 */
class ReDownloadSelectionBottomSheet : DialogFragment() {

    enum class MediaItemHealth {
        DOWNLOADED, // In Gallery (> 0 bytes, not trashed)
        MISSING,    // Deleted from device
        CORRUPTED   // 0 bytes or unreadable
    }

    data class VerifiedItem(
        val record: DownloadItemRecord,
        val index: Int,
        val health: MediaItemHealth
    )

    private var downloadId: Long = -1L
    private var downloadRecord: DownloadRecord? = null
    private val verifiedItems = mutableListOf<VerifiedItem>()
    private val selectedIndices = mutableSetOf<Int>()

    var onConfirmRestore: ((record: DownloadRecord, targetIndices: Set<Int>) -> Unit)? = null

    private lateinit var tvSheetTitle: TextView
    private lateinit var tvSheetSubtitle: TextView
    private lateinit var btnSheetClose: ImageButton
    private lateinit var btnSelectMissingOnly: Button
    private lateinit var btnSelectAll: Button
    private lateinit var pbVerifying: ProgressBar
    private lateinit var rvRedownloadItems: RecyclerView
    private lateinit var btnSheetCancel: Button
    private lateinit var btnRestoreConfirm: Button
    private lateinit var adapter: ItemsAdapter

    companion object {
        const val REQUEST_KEY_RESTORE = "request_key_restore"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"
        const val EXTRA_INDICES = "extra_indices"
        private const val ARG_DOWNLOAD_ID = "arg_download_id"

        fun newInstance(downloadId: Long): ReDownloadSelectionBottomSheet {
            return ReDownloadSelectionBottomSheet().apply {
                arguments = Bundle().apply {
                    putLong(ARG_DOWNLOAD_ID, downloadId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloadId = arguments?.getLong(ARG_DOWNLOAD_ID, -1L) ?: -1L
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setGravity(android.view.Gravity.BOTTOM)
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setDimAmount(0.80f)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_redownload_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSheetTitle = view.findViewById(R.id.tvSheetTitle)
        tvSheetSubtitle = view.findViewById(R.id.tvSheetSubtitle)
        btnSheetClose = view.findViewById(R.id.btnSheetClose)
        btnSelectMissingOnly = view.findViewById(R.id.btnSelectMissingOnly)
        btnSelectAll = view.findViewById(R.id.btnSelectAll)
        pbVerifying = view.findViewById(R.id.pbVerifying)
        rvRedownloadItems = view.findViewById(R.id.rvRedownloadItems)
        btnSheetCancel = view.findViewById(R.id.btnSheetCancel)
        btnRestoreConfirm = view.findViewById(R.id.btnRestoreConfirm)

        adapter = ItemsAdapter()
        rvRedownloadItems.layoutManager = LinearLayoutManager(requireContext())
        rvRedownloadItems.adapter = adapter

        btnSheetClose.setOnClickListener { dismiss() }
        btnSheetCancel.setOnClickListener { dismiss() }

        btnSelectMissingOnly.setOnClickListener {
            selectedIndices.clear()
            verifiedItems.filter { it.health != MediaItemHealth.DOWNLOADED }.forEach {
                selectedIndices.add(it.index)
            }
            updateConfirmButton()
            adapter.notifyDataSetChanged()
        }

        btnSelectAll.setOnClickListener {
            if (selectedIndices.size == verifiedItems.size) {
                selectedIndices.clear()
            } else {
                selectedIndices.clear()
                verifiedItems.forEach { selectedIndices.add(it.index) }
            }
            updateConfirmButton()
            adapter.notifyDataSetChanged()
        }

        btnRestoreConfirm.setOnClickListener {
            val record = downloadRecord
            if (record == null || selectedIndices.isEmpty()) {
                Toast.makeText(requireContext(), "Please select at least 1 item", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            setFragmentResult(
                REQUEST_KEY_RESTORE,
                Bundle().apply {
                    putLong(EXTRA_DOWNLOAD_ID, record.id)
                    putIntArray(EXTRA_INDICES, selectedIndices.toIntArray())
                }
            )
            dismiss()
            onConfirmRestore?.invoke(record, selectedIndices.toSet())
        }

        loadAndVerify()
    }

    private fun loadAndVerify() {
        if (downloadId == -1L) {
            dismiss()
            return
        }

        pbVerifying.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val repo = DownloadRepository.getInstance(requireContext())
            val record = repo.getTransactionById(downloadId)
            if (record == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Post record not found", Toast.LENGTH_SHORT).show()
                    dismiss()
                }
                return@launch
            }
            downloadRecord = record

            val verified = record.items.mapIndexed { idx, item ->
                val health = verifyItemHealth(requireContext(), item)
                VerifiedItem(item, idx, health)
            }

            withContext(Dispatchers.Main) {
                verifiedItems.clear()
                verifiedItems.addAll(verified)

                val missingCount = verified.count { it.health != MediaItemHealth.DOWNLOADED }
                val totalCount = verified.size

                tvSheetTitle.text = "Restore ${record.userFacingTitle}"
                tvSheetSubtitle.text = if (missingCount > 0) {
                    "$missingCount of $totalCount items missing from Gallery"
                } else {
                    "All $totalCount items are already in Gallery"
                }

                // Default selection: select missing/corrupted items; if none missing, select all
                selectedIndices.clear()
                if (missingCount > 0) {
                    verified.filter { it.health != MediaItemHealth.DOWNLOADED }.forEach {
                        selectedIndices.add(it.index)
                    }
                } else {
                    selectedIndices.clear()
                }

                pbVerifying.visibility = View.GONE
                updateConfirmButton()
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun updateConfirmButton() {
        val count = selectedIndices.size
        btnRestoreConfirm.text = if (count > 0) "Restore ($count)" else "Restore (0)"
        btnRestoreConfirm.isEnabled = count > 0
        btnRestoreConfirm.alpha = if (count > 0) 1.0f else 0.5f
    }

    private fun verifyItemHealth(context: Context, item: DownloadItemRecord): MediaItemHealth {
        val uriStr = item.mediaUri ?: return MediaItemHealth.MISSING
        val uri = try { Uri.parse(uriStr) } catch (_: Exception) { return MediaItemHealth.MISSING }

        return try {
            val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.IS_TRASHED)
            } else {
                arrayOf(MediaStore.MediaColumns.SIZE)
            }

            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else -1L
                    if (size == 0L) return MediaItemHealth.CORRUPTED

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val trashIndex = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                        if (trashIndex >= 0 && cursor.getInt(trashIndex) != 0) {
                            return MediaItemHealth.MISSING
                        }
                    }
                    return MediaItemHealth.DOWNLOADED
                }
            }

            // Fallback check: try reading stream
            context.contentResolver.openInputStream(uri)?.use { stream ->
                if (stream.available() > 0) MediaItemHealth.DOWNLOADED else MediaItemHealth.CORRUPTED
            } ?: MediaItemHealth.MISSING
        } catch (_: Exception) {
            MediaItemHealth.MISSING
        }
    }

    private inner class ItemsAdapter : RecyclerView.Adapter<ItemsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivThumb: ImageView = view.findViewById(R.id.ivItemThumb)
            val tvTitle: TextView = view.findViewById(R.id.tvItemTitle)
            val tvBadge: TextView = view.findViewById(R.id.tvItemStatusBadge)
            val cbSelected: CheckBox = view.findViewById(R.id.cbItemSelected)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_redownload_selection, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = verifiedItems[position]
            val slideNum = item.index + 1
            val typeStr = if (item.record.mediaType.equals("VIDEO", ignoreCase = true)) "Video" else "Photo"
            holder.tvTitle.text = "Slide $slideNum ($typeStr)"

            when (item.health) {
                MediaItemHealth.DOWNLOADED -> {
                    holder.tvBadge.text = "In Gallery"
                    holder.tvBadge.setBackgroundResource(R.drawable.badge_status_downloaded)
                    holder.tvBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_green))
                }
                MediaItemHealth.MISSING -> {
                    holder.tvBadge.text = "Deleted from Device"
                    holder.tvBadge.setBackgroundResource(R.drawable.badge_status_missing)
                    holder.tvBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_amber))
                }
                MediaItemHealth.CORRUPTED -> {
                    holder.tvBadge.text = "Corrupted (0 KB)"
                    holder.tvBadge.setBackgroundResource(R.drawable.badge_status_corrupted)
                    holder.tvBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.status_red))
                }
            }

            holder.cbSelected.isChecked = selectedIndices.contains(item.index)

            val toggleAction = View.OnClickListener {
                if (selectedIndices.contains(item.index)) {
                    selectedIndices.remove(item.index)
                } else {
                    selectedIndices.add(item.index)
                }
                holder.cbSelected.isChecked = selectedIndices.contains(item.index)
                updateConfirmButton()
            }

            holder.itemView.setOnClickListener(toggleAction)
            holder.cbSelected.setOnClickListener(toggleAction)

            val thumbUri = item.record.mediaUri ?: downloadRecord?.thumbnailPath
            MediaThumbnailLoader.loadInto(
                context = requireContext(),
                uriString = thumbUri,
                imageView = holder.ivThumb,
                isVideo = item.record.mediaType.equals("VIDEO", ignoreCase = true),
                targetWidth = 120,
                targetHeight = 120,
                scope = viewLifecycleOwner.lifecycleScope
            )
        }

        override fun getItemCount(): Int = verifiedItems.size
    }
}
