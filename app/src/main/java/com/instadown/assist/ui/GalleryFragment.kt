package com.instadown.assist.ui

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import androidx.core.widget.doAfterTextChanged
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.button.MaterialButton
import com.instadown.assist.R
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DeleteStorageResult
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.data.db.ItemStatus
import com.instadown.assist.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

class GalleryFragment : Fragment() {

    private enum class FilterType { ALL, PHOTOS, REELS, CAROUSELS }

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private var currentFilter = FilterType.ALL
    private var allTransactions: List<DownloadRecord> = emptyList()
    private lateinit var adapter: GalleryAdapter
    private var lastReturnedPosition = -1

    // Sequential Queue for Scoped Storage Deletion Consent
    private val pendingFreeUpQueue = ArrayDeque<Long>()
    private var pendingConsentDownloadId: Long? = null
    private var pendingConsentItemIds: List<Long>? = null

    private val deleteConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val dlId = pendingConsentDownloadId
        val itemIds = pendingConsentItemIds
        pendingConsentDownloadId = null
        pendingConsentItemIds = null

        if (result.resultCode == Activity.RESULT_OK && dlId != null && itemIds != null) {
            val appCtx = context?.applicationContext ?: return@registerForActivityResult
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DownloadRepository.getInstance(appCtx).finalizeConsentedDeletion(dlId, itemIds)
                    StorageAccountant.invalidateCache()
                }
                processNextFreeUpItem()
            }
        } else {
            // User cancelled or failed this item; continue queue
            processNextFreeUpItem()
        }
    }

    // Intercept back key when in selection mode
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (::adapter.isInitialized && adapter.isSelectionMode) {
                exitSelectionMode()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            currentFilter = FilterType.values().firstOrNull { it.name == savedInstanceState.getString("galleryFilter") } ?: FilterType.ALL
            pendingConsentDownloadId = savedInstanceState.getLong(KEY_PENDING_DL_ID, -1L).takeIf { it != -1L }
            pendingConsentItemIds = savedInstanceState.getLongArray(KEY_PENDING_ITEM_IDS)?.toList()
            val queueArray = savedInstanceState.getLongArray("key_pending_free_up_queue")
            if (queueArray != null) {
                pendingFreeUpQueue.clear()
                queueArray.forEach { pendingFreeUpQueue.add(it) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("galleryFilter", currentFilter.name)
        pendingConsentDownloadId?.let { outState.putLong(KEY_PENDING_DL_ID, it) }
        pendingConsentItemIds?.let { outState.putLongArray(KEY_PENDING_ITEM_IDS, it.toLongArray()) }
        if (pendingFreeUpQueue.isNotEmpty()) {
            outState.putLongArray("key_pending_free_up_queue", pendingFreeUpQueue.toLongArray())
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        setupRecyclerView()
        setupListeners()
        setupFilterChips()
        binding.gallerySearch.doAfterTextChanged { applyFilter() }
        binding.gallerySearch.setOnEditorActionListener { field, action, _ ->
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            androidx.core.view.ViewCompat.getWindowInsetsController(field)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            field.clearFocus()
            true
        }

        // Sync with SQLite updates (conflated & debounced)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val appCtx = requireContext().applicationContext
                DownloadRepository.getInstance(appCtx).transactionEvents
                    .conflate()
                    .collectLatest {
                        delay(150)
                        loadGallery()
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        bindExitRemapper()
        loadGallery()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            if (::adapter.isInitialized && adapter.isSelectionMode) {
                exitSelectionMode()
            }
            ActivityCompat.setExitSharedElementCallback(requireActivity(), null)
        } else if (_binding != null) {
            bindExitRemapper()
            loadGallery()
        }
    }

    private fun bindExitRemapper() {
        if (!isAdded || _binding == null) return
        val appCompatActivity = requireActivity() as? AppCompatActivity ?: return
        SharedMediaTransition.bindGalleryExitRemapper(
            activity = appCompatActivity,
            recyclerView = binding.rvGallery,
            getCurrentReturnPosition = { lastReturnedPosition },
            getMediaIdAt = { pos -> adapter.getRecordAt(pos)?.id ?: -1L }
        )
    }

    fun onActivityReenter(returnPos: Int) {
        if (returnPos >= 0 && _binding != null) {
            lastReturnedPosition = returnPos
            binding.rvGallery.scrollToPosition(returnPos)
        }
    }

    private fun setupRecyclerView() {
        adapter = GalleryAdapter(
            scope = viewLifecycleOwner.lifecycleScope,
            onItemClick = { record, thumbView, position ->
                if (adapter.isSelectionMode) {
                    toggleSelection(record.id)
                } else {
                    lastReturnedPosition = position
                    val appCompatActivity = requireActivity() as? AppCompatActivity
                    if (appCompatActivity != null) {
                        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
                        val actions = layoutInflater.inflate(R.layout.sheet_gallery_post, null)
                        actions.findViewById<android.widget.ImageView>(R.id.postCover).setImageDrawable(thumbView.drawable?.constantState?.newDrawable() ?: thumbView.drawable)
                        val caption = actions.findViewById<android.widget.TextView>(R.id.postCaption)
                        caption.text = record.caption?.trim()
                        caption.visibility = if (caption.text.isNullOrBlank()) View.GONE else View.VISIBLE
                        actions.findViewById<android.widget.TextView>(R.id.postHeading).text = record.userFacingTitle
                        actions.findViewById<android.widget.TextView>(R.id.postSummary).text =
                            "${record.formattedDate} · ${record.userFacingStatus}"
                        actions.findViewById<View>(R.id.viewPostMedia).setOnClickListener {
                            sheet.dismiss()
                            SharedMediaTransition.launchViewer(appCompatActivity, thumbView, record.id, position)
                        }
                        actions.findViewById<View>(R.id.selectPost).setOnClickListener {
                            sheet.dismiss()
                            enterSelectionMode(record.id)
                        }
                        sheet.setContentView(actions)
                        sheet.show()
                    }
                }
            },
            onItemLongClick = { record ->
                if (!adapter.isSelectionMode) {
                    enterSelectionMode(record.id)
                } else {
                    toggleSelection(record.id)
                }
            }
        )

        binding.rvGallery.layoutManager = GridLayoutManager(requireContext(), resources.getInteger(R.integer.media_grid_columns))
        binding.rvGallery.setHasFixedSize(true)
        binding.rvGallery.adapter = adapter
    }

    private fun setupListeners() {
        binding.swipeRefreshGallery.setColorSchemeResources(R.color.insta_pink, R.color.insta_purple, R.color.insta_amber)
        binding.swipeRefreshGallery.setProgressBackgroundColorSchemeResource(R.color.card_bg)
        binding.swipeRefreshGallery.setOnRefreshListener {
            val appCtx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    DownloadRepository.getInstance(appCtx).reconcileWithMediaStore()
                }
                loadGallery()
            }
        }

        binding.btnAddLink.setOnClickListener {
            (activity as? MainActivity)?.navigateToTab(R.id.nav_home)
        }

        binding.btnStartSelection.setOnClickListener {
            if (adapter.itemCount > 0) enterSelectionMode(null)
        }

        binding.btnCloseSelection.setOnClickListener {
            exitSelectionMode()
        }

        binding.btnSelectAllToggle.setOnClickListener {
            val displayed = getFilteredList()
            if (adapter.selectedIds.size == displayed.size) {
                adapter.selectedIds.clear()
            } else {
                adapter.selectedIds.clear()
                adapter.selectedIds.addAll(displayed.map { it.id })
            }
            updateSelectionBar()
            adapter.notifyDataSetChanged()
        }

        binding.btnBatchShare.setOnClickListener { executeBatchShare() }
        binding.btnBatchDelete.setOnClickListener { executeBatchDelete() }
    }

    private fun setupFilterChips() {
        binding.chipAll.setOnClickListener { setFilter(FilterType.ALL) }
        binding.chipPhotos.setOnClickListener { setFilter(FilterType.PHOTOS) }
        binding.chipReels.setOnClickListener { setFilter(FilterType.REELS) }
        binding.chipCarousels.setOnClickListener { setFilter(FilterType.CAROUSELS) }
        setFilter(currentFilter)
    }

    private fun enterSelectionMode(initialSelectedId: Long?) {
        adapter.isSelectionMode = true
        adapter.selectedIds.clear()
        if (initialSelectedId != null) adapter.selectedIds.add(initialSelectedId)

        binding.gallerySearch.clearFocus()
        androidx.core.view.ViewCompat.getWindowInsetsController(binding.root)?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        binding.gallerySearchBox.visibility = View.GONE
        binding.scrollFilters.visibility = View.GONE
        binding.galleryTopBar.visibility = View.GONE
        binding.gallerySelectionBar.visibility = View.VISIBLE
        backCallback.isEnabled = true
        updateSelectionBar()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        adapter.isSelectionMode = false
        adapter.selectedIds.clear()
        binding.gallerySelectionBar.visibility = View.GONE
        binding.gallerySearchBox.visibility = View.VISIBLE
        binding.scrollFilters.visibility = View.VISIBLE
        binding.galleryTopBar.visibility = View.VISIBLE
        backCallback.isEnabled = false
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(id: Long) {
        val position = adapter.getPositionForId(id)
        if (adapter.selectedIds.contains(id)) adapter.selectedIds.remove(id)
        else adapter.selectedIds.add(id)

        if (adapter.selectedIds.isEmpty()) {
            exitSelectionMode()
        } else {
            updateSelectionBar()
            if (position >= 0) adapter.notifyItemChanged(position)
            else adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionBar() {
        val count = adapter.selectedIds.size
        binding.tvSelectionCount.text = "$count selected"
        val displayed = getFilteredList()
        binding.btnSelectAllToggle.text = if (count == displayed.size) "Clear" else "Select All"
    }

    private fun loadGallery() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val transactions = withContext(Dispatchers.IO) {
                DownloadRepository.getInstance(appCtx).getAllTransactions(includeFailed = false)
            }
            if (_binding == null || !isAdded) return@launch
            binding.swipeRefreshGallery.isRefreshing = false
            allTransactions = transactions
            applyFilter()
        }
    }

    private fun setFilter(filter: FilterType) {
        currentFilter = filter
        val pink = ContextCompat.getColor(requireContext(), R.color.insta_pink)
        val muted = ContextCompat.getColor(requireContext(), R.color.text_secondary)
        val activeText = ContextCompat.getColor(requireContext(), R.color.bg_dark)
        val cardBg = ContextCompat.getColor(requireContext(), R.color.card_bg)

        fun styleChip(btn: MaterialButton, isActive: Boolean) {
            btn.strokeColor = ContextCompat.getColorStateList(requireContext(), if (isActive) R.color.insta_pink else R.color.card_stroke)
            btn.isSelected = isActive
            btn.setTextColor(if (isActive) activeText else muted)
            btn.backgroundTintList = ColorStateList.valueOf(if (isActive) pink else cardBg)
        }

        styleChip(binding.chipAll, filter == FilterType.ALL)
        styleChip(binding.chipPhotos, filter == FilterType.PHOTOS)
        styleChip(binding.chipReels, filter == FilterType.REELS)
        styleChip(binding.chipCarousels, filter == FilterType.CAROUSELS)
        applyFilter()
    }

    private fun getFilteredList(): List<DownloadRecord> {
        val byType = when (currentFilter) {
            FilterType.ALL -> allTransactions
            FilterType.PHOTOS -> allTransactions.filter { !it.isCarousel && !it.postType.equals("REEL", ignoreCase = true) && it.items.firstOrNull()?.mediaType != "VIDEO" }
            FilterType.REELS -> allTransactions.filter { it.postType.equals("REEL", ignoreCase = true) || it.items.firstOrNull()?.mediaType == "VIDEO" }
            FilterType.CAROUSELS -> allTransactions.filter { it.isCarousel }
        }
        val query = binding.gallerySearch.text.toString().trim()
        return if (query.isEmpty()) byType else byType.filter { record ->
            record.userFacingTitle.contains(query, ignoreCase = true) ||
                record.caption.orEmpty().contains(query, ignoreCase = true) ||
                record.shortcode.contains(query, ignoreCase = true)
        }
    }

    private fun applyFilter() {
        val filtered = getFilteredList()
        binding.btnStartSelection.isEnabled = filtered.isNotEmpty()
        if (adapter.isSelectionMode) {
            adapter.selectedIds.retainAll(filtered.map { it.id }.toSet())
            if (filtered.isEmpty()) exitSelectionMode() else updateSelectionBar()
        }
        if (filtered.isEmpty()) {
            binding.tvEmptyGallery.setText(when {
                binding.gallerySearch.text.toString().isNotBlank() -> R.string.gallery_search_empty
                currentFilter != FilterType.ALL -> R.string.gallery_filter_empty
                else -> R.string.gallery_library_empty
            })
            binding.swipeRefreshGallery.visibility = View.GONE
            binding.tvEmptyGallery.visibility = View.VISIBLE
        } else {
            binding.swipeRefreshGallery.visibility = View.VISIBLE
            binding.tvEmptyGallery.visibility = View.GONE
        }
        adapter.submitList(filtered)
    }

    private fun executeBatchShare() {
        val selectedRecords = allTransactions.filter { adapter.selectedIds.contains(it.id) }
        val allUris = selectedRecords.flatMap { record ->
            record.items.filter { it.status == ItemStatus.AVAILABLE && it.mediaUri != null }
                .mapNotNull { runCatching { Uri.parse(it.mediaUri) }.getOrNull() }
        }

        if (allUris.isEmpty()) {
            Toast.makeText(requireContext(), "No media files available to share", Toast.LENGTH_SHORT).show()
            return
        }

        val shareIntent = if (allUris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, allUris[0])
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(allUris))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(shareIntent, "Share ${allUris.size} Media Item(s)"))
    }

    private fun executeBatchDelete() {
        val count = adapter.selectedIds.size
        if (count == 0) return
        val targetIds = adapter.selectedIds.toList()

        val sheet = GalleryBatchOptionsBottomSheet.newInstance(targetIds)
        sheet.setListener(object : GalleryBatchOptionsBottomSheet.Listener {
            override fun onFreeUpStorage(selectedIds: List<Long>) {
                pendingFreeUpQueue.clear()
                pendingFreeUpQueue.addAll(selectedIds)
                processNextFreeUpItem()
            }

            override fun onPurgePermanently(selectedIds: List<Long>) {
                val appCtx = context?.applicationContext ?: return
                lifecycleScope.launch {
                    val repo = DownloadRepository.getInstance(appCtx)
                    withContext(Dispatchers.IO) {
                        for (id in selectedIds) {
                            repo.deleteDownload(id, deleteFiles = true)
                        }
                        StorageAccountant.invalidateCache()
                    }
                    withContext(Dispatchers.Main) {
                        if (_binding == null || !isAdded) return@withContext
                        Toast.makeText(
                            context ?: return@withContext,
                            "Permanently erased from history and storage",
                            Toast.LENGTH_SHORT
                        ).show()
                        exitSelectionMode()
                        loadGallery()
                    }
                }
            }
        })
        sheet.show(parentFragmentManager, GalleryBatchOptionsBottomSheet.TAG)
    }

    private fun processNextFreeUpItem() {
        val nextId: Long? = pendingFreeUpQueue.pollFirst()
        if (nextId == null) {
            StorageAccountant.invalidateCache()
            if (_binding != null && isAdded) {
                Toast.makeText(context ?: return, "✓ Freed up storage. Posts preserved in History!", Toast.LENGTH_SHORT).show()
                exitSelectionMode()
                loadGallery()
            }
            return
        }

        val appCtx = context?.applicationContext ?: return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DownloadRepository.getInstance(appCtx).deleteDeviceStorageOnly(nextId)
            }
            if (_binding == null || !isAdded) return@launch

            when (result) {
                is DeleteStorageResult.RequiresUserConsent -> {
                    pendingConsentDownloadId = result.downloadId
                    pendingConsentItemIds = result.pendingItemIds
                    try {
                        deleteConsentLauncher.launch(IntentSenderRequest.Builder(result.intentSender).build())
                    } catch (e: Exception) {
                        Log.e("GalleryFragment", "Failed to launch delete consent: ${e.message}")
                        pendingConsentDownloadId = null
                        pendingConsentItemIds = null
                        processNextFreeUpItem()
                    }
                }
                is DeleteStorageResult.Completed,
                is DeleteStorageResult.Failed -> {
                    processNextFreeUpItem()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ActivityCompat.setExitSharedElementCallback(requireActivity(), null)
        binding.rvGallery.adapter = null
        _binding = null
    }

    companion object {
        private const val KEY_PENDING_DL_ID = "key_pending_consent_dl_id"
        private const val KEY_PENDING_ITEM_IDS = "key_pending_consent_item_ids"
    }
}
