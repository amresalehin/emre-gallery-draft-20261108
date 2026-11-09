package com.amresalehin.emregallery.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.amresalehin.emregallery.data.ScrapedMediaItem
import java.util.Locale

/**
 * Generic media source classification for direct files vs web pages.
 */
enum class DetectedPlatform(
    val title: String,
    val icon: ImageVector,
    val badgeColor: Color,
    val onBadgeColor: Color,
    val description: String,
    val defaultUrl: String
) {
    DIRECT_MEDIA("Direct Media", Icons.Default.Download, Color(0xFF2E7D32), Color.White, "Direct Audio / Video / Image Stream", ""),
    GENERIC_WEB("Web Page", Icons.Default.Language, Color(0xFF455A64), Color.White, "Generic Web Media", "")
}

fun detectPlatform(url: String): DetectedPlatform {
    val lower = url.trim().lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".m3u8") ||
                lower.endsWith(".mpd") || lower.endsWith(".jpg") || lower.endsWith(".png") ||
                lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".mp3") -> DetectedPlatform.DIRECT_MEDIA
        else -> DetectedPlatform.GENERIC_WEB
    }
}

/**
 * Downloader pill data model.
 */
data class DownloaderPill(
    val domain: String,
    val name: String,
    val url: String,
    val explicitFaviconUrl: String? = null
) {
    val faviconUrl: String
        get() = explicitFaviconUrl ?: "https://www.google.com/s2/favicons?domain=${domain.removePrefix("www.")}&sz=64"

    val initial: String
        get() = name.firstOrNull()?.uppercaseChar()?.toString() ?: "W"
}

fun parseScrapedMediaDimensions(item: ScrapedMediaItem): Pair<Int?, Int?> {
    val hint = item.resolutionHint ?: return null to null
    val parts = hint.split("×", "x", "*").map { it.trim().filter { ch -> ch.isDigit() } }
    if (parts.size >= 2) {
        val w = parts[0].toIntOrNull()
        val h = parts[1].toIntOrNull()
        return w to h
    }
    return null to null
}

fun passesDimensionFilter(
    item: ScrapedMediaItem,
    minWidth: Int,
    minHeight: Int,
    dimensionsMap: Map<String, Pair<Int, Int>>
): Boolean {
    if (minWidth <= 0 && minHeight <= 0) return true
    if (item.isVideo || item.isAudio || item.isStream) return true
    val (w, h) = dimensionsMap[item.id] ?: parseScrapedMediaDimensions(item)
    if (w == null || h == null) return false
    val widthOk = if (minWidth > 0) w >= minWidth else true
    val heightOk = if (minHeight > 0) h >= minHeight else true
    return widthOk && heightOk
}
