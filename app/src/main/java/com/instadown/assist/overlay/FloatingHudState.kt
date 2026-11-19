package com.instadown.assist.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Universal state model representing real-time visual background operations
 * rendered by the Dynamic Island-Style Floating Progress HUD Pill across all apps.
 */
sealed class FloatingHudState {
    object Idle : FloatingHudState()

    data class LinkDetected(
        val shortcode: String,
        val message: String = "Link Detected"
    ) : FloatingHudState()

    data class Resolving(
        val shortcode: String,
        val message: String = "Resolving Post..."
    ) : FloatingHudState()

    data class Downloading(
        val shortcode: String,
        val percent: Int,
        val currentItem: Int,
        val totalItems: Int,
        val message: String = if (totalItems > 1) "Saving $currentItem/$totalItems ($percent%)" else "Downloading $percent%"
    ) : FloatingHudState()

    data class Success(
        val shortcode: String,
        val message: String = "Saved to Gallery",
        val totalItems: Int = 1
    ) : FloatingHudState()

    data class Error(
        val shortcode: String,
        val message: String
    ) : FloatingHudState()
}

/**
 * Concurrency-safe event bus for the Floating HUD Pill.
 * Any background service, coroutine scope, or broadcast receiver can post status updates
 * to render real-time progress without blocking the user or stealing focus.
 */
object FloatingHudBus {
    private val _state = MutableStateFlow<FloatingHudState>(FloatingHudState.Idle)
    val state: StateFlow<FloatingHudState> = _state.asStateFlow()

    fun emit(newState: FloatingHudState) {
        _state.value = newState
    }

    fun reset() {
        _state.value = FloatingHudState.Idle
    }
}
