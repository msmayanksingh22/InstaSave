package com.instadown.assist.resolver.model

enum class PostType {
    PHOTO,
    CAROUSEL,
    REEL,
    UNKNOWN
}

enum class MediaType {
    IMAGE,
    VIDEO
}

enum class ResolveErrorCode {
    UNSUPPORTED_URL,
    PRIVATE_POST,
    AUTH_REQUIRED,
    RATE_LIMITED,
    CONTENT_NOT_FOUND,
    NETWORK_ERROR,
    UNSUPPORTED_MEDIA,
    PARSE_ERROR,
    UNVERIFIED_IDENTITY,
    PARTIAL_MEDIA,
    SECURITY_REDIRECT_REJECTED,
    UNKNOWN
}

data class MediaCandidate(
    val url: String,
    val width: Int?,
    val height: Int?,
    val mimeType: String? = null,
    val bitRate: Long? = null,
    val frameRate: Double? = null,
    val tag: String? = null,
    val rankingScore: Long? = null,
    val isSelected: Boolean = false,
    val selectionReason: String? = null
)

data class CdnValidationResult(
    val checked: Boolean = false,
    val httpStatusCode: Int? = null,
    val contentType: String? = null,
    val contentLength: Long? = null,
    val isAccessible: Boolean = false,
    val notes: String? = null
)

data class ResolvedMedia(
    val index: Int,
    val mediaType: MediaType,
    val mediaUrl: String,
    val thumbnailUrl: String?,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val bitRate: Long?,
    val qualityClassification: String,
    val selectionReason: String,
    val candidates: List<MediaCandidate>,
    val cdnValidation: CdnValidationResult? = null
)

data class ResolveError(
    val code: ResolveErrorCode,
    val message: String,
    val rawDetails: String? = null
)

data class StageAResult(
    val success: Boolean,
    val postType: PostType?,
    val itemsCount: Int,
    val shortcode: String?,
    val identityVerification: String,
    val extractionTimeMs: Long,
    val error: ResolveError? = null
)

data class StageBResult(
    val success: Boolean,
    val validationTimeMs: Long,
    val allAccessible: Boolean,
    val itemsValidated: Int,
    val error: String? = null
)

data class ResolveResult(
    val success: Boolean,
    val isPartial: Boolean = false,
    val originalInputUrl: String,
    val canonicalUrl: String?,
    val shortcode: String?,
    val sourcePathType: String?,
    val postType: PostType?,
    val identityVerified: Boolean = false,
    val identityVerificationMethod: String? = null,
    val items: List<ResolvedMedia>,
    val methodUsed: String?,
    val totalResolutionTimeMs: Long,
    val stageA: StageAResult? = null,
    val stageB: StageBResult? = null,
    val initialHost: String? = null,
    val finalHost: String? = null,
    val error: ResolveError? = null
)
