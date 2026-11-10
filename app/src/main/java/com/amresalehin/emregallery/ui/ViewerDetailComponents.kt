package com.amresalehin.emregallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amresalehin.emregallery.data.CustomAlbum
import com.amresalehin.emregallery.data.MediaItem
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.DetectedMediaFace
import com.amresalehin.emregallery.face.EnteFaceConstants
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceProcessingCoordinator
import com.amresalehin.emregallery.face.FaceThumbnailHelper
import com.amresalehin.emregallery.ocr.TesseractOcr
import com.amresalehin.emregallery.ffmpeg.FfmpegKitEngine
import com.amresalehin.emregallery.ffmpeg.FfprobeMediaInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.testTag
import kotlin.math.abs

enum class DetailDrawerTab {
    OVERVIEW, ACTIONS
}

enum class ViewerFaceState {
    NOT_INDEXED,
    IN_PROGRESS,
    WAITING_FOR_GROUPING,
    GROUPING_INCOMPLETE,
    COMPLETED_FACES_FOUND,
    COMPLETED_NO_FACES,
    MODEL_MISSING,
    FAILED
}

data class ViewerFaceUiState(
    val state: ViewerFaceState = ViewerFaceState.NOT_INDEXED,
    val faces: List<DetectedMediaFace> = emptyList(),
    val progressPhase: String? = null,
    val errorMessage: String? = null,
    val engineName: String = "",
    val engineId: String = "",
    val groupingFailure: Boolean = false
)

@Composable
fun ViewerButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 3.dp)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.94f), CircleShape)
    ) {
        Icon(icon, label, tint = scheme.onSurface)
    }
}

@Composable
fun ViewerAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val actionColor = tint ?: scheme.onSurface
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .defaultMinSize(minWidth = 54.dp, minHeight = 48.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = scheme.surfaceContainerHigh.copy(alpha = 0.94f),
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
            modifier = Modifier.size(46.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = actionColor)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = actionColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun DetailCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        shadowElevation = 0.dp,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
    ) {
        Box(modifier = Modifier.padding(14.dp)) {
            content()
        }
    }
}

/**
 * Top header displaying:
 * - Line 1: Filename
 * - Line 2: Capture date/time and file size (e.g., 25 Oct 2026 · 4:32 PM · 3.8 MB)
 * - Tabs: Overview and Actions in a subtle rounded pill container with underline on selected tab.
 * No Edit button in header.
 */
@Composable
fun DetailDrawerHeader(
    item: MediaItem,
    selectedTab: DetailDrawerTab,
    onSelectTab: (DetailDrawerTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatMediaSubtitle(item),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(14.dp))

        // Subtle rounded container for Overview & Actions tabs
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                val isOverview = selectedTab == DetailDrawerTab.OVERVIEW
                val isActions = selectedTab == DetailDrawerTab.ACTIONS

                // Overview Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelectTab(DetailDrawerTab.OVERVIEW) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Overview",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isOverview) FontWeight.Bold else FontWeight.Medium,
                            color = if (isOverview) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                        if (isOverview) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(2.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface,
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                // Actions Tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelectTab(DetailDrawerTab.ACTIONS) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Actions",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (isActions) FontWeight.Bold else FontWeight.Medium,
                            color = if (isActions) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            }
                        )
                        if (isActions) {
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .width(84.dp)
                                    .height(2.dp)
                                    .background(
                                        MaterialTheme.colorScheme.onSurface,
                                        RoundedCornerShape(1.dp)
                                    )
                            )
                        } else {
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewerFaceContainer(
    faceUiState: ViewerFaceUiState,
    onScanThisPhoto: () -> Unit,
    onCancelScan: () -> Unit,
    onRescanPhoto: () -> Unit,
    onRescanLowThreshold: () -> Unit,
    onInstallModel: () -> Unit,
    onRetryScan: () -> Unit,
    onViewErrorDetails: () -> Unit,
    onEditTagOrPerson: () -> Unit,
    onFaceClick: ((DetectedMediaFace) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
    val isBusy = isProcessingActive || FaceProcessingCoordinator.isBusy(context)

    when (faceUiState.state) {
        ViewerFaceState.NOT_INDEXED -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Face indexing has not been run for this item.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = onScanThisPhoto,
                        enabled = !isBusy,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("scan_this_photo_button")
                    ) {
                        Icon(Icons.Default.Face, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan This Photo")
                    }
                }
            }
        }
        ViewerFaceState.IN_PROGRESS -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = faceUiState.progressPhase ?: "Scanning photo...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    OutlinedButton(
                        onClick = onCancelScan,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("cancel_scan_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Cancel Scan")
                    }
                }
            }
        }
        ViewerFaceState.WAITING_FOR_GROUPING -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        text = faceUiState.progressPhase ?: "Faces found · waiting to be grouped",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        ViewerFaceState.GROUPING_INCOMPLETE -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Text(
                    text = faceUiState.progressPhase ?: "Faces found, but no person group is available yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
        ViewerFaceState.COMPLETED_FACES_FOUND -> {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    faceUiState.faces.forEach { face ->
                        OverviewFacePortrait(
                            face = face,
                            enabled = !isBusy,
                            onClick = if (!isBusy) { { onFaceClick?.invoke(face) } } else null
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(
                        onClick = onRescanPhoto,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("rescan_photo_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Re-scan Photo", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onEditTagOrPerson,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("edit_tag_add_person_button")
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit Tag / Add Person", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        ViewerFaceState.COMPLETED_NO_FACES -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Face,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "No faces detected in this media item.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onRescanLowThreshold,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("rescan_low_threshold_button")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-scan with Low Threshold", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onRescanPhoto,
                            enabled = !isBusy,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("rescan_photo_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Force Re-scan")
                        }
                    }
                }
            }
        }
        ViewerFaceState.MODEL_MISSING -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Face model files missing for ${faceUiState.engineName.ifBlank { "active engine" }}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = onInstallModel,
                        enabled = !isBusy,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 48.dp)
                            .testTag("install_model_button")
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Install Model")
                    }
                }
            }
        }
        ViewerFaceState.FAILED -> {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = faceUiState.errorMessage ?: "Face indexing failed for this photo.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onRetryScan,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("retry_scan_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (faceUiState.groupingFailure) "Retry Grouping" else "Retry Scan")
                        }
                        OutlinedButton(
                            onClick = onViewErrorDetails,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .testTag("view_error_details_button")
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("View Error Details")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FaceErrorDetailsDialog(
    errorMessage: String,
    engineName: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(8.dp))
                Text("Error Details")
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Engine: $engineName",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
            ) {
                Text("Dismiss")
            }
        }
    )
}

