package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amresalehin.emregallery.data.*
import java.text.SimpleDateFormat
import java.util.*

// One UI / Samsung Gallery-inspired neutral color tokens
object SearchColors {
    val LightBg = Color(0xFFF1F2F4)
    val LightBarSurface = Color(0xFFE7E9ED)
    val LightCardSurface = Color(0xFFFAFAFB)
    val LightPrimaryText = Color(0xFF26313D)
    val LightSecondaryText = Color(0xFF77808C)
    val LightSelectedSurface = Color(0xFFDCE1E7)
    val LightDivider = Color(0xFFD9DDE2)

    val DarkBg = Color(0xFF131518)
    val DarkBarSurface = Color(0xFF1E2228)
    val DarkCardSurface = Color(0xFF23272F)
    val DarkPrimaryText = Color(0xFFE3E5E8)
    val DarkSecondaryText = Color(0xFF939AA3)
    val DarkSelectedSurface = Color(0xFF2E343D)
    val DarkDivider = Color(0xFF2C3038)
}

@Composable
fun searchBgColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkBg else SearchColors.LightBg

@Composable
fun searchBarSurfaceColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkBarSurface else SearchColors.LightBarSurface

@Composable
fun searchCardSurfaceColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkCardSurface else SearchColors.LightCardSurface

@Composable
fun searchPrimaryTextColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkPrimaryText else SearchColors.LightPrimaryText

@Composable
fun searchSecondaryTextColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkSecondaryText else SearchColors.LightSecondaryText

@Composable
fun searchSelectedSurfaceColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkSelectedSurface else SearchColors.LightSelectedSurface

@Composable
fun searchDividerColor(isDark: Boolean): Color = if (isDark) SearchColors.DarkDivider else SearchColors.LightDivider

/**
 * Compact, single-row rounded capsule search bar.
 * Contains: Back button inside leading edge, Search icon, text input with visible caret,
 * Clear button (when query is non-empty), and Filter funnel button inside the trailing edge.
 */
@Composable
fun SearchBarCapsule(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onOpenFilterSheet: () -> Unit,
    hasActiveFilters: Boolean,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    isDark: Boolean = false,
    placeholder: String = "Search photos, people, places…"
) {
    val barColor = searchBarSurfaceColor(isDark)
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val dividerCol = searchDividerColor(isDark)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = CircleShape,
        color = barColor,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Inside Leading Back Button
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(44.dp)
                    .testTag("search_back_button")
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = textColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Search Icon
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = secondaryText,
                modifier = Modifier
                    .padding(start = 2.dp, end = 8.dp)
                    .size(20.dp)
            )

            // Search Input with visible caret
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .testTag("search_input_field"),
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(textColor),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            if (query.isNotBlank()) {
                                onSearchSubmit(query.trim())
                            }
                        }
                    )
                )
            }

            // Clear Query Action 'X'
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(40.dp)
                        .testTag("search_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = secondaryText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Subtle vertical separator between text field and filter icon inside bar
            Spacer(
                modifier = Modifier
                    .height(20.dp)
                    .width(1.dp)
                    .background(dividerCol.copy(alpha = 0.7f))
            )

            // Trailing Filter Funnel Action Inside Search Bar
            Box {
                IconButton(
                    onClick = onOpenFilterSheet,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("search_filter_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterList,
                        contentDescription = "Search filters",
                        tint = if (hasActiveFilters) textColor else secondaryText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (hasActiveFilters) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 10.dp, end = 10.dp)
                            .size(7.dp)
                            .background(textColor, CircleShape)
                    )
                }
            }
        }
    }
}

/**
 * Horizontally scrollable quick-filter chips row.
 * Includes interactive toggle pills: Videos, Images, Gifs, GPS, No GPS,
 * Text, Without Text, Face, No Face, Time, and dynamic entity chips.
 * Tap to select, tap again to unselect.
 */
