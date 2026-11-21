package com.instadown.assist.ui.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.instadown.assist.data.StorageAccountant
import com.instadown.assist.data.db.DownloadRecord
import com.instadown.assist.data.db.DownloadRepository
import com.instadown.assist.overlay.InstaSavePreferences
import com.instadown.assist.service.FloatingTriggerService
import com.instadown.assist.service.InstaAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val isServiceRunning: Boolean = false,
    val isA11yEnabled: Boolean = false,
    val canDrawOverlays: Boolean = false,
    val canPostNotifications: Boolean = true,
    val isSubmittingUrl: Boolean = false,
    val urlError: String? = null,
    val recentDownloads: List<DownloadRecord> = emptyList(),
    val storageSummary: String = "Calculating storage..."
) {
    val isMissingPermissions: Boolean
        get() = !isA11yEnabled || !canDrawOverlays
}

sealed interface HomeEvent {
    data class ShowMessage(val message: String) : HomeEvent
    data object OpenOverlayPermissionSettings : HomeEvent
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val repo = DownloadRepository.getInstance(context)
    private val prefs = InstaSavePreferences(context)

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _events = Channel<HomeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repo.transactionEvents
                .conflate()
                .collectLatest {
                    delay(200) // Collapse burst carousel inserts into 1 I/O scan
                    refreshDataInternal()
                }
        }
    }

    fun refreshState() {
        val a11y = InstaAccessibilityService.isServiceEnabled(context)
        val overlay = Settings.canDrawOverlays(context)
        val running = FloatingTriggerService.isRunning
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        _uiState.update {
            it.copy(
                isServiceRunning = running,
                isA11yEnabled = a11y,
                canDrawOverlays = overlay,
                canPostNotifications = notifications
            )
        }
        triggerDataRefresh()
    }

    private fun triggerDataRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            refreshDataInternal()
        }
    }

    private suspend fun refreshDataInternal() {
        val (recents, summary) = withContext(Dispatchers.IO) {
            val list = repo.getRecentTransactions(limit = 10)
            val storage = StorageAccountant.getStorageSummary(context)
            Pair(list, "${storage.formattedSize} · ${storage.totalItems} ${if (storage.totalItems == 1) "item" else "items"}")
        }
        _uiState.update {
            it.copy(
                recentDownloads = recents,
                storageSummary = summary
            )
        }
    }

    fun setUrlError(message: String?) {
        _uiState.update { it.copy(urlError = message) }
    }

    fun clearUrlError() {
        if (_uiState.value.urlError != null) {
            _uiState.update { it.copy(urlError = null) }
        }
    }

    fun setSubmittingUrl(isSubmitting: Boolean) {
        _uiState.update { it.copy(isSubmittingUrl = isSubmitting) }
    }

    fun toggleService() {
        val shouldRun = !FloatingTriggerService.isRunning
        if (shouldRun && !Settings.canDrawOverlays(context)) {
            _events.trySend(HomeEvent.OpenOverlayPermissionSettings)
            return
        }

        prefs.isButtonEnabled = shouldRun
        prefs.isServiceRunningDesired = shouldRun

        runCatching {
            if (shouldRun) FloatingTriggerService.start(context)
            else FloatingTriggerService.stop(context)
        }.onSuccess {
            // Optimistic update avoids the async onStartCommand() race
            _uiState.update { it.copy(isServiceRunning = shouldRun) }
        }.onFailure {
            _uiState.update { it.copy(isServiceRunning = FloatingTriggerService.isRunning) }
            _events.trySend(HomeEvent.ShowMessage("Couldn't toggle service right now"))
        }
    }
}
