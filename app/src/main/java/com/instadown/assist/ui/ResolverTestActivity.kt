package com.instadown.assist.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.instadown.assist.R
import com.instadown.assist.resolver.CompositeInstagramResolver
import com.instadown.assist.resolver.InstagramResolver
import com.instadown.assist.resolver.model.MediaType
import com.instadown.assist.resolver.model.ResolveResult
import kotlinx.coroutines.launch

class ResolverTestActivity : AppCompatActivity() {

    private lateinit var etUrlInput: EditText
    private lateinit var cbValidateCdn: CheckBox
    private lateinit var btnResolve: Button
    private lateinit var btnClear: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var cardResult: View
    private lateinit var tvResultHeader: TextView
    private lateinit var tvStatusSummary: TextView
    private lateinit var tvMetadataDetails: TextView
    private lateinit var tvItemsList: TextView
    private lateinit var tvRawError: TextView

    private lateinit var btnDownload: Button

    private val resolver: InstagramResolver by lazy { CompositeInstagramResolver(this) }
    private val coordinator: com.instadown.assist.download.InstagramDownloadCoordinator by lazy {
        com.instadown.assist.download.InstagramDownloadCoordinator(this, resolver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_resolver_test)

        etUrlInput = findViewById(R.id.etUrlInput)
        cbValidateCdn = findViewById(R.id.cbValidateCdn)
        btnResolve = findViewById(R.id.btnResolve)
        btnDownload = findViewById(R.id.btnDownload)
        btnClear = findViewById(R.id.btnClear)
        progressBar = findViewById(R.id.progressBar)
        cardResult = findViewById(R.id.cardResult)
        tvResultHeader = findViewById(R.id.tvResultHeader)
        tvStatusSummary = findViewById(R.id.tvStatusSummary)
        tvMetadataDetails = findViewById(R.id.tvMetadataDetails)
        tvItemsList = findViewById(R.id.tvItemsList)
        tvRawError = findViewById(R.id.tvRawError)

        setupPresets()

        btnResolve.setOnClickListener {
            val url = etUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                performResolution(url, cbValidateCdn.isChecked)
            } else {
                Toast.makeText(this, "Please enter an Instagram URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnDownload.setOnClickListener {
            val url = etUrlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                performDownload(url)
            } else {
                Toast.makeText(this, "Please enter an Instagram URL", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            etUrlInput.setText("")
            cardResult.visibility = View.GONE
        }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(newIntent: android.content.Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        handleIncomingIntent(newIntent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra("CLEAR_DUPLICATES", false)) {
            com.instadown.assist.download.DuplicateDetector.clearHistory(this)
            Toast.makeText(this, "Download history cleared", Toast.LENGTH_SHORT).show()
        }

        intent.getStringExtra("TEST_URL")?.let { autoUrl ->
            etUrlInput.setText(autoUrl)
            val validate = intent.getBooleanExtra("VALIDATE_CDN", true)
            cbValidateCdn.isChecked = validate
            val doDownload = intent.getBooleanExtra("EXECUTE_DOWNLOAD", false)

            if (doDownload) {
                val testMismatch = intent.getBooleanExtra("TEST_MISMATCH", false)
                performDownload(autoUrl, testMismatch = testMismatch)
            } else {
                performResolution(autoUrl, validate)
            }
        }
    }

    private fun setupPresets() {
        findViewById<Button>(R.id.btnPresetPhoto1).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/p/DUpiJzVjHYP/")
        }
        findViewById<Button>(R.id.btnPresetPhoto2).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/p/DV_AEYKjKyR/")
        }
        findViewById<Button>(R.id.btnPresetCarousel1).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/p/Dcf1nCmjwre/")
        }
        findViewById<Button>(R.id.btnPresetCarousel2).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/p/DcdhmVkmBps/")
        }
        findViewById<Button>(R.id.btnPresetReel1).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/reel/DcWSrBiBieA/")
        }
        findViewById<Button>(R.id.btnPresetReel2).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/reel/DcylaursOxC/")
        }
        findViewById<Button>(R.id.btnPresetDeleted).setOnClickListener {
            etUrlInput.setText("https://www.instagram.com/p/ZZZdeletedZZZ/")
        }
        findViewById<Button>(R.id.btnPresetInvalid).setOnClickListener {
            etUrlInput.setText("https://example.com/not_an_insta_url")
        }
    }

    private fun performResolution(url: String, validateCdn: Boolean) {
        btnResolve.isEnabled = false
        progressBar.visibility = View.VISIBLE
        cardResult.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = resolver.resolve(url, validateCdn)
                displayResult(result)
            } catch (e: Exception) {
                displayException(e)
            } finally {
                btnResolve.isEnabled = true
                progressBar.visibility = View.GONE
                cardResult.visibility = View.VISIBLE
            }
        }
    }

    private fun displayResult(result: ResolveResult) {
        val statusText = when {
            result.success && !result.isPartial -> "RESOLVED (SUCCESS)"
            result.isPartial -> "PARTIAL RESOLUTION"
            else -> "FAILED (${result.error?.code ?: "UNKNOWN"})"
        }
        val statusColor = when {
            result.success && !result.isPartial -> getColor(R.color.primary)
            result.isPartial -> getColor(R.color.accent_yellow)
            else -> getColor(R.color.accent_red)
        }

        tvResultHeader.text = statusText
        tvResultHeader.setTextColor(statusColor)

        try {
            val jsonTelemetry = org.json.JSONObject().apply {
                put("success", result.success)
                put("isPartial", result.isPartial)
                put("originalInputUrl", result.originalInputUrl)
                put("canonicalUrl", result.canonicalUrl)
                put("shortcode", result.shortcode)
                put("sourcePathType", result.sourcePathType)
                put("postType", result.postType?.name)
                put("identityVerified", result.identityVerified)
                put("identityVerificationMethod", result.identityVerificationMethod)
                put("methodUsed", result.methodUsed)
                put("totalResolutionTimeMs", result.totalResolutionTimeMs)
                put("stageA", result.stageA?.let { sa ->
                    org.json.JSONObject().apply {
                        put("success", sa.success)
                        put("extractionTimeMs", sa.extractionTimeMs)
                        put("itemsCount", sa.itemsCount)
                        put("identityVerification", sa.identityVerification)
                    }
                })
                put("stageB", result.stageB?.let { sb ->
                    org.json.JSONObject().apply {
                        put("success", sb.success)
                        put("validationTimeMs", sb.validationTimeMs)
                        put("allAccessible", sb.allAccessible)
                        put("itemsValidated", sb.itemsValidated)
                        put("error", sb.error)
                    }
                })
                put("items", org.json.JSONArray().apply {
                    for (item in result.items) {
                        put(org.json.JSONObject().apply {
                            put("index", item.index)
                            put("mediaType", item.mediaType.name)
                            put("width", item.width)
                            put("height", item.height)
                            put("durationMs", item.durationMs)
                            put("bitRate", item.bitRate)
                            put("qualityClassification", "${item.width}x${item.height}")
                            put("selectionReason", "Best candidate")
                            put("candidatesCount", item.candidates.size)
                            put("cdnValidation", item.cdnValidation?.let { cv ->
                                org.json.JSONObject().apply {
                                    put("httpStatusCode", cv.httpStatusCode)
                                    put("contentType", cv.contentType)
                                    put("contentLength", cv.contentLength)
                                    put("isAccessible", cv.isAccessible)
                                }
                            })
                        })
                    }
                })
                put("error", result.error?.let { err ->
                    org.json.JSONObject().apply {
                        put("code", err.code.name)
                        put("message", err.message)
                        put("rawDetails", err.rawDetails)
                    }
                })
            }
            android.util.Log.i("RESOLVER_TEST", "RESULT_JSON: $jsonTelemetry")
        } catch (e: Exception) {
            android.util.Log.e("RESOLVER_TEST", "Failed to serialize telemetry", e)
        }

        tvStatusSummary.text = buildString {
            append("Shortcode: ${result.shortcode ?: "N/A"} | Type: ${result.postType ?: "N/A"}\n")
            append("Canonical: ${result.canonicalUrl ?: "N/A"}\n")
            append("Source Path: /${result.sourcePathType ?: "?"}/ (Preserved)\n")
            append("Method: ${result.methodUsed} | Total Latency: ${result.totalResolutionTimeMs}ms")
        }

        val metaSb = StringBuilder()
        metaSb.append("=== STAGE A: SSR EXTRACTION ===\n")
        if (result.stageA != null) {
            metaSb.append("Success: ${result.stageA.success}\n")
            metaSb.append("Identity Match: ${result.stageA.identityVerification}\n")
            metaSb.append("Extracted Items: ${result.stageA.itemsCount}\n")
            metaSb.append("Stage A Latency: ${result.stageA.extractionTimeMs}ms\n")
        } else {
            metaSb.append("Stage A not completed\n")
        }

        metaSb.append("\n=== STAGE B: CDN VALIDATION ===\n")
        if (result.stageB != null) {
            metaSb.append("Success: ${result.stageB.success}\n")
            metaSb.append("All Accessible: ${result.stageB.allAccessible}\n")
            metaSb.append("Validated Items: ${result.stageB.itemsValidated}\n")
            metaSb.append("Stage B Latency: ${result.stageB.validationTimeMs}ms\n")
            if (result.stageB.error != null) {
                metaSb.append("Validation Note: ${result.stageB.error}\n")
            }
        } else {
            metaSb.append("Stage B skipped (validateCdn = false)\n")
        }

        tvMetadataDetails.text = metaSb.toString()

        val itemsSb = StringBuilder()
        if (result.items.isNotEmpty()) {
            itemsSb.append("=== RESOLVED MEDIA ITEMS (${result.items.size}) ===\n\n")
            for (item in result.items) {
                itemsSb.append("Slide [${item.index}] - ${item.mediaType}\n")
                itemsSb.append("  Dimensions: ${item.width ?: "?"} x ${item.height ?: "?"}\n")
                if (item.mediaType == MediaType.VIDEO) {
                    itemsSb.append("  Duration: ${item.durationMs?.let { "${it / 1000}s" } ?: "N/A"}\n")
                    itemsSb.append("  Bitrate: ${item.bitRate ?: "N/A"}\n")
                }
                itemsSb.append("  Quality: ${item.qualityClassification}\n")
                itemsSb.append("  Selection Reason: ${item.selectionReason}\n")

                if (item.cdnValidation != null) {
                    val cdn = item.cdnValidation
                    itemsSb.append("  CDN Check: Status=${cdn.httpStatusCode}, Type=${cdn.contentType}, Len=${cdn.contentLength ?: "chunked"}, Accessible=${cdn.isAccessible}\n")
                }

                itemsSb.append("  Candidate Streams: ${item.candidates.size} total\n")
                for ((cIdx, c) in item.candidates.take(5).withIndex()) {
                    val selMark = if (c.isSelected) " [*SELECTED*]" else ""
                    itemsSb.append("    #$cIdx: ${c.width}x${c.height} tag=${c.tag ?: "none"}$selMark\n")
                }
                itemsSb.append("\n")
            }
        }
        tvItemsList.text = itemsSb.toString()

        if (result.error != null) {
            tvRawError.visibility = View.VISIBLE
            tvRawError.text = buildString {
                append("Error Code: ${result.error.code}\n")
                append("Message: ${result.error.message}\n")
                result.error.rawDetails?.let { append("Details: $it\n") }
            }
        } else {
            tvRawError.visibility = View.GONE
        }
    }

    private fun performDownload(url: String, testMismatch: Boolean = false) {
        btnResolve.isEnabled = false
        btnDownload.isEnabled = false
        progressBar.visibility = View.VISIBLE
        cardResult.visibility = View.GONE
        tvStatusSummary.text = "Starting download transaction..."

        lifecycleScope.launch {
            try {
                if (testMismatch) {
                    // Test H: Artificially mismatched shortcode safety gate verification
                    val resolveResult = resolver.resolve(url, validateCdn = false)
                    val plan = com.instadown.assist.download.DownloadPlanner.createPlan(
                        expectedShortcode = "ARTIFICIAL_MISMATCH",
                        resolveResult = resolveResult
                    )
                    val result = com.instadown.assist.download.model.DownloadResult(
                        transactionId = "TEST_MISMATCH",
                        shortcode = "ARTIFICIAL_MISMATCH",
                        success = plan.success,
                        isPartial = false,
                        postType = plan.postType,
                        items = emptyList(),
                        completedCount = 0,
                        failedCount = 0,
                        skippedCount = 0,
                        totalBytes = 0L,
                        elapsedTimeMs = 50L,
                        error = plan.error,
                        errorCode = plan.errorCode
                    )
                    displayDownloadResult(result)
                } else {
                    val result = coordinator.executeDirectDownload(
                        url = url,
                        onStatusUpdate = { status ->
                            runOnUiThread {
                                tvStatusSummary.text = status
                            }
                        },
                        onProgress = { prog ->
                            runOnUiThread {
                                tvStatusSummary.text = prog.statusMessage
                            }
                        }
                    )
                    displayDownloadResult(result)
                }
            } catch (e: Exception) {
                displayException(e)
            } finally {
                btnResolve.isEnabled = true
                btnDownload.isEnabled = true
                progressBar.visibility = View.GONE
                cardResult.visibility = View.VISIBLE
            }
        }
    }

    private fun displayDownloadResult(result: com.instadown.assist.download.model.DownloadResult) {
        val statusText = when {
            result.success -> "DOWNLOAD SUCCESS (${result.completedCount} saved)"
            result.isPartial -> "PARTIAL DOWNLOAD (${result.completedCount}/${result.items.size})"
            result.skippedCount > 0 && result.completedCount == 0 -> "SKIPPED (ALREADY DOWNLOADED)"
            else -> "DOWNLOAD FAILED (${result.errorCode ?: "UNKNOWN"})"
        }
        val statusColor = when {
            result.success -> getColor(R.color.primary)
            result.isPartial -> getColor(R.color.accent_yellow)
            result.skippedCount > 0 && result.completedCount == 0 -> getColor(R.color.accent_yellow)
            else -> getColor(R.color.accent_red)
        }

        tvResultHeader.text = statusText
        tvResultHeader.setTextColor(statusColor)

        try {
            val jsonTelemetry = org.json.JSONObject().apply {
                put("transactionId", result.transactionId)
                put("shortcode", result.shortcode)
                put("success", result.success)
                put("isPartial", result.isPartial)
                put("postType", result.postType?.name)
                put("completedCount", result.completedCount)
                put("failedCount", result.failedCount)
                put("skippedCount", result.skippedCount)
                put("totalBytes", result.totalBytes)
                put("elapsedTimeMs", result.elapsedTimeMs)
                put("error", result.error)
                put("errorCode", result.errorCode?.name)
                put("items", org.json.JSONArray().apply {
                    for (item in result.items) {
                        put(org.json.JSONObject().apply {
                            put("index", item.index)
                            put("mediaType", item.mediaType.name)
                            put("fileName", item.fileName)
                            put("mimeType", item.mimeType)
                            put("width", item.width)
                            put("height", item.height)
                            put("status", item.status.name)
                            put("bytesDownloaded", item.bytesDownloaded)
                            put("totalBytes", item.totalBytes)
                            put("localUri", item.localUri?.toString())
                            put("isVerified", item.isVerified)
                            put("error", item.error)
                            put("errorCode", item.errorCode?.name)
                        })
                    }
                })
            }
            android.util.Log.i("DOWNLOADER_TEST", "RESULT_JSON: $jsonTelemetry")
        } catch (e: Exception) {
            android.util.Log.e("DOWNLOADER_TEST", "Failed to serialize download telemetry", e)
        }

        tvStatusSummary.text = buildString {
            append("Shortcode: ${result.shortcode ?: "N/A"} | Type: ${result.postType ?: "N/A"}\n")
            append("Completed: ${result.completedCount} | Failed: ${result.failedCount} | Skipped: ${result.skippedCount}\n")
            append("Total Bytes: ${result.totalBytes} (${result.totalBytes / 1024} KB) | Time: ${result.elapsedTimeMs}ms")
        }

        val metaSb = StringBuilder()
        metaSb.append("=== DOWNLOAD TRANSACTION DETAILS ===\n")
        metaSb.append("Transaction ID: ${result.transactionId}\n")
        metaSb.append("Verdict: ${if (result.success) "SUCCESS" else if (result.isPartial) "PARTIAL" else "FAILED"}\n")
        if (result.error != null) {
            metaSb.append("Error Code: ${result.errorCode}\n")
            metaSb.append("Message: ${result.error}\n")
        }
        tvMetadataDetails.text = metaSb.toString()

        val itemsSb = StringBuilder()
        if (result.items.isNotEmpty()) {
            itemsSb.append("=== MEDIASTORE SAVED ITEMS (${result.items.size}) ===\n\n")
            for (item in result.items) {
                itemsSb.append("Item [${item.index}] - ${item.mediaType} | Status: ${item.status}\n")
                itemsSb.append("  File: ${item.fileName} (${item.mimeType})\n")
                itemsSb.append("  Dimensions: ${item.width ?: "?"} x ${item.height ?: "?"}\n")
                itemsSb.append("  Bytes: ${item.bytesDownloaded} / ${item.totalBytes ?: "?"} (Verified: ${item.isVerified})\n")
                itemsSb.append("  MediaStore URI: ${item.localUri ?: "None"}\n")
                if (item.error != null) {
                    itemsSb.append("  Error: ${item.errorCode}: ${item.error}\n")
                }
                itemsSb.append("\n")
            }
        }
        tvItemsList.text = itemsSb.toString()

        if (result.error != null) {
            tvRawError.visibility = View.VISIBLE
            tvRawError.text = "Error: ${result.errorCode}\n${result.error}"
        } else {
            tvRawError.visibility = View.GONE
        }
    }

    private fun displayException(e: Exception) {
        tvResultHeader.text = "FATAL EXCEPTION"
        tvResultHeader.setTextColor(getColor(R.color.accent_red))
        tvStatusSummary.text = "Exception: ${e.javaClass.simpleName}"
        tvMetadataDetails.text = ""
        tvItemsList.text = ""
        tvRawError.visibility = View.VISIBLE
        tvRawError.text = e.stackTraceToString()
    }
}