@Composable
fun SearchFilterChipsRow(
    filterState: SearchFilterState,
    onFilterChange: (SearchFilterState) -> Unit,
    dynamicChips: List<DynamicEntityChip>,
    onToggleDynamicChip: (DynamicEntityChip) -> Unit,
    onRequestCustomDatePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val scrollState = rememberScrollState()

    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val selectedSurface = searchSelectedSurfaceColor(isDark)
    val dividerCol = searchDividerColor(isDark)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. VIDEOS Pill (Icon only)
        val isVideosSelected = filterState.mediaType == MediaTypeFilter.VIDEOS
        SearchFilterPill(
            label = "Videos",
            isSelected = isVideosSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        mediaType = if (isVideosSelected) MediaTypeFilter.ALL else MediaTypeFilter.VIDEOS
                    )
                )
            },
            icon = Icons.Default.Videocam,
            contentDescription = "Videos",
            showLabel = false,
            testTag = "filter_pill_videos",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 2. IMAGES Pill (Icon only)
        val isImagesSelected = filterState.mediaType == MediaTypeFilter.PHOTOS
        SearchFilterPill(
            label = "Images",
            isSelected = isImagesSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        mediaType = if (isImagesSelected) MediaTypeFilter.ALL else MediaTypeFilter.PHOTOS
                    )
                )
            },
            icon = Icons.Default.Image,
            contentDescription = "Images",
            showLabel = false,
            testTag = "filter_pill_images",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 3. GIFS Pill (Icon only)
        val isGifsSelected = filterState.mediaType == MediaTypeFilter.GIFS
        SearchFilterPill(
            label = "Gifs",
            isSelected = isGifsSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        mediaType = if (isGifsSelected) MediaTypeFilter.ALL else MediaTypeFilter.GIFS
                    )
                )
            },
            icon = Icons.Default.Gif,
            contentDescription = "Gifs",
            showLabel = false,
            testTag = "filter_pill_gifs",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 4. FAVOURITES Pill (Icon only)
        val isFavSelected = filterState.favoritesOnly
        SearchFilterPill(
            label = "Favourites",
            isSelected = isFavSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(favoritesOnly = !isFavSelected)
                )
            },
            icon = if (isFavSelected) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = "Favourites",
            showLabel = false,
            testTag = "filter_pill_favourites",
            textColor = if (isFavSelected) Color(0xFFE53935) else textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 5. GPS Pill (Icon only)
        val isGpsSelected = filterState.gpsFilter == GpsFilter.HAS_GPS
        SearchFilterPill(
            label = "GPS",
            isSelected = isGpsSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        gpsFilter = if (isGpsSelected) GpsFilter.ALL else GpsFilter.HAS_GPS
                    )
                )
            },
            icon = Icons.Default.LocationOn,
            contentDescription = "GPS",
            showLabel = false,
            testTag = "filter_pill_gps",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 6. NO GPS Pill (Icon only)
        val isNoGpsSelected = filterState.gpsFilter == GpsFilter.NO_GPS
        SearchFilterPill(
            label = "No GPS",
            isSelected = isNoGpsSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        gpsFilter = if (isNoGpsSelected) GpsFilter.ALL else GpsFilter.NO_GPS
                    )
                )
            },
            icon = Icons.Default.LocationOff,
            contentDescription = "No GPS",
            showLabel = false,
            testTag = "filter_pill_no_gps",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 7. WITH TEXT Pill (Icon only)
        val isTextSelected = filterState.ocrTextFilter == OcrTextFilter.HAS_TEXT
        SearchFilterPill(
            label = "With Text",
            isSelected = isTextSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        ocrTextFilter = if (isTextSelected) OcrTextFilter.ALL else OcrTextFilter.HAS_TEXT
                    )
                )
            },
            icon = Icons.Default.TextFields,
            contentDescription = "With text",
            showLabel = false,
            testTag = "filter_pill_text",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 8. WITHOUT TEXT Pill (Icon only)
        val isWithoutTextSelected = filterState.ocrTextFilter == OcrTextFilter.NO_TEXT
        SearchFilterPill(
            label = "Without Text",
            isSelected = isWithoutTextSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        ocrTextFilter = if (isWithoutTextSelected) OcrTextFilter.ALL else OcrTextFilter.NO_TEXT
                    )
                )
            },
            icon = Icons.Default.FontDownloadOff,
            contentDescription = "Without text",
            showLabel = false,
            testTag = "filter_pill_without_text",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 9. FACE Pill (Icon only)
        val isFaceSelected = filterState.faceFilter == FaceFilter.HAS_FACE
        SearchFilterPill(
            label = "Face",
            isSelected = isFaceSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        faceFilter = if (isFaceSelected) FaceFilter.ALL else FaceFilter.HAS_FACE
                    )
                )
            },
            icon = Icons.Default.Face,
            contentDescription = "Face",
            showLabel = false,
            testTag = "filter_pill_face",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 10. NO FACE Pill (Icon only)
        val isNoFaceSelected = filterState.faceFilter == FaceFilter.NO_FACE
        SearchFilterPill(
            label = "No Face",
            isSelected = isNoFaceSelected,
            onToggle = {
                onFilterChange(
                    filterState.copy(
                        faceFilter = if (isNoFaceSelected) FaceFilter.ALL else FaceFilter.NO_FACE
                    )
                )
            },
            icon = Icons.Default.PersonOff,
            contentDescription = "No face",
            showLabel = false,
            testTag = "filter_pill_no_face",
            textColor = textColor,
            secondaryText = secondaryText,
            cardSurface = cardSurface,
            selectedSurface = selectedSurface,
            dividerCol = dividerCol
        )

        // 11. Dynamic Entity Chips (Strictly derived from matching results)
        dynamicChips.forEach { chip ->
            val isChipSelected = when (chip.kind) {
                ChipKind.ALBUM -> filterState.selectedAlbumId == chip.value
                ChipKind.PERSON -> filterState.selectedPersonId == chip.value
                ChipKind.PLACE -> filterState.selectedPlace == chip.value
                ChipKind.TAG -> filterState.selectedTag == chip.value
            }

            Surface(
                modifier = Modifier
                    .height(34.dp)
                    .clip(CircleShape)
                    .clickable { onToggleDynamicChip(chip) }
                    .testTag("dynamic_chip_${chip.id}"),
                shape = CircleShape,
                color = if (isChipSelected) selectedSurface else cardSurface,
                border = BorderStroke(1.dp, if (isChipSelected) textColor.copy(alpha = 0.5f) else dividerCol)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val icon = when (chip.kind) {
                        ChipKind.ALBUM -> Icons.Default.PhotoAlbum
                        ChipKind.PERSON -> Icons.Default.Person
                        ChipKind.PLACE -> Icons.Default.Place
                        ChipKind.TAG -> Icons.Default.Tag
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (isChipSelected) textColor else secondaryText
                    )
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isChipSelected) FontWeight.SemiBold else FontWeight.Normal
                        ),
                        color = textColor
                    )
                    Text(
                        text = chip.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryText
                    )
                }
            }
        }
    }
}

