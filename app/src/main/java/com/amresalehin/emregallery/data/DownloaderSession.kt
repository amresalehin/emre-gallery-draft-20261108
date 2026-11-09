package com.amresalehin.emregallery.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URL

/**
 * Single source of truth for downloader sessions across:
 * - FullScreenBrowserView
 * - DownloaderSearchMainPage
 *
 * Ensures discoveries made in the live browser automatically merge across sessions.
 */
object DownloaderSession {

    private val _currentUrl = MutableStateFlow("")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _sourceDomain = MutableStateFlow("")
    val sourceDomain: StateFlow<String> = _sourceDomain.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _discoveredItems = MutableStateFlow<List<ScrapedMediaItem>>(emptyList())
    val discoveredItems: StateFlow<List<ScrapedMediaItem>> = _discoveredItems.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val itemsLock = Any()
    private val canonicalMap = mutableMapOf<String, ScrapedMediaItem>()

    fun clear() {
        synchronized(itemsLock) {
            _currentUrl.value = ""
            _sourceDomain.value = ""
            _pageTitle.value = ""
            canonicalMap.clear()
            _discoveredItems.value = emptyList()
            _selectedItemIds.value = emptySet()
            _isExtracting.value = false
            _statusMessage.value = "Browser home"
            _errorMessage.value = null
        }
    }

    /**
     * Synchronizes the shared discovered-media review with the active browser page.
     * Unlike startSession(), this does not mark an extraction job as running.
     */
    fun updateBrowserPage(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank() || trimmed == "about:blank") {
            clear()
            return
        }
        val normalized = if (!trimmed.startsWith("http://", true) &&
            !trimmed.startsWith("https://", true)
        ) {
            "https://$trimmed"
        } else trimmed
        val domain = runCatching { URL(normalized).host }.getOrDefault("")

        synchronized(itemsLock) {
            if (normalized == _currentUrl.value) return
            _currentUrl.value = normalized
            _sourceDomain.value = domain
            _pageTitle.value = domain
            canonicalMap.clear()
            _discoveredItems.value = emptyList()
            _selectedItemIds.value = emptySet()
            _isExtracting.value = false
            _statusMessage.value = if (normalized.isBlank()) "Browser home" else "Browsing page"
            _errorMessage.value = null
        }
    }

    fun startSession(url: String) {
        val trimmed = url.trim()
        val normalized = if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) {
            "https://$trimmed"
        } else trimmed

        val domain = runCatching { URL(normalized).host }.getOrDefault("")

        synchronized(itemsLock) {
            _currentUrl.value = normalized
            _sourceDomain.value = domain
            _pageTitle.value = domain
            canonicalMap.clear()
            _discoveredItems.value = emptyList()
            _selectedItemIds.value = emptySet()
            _isExtracting.value = true
            _statusMessage.value = "Starting extraction..."
            _errorMessage.value = null
        }
    }

    fun addDiscoveredItems(newItems: List<ScrapedMediaItem>, title: String? = null) {
        if (newItems.isEmpty()) return
        synchronized(itemsLock) {
            val updated = MediaNormalizer.mergeCandidates(canonicalMap, newItems)
            if (updated) {
                val sorted = canonicalMap.values.toList()
                _discoveredItems.value = sorted
                // Auto-select all items by default if user hasn't explicitly customized selection
                if (_selectedItemIds.value.isEmpty()) {
                    _selectedItemIds.value = sorted.map { it.id }.toSet()
                }
            }
            if (!title.isNullOrBlank() && _pageTitle.value == _sourceDomain.value) {
                _pageTitle.value = title
            }
        }
    }

    fun addDiscoveredItem(item: ScrapedMediaItem) {
        addDiscoveredItems(listOf(item))
    }

    fun updateStatus(message: String, isRunning: Boolean? = null) {
        _statusMessage.value = message
        if (isRunning != null) {
            _isExtracting.value = isRunning
        }
    }

    fun setStatusMessage(msg: String) {
        updateStatus(msg)
    }

    fun finishSession(message: String? = null, error: String? = null) {
        _isExtracting.value = false
        if (message != null) {
            _statusMessage.value = message
        }
        _errorMessage.value = error
    }

    fun setPageTitle(title: String) {
        if (title.isNotBlank()) {
            _pageTitle.value = title
        }
    }

    fun toggleSelection(itemId: String) {
        synchronized(itemsLock) {
            val current = _selectedItemIds.value.toMutableSet()
            if (current.contains(itemId)) {
                current.remove(itemId)
            } else {
                current.add(itemId)
            }
            _selectedItemIds.value = current
        }
    }

    fun selectAll(ids: Set<String>) {
        _selectedItemIds.value = ids
    }

    fun deselectAll() {
        _selectedItemIds.value = emptySet()
    }
}