/**
 * Overview Tab content:
 * 1. Albums (membership chips + add control in header, minimal empty state if none)
 * 2. Tags (clean monochrome chips + add control in header, minimal empty state if none)
 * 3. People (individual large portraits, horizontal scroll, hidden if empty)
 * 4. Location (monochrome preview box + place name, preview opens map, edit control in header/card opens edit dialog, placeholder if none)
 * 5. Metadata (compact heading with downward chevron, no duplicate fields, thin dividers)
 */
@Composable
fun OverviewTabContent(
    item: MediaItem,
    customAlbums: List<CustomAlbum>,
    customTags: Set<String>,
    detectedFaces: List<DetectedMediaFace>,
    metadata: Map<String, String>,
    onOpenMap: () -> Unit,
    onAddToAlbum: () -> Unit,
    onAddTag: () -> Unit,
    onEditLocation: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    onScanPeople: () -> Unit = {},
    isScanningPeople: Boolean = false,
    faceScanError: String? = null,
    faceUiState: ViewerFaceUiState = ViewerFaceUiState(
        state = if (isScanningPeople) ViewerFaceState.IN_PROGRESS
        else if (faceScanError != null) ViewerFaceState.FAILED
        else if (detectedFaces.isNotEmpty()) ViewerFaceState.COMPLETED_FACES_FOUND
        else ViewerFaceState.NOT_INDEXED,
        faces = detectedFaces,
        errorMessage = faceScanError
    ),
    onScanThisPhoto: () -> Unit = onScanPeople,
    onCancelScan: () -> Unit = {},
    onRescanPhoto: () -> Unit = onScanPeople,
    onRescanLowThreshold: () -> Unit = onScanPeople,
    onInstallModel: () -> Unit = {},
    onRetryScan: () -> Unit = onScanPeople,
    onViewErrorDetails: () -> Unit = {},
    onEditTagOrPerson: () -> Unit = {},
    onFaceClick: ((DetectedMediaFace) -> Unit)? = null,
    selectedEngineId: String = BuffaloLFaceEngine.ENGINE_ID,
    onSelectEngine: (String) -> Unit = {},
    onRemoveTag: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var metadataExpanded by remember { mutableStateOf(true) }

    val cachedLocation = remember(item.uri) {
        com.amresalehin.emregallery.data.PhotoMapLocationCache(context).getLocation(item.uri)
    }
    val hasTags = customTags.isNotEmpty()
    val hasPeople = detectedFaces.isNotEmpty()
    val hasLocation = metadata["location"]?.isNotBlank() == true || metadata.containsKey("latitude") || cachedLocation != null
    val assignedAlbums = remember(item.uri, customAlbums) {
        customAlbums.filter { item.uri in it.mediaUris }
    }

    // Readable place split into primary and secondary
    val rawLocation = metadata["location"].orEmpty()
    val (primaryLocation, secondaryLocation) = remember(rawLocation, metadata["latitude"], metadata["longitude"], cachedLocation) {
        if (rawLocation.isNotBlank()) {
            val parts = rawLocation.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            when {
                parts.size > 1 -> parts.first() to parts.drop(1).joinToString(", ")
                parts.size == 1 -> parts.first() to ""
                else -> "Location" to ""
            }
        } else if (metadata.containsKey("latitude") && metadata.containsKey("longitude")) {
            val lat = metadata["latitude"]?.toDoubleOrNull() ?: 0.0
            val lon = metadata["longitude"]?.toDoubleOrNull() ?: 0.0
            "%.4f°, %.4f°".format(lat, lon) to "GPS coordinates"
        } else if (cachedLocation?.point != null) {
            "%.4f°, %.4f°".format(java.util.Locale.US, cachedLocation.point.latitude, cachedLocation.point.longitude) to "In-app map location"
        } else {
            "" to ""
        }
    }

    // Filter metadata to avoid duplicating header/location/tags
    val metadataRows = remember(metadata, item) {
        buildMetadataRows(item, metadata)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Albums Section
        item(key = "overview_albums") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onAddToAlbum,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add to album",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (assignedAlbums.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        assignedAlbums.forEach { album ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.clickable { onAddToAlbum() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PhotoAlbum,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = album.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable { onAddToAlbum() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("Add to album", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        // 2. Tags Section
        item(key = "overview_tags") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Tags",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(
                        onClick = onAddTag,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("add_tag_manual_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add tag manually",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (hasTags) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        customTags.sorted().forEach { tag ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(
                                        start = 12.dp,
                                        end = if (onRemoveTag != null) 6.dp else 12.dp,
                                        top = 6.dp,
                                        bottom = 6.dp
                                    ),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (onRemoveTag != null) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Remove $tag",
                                            modifier = Modifier
                                                .size(15.dp)
                                                .clickable { onRemoveTag(tag) }
                                                .padding(2.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .clickable { onAddTag() }
                            .testTag("empty_manual_add_tag_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text("Add tag", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // 3. People Section: 6 deterministic states with actionable controls
        item(key = "overview_people") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "People",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val context = LocalContext.current
                    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
                    val isBusy = isProcessingActive || FaceProcessingCoordinator.isBusy(context)
                    var modelMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clickable(enabled = !isBusy) { modelMenuExpanded = true }
                                .alpha(if (!isBusy) 1.0f else 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                val currentEngineName = if (selectedEngineId == BuffaloLFaceEngine.ENGINE_ID) "Buffalo-L" else "YOLO5Face + MobileFaceNet"
                                Text(
                                    text = currentEngineName,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Switch face model",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded && !isBusy,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Buffalo-L") },
                                enabled = !isBusy,
                                trailingIcon = if (selectedEngineId == BuffaloLFaceEngine.ENGINE_ID) {
                                    { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                } else null,
                                onClick = {
                                    modelMenuExpanded = false
                                    if (!FaceProcessingCoordinator.isBusy(context)) {
                                        onSelectEngine(BuffaloLFaceEngine.ENGINE_ID)
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("YOLO5Face + MobileFaceNet") },
                                enabled = !isBusy,
                                trailingIcon = if (selectedEngineId == EnteFaceConstants.ENGINE_ID) {
                                    { Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                } else null,
                                onClick = {
                                    modelMenuExpanded = false
                                    if (!FaceProcessingCoordinator.isBusy(context)) {
                                        onSelectEngine(EnteFaceConstants.ENGINE_ID)
                                    }
                                }
                            )
                        }
                    }
                }

                ViewerFaceContainer(
                    faceUiState = faceUiState,
                    onScanThisPhoto = onScanThisPhoto,
                    onCancelScan = onCancelScan,
                    onRescanPhoto = onRescanPhoto,
                    onRescanLowThreshold = onRescanLowThreshold,
                    onInstallModel = onInstallModel,
                    onRetryScan = onRetryScan,
                    onViewErrorDetails = onViewErrorDetails,
                    onEditTagOrPerson = onEditTagOrPerson,
                    onFaceClick = onFaceClick
                )
            }
        }

        // 4. Location Section
        item(key = "overview_location") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Location",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (hasLocation) {
                        IconButton(
                            onClick = onEditLocation,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit location",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                if (hasLocation) {
                    DetailCard(onClick = onOpenMap) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = primaryLocation,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (secondaryLocation.isNotBlank()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = secondaryLocation,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            val prov = cachedLocation?.provenance
                            if (prov != null) {
                                Spacer(Modifier.height(10.dp))
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Spacer(Modifier.height(8.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    LocationProvenanceRow(
                                        label = "Location Source",
                                        value = when {
                                            prov.source?.contains("exif", ignoreCase = true) == true -> "EXIF Metadata"
                                            else -> prov.source ?: "Unknown source"
                                        }
                                    )
                                    LocationProvenanceRow(
                                        label = "Match Method",
                                        value = when (prov.matchMethod?.lowercase()) {
                                            "interpolated" -> "Interpolated path"
                                            "staypoint" -> "Staypoint visit"
                                            "nearest" -> "Nearest sample"
                                            "existing_exif" -> "Existing EXIF"
                                            else -> prov.matchMethod ?: "Matched"
                                        }
                                    )
                                    if (prov.sampleGapMinutes != null && prov.sampleGapMinutes > 0.0) {
                                        LocationProvenanceRow(
                                            label = "Sample Gap",
                                            value = "%.1f min".format(java.util.Locale.US, prov.sampleGapMinutes)
                                        )
                                    }
                                    if (!prov.timestampSource.isNullOrBlank()) {
                                        LocationProvenanceRow(
                                            label = "Timestamp Source",
                                            value = prov.timestampSource
                                        )
                                    }
                                    LocationProvenanceRow(
                                        label = "EXIF Status",
                                        value = if (prov.exifWritten) "Saved to file EXIF" else "In-app map only (File untouched)"
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.clickable { onEditLocation() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text("Add location", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        // 4. Metadata Section
        if (metadataRows.isNotEmpty()) {
            item(key = "overview_metadata") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { metadataExpanded = !metadataExpanded }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Metadata",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Icon(
                            imageVector = if (metadataExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = "Toggle metadata",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    if (metadataExpanded) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                metadataRows.forEachIndexed { index, (label, value) ->
                                    if (index > 0) {
                                        HorizontalDivider(
                                            thickness = 0.5.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                                            modifier = Modifier.padding(vertical = 10.dp)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1.1f)
                                        )
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads a person's stored representative face crop. A missing crop remains blank,
 * rather than substituting a fabricated face or the whole source photo.
 */
@Composable
fun FaceCropThumbnail(
    mediaUri: String?,
    faceIndex: Int?,
    engineId: String,
    contentDescription: String?,
    bounds: RectF? = null,
    fallbackText: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var faceCrop by remember(engineId, mediaUri, faceIndex, bounds) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(engineId, mediaUri, faceIndex, bounds) {
        faceCrop = if (mediaUri.isNullOrBlank() || faceIndex == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                FaceThumbnailHelper.getFaceCrop(
                    context = context,
                    uri = Uri.parse(mediaUri),
                    faceIndex = faceIndex,
                    engineId = engineId,
                    providedBounds = bounds
                )
            }
        }
    }

    val currentFaceCrop = faceCrop
    if (currentFaceCrop != null) {
        Image(
            bitmap = currentFaceCrop.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = ContentScale.Crop
        )
    } else {
        Surface(
            modifier = modifier,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (!fallbackText.isNullOrBlank()) {
                    Text(
                        text = fallbackText.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = contentDescription,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Large portrait for Overview tab. Uses a person's persistent master-face identity.
 */
@Composable
fun OverviewFacePortrait(
    face: DetectedMediaFace,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val portraitUri = face.portraitMediaUri ?: face.mediaUri
    val portraitIndex = face.portraitFaceIndex ?: face.faceIndex

    val displayName = face.displayName.trim()
    val isNamed = displayName.isNotBlank() && !displayName.equals("Unknown", ignoreCase = true)
    val width = kotlin.math.max(0, (face.boxRight - face.boxLeft).toInt())
    val height = kotlin.math.max(0, (face.boxBottom - face.boxTop).toInt())
    val boxText = if (width > 0 && height > 0) "${width}×${height}" else null

    val faceBounds = if (face.boxRight > face.boxLeft && face.boxBottom > face.boxTop) {
        RectF(face.boxLeft, face.boxTop, face.boxRight, face.boxBottom)
    } else null

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(min = 96.dp, max = 110.dp)
            .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)),
            modifier = Modifier.size(96.dp)
        ) {
            FaceCropThumbnail(
                mediaUri = portraitUri,
                faceIndex = portraitIndex,
                engineId = face.engineId,
                bounds = if (portraitUri == face.mediaUri && portraitIndex == face.faceIndex) faceBounds else null,
                fallbackText = if (isNamed) displayName else null,
                contentDescription = if (isNamed) displayName else "Face",
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        if (isNamed) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Stylized cartoon avatar portrait matching reference screenshot.
 */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ActionsTabContent(
    item: MediaItem,
    customTags: Set<String>,
    detectedFaces: List<DetectedMediaFace>,
    metadata: Map<String, String>,
    detailsState: ViewerDetailsState,
    onRenameFaceClick: (DetectedMediaFace) -> Unit,
    onAddTo: (() -> Unit)? = null,
    onAddLocationClick: (() -> Unit)? = null,
    onAddTagClick: (() -> Unit)? = null,
    onRescanPhoto: (() -> Unit)? = null,
    onAddPersonClick: (() -> Unit)? = null,
    onRemoveTag: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState()
) {
    val coroutineScope = rememberCoroutineScope()
    val hasPeople = detectedFaces.isNotEmpty()

    var selectedEngine by remember { mutableStateOf("Tesseract") }
    var engineExpanded by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }
    var langExpanded by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val isProcessingActive by FaceProcessingCoordinator.isProcessingActive.collectAsStateWithLifecycle()
    val isBusy = isProcessingActive || FaceProcessingCoordinator.isBusy(context)

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // 1. People (detected faces, smaller portraits, muted look, pencil directly beneath)
        if (hasPeople) {
            item(key = "actions_people") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "People",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (onRescanPhoto != null) {
                            TextButton(
                                onClick = onRescanPhoto,
                                enabled = !isBusy,
                                modifier = Modifier.defaultMinSize(minHeight = 48.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Re-scan")
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        detectedFaces.forEach { face ->
                            ActionFacePortrait(
                                face = face,
                                enabled = !isBusy,
                                onRename = { if (!FaceProcessingCoordinator.isBusy(context)) onRenameFaceClick(face) }
                            )
                        }
                    }
                }
            }
        }

        // 2. Photo Tags Card (Images only)
        if (!item.isVideo) {
            item(key = "actions_tags") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Label,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Column {
                                    Text(
                                        text = "Photo Tags",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Organize photos with custom labels",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // Existing tags if any
                        if (customTags.isNotEmpty()) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                customTags.forEach { tag ->
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(start = 10.dp, end = 6.dp)
                                        ) {
                                            Text(
                                                text = tag,
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            if (onRemoveTag != null) {
                                                Spacer(Modifier.width(4.dp))
                                                IconButton(
                                                    onClick = { onRemoveTag(tag) },
                                                    modifier = Modifier.size(20.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Close,
                                                        contentDescription = "Remove tag $tag",
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (onAddTagClick != null) {
                            Button(
                                onClick = onAddTagClick,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .defaultMinSize(minHeight = 44.dp)
                                    .testTag("action_add_tag_manual_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add Tag")
                            }
                        }
                    }
                }
            }
        }

        // 3. OCR Section (Images only)
        if (!item.isVideo) {
            item(key = "actions_ocr") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Plain Extract text heading without leading icon
                Text(
                    text = "Extract text",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Full-width Engine dropdown on its own row
                ExposedDropdownMenuBox(
                    expanded = engineExpanded,
                    onExpandedChange = { engineExpanded = it },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = selectedEngine,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Engine") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = engineExpanded,
                        onDismissRequest = { engineExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Tesseract") },
                            onClick = {
                                selectedEngine = "Tesseract"
                                engineExpanded = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Google ML Kit") },
                            onClick = {
                                selectedEngine = "Google ML Kit"
                                engineExpanded = false
                            }
                        )
                    }
                }

                // Mode and Language dropdowns side-by-side
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isTesseract = selectedEngine == "Tesseract"
                    val isBest = detailsState.ocrModel.equals("best", ignoreCase = true)

                    // Mode dropdown
                    ExposedDropdownMenuBox(
                        expanded = modeExpanded,
                        onExpandedChange = { if (isTesseract) modeExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = if (isTesseract) (if (isBest) "Best" else "Fast") else "Multi-Script",
                            onValueChange = {},
                            readOnly = true,
                            enabled = isTesseract,
                            label = { Text("Mode") },
                            trailingIcon = { if (isTesseract) ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        if (isTesseract) {
                            ExposedDropdownMenu(
                                expanded = modeExpanded,
                                onDismissRequest = { modeExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Fast") },
                                    onClick = {
                                        detailsState.ocrModel = "fast"
                                        modeExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Best") },
                                    onClick = {
                                        detailsState.ocrModel = "best"
                                        modeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Language dropdown
                    val currentLangCode = detailsState.ocrLanguages.firstOrNull() ?: "eng"
                    val currentLangName = TesseractOcr.supportedLanguages.entries.firstOrNull { it.value == currentLangCode }?.key ?: "English"
                    val currentMlKitLabel = remember(detailsState.mlKitLanguages.toList()) {
                        com.amresalehin.emregallery.ocr.MlKitLanguage.getDisplayLabel(detailsState.mlKitLanguages)
                    }

                    ExposedDropdownMenuBox(
                        expanded = langExpanded,
                        onExpandedChange = { langExpanded = it },
                        modifier = Modifier.weight(1f)
                    ) {
                        OutlinedTextField(
                            value = if (isTesseract) currentLangName else currentMlKitLabel,
                            onValueChange = {},
                            readOnly = true,
                            enabled = true,
                            label = { Text("Language") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        )
                        ExposedDropdownMenu(
                            expanded = langExpanded,
                            onDismissRequest = { langExpanded = false }
                        ) {
                            if (isTesseract) {
                                TesseractOcr.supportedLanguages.forEach { (name, code) ->
                                    DropdownMenuItem(
                                        text = { Text(name) },
                                        onClick = {
                                            detailsState.ocrLanguages.clear()
                                            detailsState.ocrLanguages.add(code)
                                            langExpanded = false
                                        }
                                    )
                                }
                            } else {
                                val allSelected = detailsState.mlKitLanguages.containsAll(com.amresalehin.emregallery.ocr.MlKitLanguage.ALL_CODES)
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("All Scripts (${com.amresalehin.emregallery.ocr.MlKitLanguage.entries.size})", fontWeight = FontWeight.SemiBold)
                                            Text("Latin, Chinese, Devanagari, Japanese, Korean", style = MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                    trailingIcon = {
                                        if (allSelected) {
                                            Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                        }
                                    },
                                    onClick = {
                                        detailsState.mlKitLanguages.clear()
                                        if (allSelected) {
                                            detailsState.mlKitLanguages.add("latin")
                                        } else {
                                            detailsState.mlKitLanguages.addAll(com.amresalehin.emregallery.ocr.MlKitLanguage.ALL_CODES)
                                        }
                                        langExpanded = false
                                    }
                                )
                                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                                com.amresalehin.emregallery.ocr.MlKitLanguage.entries.forEach { lang ->
                                    val isSelected = lang.code in detailsState.mlKitLanguages
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(lang.title, fontWeight = FontWeight.SemiBold)
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("(${lang.sampleText})", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                }
                                                Text(lang.nativeScript, style = MaterialTheme.typography.labelSmall)
                                            }
                                        },
                                        trailingIcon = {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = null
                                            )
                                        },
                                        onClick = {
                                            if (isSelected) {
                                                if (detailsState.mlKitLanguages.size > 1) {
                                                    detailsState.mlKitLanguages.remove(lang.code)
                                                }
                                            } else {
                                                detailsState.mlKitLanguages.add(lang.code)
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Compact Run OCR button below dropdowns, aligned to the right
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                detailsState.runOcr(engine = if (selectedEngine == "Google ML Kit") "google_mlkit" else "tesseract")
                            }
                        },
                        enabled = !item.isVideo && !detailsState.ocrBusy,
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp)
                    ) {
                        if (detailsState.ocrBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Running...")
                        } else {
                            Text("Run OCR")
                        }
                    }
                }

                if (detailsState.ocrError != null) {
                    Text(
                        text = detailsState.ocrError.orEmpty(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (detailsState.ocrText.isNotBlank()) {
                    DetailCard(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recognized Text",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                IconButton(
                                    onClick = { detailsState.copyOcrText() },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy text",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Text(
                                text = detailsState.ocrText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // 4. FFmpeg Video Tools (Videos only)
        if (item.isVideo) {
            item(key = "actions_video_ffmpeg") {
                FfmpegVideoToolsSection(
                    item = item,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * Smaller portrait for Actions tab with muted visual treatment and a small pencil button directly beneath.
 */
@Composable
fun ActionFacePortrait(
    face: DetectedMediaFace,
    enabled: Boolean = true,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    val portraitUri = face.portraitMediaUri ?: face.mediaUri
    val portraitIndex = face.portraitFaceIndex ?: face.faceIndex

    val displayName = face.displayName.trim()
    val isNamed = displayName.isNotBlank() && !displayName.equals("Unknown", ignoreCase = true)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(min = 56.dp, max = 70.dp)
            .alpha(if (enabled) 1.0f else 0.45f)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { alpha = 0.88f }
        ) {
            FaceCropThumbnail(
                mediaUri = portraitUri,
                faceIndex = portraitIndex,
                engineId = face.engineId,
                contentDescription = if (isNamed) displayName else null,
                modifier = Modifier.fillMaxSize().clip(CircleShape)
            )
        }
        if (isNamed) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(2.dp))
        IconButton(
            onClick = onRename,
            enabled = enabled,
            modifier = Modifier.size(26.dp)
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = "Rename person",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun buildMetadataRows(item: MediaItem, metadata: Map<String, String>): List<Pair<String, String>> {
    val videoWidth = metadata["video_width"]?.toIntOrNull() ?: 0
    val videoHeight = metadata["video_height"]?.toIntOrNull() ?: 0
    val width = item.width.takeIf { it > 0 } ?: videoWidth
    val height = item.height.takeIf { it > 0 } ?: videoHeight
    val dimensions = if (width > 0 && height > 0) "$width × $height px" else null

    val videoDurationMillis = item.durationMillis.takeIf { it > 0 }
        ?: metadata["video_duration_ms"]?.toLongOrNull()?.takeIf { it > 0 }
    val videoDuration = if (item.isVideo) {
        videoDurationMillis?.let { formatDuration(it) }
    } else null

    val bitrate = metadata["video_bitrate_bps"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
        when {
            it >= 1_000_000L -> "%.2f Mbps".format(it / 1_000_000.0)
            it >= 1_000L -> "%.0f kbps".format(it / 1_000.0)
            else -> "$it bps"
        }
    }
    val captureFrameRate = metadata["video_capture_framerate"]?.toDoubleOrNull()
        ?.takeIf { it > 0.0 }?.let { "%.2f fps".format(it) }
    val rotation = metadata["video_rotation_degrees"]?.toIntOrNull()?.let { "$it°" }
    val audioTrack = metadata["video_has_audio"]?.let {
        when {
            it.equals("yes", ignoreCase = true) -> "Present"
            it.equals("no", ignoreCase = true) -> "Not present"
            else -> it
        }
    }

    val formatString = run {
        val raw = item.mimeType?.takeIf { it.isNotBlank() } ?: metadata["mime_type"].orEmpty()
        when {
            raw.contains("jpeg", ignoreCase = true) || raw.contains("jpg", ignoreCase = true) -> "JPEG"
            raw.contains("png", ignoreCase = true) -> "PNG"
            raw.contains("webp", ignoreCase = true) -> "WEBP"
            raw.contains("gif", ignoreCase = true) -> "GIF"
            raw.contains("heic", ignoreCase = true) || raw.contains("heif", ignoreCase = true) -> "HEIF"
            raw.contains("mp4", ignoreCase = true) -> "MP4"
            raw.contains("webm", ignoreCase = true) -> "WEBM"
            raw.contains("3gp", ignoreCase = true) -> "3GP"
            raw.isNotBlank() -> raw.substringAfter('/') .uppercase()
            else -> null
        }
    }

    val gpsCoordinates = if (metadata.containsKey("latitude") && metadata.containsKey("longitude")) {
        val lat = metadata["latitude"]?.toDoubleOrNull()
        val lon = metadata["longitude"]?.toDoubleOrNull()
        if (lat != null && lon != null) {
            "%.4f° %s, %.4f° %s".format(
                abs(lat), if (lat >= 0) "N" else "S",
                abs(lon), if (lon >= 0) "E" else "W"
            )
        } else null
    } else null

    val rawRows = listOf(
        "Media type" to if (item.isVideo) "Video" else "Image",
        "File format" to formatString,
        "Dimensions" to dimensions,
        "Camera manufacturer" to metadata["make"],
        "Camera model" to metadata["model"],
        "Aperture" to metadata["aperture"],
        "Shutter speed" to metadata["shutter"],
        "ISO" to metadata["iso"],
        "Focal length" to metadata["focal"],
        "Flash" to metadata["flash"],
        "Color space" to metadata["color"],
        "GPS coordinates" to gpsCoordinates,
        "Duration" to videoDuration,
        "Video bitrate" to (if (item.isVideo) bitrate else null),
        "Capture frame rate" to (if (item.isVideo) captureFrameRate else null),
        "Rotation" to (if (item.isVideo) rotation else null),
        "Audio track" to (if (item.isVideo) audioTrack else null),
        "Track count" to (if (item.isVideo) metadata["video_track_count"] else null),
        "Creator" to metadata["creator"],
        "Description" to metadata["description"],
        "Copyright" to metadata["copyright"],
        "Subject" to metadata["subject"],
        "Keywords" to metadata["keywords"],
        "Rating" to metadata["rating"]
    )

    return rawRows.mapNotNull { (label, value) ->
        value?.trim()?.takeIf { it.isNotEmpty() && it != "Not available" }?.let { label to it }
    }
}

/**
 * FFmpeg Multimedia Tools section for videos in Viewer details actions tab.
 */
@Composable
fun FfmpegVideoToolsSection(
    item: MediaItem,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isProbing by remember { mutableStateOf(false) }
    var mediaInfo by remember { mutableStateOf<FfprobeMediaInfo?>(null) }
    var probeAttempted by remember { mutableStateOf(false) }

    var isExtractingAudio by remember { mutableStateOf(false) }
    var audioSuccessMsg by remember { mutableStateOf<String?>(null) }

    var isCompressing by remember { mutableStateOf(false) }
    var compressSuccessMsg by remember { mutableStateOf<String?>(null) }

    var isExtractingFrame by remember { mutableStateOf(false) }
    var frameSuccessMsg by remember { mutableStateOf<String?>(null) }

    var errorMsg by remember { mutableStateOf<String?>(null) }

    // Auto-probe media info once on mount
    LaunchedEffect(item.uri) {
        if (!probeAttempted) {
            probeAttempted = true
            isProbing = true
            coroutineScope.launch {
                val probed = FfmpegKitEngine.probeMedia(item.uri, context)
                mediaInfo = probed
                isProbing = false
            }
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MovieFilter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "FFmpeg Video Tools",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            ) {
                Text(
                    text = "FFmpegKit",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // FFprobe Media Stream Inspector Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stream Details (FFprobe)",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isProbing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }

                if (mediaInfo != null) {
                    val info = mediaInfo!!
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Container Format", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(info.format.take(24), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                        info.videoCodec?.let { codec ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Video Codec", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(codec + (info.resolution?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        info.audioCodec?.let { codec ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Audio Codec", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val channelsStr = if (info.audioChannels != null) " · ${info.audioChannels}ch" else ""
                                val rateStr = if (info.sampleRateHz != null) " · ${info.sampleRateHz}Hz" else ""
                                Text("$codec$channelsStr$rateStr", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (info.bitrateKbps > 0) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Bitrate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${info.bitrateKbps} kbps", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                } else if (!isProbing) {
                    Text(
                        text = "Native FFmpeg probe ready. Tap actions below to process.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Action Buttons Row
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Extract Audio
            OutlinedButton(
                onClick = {
                    if (isExtractingAudio || isCompressing || isExtractingFrame) return@OutlinedButton
                    isExtractingAudio = true
                    audioSuccessMsg = null
                    errorMsg = null
                    coroutineScope.launch {
                        val file = FfmpegKitEngine.resolveUriToFile(context, item.uri, item.name)
                        if (file != null && file.exists()) {
                            val res = FfmpegKitEngine.extractAudio(file, outputFormat = "mp3", context = context)
                            if (res.isSuccess && res.outputFile != null) {
                                audioSuccessMsg = "Saved: ${res.outputFile.name}"
                            } else {
                                errorMsg = "Audio extraction failed (Code ${res.returnCode})"
                            }
                        } else {
                            errorMsg = "Unable to read local video file"
                        }
                        isExtractingAudio = false
                    }
                },
                enabled = !isExtractingAudio && !isCompressing && !isExtractingFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isExtractingAudio) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Extracting MP3 Audio...")
                } else {
                    Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Extract Audio Track (MP3)")
                }
            }

            // 2. Compress Video
            OutlinedButton(
                onClick = {
                    if (isExtractingAudio || isCompressing || isExtractingFrame) return@OutlinedButton
                    isCompressing = true
                    compressSuccessMsg = null
                    errorMsg = null
                    coroutineScope.launch {
                        val file = FfmpegKitEngine.resolveUriToFile(context, item.uri, item.name)
                        if (file != null && file.exists()) {
                            val res = FfmpegKitEngine.compressVideo(file, targetHeight = 720, crf = 26, context = context)
                            if (res.isSuccess && res.outputFile != null) {
                                compressSuccessMsg = "Saved: ${res.outputFile.name} (${res.outputFile.length() / 1024} KB)"
                            } else {
                                errorMsg = "Video compression failed (Code ${res.returnCode})"
                            }
                        } else {
                            errorMsg = "Unable to read local video file"
                        }
                        isCompressing = false
                    }
                },
                enabled = !isExtractingAudio && !isCompressing && !isExtractingFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isCompressing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Compressing (720p H.264)...")
                } else {
                    Icon(Icons.Default.VideoSettings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Compress Video (720p H.264)")
                }
            }

            // 3. Save Poster Frame
            OutlinedButton(
                onClick = {
                    if (isExtractingAudio || isCompressing || isExtractingFrame) return@OutlinedButton
                    isExtractingFrame = true
                    frameSuccessMsg = null
                    errorMsg = null
                    coroutineScope.launch {
                        val file = FfmpegKitEngine.resolveUriToFile(context, item.uri, item.name)
                        if (file != null && file.exists()) {
                            val thumb = FfmpegKitEngine.extractThumbnail(file, positionSec = 1.0, context = context)
                            if (thumb != null && thumb.exists()) {
                                frameSuccessMsg = "Saved poster: ${thumb.name}"
                            } else {
                                errorMsg = "Frame extraction failed"
                            }
                        } else {
                            errorMsg = "Unable to read local video file"
                        }
                        isExtractingFrame = false
                    }
                },
                enabled = !isExtractingAudio && !isCompressing && !isExtractingFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isExtractingFrame) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Extracting Poster Frame...")
                } else {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save Video Poster Frame (JPEG)")
                }
            }
        }

        // Status messages
        audioSuccessMsg?.let { msg ->
            Text(
                text = "✓ $msg",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Medium
            )
        }
        compressSuccessMsg?.let { msg ->
            Text(
                text = "✓ $msg",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Medium
            )
        }
        frameSuccessMsg?.let { msg ->
            Text(
                text = "✓ $msg",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF2E7D32),
                fontWeight = FontWeight.Medium
            )
        }
        errorMsg?.let { err ->
            Text(
                text = "✕ $err",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LocationProvenanceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