/**
 * Reusable filter pill with tap-to-select and tap-again-to-unselect behavior.
 * Supports both icon-only mode (showLabel = false) and icon+text mode (showLabel = true).
 */
@Composable
fun SearchFilterPill(
    label: String = "",
    isSelected: Boolean,
    onToggle: () -> Unit,
    icon: ImageVector? = null,
    contentDescription: String = label,
    showLabel: Boolean = true,
    testTag: String = "",
    textColor: Color,
    secondaryText: Color,
    cardSurface: Color,
    selectedSurface: Color,
    dividerCol: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .then(
                if (!showLabel) Modifier.width(38.dp) else Modifier
            )
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .testTag(testTag),
        shape = CircleShape,
        color = if (isSelected) selectedSurface else cardSurface,
        border = BorderStroke(
            width = if (isSelected) 1.2.dp else 1.dp,
            color = if (isSelected) textColor.copy(alpha = 0.55f) else dividerCol
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = if (showLabel) 12.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            if (showLabel) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = contentDescription,
                            modifier = Modifier.size(15.dp),
                            tint = if (isSelected) textColor else secondaryText
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        ),
                        color = textColor
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear $label filter",
                            modifier = Modifier.size(13.dp),
                            tint = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = contentDescription,
                        modifier = Modifier.size(18.dp),
                        tint = if (isSelected) textColor else secondaryText
                    )
                }
            }
        }
    }
}

