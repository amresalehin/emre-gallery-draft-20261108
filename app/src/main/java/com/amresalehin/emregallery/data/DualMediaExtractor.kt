package com.amresalehin.emregallery.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.net.URL

data class MediaRequestContext(
    val url: String,
    val domain: String
) {
    companion object {
        fun create(targetUrl: String): MediaRequestContext {
            val trimmed = targetUrl.trim()
            val normalized = if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
                "https://$trimmed"
            } else {
                trimmed
            }
            val domain = runCatching { URL(normalized).host }.getOrDefault("")
            return MediaRequestContext(url = normalized, domain = domain)
        }
    }
}

data class MediaExtractionResult(
    val items: List<ScrapedMediaItem> = emptyList(),
    val pageTitle: String? = null,
    val errorMessage: String? = null
)

/**
 * DualMediaExtractor delegates to ExtractionCoordinator.
 * Preserves ExtractionPhase and ExtractionState for backwards-compatibility with existing tests and UI.
 */
class DualMediaExtractor(private val context: Context) {

    enum class ExtractionPhase {
        IDLE,
        DIRECT_MEDIA,
        YT_DLP,
        GALLERY_DL,
        STATIC_SCRAPING,
        BROWSER_INTERCEPTING,
        AUTO_SCROLLING,
        COMPLETED,
        ERROR
    }

    data class ExtractionState(
        val isRunning: Boolean = false,
        val statusMessage: String = "",
        val phase: ExtractionPhase = ExtractionPhase.IDLE,
        val pageTitle: String = "",
        val domain: String = "",
        val items: List<ScrapedMediaItem> = emptyList(),
        val errorMessage: String? = null,
        val requiresSignIn: Boolean = false,
        val signInUrl: String? = null,
        val isObtainingCookiesInBackground: Boolean = false
    )

    private val coordinator = ExtractionCoordinator(context)

    val state: StateFlow<ExtractionState> = coordinator.state

    fun extract(targetUrl: String, scope: CoroutineScope, directOnly: Boolean = false) {
        coordinator.extract(targetUrl, scope, directOnly)
    }

    fun addDiscoveredItems(newList: List<ScrapedMediaItem>, title: String? = null, domain: String? = null) {
        coordinator.addDiscoveredItems(newList, title, domain)
    }

    fun addDiscoveredItem(item: ScrapedMediaItem) {
        coordinator.addDiscoveredItem(item)
    }

    fun cancel() {
        coordinator.cancel()
    }

    fun reset() {
        coordinator.reset()
    }

    fun cleanup() {
        coordinator.cleanup()
    }
}
