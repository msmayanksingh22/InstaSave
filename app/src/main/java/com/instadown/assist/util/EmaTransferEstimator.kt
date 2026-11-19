package com.instadown.assist.util

import android.os.SystemClock
import java.util.Locale

/**
 * Snapshot of smoothed transfer progress metrics suitable for UI presentation.
 */
data class TransferProgressSnapshot(
    val percent: Int,
    val bytesRead: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Double,
    val formattedSpeed: String,
    val etaSeconds: Long,
    val formattedEta: String,
    val formattedBytes: String
)

/**
 * Exponential Moving Average (EMA) transfer speed and ETA estimator.
 *
 * Smooths out network bursts and socket buffering jitter using an alpha-decay rate filter,
 * while throttling snapshot emissions to prevent UI layout/rendering thrashing.
 *
 * @param alpha Smoothing factor (0.0 < alpha <= 1.0). Default is 0.25 for a balanced 4-step window.
 * @param minEmitIntervalMs Minimum interval between progress updates. Default 250ms (4 fps).
 */
class EmaTransferEstimator(
    private val alpha: Double = 0.25,
    private val minEmitIntervalMs: Long = 250L
) {
    private var lastEmitElapsedRealtimeMs = 0L
    private var lastBytesRead = 0L
    private var lastTimeNanos = 0L
    private var smoothedSpeedBytesPerSec = 0.0

    /**
     * Resets the estimator state for a new transfer.
     */
    fun reset() {
        lastEmitElapsedRealtimeMs = 0L
        lastBytesRead = 0L
        lastTimeNanos = 0L
        smoothedSpeedBytesPerSec = 0.0
    }

    /**
     * Updates bytes read and returns a [TransferProgressSnapshot] if [minEmitIntervalMs]
     * has elapsed since the last emission or if [forceEmit] is true.
     */
    fun update(
        bytesRead: Long,
        totalBytes: Long,
        forceEmit: Boolean = false
    ): TransferProgressSnapshot? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val nowMs = SystemClock.elapsedRealtime()

        if (lastTimeNanos == 0L) {
            lastTimeNanos = nowNanos
            lastBytesRead = bytesRead
            lastEmitElapsedRealtimeMs = nowMs
            if (!forceEmit) return null
        }

        val elapsedNanos = nowNanos - lastTimeNanos
        val elapsedSinceEmitMs = nowMs - lastEmitElapsedRealtimeMs

        if (!forceEmit && elapsedSinceEmitMs < minEmitIntervalMs) {
            return null
        }

        if (elapsedNanos > 0) {
            val deltaBytes = bytesRead - lastBytesRead
            val currentSpeed = (deltaBytes.toDouble() / (elapsedNanos.toDouble() / 1_000_000_000.0))
            if (smoothedSpeedBytesPerSec <= 0.0) {
                smoothedSpeedBytesPerSec = currentSpeed.coerceAtLeast(0.0)
            } else {
                smoothedSpeedBytesPerSec = (alpha * currentSpeed) + ((1.0 - alpha) * smoothedSpeedBytesPerSec)
            }
        }

        lastTimeNanos = nowNanos
        lastBytesRead = bytesRead
        lastEmitElapsedRealtimeMs = nowMs

        val percent = if (totalBytes > 0) {
            ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            -1
        }

        val etaSeconds = if (totalBytes > 0 && smoothedSpeedBytesPerSec > 1024) {
            val remainingBytes = (totalBytes - bytesRead).coerceAtLeast(0L)
            (remainingBytes / smoothedSpeedBytesPerSec).toLong().coerceAtLeast(0L)
        } else {
            -1L
        }

        return TransferProgressSnapshot(
            percent = percent,
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            speedBytesPerSec = smoothedSpeedBytesPerSec,
            formattedSpeed = formatSpeed(smoothedSpeedBytesPerSec),
            etaSeconds = etaSeconds,
            formattedEta = formatEta(etaSeconds),
            formattedBytes = formatBytes(bytesRead, totalBytes)
        )
    }

    companion object {
        fun formatSpeed(bytesPerSec: Double): String {
            if (bytesPerSec <= 0.0) return "0 B/s"
            val mb = bytesPerSec / (1024.0 * 1024.0)
            if (mb >= 1.0) {
                return String.format(Locale.US, "%.1f MB/s", mb)
            }
            val kb = bytesPerSec / 1024.0
            if (kb >= 1.0) {
                return String.format(Locale.US, "%.0f KB/s", kb)
            }
            return String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }

        fun formatEta(etaSeconds: Long): String {
            if (etaSeconds < 0) return "--"
            if (etaSeconds < 60) return "${etaSeconds}s left"
            val minutes = etaSeconds / 60
            val seconds = etaSeconds % 60
            return "${minutes}m ${seconds}s left"
        }

        fun formatBytes(bytesRead: Long, totalBytes: Long): String {
            val readMb = bytesRead.toDouble() / (1024.0 * 1024.0)
            if (totalBytes > 0) {
                val totalMb = totalBytes.toDouble() / (1024.0 * 1024.0)
                return String.format(Locale.US, "%.1f / %.1f MB", readMb, totalMb)
            }
            return String.format(Locale.US, "%.1f MB", readMb)
        }
    }
}