/**
 * Compact Results Header (State C):
 * Left: Query title and result count / content type summary (e.g. "624 items · Photos, Videos").
 * Right: Sort control (default "Most relevant") and View control (Masonry, Grid, Large grid, List).
 */
@Composable
fun SearchResultsHeader(
    queryTitle: String,
    totalCount: Int,
    photosCount: Int,
    videosCount: Int,
    sortMode: SearchSortMode,
    onSortChange: (SearchSortMode) -> Unit,
    viewMode: SearchViewMode,
    onViewModeChange: (SearchViewMode) -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }

    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val barSurface = searchBarSurfaceColor(isDark)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Query Title & Real Result Count Summary
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = queryTitle.ifBlank { "All items" },
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val summaryParts = mutableListOf<String>()
            if (photosCount > 0) summaryParts.add("$photosCount photos")
            if (videosCount > 0) summaryParts.add("$videosCount videos")
            val summaryText = if (summaryParts.isNotEmpty()) {
                "$totalCount items · " + summaryParts.joinToString(", ")
            } else {
                "$totalCount items"
            }

            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Right Controls: Sort & View
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Sort Control
            Box {
                Surface(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showSortMenu = true }
                        .testTag("sort_menu_button"),
                    shape = RoundedCornerShape(8.dp),
                    color = barSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Sort",
                            modifier = Modifier.size(16.dp),
                            tint = textColor
                        )
                        Text(
                            text = sortMode.label,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = textColor
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = secondaryText
                        )
                    }
                }

                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    modifier = Modifier.background(cardSurface)
                ) {
                    SearchSortMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.label,
                                    color = textColor,
                                    fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onSortChange(mode)
                                showSortMenu = false
                            }
                        )
                    }
                }
            }

            // View Control
            Box {
                Surface(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showViewMenu = true }
                        .testTag("view_mode_button"),
                    shape = RoundedCornerShape(8.dp),
                    color = barSurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val viewIcon = when (viewMode) {
                            SearchViewMode.MASONRY -> Icons.Default.Dashboard
                            SearchViewMode.GRID -> Icons.Default.GridView
                            SearchViewMode.LARGE_GRID -> Icons.Default.Window
                            SearchViewMode.LIST -> Icons.Default.ViewAgenda
                        }
                        Icon(
                            imageVector = viewIcon,
                            contentDescription = "View",
                            modifier = Modifier.size(16.dp),
                            tint = textColor
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = secondaryText
                        )
                    }
                }

                DropdownMenu(
                    expanded = showViewMenu,
                    onDismissRequest = { showViewMenu = false },
                    modifier = Modifier.background(cardSurface)
                ) {
                    SearchViewMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = mode.label,
                                    color = textColor,
                                    fontWeight = if (viewMode == mode) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            onClick = {
                                onViewModeChange(mode)
                                showViewMenu = false
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Compact Live Suggestion Row (State B):
 * Height ~56-72 dp.
 * Shows thumbnail / entity icon, primary label, secondary context, and trailing northwest arrow affordance.
 */
@Composable
fun SearchSuggestionRow(
    suggestion: SearchSuggestion,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val barSurface = searchBarSurfaceColor(isDark)
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("suggestion_${suggestion.id}"),
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading Thumbnail or Category Icon
            if (suggestion.thumbnailUri != null) {
                AsyncImage(
                    model = remember(suggestion.thumbnailUri, context) {
                        ThumbnailUtils.buildThumbnailRequest(context, suggestion.thumbnailUri)
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(if (suggestion.isPersonPortrait) CircleShape else RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = if (suggestion.isPersonPortrait) CircleShape else RoundedCornerShape(8.dp),
                    color = barSurface
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val icon = when (suggestion.type) {
                            SuggestionType.RECENT -> Icons.Default.History
                            SuggestionType.PERSON -> Icons.Default.Person
                            SuggestionType.PLACE -> Icons.Default.Place
                            SuggestionType.ALBUM -> Icons.Default.PhotoAlbum
                            SuggestionType.FILE -> Icons.Default.Image
                            SuggestionType.TAG -> Icons.Default.Tag
                            SuggestionType.DOCUMENT -> Icons.Default.Description
                        }
                        Icon(icon, contentDescription = null, tint = secondaryText, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Text column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = suggestion.title,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!suggestion.subtitle.isNullOrBlank()) {
                    Text(
                        text = suggestion.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Trailing northwest arrow affordance
            Icon(
                imageVector = Icons.Default.NorthWest,
                contentDescription = "Fill query",
                tint = secondaryText.copy(alpha = 0.7f),
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(18.dp)
            )
        }
    }
}

/**
 * Advanced Filter Bottom Sheet opened by the search bar funnel icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchAdvancedFilterSheet(
    filterState: SearchFilterState,
    onApplyFilter: (SearchFilterState) -> Unit,
    onDismiss: () -> Unit,
    onRequestCustomDatePicker: () -> Unit,
    isDark: Boolean = false
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draftFilter by remember { mutableStateOf(filterState) }

    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val bg = searchBgColor(isDark)
    val cardSurface = searchCardSurfaceColor(isDark)
    val barSurface = searchBarSurfaceColor(isDark)
    val selectedSurface = searchSelectedSurfaceColor(isDark)
    val dividerCol = searchDividerColor(isDark)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cardSurface,
        scrimColor = Color.Black.copy(alpha = 0.45f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Search Filters",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = textColor
                )
                TextButton(
                    onClick = {
                        draftFilter = SearchFilterState()
                    },
                    modifier = Modifier.testTag("filter_sheet_reset_button")
                ) {
                    Text("Reset all", color = secondaryText)
                }
            }

            HorizontalDivider(color = dividerCol)

            // Media Type Selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Media Type",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MediaTypeFilter.entries.forEach { type ->
                        val selected = draftFilter.mediaType == type
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draftFilter = draftFilter.copy(mediaType = type) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) selectedSurface else barSurface,
                            border = BorderStroke(1.dp, if (selected) textColor.copy(alpha = 0.4f) else Color.Transparent)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = type.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }

            // GPS Location Filter
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "GPS Location",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GpsFilter.entries.forEach { gpsOpt ->
                        val selected = draftFilter.gpsFilter == gpsOpt
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draftFilter = draftFilter.copy(gpsFilter = gpsOpt) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) selectedSurface else barSurface,
                            border = BorderStroke(1.dp, if (selected) textColor.copy(alpha = 0.4f) else Color.Transparent)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = gpsOpt.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }

            // OCR Text Filter
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "OCR Text",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OcrTextFilter.entries.forEach { ocrOpt ->
                        val selected = draftFilter.ocrTextFilter == ocrOpt
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draftFilter = draftFilter.copy(ocrTextFilter = ocrOpt) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) selectedSurface else barSurface,
                            border = BorderStroke(1.dp, if (selected) textColor.copy(alpha = 0.4f) else Color.Transparent)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = ocrOpt.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }

            // Face Filter
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Faces",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FaceFilter.entries.forEach { faceOpt ->
                        val selected = draftFilter.faceFilter == faceOpt
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draftFilter = draftFilter.copy(faceFilter = faceOpt) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) selectedSurface else barSurface,
                            border = BorderStroke(1.dp, if (selected) textColor.copy(alpha = 0.4f) else Color.Transparent)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = faceOpt.label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }

            // Time Range Selection
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Date / Time Range",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = textColor
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        TimeRangeFilter.AllTime to "All time",
                        TimeRangeFilter.Last30Days to "30 days",
                        TimeRangeFilter.Last90Days to "90 days"
                    ).forEach { (range, label) ->
                        val selected = when (range) {
                            is TimeRangeFilter.AllTime -> draftFilter.timeRange is TimeRangeFilter.AllTime
                            is TimeRangeFilter.Last30Days -> draftFilter.timeRange is TimeRangeFilter.Last30Days
                            is TimeRangeFilter.Last90Days -> draftFilter.timeRange is TimeRangeFilter.Last90Days
                            else -> false
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { draftFilter = draftFilter.copy(timeRange = range) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) selectedSurface else barSurface,
                            border = BorderStroke(1.dp, if (selected) textColor.copy(alpha = 0.4f) else Color.Transparent)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                    ),
                                    color = textColor
                                )
                            }
                        }
                    }
                }

                // Custom date option
                val isCustomDate = draftFilter.timeRange is TimeRangeFilter.Custom
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onRequestCustomDatePicker() },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isCustomDate) selectedSurface else barSurface,
                    border = BorderStroke(1.dp, if (isCustomDate) textColor.copy(alpha = 0.4f) else Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.DateRange, null, modifier = Modifier.size(16.dp), tint = secondaryText)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isCustomDate) "Custom date selected" else "Choose custom date range…",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = if (isCustomDate) FontWeight.SemiBold else FontWeight.Normal
                            ),
                            color = textColor
                        )
                    }
                }
            }

            // Favorites Only Switch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Favorites only",
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = textColor
                    )
                    Text(
                        text = "Show only starred/favorite photos",
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText
                    )
                }
                Switch(
                    checked = draftFilter.favoritesOnly,
                    onCheckedChange = { draftFilter = draftFilter.copy(favoritesOnly = it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = textColor,
                        checkedTrackColor = selectedSurface,
                        uncheckedThumbColor = secondaryText,
                        uncheckedTrackColor = barSurface
                    )
                )
            }

            // Apply Button
            Button(
                onClick = {
                    onApplyFilter(draftFilter)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("filter_sheet_apply_button"),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = textColor,
                    contentColor = if (isDark) SearchColors.DarkBg else Color.White
                )
            ) {
                Text(
                    text = "Apply Filters",
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

/**
 * Compact Date Header for grouping photo results by day/month/year.
 */
@Composable
fun SearchDateGroupHeader(
    label: String,
    count: Int,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            color = textColor
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$count items",
            style = MaterialTheme.typography.bodySmall,
            color = secondaryText,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = if (isExpanded) "Collapse group" else "Expand group",
            tint = secondaryText,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Clean neutral empty state when no results match.
 */
@Composable
fun SearchEmptyState(
    query: String,
    hasActiveFilters: Boolean,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
    isDark: Boolean = false
) {
    val textColor = searchPrimaryTextColor(isDark)
    val secondaryText = searchSecondaryTextColor(isDark)
    val barSurface = searchBarSurfaceColor(isDark)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 56.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(64.dp),
            shape = CircleShape,
            color = barSurface
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.SearchOff,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = secondaryText
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text = if (query.isNotBlank()) "No matches for “$query”" else "No matching items",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = textColor
        )

        Spacer(Modifier.height(6.dp))

        Text(
            text = if (hasActiveFilters) {
                "Try adjusting or clearing your filters to see more results."
            } else {
                "Check the spelling or try searching by person name, place, album, or file tag."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = secondaryText,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (hasActiveFilters) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = onResetFilters,
                shape = CircleShape,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = textColor)
            ) {
                Text("Clear active filters")
            }
        }
    }
}
