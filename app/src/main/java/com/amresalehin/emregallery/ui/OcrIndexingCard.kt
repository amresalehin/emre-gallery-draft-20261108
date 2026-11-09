package com.amresalehin.emregallery.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.ocr.MlKitLanguage
import com.amresalehin.emregallery.ocr.TesseractModel
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.NumberFormat
import kotlin.math.roundToInt

@Composable
fun OcrIndexingCard(
    totalPhotos: Int,
    ocrState: OcrState,
    ocrLanguages: Set<String>,
    countsReady: Boolean,
    cardBackground: Color,
    cardBorder: BorderStroke,
    numberFormat: NumberFormat,
    onToggleOcr: () -> Unit,
    onStopOcr: () -> Unit,
    onReindexOcr: () -> Unit,
    onEngineChange: (String) -> Unit = {},
    onModelChange: (String) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onToggleMlKitLanguage: (String) -> Unit = {},
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) {
            isExpanded = true
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocrProgress = if (totalPhotos > 0) (ocrState.processed.toFloat() / totalPhotos).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(targetValue = ocrProgress, label = "ocr_progress")
    val ocrPercent = (ocrProgress * 100f).roundToInt()

    val isMlKit = ocrState.engine.equals("google_mlkit", ignoreCase = true) || ocrState.engine.equals("mlkit", ignoreCase = true)
    val isBestModel = ocrState.model.equals("best", ignoreCase = true)
    val activeTesseractModel = if (isBestModel) TesseractModel.BEST else TesseractModel.FAST

    val selectedEngineOption = when {
        isMlKit -> "mlkit"
        isBestModel -> "tess_best"
        else -> "tess_fast"
    }

    var showActionMenu by remember { mutableStateOf(false) }
    var downloadingLanguageCode by remember { mutableStateOf<String?>(null) }

    fun downloadAndSelectLanguage(code: String) {
        if (downloadingLanguageCode != null) return
        downloadingLanguageCode = code
        scope.launch(Dispatchers.IO) {
            val success = runCatching {
                TesseractOcr.prepareLanguages(context, listOf(code), activeTesseractModel)
                true
            }.getOrDefault(false)

            if (success) {
                onToggleLanguage(code)
            }
            downloadingLanguageCode = null
        }
    }

    val isRunning = ocrState.isRunning && !ocrState.isPaused
    val isPaused = ocrState.isRunning && ocrState.isPaused
    val isCompleted = totalPhotos > 0 && ocrState.processed >= totalPhotos

    Card(
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        border = cardBorder,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("ocr_indexing_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row: OCR icon, OCR Title, Dynamic Corner Box, Expand Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clickable Left area (Icon + Title) to toggle expansion
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { isExpanded = !isExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Circle with OCR Icon matching WelcomeScreen
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(46.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            OcrIcon(tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Text(
                        text = "OCR",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }

                // Optional Stop button when running/paused
                if (ocrState.isRunning) {
                    IconButton(
                        onClick = onStopOcr,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("ocr_stop_button")
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                // Dynamic Corner Box
                Box {
                    Surface(
                        onClick = {
                            if (isCompleted) {
                                showActionMenu = true
                            } else if (countsReady && totalPhotos > 0) {
                                onToggleOcr()
                            }
                        },
                        enabled = countsReady && totalPhotos > 0,
                        shape = RoundedCornerShape(14.dp),
                        color = when {
                            isRunning -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            isPaused -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                            isCompleted -> MaterialTheme.colorScheme.surfaceContainerHigh
                            else -> MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        border = BorderStroke(
                            1.dp,
                            when {
                                isRunning -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                isPaused -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f)
                                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            }
                        ),
                        modifier = Modifier
                            .height(36.dp)
                            .defaultMinSize(minWidth = 84.dp)
                            .testTag("ocr_corner_box_action")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            when {
                                isRunning -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "$ocrPercent% · Pause",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                                isPaused -> {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "$ocrPercent% · Resume",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.secondary
                                        )
                                    )
                                }
                                isCompleted -> {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = "100% · Done",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                                else -> {
                                    Icon(
                                        Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = if (ocrState.processed > 0) "Start ($ocrPercent%)" else "Start",
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // Dropdown menu for additional actions (Re-index, Clear Cache)
                    DropdownMenu(
                        expanded = showActionMenu,
                        onDismissRequest = { showActionMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Re-index All Photos") },
                            onClick = {
                                showActionMenu = false
                                onReindexOcr()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Clear OCR Cache") },
                            onClick = {
                                showActionMenu = false
                                onClearCache()
                            }
                        )
                    }
                }

                Spacer(Modifier.width(6.dp))

                IconButton(
                    onClick = { isExpanded = !isExpanded },
                    modifier = Modifier
                        .size(34.dp)
                        .testTag("ocr_expand_toggle")
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "Collapse OCR" else "Expand OCR",
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Minimalist Divider with integrated Progress Indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
            ) {
                if (isExpanded) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        thickness = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (ocrProgress > 0f) {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = Color.Transparent
                    )
                }
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Engine Label (No explanatory text)
                    Text(
                        text = "Engine",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Engine Segmented Buttons: ML Kit | Tess-Best | Tess-Fast
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SegmentOption(
                                text = "ML Kit",
                                isSelected = selectedEngineOption == "mlkit",
                                onClick = {
                                    if (!ocrState.isRunning) onEngineChange("google_mlkit")
                                },
                                modifier = Modifier.weight(1f)
                            )

                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxHeight(0.6f).width(1.dp)
                            )

                            SegmentOption(
                                text = "Tess-Best",
                                isSelected = selectedEngineOption == "tess_best",
                                onClick = {
                                    if (!ocrState.isRunning) {
                                        onEngineChange("tesseract")
                                        onModelChange("best")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )

                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxHeight(0.6f).width(1.dp)
                            )

                            SegmentOption(
                                text = "Tess-Fast",
                                isSelected = selectedEngineOption == "tess_fast",
                                onClick = {
                                    if (!ocrState.isRunning) {
                                        onEngineChange("tesseract")
                                        onModelChange("fast")
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Language(s) Label (No explanatory text)
                    Text(
                        text = "Language(s)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Language Chips Row (Horizontally scrollable with all supported languages)
                    if (isMlKit) {
                        val mlKitOptions = remember {
                            MlKitLanguage.entries.map { it.code to it.title }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("ocr_languages_mlkit_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            mlKitOptions.forEach { (code, label) ->
                                val isSelected = code in ocrState.mlKitLanguages
                                LanguageChip(
                                    label = label,
                                    isSelected = isSelected,
                                    onClick = { onToggleMlKitLanguage(code) },
                                    testTag = "ocr_lang_$code"
                                )
                            }
                        }
                    } else {
                        val tesseractOptions = remember {
                            TesseractOcr.supportedLanguages.entries.map { it.value to it.key }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("ocr_languages_tesseract_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tesseractOptions.forEach { (code, label) ->
                                val isSelected = code in ocrLanguages
                                LanguageChip(
                                    label = label,
                                    isSelected = isSelected,
                                    onClick = {
                                        val isInstalled = TesseractOcr.isLanguageInstalled(context, code, activeTesseractModel) ||
                                            TesseractOcr.isLanguageBundledInAssets(context, code, activeTesseractModel)
                                        if (!isInstalled) {
                                            downloadAndSelectLanguage(code)
                                        } else {
                                            onToggleLanguage(code)
                                        }
                                    },
                                    testTag = "ocr_lang_$code"
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentOption(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelected) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF161A20),
            modifier = modifier.fillMaxHeight()
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                )
            }
        }
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .fillMaxHeight()
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun LanguageChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            1.dp,
            if (isSelected) Color(0xFF161A20) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        ),
        modifier = Modifier
            .height(38.dp)
            .defaultMinSize(minWidth = 72.dp)
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 14.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface
                )
            )
        }
    }
}

@Composable
private fun OcrIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF181C22)) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.8.dp.toPx()
            val w = size.width
            val h = size.height

            // Top-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, cornerLength), strokeWidth)

            // Top-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w - cornerLength, 0f), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, 0f), androidx.compose.ui.geometry.Offset(w, cornerLength), strokeWidth)

            // Bottom-left
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(0f, h - cornerLength), strokeWidth)

            // Bottom-right
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w - cornerLength, h), strokeWidth)
            drawLine(tint, androidx.compose.ui.geometry.Offset(w, h), androidx.compose.ui.geometry.Offset(w, h - cornerLength), strokeWidth)
        }
        Text(
            text = "OCR",
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 7.5.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                color = tint
            )
        )
    }
}
