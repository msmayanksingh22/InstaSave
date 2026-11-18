package com.instadown.assist.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedDeque

data class LogEntry(
    val timestamp: Long,
    val level: String, // "INFO", "WARN", "ERROR", "DEBUG"
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))

    override fun toString(): String {
        return "[$formattedTime] [$level/$tag] $message"
    }
}

object AppLogger {

    private const val MAX_LOGS = 500
    private val buffer = ConcurrentLinkedDeque<LogEntry>()

    private val _logEvents = MutableSharedFlow<LogEntry>(extraBufferCapacity = 64)
    val logEvents: SharedFlow<LogEntry> = _logEvents

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        append("INFO", tag, msg)
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append("DEBUG", tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        Log.w(tag, fullMsg)
        append("WARN", tag, fullMsg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        Log.e(tag, fullMsg)
        append("ERROR", tag, fullMsg)
    }

    private fun append(level: String, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        buffer.add(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
        _logEvents.tryEmit(entry)
    }

    fun getLogs(): List<LogEntry> {
        return buffer.toList()
    }

    fun clear() {
        buffer.clear()
    }

    fun exportFormattedLogs(): String {
        val sb = StringBuilder()
        sb.appendLine("=== INSTASAVE RUNTIME DIAGNOSTIC LOGS ===")
        sb.appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("Total Entries: ${buffer.size}")
        sb.appendLine("=========================================\n")
        for (log in buffer) {
            sb.appendLine(log.toString())
        }
        return sb.toString()
    }

    /**
     * Retrieves the device's local Wi-Fi IPv4 address for wireless debugging.
     */
    fun getLocalWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                if (!iface.name.contains("wlan", ignoreCase = true) && !iface.name.contains("wifi", ignoreCase = true)) {
                    continue
                }
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
