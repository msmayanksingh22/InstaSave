package com.instadown.assist.overlay

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicLong

/**
 * Every visible HUD state carries:
 *  - operationId: prevents stale coroutines from clobbering newer tasks
 *  - maxTtlMs: hard upper bound guaranteeing the pill can NEVER stick forever
 *  - heartbeatNanos: guarantees StateFlow re-emits even if numeric percentage is unchanged
 */
sealed interface ResilientHudState {
    val operationId: Long
    val maxTtlMs: Long
    val heartbeatNanos: Long

    data class Hidden(
        override val operationId: Long = 0L,
        override val maxTtlMs: Long = 0L,
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState

    data class LinkDetected(
        override val operationId: Long,
        val label: String,
        override val maxTtlMs: Long = 4_000L,
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState

    data class Resolving(
        override val operationId: Long,
        val stageLabel: String,
        val elapsedMs: Long = 0L,
        override val maxTtlMs: Long = 10_000L,
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState

    data class Downloading(
        override val operationId: Long,
        val currentIndex: Int,
        val totalCount: Int,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int,
        override val maxTtlMs: Long = 18_000L, // Stall watchdog: resets on every byte chunk
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState

    data class Success(
        override val operationId: Long,
        val message: String,
        val totalItems: Int = 1,
        override val maxTtlMs: Long = 2_400L,
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState

    data class Error(
        override val operationId: Long,
        val message: String,
        override val maxTtlMs: Long = 3_500L,
        override val heartbeatNanos: Long = SystemClock.elapsedRealtimeNanos()
    ) : ResilientHudState
}

/**
 * Lease-guarded StateFlow bus. Stale operations can never overwrite newer HUD states.
 */
object ResilientHudBus {
    private val opCounter = AtomicLong(0L)
    private val activeOpId = AtomicLong(0L)

    private val _state = MutableStateFlow<ResilientHudState>(ResilientHudState.Hidden())
    val state: StateFlow<ResilientHudState> = _state.asStateFlow()

    /** Allocates a fresh monotonic lease ID for a new capture/download workflow. */
    fun startOperation(): Long {
        val nextId = opCounter.incrementAndGet()
        activeOpId.set(nextId)
        return nextId
    }

    /** Returns current active lease ID. */
    fun getActiveOperationId(): Long = activeOpId.get()

    /** Emits state ONLY if operationId is still the active lease. */
    fun emit(newState: ResilientHudState): Boolean {
        if (newState is ResilientHudState.Hidden) {
            _state.value = newState
            return true
        }
        if (newState.operationId != activeOpId.get()) {
            return false // Drop stale emission safely
        }
        _state.value = newState
        return true
    }

    /** Dismisses only if matching opId (or force=true for manual user tap/swipe). */
    fun dismiss(operationId: Long? = null, force: Boolean = false) {
        _state.update { current ->
            if (force || operationId == null || current.operationId == operationId) {
                ResilientHudState.Hidden(operationId = current.operationId)
            } else {
                current
            }
        }
    }
}

/**
 * Watchdog driver to manage the collector loop inside FloatingHudOverlayController.
 * Mathematically guarantees NO non-Hidden state can outlive its maxTtlMs.
 */
class HudWatchdogDriver(
    private val onRenderVisible: (ResilientHudState) -> Unit,
    private val onAnimateOutAndDetach: () -> Unit,
    private val onForceDetachImmediately: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    fun start() {
        if (watchJob != null) return
        watchJob = scope.launch {
            ResilientHudBus.state.collectLatest { state ->
                if (state is ResilientHudState.Hidden) {
                    onAnimateOutAndDetach()
                    return@collectLatest
                }

                onRenderVisible(state)

                // Every visible state gets a strict, non-optional expiry timer.
                // Any new heartbeat/progress event automatically cancels & resets this timer.
                val safeTtl = state.maxTtlMs.coerceIn(1_500L, 30_000L)
                delay(safeTtl)

                when (state) {
                    is ResilientHudState.Success,
                    is ResilientHudState.Error -> {
                        ResilientHudBus.dismiss(operationId = state.operationId)
                    }
                    is ResilientHudState.LinkDetected,
                    is ResilientHudState.Resolving,
                    is ResilientHudState.Downloading -> {
                        // In-progress state silently hung -> surface clear timeout error,
                        // which itself will auto-hide in 3.5 seconds!
                        ResilientHudBus.emit(
                            ResilientHudState.Error(
                                operationId = state.operationId,
                                message = "Operation timed out. Tap to retry."
                            )
                        )
                    }
                    is ResilientHudState.Hidden -> Unit
                }
            }
        }
    }

    /** Always invoke from FloatingTriggerService.onDestroy() AND onTaskRemoved(). */
    fun stop() {
        watchJob?.cancel()
        watchJob = null
        ResilientHudBus.dismiss(force = true)
        onForceDetachImmediately()
        scope.cancel()
    }
}

/**
 * Structured-concurrency guard for InstagramDownloadCoordinator.
 * Guarantees that timeouts, network exceptions, or coroutine cancellations
 * ALWAYS transition the HUD out of any in-progress state.
 */
suspend fun <T> runHudTrackedOperation(
    initialLinkLabel: String = "Instagram Post Detected",
    totalTimeoutMs: Long = 30_000L,
    block: suspend (operationId: Long) -> T
): Result<T> {
    val opId = ResilientHudBus.startOperation()
    ResilientHudBus.emit(ResilientHudState.LinkDetected(opId, initialLinkLabel))
    var emittedTerminalState = false

    return try {
        val result = withTimeout(totalTimeoutMs) {
            block(opId)
        }
        emittedTerminalState = true
        Result.success(result)
    } catch (_: TimeoutCancellationException) {
        // Note: TimeoutCancellationException extends CancellationException — catch it FIRST!
        emittedTerminalState = true
        withContext(NonCancellable) {
            ResilientHudBus.emit(
                ResilientHudState.Error(opId, "Timed out while fetching media")
            )
        }
        Result.failure(RuntimeException("Resolution/download timed out after ${totalTimeoutMs}ms"))
    } catch (ce: CancellationException) {
        emittedTerminalState = true
        withContext(NonCancellable) {
            ResilientHudBus.dismiss(operationId = opId)
        }
        throw ce // Preserve structured concurrency cancellation
    } catch (t: Throwable) {
        emittedTerminalState = true
        withContext(NonCancellable) {
            ResilientHudBus.emit(
                ResilientHudState.Error(opId, t.message ?: "Unable to resolve link")
            )
        }
        Result.failure(t)
    } finally {
        // Safety net if caller returned early without emitting Success/Error
        if (!emittedTerminalState) {
            withContext(NonCancellable) {
                ResilientHudBus.dismiss(operationId = opId)
            }
        }
    }
}
