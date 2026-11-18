package com.instadown.assist.model

enum class AutomationState {
    IDLE,
    IDENTIFY_POST,
    LOCATE_SHARE,
    REVEAL_SHARE,
    CLICK_SHARE,
    WAIT_FOR_SHARE_SURFACE,
    LOCATE_COPY_LINK,
    REVEAL_COPY_LINK,
    CLICK_COPY_LINK,
    WAIT_FOR_CLIPBOARD,
    VALIDATE_URL,
    DISMISS_SHARE,
    RESTORE_INSTAGRAM,
    AMBIGUOUS_TARGET,
    SUCCESS,
    FAILED
}

data class StateTransition(
    val fromState: AutomationState,
    val toState: AutomationState,
    val timestampMs: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val reason: String = "",
    val diagnosticDetails: String = ""
) {
    fun format(): String {
        val durStr = if (durationMs > 0) " (${durationMs}ms)" else ""
        val reasonStr = if (reason.isNotBlank()) " -> $reason" else ""
        return "[$fromState -> $toState]$durStr$reasonStr"
    }
}
