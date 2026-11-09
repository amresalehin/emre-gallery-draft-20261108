package com.amresalehin.emregallery.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

data class BrowserSite(
    val url: String,
    val title: String,
    val domain: String,
    val visitCount: Int = 1,
    val lastVisitedMillis: Long = System.currentTimeMillis()
)

data class BrowserShortcut(
    val id: String,
    val title: String,
    val url: String,
    val iconKey: String = ""
)

data class BrowserTabItem(
    val id: String,
    val title: String,
    val url: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Manages persistent browser history, shortcuts (flexible pinning), and tabs.
 */
class BrowserHistoryManager(private val context: Context) {

    private val historyKey = stringPreferencesKey("browser_sites_history_v1")
    private val searchQueriesKey = stringPreferencesKey("browser_downloader_recent_searches_v1")
    private val shortcutsKey = stringPreferencesKey("browser_pinned_shortcuts_v1")
    private val tabsKey = stringPreferencesKey("browser_open_tabs_v1")

    val defaultTopSites: List<BrowserSite> = emptyList()

    // 1. Pinned Shortcuts (User-pinned, empty by default)
    val defaultShortcuts: List<BrowserShortcut> = emptyList()

    val shortcuts: Flow<List<BrowserShortcut>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[shortcutsKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<BrowserShortcut>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        BrowserShortcut(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.getString("title"),
                            url = obj.getString("url"),
                            iconKey = obj.optString("iconKey", "")
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun addShortcut(title: String, url: String, iconKey: String = "") {
        val cleanTitle = title.trim()
        val cleanUrl = MediaClassifier.formatQueryOrUrl(url.trim())
        if (cleanTitle.isBlank() || cleanUrl.isBlank()) return

        context.galleryDataStore.edit { prefs ->
            val currentList = loadShortcutsFromPrefs(prefs[shortcutsKey]).toMutableList()
            // Deduplicate if URL already pinned
            currentList.removeAll { it.url.equals(cleanUrl, ignoreCase = true) }
            val determinedKey = if (iconKey.isNotBlank()) iconKey else determineIconKey(cleanUrl, cleanTitle)
            currentList.add(
                BrowserShortcut(
                    id = "sc_${System.currentTimeMillis()}",
                    title = cleanTitle,
                    url = cleanUrl,
                    iconKey = determinedKey
                )
            )
            prefs[shortcutsKey] = serializeShortcuts(currentList)
        }
    }

    suspend fun updateShortcut(id: String, newTitle: String, newUrl: String) {
        val cleanTitle = newTitle.trim()
        val cleanUrl = MediaClassifier.formatQueryOrUrl(newUrl.trim())
        if (cleanTitle.isBlank() || cleanUrl.isBlank()) return

        context.galleryDataStore.edit { prefs ->
            val currentList = loadShortcutsFromPrefs(prefs[shortcutsKey]).toMutableList()
            val idx = currentList.indexOfFirst { it.id == id }
            if (idx >= 0) {
                currentList[idx] = currentList[idx].copy(
                    title = cleanTitle,
                    url = cleanUrl,
                    iconKey = determineIconKey(cleanUrl, cleanTitle)
                )
                prefs[shortcutsKey] = serializeShortcuts(currentList)
            }
        }
    }

    suspend fun removeShortcut(id: String) {
        context.galleryDataStore.edit { prefs ->
            val currentList = loadShortcutsFromPrefs(prefs[shortcutsKey]).toMutableList()
            currentList.removeAll { it.id == id }
            prefs[shortcutsKey] = serializeShortcuts(currentList)
        }
    }

    private fun loadShortcutsFromPrefs(raw: String?): List<BrowserShortcut> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<BrowserShortcut>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    BrowserShortcut(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.getString("title"),
                        url = obj.getString("url"),
                        iconKey = obj.optString("iconKey", "")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeShortcuts(list: List<BrowserShortcut>): String {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("title", item.title)
            obj.put("url", item.url)
            obj.put("iconKey", item.iconKey)
            array.put(obj)
        }
        return array.toString()
    }

    private fun determineIconKey(url: String, title: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("youtube.com") || lower.contains("youtu.be") -> "youtube"
            lower.contains("wikipedia.org") -> "wikipedia"
            lower.contains("github.com") -> "github"
            lower.contains("drive.google.com") -> "drive"
            lower.contains("duckduckgo.com") -> "duckduckgo"
            lower.contains("claude.ai") -> "claude"
            lower.contains("anthropic.com") -> "anthropic"
            else -> ""
        }
    }

    // 2. Open Tabs (Real user tabs only, empty by default)
    val defaultTabs: List<BrowserTabItem> = emptyList()

    val openTabs: Flow<List<BrowserTabItem>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[tabsKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<BrowserTabItem>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        BrowserTabItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            title = obj.optString("title", "Tab"),
                            url = obj.optString("url", ""),
                            timestamp = obj.optLong("time", System.currentTimeMillis())
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun saveTabs(tabs: List<BrowserTabItem>) {
        context.galleryDataStore.edit { prefs ->
            val array = JSONArray()
            for (tab in tabs) {
                val obj = JSONObject()
                obj.put("id", tab.id)
                obj.put("title", tab.title)
                obj.put("url", tab.url)
                obj.put("time", tab.timestamp)
                array.put(obj)
            }
            prefs[tabsKey] = array.toString()
        }
    }

    // 3. Recent History Sites (Real visited sites only, empty by default)
    val defaultRecentSites: List<BrowserSite> = emptyList()

    val recentSearchQueries: Flow<List<String>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[searchQueriesKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<String>()
                for (i in 0 until array.length()) {
                    val q = array.getString(i).trim()
                    if (q.isNotEmpty()) list.add(q)
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun recordSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[searchQueriesKey]
            val list = mutableListOf<String>()
            if (!raw.isNullOrBlank()) {
                try {
                    val array = JSONArray(raw)
                    for (i in 0 until array.length()) {
                        val item = array.getString(i).trim()
                        if (item.isNotEmpty() && !item.equals(trimmed, ignoreCase = true)) {
                            list.add(item)
                        }
                    }
                } catch (_: Exception) {}
            }
            list.add(0, trimmed)
            val jsonArray = JSONArray()
            for (q in list.take(15)) {
                jsonArray.put(q)
            }
            prefs[searchQueriesKey] = jsonArray.toString()
        }
    }

    val allSites: Flow<List<BrowserSite>> = context.galleryDataStore.data.map { prefs ->
        val raw = prefs[historyKey]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                val array = JSONArray(raw)
                val list = mutableListOf<BrowserSite>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        BrowserSite(
                            url = obj.getString("url"),
                            title = obj.optString("title", ""),
                            domain = obj.optString("domain", ""),
                            visitCount = obj.optInt("count", 1),
                            lastVisitedMillis = obj.optLong("time", 0L)
                        )
                    )
                }
                list
            } catch (_: Exception) {
                emptyList()
            }
        }
    }.distinctUntilChanged()

    suspend fun recordVisit(rawUrl: String, title: String? = null) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank() || trimmed == "about:blank" || trimmed.startsWith("javascript:")) return
        val domain = runCatching {
            val host = URL(trimmed).host
            host.removePrefix("www.")
        }.getOrDefault("").ifBlank {
            trimmed.substringAfter("://").substringBefore("/").removePrefix("www.")
        }
        if (domain.isBlank()) return

        val displayTitle = title?.takeIf { it.isNotBlank() && it != "about:blank" && !it.startsWith("http") }
            ?: domain.substringBefore('.').replaceFirstChar { it.uppercase() }

        context.galleryDataStore.edit { prefs ->
            val raw = prefs[historyKey]
            val list = if (raw.isNullOrBlank()) {
                mutableListOf<BrowserSite>()
            } else {
                try {
                    val array = JSONArray(raw)
                    val l = mutableListOf<BrowserSite>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        l.add(
                            BrowserSite(
                                url = obj.getString("url"),
                                title = obj.optString("title", ""),
                                domain = obj.optString("domain", ""),
                                visitCount = obj.optInt("count", 1),
                                lastVisitedMillis = obj.optLong("time", 0L)
                            )
                        )
                    }
                    l
                } catch (_: Exception) {
                    mutableListOf<BrowserSite>()
                }
            }

            // Find existing site matching normalized domain or url
            val existingIndex = list.indexOfFirst {
                it.domain.equals(domain, ignoreCase = true) || it.url.equals(trimmed, ignoreCase = true)
            }

            if (existingIndex >= 0) {
                val existing = list[existingIndex]
                list[existingIndex] = existing.copy(
                    url = trimmed,
                    title = if (displayTitle.isNotBlank() && displayTitle != existing.domain) displayTitle else existing.title,
                    visitCount = existing.visitCount + 1,
                    lastVisitedMillis = System.currentTimeMillis()
                )
            } else {
                list.add(
                    0,
                    BrowserSite(
                        url = trimmed,
                        title = displayTitle,
                        domain = domain,
                        visitCount = 1,
                        lastVisitedMillis = System.currentTimeMillis()
                    )
                )
            }

            // Keep top 50
            val trimmedList = list.sortedByDescending { it.lastVisitedMillis }.take(50)
            val jsonArray = JSONArray()
            for (s in trimmedList) {
                val obj = JSONObject()
                obj.put("url", s.url)
                obj.put("title", s.title)
                obj.put("domain", s.domain)
                obj.put("count", s.visitCount)
                obj.put("time", s.lastVisitedMillis)
                jsonArray.put(obj)
            }
            prefs[historyKey] = jsonArray.toString()
        }
    }

    suspend fun deleteSite(url: String) {
        context.galleryDataStore.edit { prefs ->
            val raw = prefs[historyKey]
            val list = if (raw.isNullOrBlank()) {
                mutableListOf<BrowserSite>()
            } else {
                try {
                    val array = JSONArray(raw)
                    val l = mutableListOf<BrowserSite>()
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        l.add(
                            BrowserSite(
                                url = obj.getString("url"),
                                title = obj.optString("title", ""),
                                domain = obj.optString("domain", ""),
                                visitCount = obj.optInt("count", 1),
                                lastVisitedMillis = obj.optLong("time", 0L)
                            )
                        )
                    }
                    l
                } catch (_: Exception) {
                    mutableListOf()
                }
            }
            list.removeAll { it.url.equals(url, ignoreCase = true) }
            val jsonArray = JSONArray()
            for (s in list) {
                val obj = JSONObject()
                obj.put("url", s.url)
                obj.put("title", s.title)
                obj.put("domain", s.domain)
                obj.put("count", s.visitCount)
                obj.put("time", s.lastVisitedMillis)
                jsonArray.put(obj)
            }
            prefs[historyKey] = jsonArray.toString()
        }
    }

    suspend fun clearHistory() {
        context.galleryDataStore.edit { prefs ->
            // Store empty array to explicitly indicate user cleared history
            prefs[historyKey] = "[]"
        }
    }
}
