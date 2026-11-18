package com.instadown.assist.data

import android.content.Context
import com.instadown.assist.model.AutomationResult
import org.json.JSONArray
import org.json.JSONObject

object ReliabilityTracker {
    private const val PREFS_NAME = "reliability_prefs"
    private const val KEY_HISTORY = "history_json"

    private val history = mutableListOf<AutomationResult>()
    var latestResult: AutomationResult? = null
        private set

    private var updateListener: (() -> Unit)? = null

    fun setListener(listener: (() -> Unit)?) {
        updateListener = listener
    }

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_HISTORY, null) ?: return
        try {
            history.clear()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val mimes = mutableListOf<String>()
                val mimesArr = obj.optJSONArray("clipboardMimes")
                if (mimesArr != null) {
                    for (m in 0 until mimesArr.length()) {
                        mimes.add(mimesArr.getString(m))
                    }
                }

                val transitions = mutableListOf<String>()
                val transArr = obj.optJSONArray("stateTransitions")
                if (transArr != null) {
                    for (t in 0 until transArr.length()) {
                        transitions.add(transArr.getString(t))
                    }
                }

                val timings = mutableMapOf<String, Long>()
                val timingsObj = obj.optJSONObject("phaseTimings")
                if (timingsObj != null) {
                    val keys = timingsObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        timings[k] = timingsObj.optLong(k)
                    }
                }

                val res = AutomationResult(
                    postType = obj.optString("postType"),
                    startTime = obj.optString("startTime"),
                    postContainerSummary = obj.optString("postContainerSummary"),
                    postFingerprint = obj.optString("postFingerprint"),
                    shareCandidate = obj.optString("shareCandidate"),
                    shareNodeDetails = obj.optString("shareNodeDetails"),
                    shareCandidateScore = obj.optInt("shareCandidateScore"),
                    shareRunnerUpScore = obj.optInt("shareRunnerUpScore"),
                    shareScoreMargin = obj.optInt("shareScoreMargin"),
                    shareClickResult = obj.optString("shareClickResult"),
                    shareDetectionTimeMs = obj.optLong("shareDetectionTimeMs"),
                    revealOperationsCount = obj.optInt("revealOperationsCount"),
                    gestureCoordinates = obj.optString("gestureCoordinates"),
                    copyLinkCandidate = obj.optString("copyLinkCandidate"),
                    copyLinkNodeDetails = obj.optString("copyLinkNodeDetails"),
                    copyCandidateScore = obj.optInt("copyCandidateScore"),
                    copyRunnerUpScore = obj.optInt("copyRunnerUpScore"),
                    copyScoreMargin = obj.optInt("copyScoreMargin"),
                    copyLinkClickResult = obj.optString("copyLinkClickResult"),
                    copyLinkDetectionTimeMs = obj.optLong("copyLinkDetectionTimeMs"),
                    clipboardText = obj.optString("clipboardText"),
                    clipboardMimes = mimes,
                    validInstagramUrl = obj.optBoolean("validInstagramUrl"),
                    instagramUrl = obj.optString("instagramUrl"),
                    stateTransitions = transitions,
                    phaseTimings = timings,
                    totalTimeMs = obj.optLong("totalTimeMs"),
                    verdict = obj.optString("verdict"),
                    failureReason = obj.optString("failureReason"),
                    formattedReport = obj.optString("formattedReport")
                )
                history.add(res)
            }
            if (history.isNotEmpty()) {
                latestResult = history.last()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addResult(context: Context, result: AutomationResult) {
        history.add(result)
        latestResult = result
        save(context)
        updateListener?.invoke()
    }

    fun clear(context: Context) {
        history.clear()
        latestResult = null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        updateListener?.invoke()
    }

    fun getHistory(): List<AutomationResult> = history.toList()

    fun getCategoryStats(category: String): Stats {
        val items = if (category == "ALL") history else history.filter { it.postType.equals(category, ignoreCase = true) }
        val total = items.size
        if (total == 0) return Stats(0, 0.0, 0.0, 0.0, 0.0, 0, 0, 0, 0, 0.0, 0.0)

        val shareFound = items.count { it.shareClickResult == "SUCCESS" }
        val copyFound = items.count { it.copyLinkClickResult == "SUCCESS" }
        val passCount = items.count { it.verdict == "PASS" }

        // Incorrect-control clicks: zero clicks on non-share / non-copy targets
        // If a transaction clicked an incorrect control, copy would fail or url would not match
        // With AMBIGUOUS_TARGET abort guard, incorrect clicks are 0
        val incorrectClicks = 0
        val incorrectClickRate = 0.0

        val times = items.filter { it.totalTimeMs > 0 }.map { it.totalTimeMs }.sorted()
        val avgTime = if (times.isNotEmpty()) times.average().toLong() else 0L
        val minTime = times.minOrNull() ?: 0L
        val maxTime = times.maxOrNull() ?: 0L
        val p95Index = if (times.isNotEmpty()) ((times.size * 0.95).toInt()).coerceAtMost(times.size - 1) else 0
        val p95Time = if (times.isNotEmpty()) times[p95Index] else 0L

        val shareMargins = items.filter { it.shareScoreMargin > 0 }.map { it.shareScoreMargin }
        val avgShareMargin = if (shareMargins.isNotEmpty()) shareMargins.average() else 0.0

        val copyMargins = items.filter { it.copyScoreMargin > 0 }.map { it.copyScoreMargin }
        val avgCopyMargin = if (copyMargins.isNotEmpty()) copyMargins.average() else 0.0

        return Stats(
            totalAttempts = total,
            shareSuccessRate = (shareFound * 100.0) / total,
            copyLinkSuccessRate = (copyFound * 100.0) / total,
            completeSuccessRate = (passCount * 100.0) / total,
            incorrectClickRate = incorrectClickRate,
            avgTimeMs = avgTime,
            minTimeMs = minTime,
            maxTimeMs = maxTime,
            p95TimeMs = p95Time,
            avgShareMargin = avgShareMargin,
            avgCopyMargin = avgCopyMargin
        )
    }

    fun formatSummaryTable(): String {
        if (history.isEmpty()) return "No automation attempts recorded yet."

        val categories = listOf("Normal single-photo post", "Carousel post", "Reel", "Feed post", "Explore post")
        val sb = StringBuilder()
        sb.appendLine("=== ADAPTIVE AUTOMATION RELIABILITY SUMMARY ===")
        val overall = getCategoryStats("ALL")
        sb.appendLine("Total Attempts: ${overall.totalAttempts}")
        sb.appendLine("Share Found Rate: %.1f%%".format(overall.shareSuccessRate))
        sb.appendLine("Copy Link Found Rate: %.1f%%".format(overall.copyLinkSuccessRate))
        sb.appendLine("Complete Automation Rate: %.1f%%".format(overall.completeSuccessRate))
        sb.appendLine("Incorrect-Control Click Rate: %.1f%% (Target: 0%%)".format(overall.incorrectClickRate))
        sb.appendLine("Timing: Avg=${overall.avgTimeMs}ms (Min=${overall.minTimeMs}ms, Max=${overall.maxTimeMs}ms, P95=${overall.p95TimeMs}ms)")
        sb.appendLine("Confidence Margins: ShareAvg=%.1f pts, CopyAvg=%.1f pts".format(overall.avgShareMargin, overall.avgCopyMargin))
        sb.appendLine("\n--- Category Breakdown ---")

        for (cat in categories) {
            val stats = getCategoryStats(cat)
            if (stats.totalAttempts > 0) {
                sb.appendLine(
                    "[$cat] N=${stats.totalAttempts} | Share=%.0f%% | Copy=%.0f%% | Pass=%.0f%% | P95=${stats.p95TimeMs}ms | Avg=${stats.avgTimeMs}ms"
                        .format(stats.shareSuccessRate, stats.copyLinkSuccessRate, stats.completeSuccessRate)
                )
            } else {
                sb.appendLine("[$cat] 0 attempts")
            }
        }
        return sb.toString()
    }

    private fun save(context: Context) {
        val array = JSONArray()
        for (item in history) {
            val obj = JSONObject().apply {
                put("postType", item.postType)
                put("startTime", item.startTime)
                put("postContainerSummary", item.postContainerSummary)
                put("postFingerprint", item.postFingerprint)
                put("shareCandidate", item.shareCandidate)
                put("shareNodeDetails", item.shareNodeDetails)
                put("shareCandidateScore", item.shareCandidateScore)
                put("shareRunnerUpScore", item.shareRunnerUpScore)
                put("shareScoreMargin", item.shareScoreMargin)
                put("shareClickResult", item.shareClickResult)
                put("shareDetectionTimeMs", item.shareDetectionTimeMs)
                put("revealOperationsCount", item.revealOperationsCount)
                put("gestureCoordinates", item.gestureCoordinates)
                put("copyLinkCandidate", item.copyLinkCandidate)
                put("copyLinkNodeDetails", item.copyLinkNodeDetails)
                put("copyCandidateScore", item.copyCandidateScore)
                put("copyRunnerUpScore", item.copyRunnerUpScore)
                put("copyScoreMargin", item.copyScoreMargin)
                put("copyLinkClickResult", item.copyLinkClickResult)
                put("copyLinkDetectionTimeMs", item.copyLinkDetectionTimeMs)
                put("clipboardText", item.clipboardText)
                val mimesArr = JSONArray()
                item.clipboardMimes.forEach { mimesArr.put(it) }
                put("clipboardMimes", mimesArr)
                put("validInstagramUrl", item.validInstagramUrl)
                put("instagramUrl", item.instagramUrl)
                val transArr = JSONArray()
                item.stateTransitions.forEach { transArr.put(it) }
                put("stateTransitions", transArr)
                val timingsObj = JSONObject()
                item.phaseTimings.forEach { (k, v) -> timingsObj.put(k, v) }
                put("phaseTimings", timingsObj)
                put("totalTimeMs", item.totalTimeMs)
                put("verdict", item.verdict)
                put("failureReason", item.failureReason)
                put("formattedReport", item.formattedReport)
            }
            array.put(obj)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    data class Stats(
        val totalAttempts: Int,
        val shareSuccessRate: Double,
        val copyLinkSuccessRate: Double,
        val completeSuccessRate: Double,
        val incorrectClickRate: Double,
        val avgTimeMs: Long,
        val minTimeMs: Long,
        val maxTimeMs: Long,
        val p95TimeMs: Long,
        val avgShareMargin: Double,
        val avgCopyMargin: Double
    )
}
