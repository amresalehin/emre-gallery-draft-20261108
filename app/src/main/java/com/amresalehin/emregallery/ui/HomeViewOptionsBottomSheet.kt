package com.amresalehin.emregallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeViewOptionsBottomSheet(
    sortOrder: String,
    groupBy: String,
    gridColumns: Int,
    gridStyle: String,
    onSortOrderChange: (String) -> Unit,
    onGroupByChange: (String) -> Unit,
    onGridColumnsChange: (Int) -> Unit,
    onGridStyleChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tempGridStyle by remember(gridStyle) { mutableStateOf(gridStyle) }
    var tempGridColumns by remember(gridColumns) { mutableIntStateOf(gridColumns.coerceIn(1, 6)) }

    val initialField = when {
        sortOrder.startsWith("date_added") -> "date_added"
        sortOrder.startsWith("name") -> "name"
        sortOrder.startsWith("size") -> "size"
        else -> "date_taken"
    }
    val initialDirection = if (sortOrder.endsWith("_asc")) "asc" else "desc"

    var tempSortField by remember(sortOrder) { mutableStateOf(initialField) }
    var tempSortDirection by remember(sortOrder) { mutableStateOf(initialDirection) }
    var tempGroupBy by remember(groupBy) { mutableStateOf(groupBy.ifBlank { "none" }) }

    val applySelections = {
        onGridStyleChange(tempGridStyle)
        onGridColumnsChange(tempGridColumns)
        val mappedSortOrder = when (tempSortField) {
            "date_taken" -> if (tempSortDirection == "asc") "date_taken_asc" else "date_taken_desc"
            "date_added" -> if (tempSortDirection == "asc") "date_added_asc" else "date_added_desc"
            "name" -> if (tempSortDirection == "asc") "name_asc" else "name_desc"
            "size" -> if (tempSortDirection == "asc") "size_asc" else "size_desc"
            else -> "date_taken_desc"
        }
        onSortOrderChange(mappedSortOrder)
        onGroupByChange(tempGroupBy)
        onDismiss()
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        sheetState = sheetState,
        onDismissRequest = applySelections,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // 1. View section
            Text(
                text = "View",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Grid Button
                val isGridSelected = tempGridStyle == "square"
                Surface(
                    onClick = { tempGridStyle = "square" },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isGridSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (isGridSelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .testTag("view_option_grid")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.GridView,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Grid",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        if (isGridSelected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Masonry Button
                val isMasonrySelected = tempGridStyle == "masonry"
                Surface(
                    onClick = { tempGridStyle = "masonry" },
                    shape = RoundedCornerShape(16.dp),
                    color = if (isMasonrySelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (isMasonrySelected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)) else null,
                    modifier = Modifier
                        .weight(1f)
                        .height(58.dp)
                        .testTag("view_option_masonry")
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Dashboard,
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Masonry",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        if (isMasonrySelected) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // 2. Columns section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Columns",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(16.dp))
                Icon(
                    Icons.Default.GridView,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Slider(
                    value = tempGridColumns.toFloat(),
                    onValueChange = { tempGridColumns = it.roundToInt() },
                    valueRange = 1f..6f,
                    steps = 4,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .testTag("view_columns_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        activeTickColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Icon(
                    Icons.Default.Apps,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "$tempGridColumns",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            // 3. Sort by section
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val sortFields = listOf(
                Triple("date_taken", "Date taken", Icons.Default.CalendarMonth),
                Triple("date_added", "Date added", Icons.Default.EventAvailable),
                Triple("name", "File name", Icons.Default.SortByAlpha),
                Triple("size", "File size", Icons.Default.Description)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                sortFields.forEach { (field, label, icon) ->
                    val isSelected = tempSortField == field
                    Surface(
                        onClick = {
                            if (!isSelected) {
                                tempSortField = field
                            } else {
                                tempSortDirection = if (tempSortDirection == "asc") "desc" else "asc"
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(82.dp)
                            .testTag("sort_card_$field")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 7.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp),
                                    tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.10f) else Color.Transparent)
                                        .padding(horizontal = 2.dp, vertical = 2.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                tempSortField = field
                                                tempSortDirection = "asc"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "Ascending",
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected && tempSortDirection == "asc") MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clickable {
                                                tempSortField = field
                                                tempSortDirection = "desc"
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.ArrowDownward,
                                            contentDescription = "Descending",
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected && tempSortDirection == "desc") MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 4. Group by section
            Text(
                text = "Group by",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            val groupOptions = listOf(
                Triple("none", "None", Icons.Default.Block),
                Triple("day", "Day", Icons.Default.CalendarToday),
                Triple("month", "Month", Icons.Default.CalendarMonth),
                Triple("year", "Year", Icons.Default.DateRange),
                Triple("folder", "Device folder", Icons.Default.Folder),
                Triple("initial", "Initial", Icons.Default.TextFields)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupOptions.forEach { (key, label, icon) ->
                    val isSelected = tempGroupBy == key
                    Surface(
                        onClick = { tempGroupBy = key },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = if (isSelected) {
                            BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                        } else {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        },
                        modifier = Modifier
                            .width(68.dp)
                            .height(72.dp)
                            .testTag("group_card_$key")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // 5. Reset to default
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Reset to default
                Surface(
                    onClick = {
                        tempGridStyle = "square"
                        tempGridColumns = 3
                        tempSortField = "date_taken"
                        tempSortDirection = "desc"
                        tempGroupBy = "none"
                    },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("reset_view_options_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Reset to default",
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }


            }
        }
    }
}

