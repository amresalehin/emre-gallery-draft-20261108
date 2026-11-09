package com.amresalehin.emregallery.data

data class MediaProvenance(
    val extractorEngine: String = "Static HTML",
    val pageUrl: String = "",
    val domSelector: String? = null
)

data class ScrapedMediaItem(
    val id: String,
    val url: String,
    val title: String,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val isStream: Boolean = false,
    val category: MediaCategory = when {
        isStream -> MediaCategory.STREAMS
        isVideo -> MediaCategory.VIDEOS
        isAudio -> MediaCategory.AUDIO
        else -> MediaCategory.IMAGES
    },
    val format: String = when {
        isStream -> "HLS/DASH"
        isVideo -> "MP4"
        isAudio -> "AUDIO"
        else -> "IMAGE"
    },
    val thumbnailUrl: String? = null,
    val source: String = "Static HTML",
    val mimeType: String = when {
        isStream -> "application/x-mpegURL"
        isVideo -> "video/mp4"
        isAudio -> "audio/mpeg"
        else -> "image/jpeg"
    },
    val resolutionHint: String? = null,
    val sizeBytes: Long? = null,
    val provenance: MediaProvenance = MediaProvenance(extractorEngine = source),
    val metadata: Map<String, String> = emptyMap(),
    val downloadHeaders: Map<String, String> = emptyMap(),
    val detectedAt: Long = System.currentTimeMillis()
)

data class ScrapeResult(
    val sourceUrl: String,
    val pageTitle: String,
    val domain: String,
    val items: List<ScrapedMediaItem>,
    val errorMessage: String? = null
)
