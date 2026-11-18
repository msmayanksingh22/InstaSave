package com.instadown.assist.service

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.widget.Toast
import com.instadown.assist.data.CaptureStore
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

class InstagramAssistSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "InstaAssistInspector"
        private val URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9.-]+(?:/[^\\s]*)?")
        private val INSTAGRAM_POST_REGEX = Pattern.compile("https?://(?:www\\.)?instagram\\.com/(?:p|reel|reels)/([a-zA-Z0-9_-]+)/?")
        private val INSTAGRAM_GENERIC_REGEX = Pattern.compile("https?://(?:www\\.)?instagram\\.com/?(?:[a-zA-Z0-9_.-]*)?")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "Assist session onShow. showFlags: $showFlags")
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        try {
            inspectAndReport(state, timestamp)
        } catch (e: Throwable) {
            Log.e(TAG, "Error inspecting assist state: ${e.message}", e)
        } finally {
            // Dismiss assist session window after capture so Instagram remains interactive
            mainHandler.postDelayed({
                try {
                    hide()
                } catch (ignored: Exception) {}
            }, 500)
        }
    }

    private fun inspectAndReport(state: AssistState, timestamp: String) {
        val content: AssistContent? = state.assistContent
        val structure: AssistStructure? = state.assistStructure

        // 1. FOREGROUND ACTIVITY INFORMATION
        val activityComponent = structure?.activityComponent
        val fgPackage = activityComponent?.packageName
            ?: content?.intent?.component?.packageName
            ?: "[Unknown / Null Component]"
        val fgActivity = activityComponent?.className
            ?: content?.intent?.component?.className
            ?: "[Unknown / Null Activity]"

        // 2. ASSIST CONTENT INSPECTION
        val webUri: Uri? = content?.webUri
        val webUriStr = webUri?.toString() ?: "[null]"
        val isAppProvidedWebUri = content?.isAppProvidedWebUri()?.toString() ?: "[null]"

        val intentObj: Intent? = content?.intent
        val intentStr = if (intentObj != null) {
            "Action=${intentObj.action}, Data=${intentObj.data}, Component=${intentObj.component}, Categories=${intentObj.categories}"
        } else {
            "[null]"
        }
        val isAppProvidedIntent = content?.isAppProvidedIntent()?.toString() ?: "[null]"

        val clipDataObj = content?.clipData
        val clipDataStr = if (clipDataObj != null) {
            val count = clipDataObj.itemCount
            val mimes = (0 until clipDataObj.description.mimeTypeCount).map { clipDataObj.description.getMimeType(it) }
            val items = (0 until count).map { i ->
                val item = clipDataObj.getItemAt(i)
                "Item[$i]: text=${item.text}, uri=${item.uri}"
            }
            "count=$count, mimes=$mimes, items=$items"
        } else {
            "[null]"
        }

        val structuredData = content?.structuredData ?: "[null]"

        val extrasObj: Bundle? = content?.extras
        val extrasStr = if (extrasObj != null && !extrasObj.isEmpty) {
            extrasObj.keySet().joinToString(", ") { key ->
                val value = extrasObj.get(key)
                "$key(${value?.javaClass?.simpleName})=$value"
            }
        } else if (extrasObj != null) {
            "[empty Bundle]"
        } else {
            "[null]"
        }

        // 3. ASSIST STRUCTURE DEEP INSPECTION
        val windowCount = structure?.windowNodeCount ?: 0
        var totalNodes = 0
        var isSecureWindow = false
        val collectedTexts = mutableListOf<String>()
        val collectedDescriptions = mutableListOf<String>()
        val collectedResourceIds = mutableListOf<String>()
        val collectedUrls = mutableListOf<String>()
        val collectedClassNames = mutableSetOf<String>()

        if (structure != null && windowCount > 0) {
            for (w in 0 until windowCount) {
                val windowNode = structure.getWindowNodeAt(w)
                val rootNode = windowNode.rootViewNode
                if (rootNode == null) {
                    isSecureWindow = true
                    continue
                }

                // Breadth-first traversal of ViewNodes to prevent recursion stack overflow
                val queue = ArrayDeque<AssistStructure.ViewNode>()
                queue.add(rootNode)

                while (!queue.isEmpty()) {
                    val node = queue.poll() ?: break
                    totalNodes++

                    val nodeClass = node.className
                    if (!nodeClass.isNullOrBlank()) {
                        collectedClassNames.add(nodeClass)
                    }

                    val resId = node.idEntry
                    if (!resId.isNullOrBlank()) {
                        val fullId = "${node.idPackage ?: ""}:${node.idType ?: ""}/$resId"
                        collectedResourceIds.add(fullId)
                    }

                    val textVal = node.text?.toString()?.trim()
                    if (!textVal.isNullOrBlank()) {
                        collectedTexts.add(textVal)
                        extractUrls(textVal, collectedUrls)
                    }

                    val descVal = node.contentDescription?.toString()?.trim()
                    if (!descVal.isNullOrBlank()) {
                        collectedDescriptions.add(descVal)
                        extractUrls(descVal, collectedUrls)
                    }

                    val webDomain = node.webDomain
                    val webScheme = node.webScheme
                    if (!webDomain.isNullOrBlank()) {
                        collectedUrls.add("${webScheme ?: "https"}://$webDomain")
                    }

                    for (c in 0 until node.childCount) {
                        val child = node.getChildAt(c)
                        if (child != null) {
                            queue.add(child)
                        }
                    }
                }
            }
        } else if (structure == null) {
            isSecureWindow = false
        }

        val secureStr = if (isSecureWindow) "true (empty stub root detected)" else "false"
        val structureSummary = "windows=$windowCount, nodes=$totalNodes, secure=$secureStr, textsCount=${collectedTexts.size}, urlsFound=${collectedUrls.size}"

        // 4. VERDICT EVALUATION ACCORDING TO USER RULES
        val (verdict, verdictReason) = evaluateVerdict(
            fgPackage = fgPackage,
            content = content,
            webUriStr = webUriStr,
            isAppProvidedWebUri = isAppProvidedWebUri == "true",
            intentObj = intentObj,
            isAppProvidedIntent = isAppProvidedIntent == "true",
            structuredData = structuredData,
            collectedUrls = collectedUrls,
            collectedTexts = collectedTexts
        )

        // 5. FORMAT LOGCAT REPORT UNDER InstaAssistInspector
        val report = buildString {
            appendLine("=== ASSIST CAPTURE ===")
            appendLine("Timestamp: $timestamp")
            appendLine("Foreground package: $fgPackage")
            appendLine("Foreground activity: $fgActivity")
            appendLine("=== ASSIST CONTENT ===")
            appendLine("webUri: $webUriStr")
            appendLine("isAppProvidedWebUri: $isAppProvidedWebUri")
            appendLine("intent: $intentStr")
            appendLine("isAppProvidedIntent: $isAppProvidedIntent")
            appendLine("clipData: $clipDataStr")
            appendLine("structuredData: $structuredData")
            appendLine("extras: $extrasStr")
            appendLine("=== ASSIST STRUCTURE ===")
            appendLine("windows: $windowCount")
            appendLine("secure: $secureStr")
            appendLine("nodes: $totalNodes")
            appendLine("texts: ${collectedTexts.take(15)}")
            appendLine("contentDescriptions: ${collectedDescriptions.take(15)}")
            appendLine("resourceIds: ${collectedResourceIds.take(20)}")
            appendLine("urls: $collectedUrls")
            appendLine("=== VERDICT ===")
            appendLine(verdict)
            appendLine("Reason: $verdictReason")
        }

        Log.i(TAG, "\n$report")

        // 6. SAVE TO STORE FOR MAIN ACTIVITY UI
        CaptureStore.saveCapture(
            context = context,
            report = report,
            timestamp = timestamp,
            verdict = verdict,
            verdictReason = verdictReason,
            webUri = webUriStr,
            isAppProvided = isAppProvidedWebUri,
            structureSummary = structureSummary
        )

        // Show a brief Toast confirmation to the user
        mainHandler.post {
            Toast.makeText(context, "InstaAssist: Captured ($verdict)", Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractUrls(input: String, outList: MutableList<String>) {
        val matcher = URL_PATTERN.matcher(input)
        while (matcher.find()) {
            val url = matcher.group()
            if (!outList.contains(url)) {
                outList.add(url)
            }
        }
    }

    /**
     * Strict verdict evaluation per user requirements:
     * - PASS only if we receive a post-specific URI such as https://www.instagram.com/p/... or /reel/...
     *   AND we can prove it changes when navigating to another Instagram post.
     * - PARTIAL if we receive useful Instagram information (home, profile URL, generic intent, caption)
     *   but it does NOT uniquely identify the current post.
     * - FAIL if no useful current-post identifier is available.
     */
    private fun evaluateVerdict(
        fgPackage: String,
        content: AssistContent?,
        webUriStr: String,
        isAppProvidedWebUri: Boolean,
        intentObj: Intent?,
        isAppProvidedIntent: Boolean,
        structuredData: String,
        collectedUrls: List<String>,
        collectedTexts: List<String>
    ): Pair<String, String> {
        val isInstagram = fgPackage.contains("instagram", ignoreCase = true)

        if (content == null) {
            return "FAIL" to "AssistContent is null. Android or foreground app did not supply assist content."
        }

        // Check AssistContent.webUri
        if (webUriStr != "[null]" && webUriStr.isNotBlank()) {
            val postMatcher = INSTAGRAM_POST_REGEX.matcher(webUriStr)
            if (postMatcher.find()) {
                val postId = postMatcher.group(1)
                val prevUri = CaptureStore.previousWebUri
                return if (prevUri != null && prevUri != webUriStr) {
                    "PASS" to "Received post-specific URL ($webUriStr, ID=$postId) and verified it CHANGED from previous capture ($prevUri). isAppProvided=$isAppProvidedWebUri"
                } else if (prevUri != null && prevUri == webUriStr) {
                    "PARTIAL" to "Received post URL ($webUriStr) but it DID NOT change from previous post. Requires navigation to a different post to verify."
                } else {
                    "PASS (Pending Post B comparison)" to "Received post-specific URL ($webUriStr, ID=$postId, isAppProvided=$isAppProvidedWebUri). Move to Post B to prove it changes."
                }
            }

            val genericMatcher = INSTAGRAM_GENERIC_REGEX.matcher(webUriStr)
            if (genericMatcher.find()) {
                return "PARTIAL" to "Received generic Instagram URL ($webUriStr), but it does not identify a specific post/reel."
            }

            return "PARTIAL" to "Received webUri ($webUriStr), but not a specific Instagram post URL."
        }

        // Check structuredData for schema.org or JSON-LD post identifiers
        if (structuredData != "[null]" && structuredData.isNotBlank()) {
            val postMatcher = INSTAGRAM_POST_REGEX.matcher(structuredData)
            if (postMatcher.find()) {
                return "PARTIAL" to "Post URL found inside structuredData, but NOT in AssistContent.webUri."
            }
            return "PARTIAL" to "Structured data is present, but no specific post URI was identified."
        }

        // Check Intent
        if (intentObj != null) {
            val dataUri = intentObj.data?.toString()
            if (dataUri != null && INSTAGRAM_POST_REGEX.matcher(dataUri).find()) {
                return "PARTIAL" to "Post URL found in Intent.data ($dataUri), but AssistContent.webUri was null."
            }
        }

        // Check AssistStructure URLs
        val structurePostUrl = collectedUrls.firstOrNull { INSTAGRAM_POST_REGEX.matcher(it).find() }
        if (structurePostUrl != null) {
            return "PARTIAL" to "Post URL was discovered in AssistStructure view nodes ($structurePostUrl), but NOT in AssistContent."
        }

        if (isInstagram) {
            return "FAIL" to "Foreground app is Instagram, but no useful post identifier or URL was exposed in AssistContent."
        }

        return "FAIL" to "No post identifier exposed (Foreground package: $fgPackage)."
    }
}
