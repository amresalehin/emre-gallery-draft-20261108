package com.amresalehin.emregallery.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.BrowserSession
import com.amresalehin.emregallery.data.DownloadQueueManager
import com.amresalehin.emregallery.data.DownloaderSession
import com.amresalehin.emregallery.data.MediaInboxStack
import com.amresalehin.emregallery.data.ScrapedMediaItem

enum class DownloaderViewMode {
    SEARCH_PAGE,
    DOWNLOADER_PAGE,
    SAVED_LIBRARY,
    ACTIVITY_PAGE,
    DISCOVERED_MEDIA
}

/**
 * Direct entry point for download activity and discovered media.
 * Un-embedded from the unnecessary intermediate wrapper and saved library page.
 */
@Composable
fun MediaInboxScreen(
    onBack: () -> Unit,
    initialViewMode: DownloaderViewMode = DownloaderViewMode.ACTIVITY_PAGE,
    initialStackForDetail: MediaInboxStack? = null,
    initialOpenBrowser: Boolean = false,
    onOpenMediaItem: ((String) -> Unit)? = null,
    onOpenMediaWithList: ((String, List<String>) -> Unit)? = null,
    onOpenBrowser: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (initialViewMode == DownloaderViewMode.DISCOVERED_MEDIA) {
        DiscoveredMediaPage(
            onBack = onBack,
            modifier = modifier.testTag("media_inbox_screen")
        )
    } else {
        DownloaderActivityPage(
            onBack = onBack,
            onAddNewDownload = { onOpenBrowser?.invoke("") },
            onOpenMedia = { item ->
                item.localUri?.let { uriStr ->
                    onOpenMediaItem?.invoke(uriStr)
                }
            },
            modifier = modifier.testTag("media_inbox_screen")
        )
    }
}

/**
 * Clean, standalone Discovered Media page allowing the user to choose exactly
 * what media they want to download (with multi-select, filters, and batch download).
 */
@Composable
fun DiscoveredMediaPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val tabSession = remember { BrowserSession.getTabSession("tab_main") }
    val tabItems by tabSession.detectedItems.collectAsState()
    val sessionItems by DownloaderSession.discoveredItems.collectAsState()
    val sourceUrl by DownloaderSession.currentUrl.collectAsState()
    val sourceDomain by DownloaderSession.sourceDomain.collectAsState()

    val discoveredItems = remember(tabItems, sessionItems) {
        (sessionItems + tabItems).distinctBy { it.id.ifBlank { it.url } }
    }

    var selectedFilter by remember { mutableStateOf("All") }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }

    val videoCount = remember(discoveredItems) { discoveredItems.count { it.isVideo || it.isStream } }
    val imageCount = remember(discoveredItems) { discoveredItems.count { !it.isVideo && !it.isStream && !it.isAudio } }
    val audioCount = remember(discoveredItems) { discoveredItems.count { it.isAudio } }

    val filteredItems = remember(discoveredItems, selectedFilter) {
        when (selectedFilter) {
            "Videos" -> discoveredItems.filter { it.isVideo || it.isStream }
            "Images" -> discoveredItems.filter { !it.isVideo && !it.isStream && !it.isAudio }
            "Audio" -> discoveredItems.filter { it.isAudio }
            else -> discoveredItems
        }
    }

    fun downloadItem(item: ScrapedMediaItem) {
        DownloadQueueManager.enqueueDownload(
            context = context,
            item = item,
            referer = sourceUrl,
            stackId = "discovered_${sourceUrl.hashCode()}",
            stackTitle = sourceDomain.ifBlank { "Discovered Media" },
            sourceDomain = sourceDomain,
            sourceUrl = sourceUrl
        )
    }

    fun downloadBatch(items: List<ScrapedMediaItem>) {
        if (items.isEmpty()) return
        items.forEach { downloadItem(it) }
        Toast.makeText(
            context,
            "Queued ${items.size} item${if (items.size > 1) "s" else ""} for download",
            Toast.LENGTH_SHORT
        ).show()
        selectedIds = emptySet()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("discovered_media_page")
    ) {
        // Top app bar with back button, dynamic item count, and Select All toggle
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth().statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("discovered_media_back_btn")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "Choose Media to Download",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (discoveredItems.isEmpty()) {
                            "0 available media options"
                        } else if (selectedIds.isEmpty()) {
                            "${discoveredItems.size} options available"
                        } else {
                            "${selectedIds.size} of ${discoveredItems.size} selected"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (filteredItems.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            val filteredIds = filteredItems.map { it.id.ifBlank { it.url } }.toSet()
                            selectedIds = if (selectedIds.containsAll(filteredIds)) {
                                selectedIds - filteredIds
                            } else {
                                selectedIds + filteredIds
                            }
                        },
                        modifier = Modifier.testTag("discovered_media_select_all_btn")
                    ) {
                        val filteredIds = filteredItems.map { it.id.ifBlank { it.url } }.toSet()
                        val allFilteredSelected = selectedIds.containsAll(filteredIds)
                        Icon(
                            imageVector = if (allFilteredSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                            contentDescription = if (allFilteredSelected) "Deselect all" else "Select all",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // Filter pills row (All, Videos, Images, Audio)
        if (discoveredItems.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val filters = buildList {
                    add("All" to discoveredItems.size)
                    if (videoCount > 0) add("Videos" to videoCount)
                    if (imageCount > 0) add("Images" to imageCount)
                    if (audioCount > 0) add("Audio" to audioCount)
                }
                items(filters) { (label, count) ->
                    val isSelected = selectedFilter == label
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedFilter = label },
                        label = { Text("$label ($count)") }
                    )
                }
            }
        }

        if (discoveredItems.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = "No media detected on this page yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Browse pages containing videos, audio, or images and detected options will dynamically appear here for selection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.id.ifBlank { it.url } }) { item ->
                        val itemId = item.id.ifBlank { item.url }
                        val isSelected = selectedIds.contains(itemId)

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedIds = if (isSelected) selectedIds - itemId else selectedIds + itemId
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Checkbox for choosing
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { checked ->
                                        selectedIds = if (checked) selectedIds + itemId else selectedIds - itemId
                                    },
                                    modifier = Modifier.size(24.dp)
                                )

                                Spacer(Modifier.width(10.dp))

                                AsyncImage(
                                    model = item.thumbnailUrl?.takeIf { it.isNotBlank() } ?: item.url,
                                    contentDescription = item.title,
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Crop
                                )

                                Spacer(Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title.ifBlank { item.url },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (item.isVideo || item.isStream) "Video" else if (item.isAudio) "Audio" else item.format.ifBlank { "Image" },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        item.resolutionHint?.takeIf { it.isNotBlank() }?.let { res ->
                                            Text(
                                                text = "• $res",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                // Single item download button
                                IconButton(
                                    onClick = {
                                        downloadItem(item)
                                        Toast.makeText(context, "Download queued", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download ${item.title}",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Bottom floating action bar for downloading chosen items
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 20.dp, vertical = 14.dp)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (selectedIds.isEmpty()) {
                                "Download all (${filteredItems.size})"
                            } else {
                                "${selectedIds.size} item${if (selectedIds.size > 1) "s" else ""} chosen"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Button(
                            onClick = {
                                val targets = if (selectedIds.isEmpty()) {
                                    filteredItems
                                } else {
                                    filteredItems.filter { selectedIds.contains(it.id.ifBlank { it.url }) }
                                }
                                downloadBatch(targets)
                            },
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (selectedIds.isEmpty()) "Download All" else "Download Selected"
                            )
                        }
                    }
                }
            }
        }
    }
}
