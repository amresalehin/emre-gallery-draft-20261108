package com.amresalehin.emregallery.ui

import coil.compose.AsyncImage
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.amresalehin.emregallery.data.BrowserShortcut
import com.amresalehin.emregallery.data.BrowserSite
import com.amresalehin.emregallery.data.BrowserTabItem
import java.text.SimpleDateFormat
import java.util.*

/**
 * Format timestamp into relative display string: "2 min ago", "1 hr ago", etc.
 */
fun formatRelativeBrowserTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24
    return when {
        diff < 60_000L -> "Just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> if (hours == 1L) "1 hr ago" else "$hours hr ago"
        days < 7 -> if (days == 1L) "1 day ago" else "$days days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}

// =========================================================================
// BRAND LOGOS & FAVICONS (High fidelity, offline-ready Compose vector art)
// =========================================================================

@Composable
fun NorthEastArrowIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF71717A)
) {
    Canvas(modifier = modifier.size(17.dp)) {
        val strokeW = 1.8.dp.toPx()
        val cap = StrokeCap.Round
        val start = Offset(size.width * 0.24f, size.height * 0.76f)
        val end = Offset(size.width * 0.76f, size.height * 0.24f)
        // Diagonal shaft
        drawLine(color = tint, start = start, end = end, strokeWidth = strokeW, cap = cap)
        // Horizontal barb
        drawLine(color = tint, start = Offset(size.width * 0.42f, size.height * 0.24f), end = end, strokeWidth = strokeW, cap = cap)
        // Vertical barb
        drawLine(color = tint, start = Offset(size.width * 0.76f, size.height * 0.58f), end = end, strokeWidth = strokeW, cap = cap)
    }
}

@Composable
fun YouTubeLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color(0xFFFF0000), shape = RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val path = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.15f)
                lineTo(size.width * 0.82f, size.height * 0.5f)
                lineTo(size.width * 0.28f, size.height * 0.85f)
                close()
            }
            drawPath(path, color = Color.White)
        }
    }
}

@Composable
fun WikipediaLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "W",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            color = Color.Black
        )
    }
}

@Composable
fun GitHubLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            // Draw head circle
            drawCircle(
                color = Color.Black,
                radius = size.width * 0.38f,
                center = Offset(size.width * 0.5f, size.height * 0.54f)
            )
            // Left ear
            val leftEar = Path().apply {
                moveTo(size.width * 0.22f, size.height * 0.38f)
                lineTo(size.width * 0.15f, size.height * 0.14f)
                lineTo(size.width * 0.42f, size.height * 0.22f)
                close()
            }
            drawPath(leftEar, color = Color.Black)
            // Right ear
            val rightEar = Path().apply {
                moveTo(size.width * 0.78f, size.height * 0.38f)
                lineTo(size.width * 0.85f, size.height * 0.14f)
                lineTo(size.width * 0.58f, size.height * 0.22f)
                close()
            }
            drawPath(rightEar, color = Color.Black)
        }
    }
}

@Composable
fun GoogleDriveLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(32.dp)
            .background(Color.White, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val yellow = Color(0xFFFFBA00)
            val green = Color(0xFF00AC47)
            val blue = Color(0xFF0066DA)

            val pYellow = Path().apply {
                moveTo(size.width * 0.35f, size.height * 0.18f)
                lineTo(size.width * 0.88f, size.height * 0.18f)
                lineTo(size.width * 0.65f, size.height * 0.55f)
                lineTo(size.width * 0.12f, size.height * 0.55f)
                close()
            }
            drawPath(pYellow, color = yellow)

            val pGreen = Path().apply {
                moveTo(size.width * 0.12f, size.height * 0.55f)
                lineTo(size.width * 0.35f, size.height * 0.18f)
                lineTo(size.width * 0.50f, size.height * 0.45f)
                lineTo(size.width * 0.26f, size.height * 0.85f)
                close()
            }
            drawPath(pGreen, color = green)

            val pBlue = Path().apply {
                moveTo(size.width * 0.26f, size.height * 0.85f)
                lineTo(size.width * 0.78f, size.height * 0.85f)
                lineTo(size.width * 0.92f, size.height * 0.60f)
                lineTo(size.width * 0.40f, size.height * 0.60f)
                close()
            }
            drawPath(pBlue, color = blue)
        }
    }
}

@Composable
fun DuckDuckGoLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color(0xFFDE5833), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            // White duck head
            drawCircle(
                color = Color.White,
                radius = size.width * 0.34f,
                center = Offset(size.width * 0.50f, size.height * 0.46f)
            )
            // Yellow beak
            val beak = Path().apply {
                moveTo(size.width * 0.66f, size.height * 0.40f)
                lineTo(size.width * 0.94f, size.height * 0.48f)
                lineTo(size.width * 0.66f, size.height * 0.56f)
                close()
            }
            drawPath(beak, color = Color(0xFFF7A200))
            // Duck eye
            drawCircle(
                color = Color(0xFF1E293B),
                radius = size.width * 0.055f,
                center = Offset(size.width * 0.56f, size.height * 0.42f)
            )
            // Green bow tie
            val bow = Path().apply {
                moveTo(size.width * 0.32f, size.height * 0.76f)
                lineTo(size.width * 0.48f, size.height * 0.68f)
                lineTo(size.width * 0.64f, size.height * 0.76f)
                lineTo(size.width * 0.48f, size.height * 0.84f)
                close()
            }
            drawPath(bow, color = Color(0xFF3B9E5A))
        }
    }
}

@Composable
fun ClaudeLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color.White, shape = CircleShape)
            .border(1.dp, Color(0xFFF1F1F1), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val orange = Color(0xFFCC5500)
            val center = Offset(size.width * 0.5f, size.height * 0.5f)
            val strokeW = 2.4.dp.toPx()
            val rOuter = size.width * 0.44f
            val rInner = size.width * 0.16f

            // 8 radiating sunburst lines
            val angles = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)
            for (deg in angles) {
                val rad = Math.toRadians(deg)
                val cos = Math.cos(rad).toFloat()
                val sin = Math.sin(rad).toFloat()
                val pStart = Offset(center.x + rInner * cos, center.y + rInner * sin)
                val pEnd = Offset(center.x + rOuter * cos, center.y + rOuter * sin)
                drawLine(
                    color = orange,
                    start = pStart,
                    end = pEnd,
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}

@Composable
fun AnthropicAiLogo(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(38.dp)
            .background(Color(0xFFEADBCE), shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "AI",
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.SansSerif,
            color = Color(0xFF1C1917)
        )
    }
}

@Composable
fun BrandFavicon(
    domain: String,
    title: String,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val d = domain.lowercase()
    val t = title.lowercase()
    when {
        d.contains("duckduckgo") || t.contains("duckduckgo") -> DuckDuckGoLogo(modifier)
        d.contains("claude") || t.contains("claude") -> ClaudeLogo(modifier)
        d.contains("wikipedia") || t.contains("wikipedia") -> Box(
            modifier = modifier
                .size(38.dp)
                .background(if (isDark) Color(0xFF262628) else Color.White, shape = CircleShape)
                .border(1.dp, if (isDark) Color(0xFF38383C) else Color(0xFFEAEAEA), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            WikipediaLogo(Modifier.size(24.dp))
        }
        d.contains("youtube") || t.contains("youtube") -> Box(
            modifier = modifier
                .size(38.dp)
                .background(if (isDark) Color(0xFF262628) else Color(0xFFF9FAFB), shape = CircleShape)
                .border(1.dp, if (isDark) Color(0xFF38383C) else Color(0xFFEAEAEA), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            YouTubeLogo(Modifier.size(26.dp))
        }
        d.contains("anthropic") || t.contains("anthropic") -> AnthropicAiLogo(modifier)
        d.contains("github") || t.contains("github") -> Box(
            modifier = modifier
                .size(38.dp)
                .background(if (isDark) Color(0xFF262628) else Color.White, shape = CircleShape)
                .border(1.dp, if (isDark) Color(0xFF38383C) else Color(0xFFEAEAEA), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            GitHubLogo(Modifier.size(24.dp))
        }
        d.contains("drive.google") || t.contains("drive") -> Box(
            modifier = modifier
                .size(38.dp)
                .background(if (isDark) Color(0xFF262628) else Color.White, shape = CircleShape)
                .border(1.dp, if (isDark) Color(0xFF38383C) else Color(0xFFEAEAEA), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            GoogleDriveLogo(Modifier.size(24.dp))
        }
        else -> {
            val letter = (title.ifBlank { domain }).trim().firstOrNull()?.uppercaseChar()?.toString() ?: "W"
            Box(
                modifier = modifier
                    .size(38.dp)
                    .background(if (isDark) Color(0xFF262628) else Color(0xFFF4F4F5), shape = CircleShape)
                    .border(1.dp, if (isDark) Color(0xFF38383C) else Color(0xFFE4E4E7), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = letter,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else Color.Black
                )
            }
        }
    }
}

// =========================================================================
// MONOCHROME THEME PALETTE (Day & Night View Optimization)
// =========================================================================

object BrowserThemeColors {
    fun background(isDark: Boolean): Color = if (isDark) Color(0xFF111111) else Color(0xFFFFFFFF)
    fun barSurface(isDark: Boolean): Color = if (isDark) Color(0xFF1E1E20) else Color(0xFFFFFFFF)
    fun barOutline(isDark: Boolean): Color = if (isDark) Color(0xFF38383A) else Color(0xFFE5E7EB)
    fun cardSurface(isDark: Boolean): Color = if (isDark) Color(0xFF1E1E20) else Color(0xFFFFFFFF)
    fun cardOutline(isDark: Boolean): Color = if (isDark) Color(0xFF2C2C2E) else Color(0xFFEFEFEF)
    fun itemSurface(isDark: Boolean): Color = if (isDark) Color(0xFF262628) else Color(0xFFF9FAFB)
    fun itemOutline(isDark: Boolean): Color = if (isDark) Color(0xFF38383C) else Color(0xFFEEEEEE)
    fun textPrimary(isDark: Boolean): Color = if (isDark) Color(0xFFF4F4F5) else Color(0xFF000000)
    fun textSecondary(isDark: Boolean): Color = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    fun iconTint(isDark: Boolean): Color = if (isDark) Color(0xFFF4F4F5) else Color(0xFF000000)
    fun secondaryIconTint(isDark: Boolean): Color = if (isDark) Color(0xFFA1A1AA) else Color(0xFF71717A)
    fun badgeBg(isDark: Boolean): Color = if (isDark) Color(0xFFF4F4F5) else Color(0xFF000000)
    fun badgeText(isDark: Boolean): Color = if (isDark) Color(0xFF111111) else Color(0xFFFFFFFF)
    fun divider(isDark: Boolean): Color = if (isDark) Color(0xFF27272A) else Color(0xFFF3F4F6)
    fun inputHint(isDark: Boolean): Color = if (isDark) Color(0xFF8E8E93) else Color(0xFF6B7280)
}

// =========================================================================
// 1. TOP BAR (Minimalist Monochrome Floating Pill with Discover Badge & Controls)
// =========================================================================

@Composable
fun BrowserTopBar(
    addressInput: String,
    onAddressChange: (String) -> Unit,
    onNavigate: (String) -> Unit,
    onHamburgerClick: () -> Unit,
    onDiscoveredMediaClick: () -> Unit,
    discoveredCount: Int = 0,
    onNewTabClick: () -> Unit,
    onReloadClick: () -> Unit,
    onPinCurrentSite: () -> Unit,
    onSaveToPocket: (() -> Unit)? = null,
    onShareClick: () -> Unit,
    onDesktopToggle: (Boolean) -> Unit,
    isDesktopMode: Boolean = false,
    onClearHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isWebPage: Boolean = false,
    onHomeClick: (() -> Unit)? = null,
    onCloseBrowser: (() -> Unit)? = null,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    var isInputFocused by remember { mutableStateOf(false) }

    val barBorderColor by animateColorAsState(
        targetValue = if (isInputFocused) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
        } else {
            BrowserThemeColors.barOutline(isDark)
        },
        animationSpec = tween(200),
        label = "top_bar_border_color"
    )
    val barBorderWidth by animateDpAsState(
        targetValue = if (isInputFocused) 1.8.dp else 1.2.dp,
        animationSpec = tween(200),
        label = "top_bar_border_width"
    )

    Surface(
        shape = CircleShape,
        color = BrowserThemeColors.barSurface(isDark),
        tonalElevation = if (isInputFocused) 8.dp else 6.dp,
        shadowElevation = if (isInputFocused) 10.dp else 8.dp,
        border = BorderStroke(barBorderWidth, barBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .height(54.dp)
            .testTag("browser_monochrome_top_bar")
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Hamburger Menu or Back to Home
            IconButton(
                onClick = {
                    if (isWebPage && onHomeClick != null) {
                        onHomeClick()
                    } else {
                        onHamburgerClick()
                    }
                },
                modifier = Modifier
                    .size(42.dp)
                    .testTag("top_bar_hamburger_btn")
            ) {
                Icon(
                    imageVector = if (isWebPage) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Menu,
                    contentDescription = if (isWebPage) "Back to home" else "Menu",
                    tint = BrowserThemeColors.iconTint(isDark),
                    modifier = Modifier.size(22.dp)
                )
            }

            // Dynamic Search Icon (Shown only when focused)
            AnimatedVisibility(
                visible = isInputFocused,
                enter = fadeIn(animationSpec = tween(180)) + expandHorizontally(animationSpec = tween(180)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkHorizontally(animationSpec = tween(150))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(start = 2.dp, end = 6.dp)
                            .size(20.dp)
                            .testTag("top_bar_search_icon")
                    )
                }
            }

            // Site Security Indicator when not focused on web page
            if (!isInputFocused && isWebPage) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Secure site",
                    tint = BrowserThemeColors.secondaryIconTint(isDark),
                    modifier = Modifier
                        .padding(start = 2.dp, end = 6.dp)
                        .size(16.dp)
                )
            } else if (!isInputFocused) {
                Spacer(Modifier.width(4.dp))
            }

            // Address / Search Input
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (addressInput.isEmpty()) {
                    Text(
                        text = "Search or type URL",
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Normal
                        ),
                        color = BrowserThemeColors.inputHint(isDark),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = addressInput,
                    onValueChange = onAddressChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            if (addressInput.isNotBlank()) {
                                onNavigate(addressInput)
                            }
                        }
                    ),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = BrowserThemeColors.textPrimary(isDark),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Normal
                    ),
                    cursorBrush = SolidColor(if (isInputFocused) MaterialTheme.colorScheme.primary else BrowserThemeColors.textPrimary(isDark)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            isInputFocused = focusState.isFocused
                        }
                        .testTag("top_bar_address_input")
                )
            }

            // Clear 'x' button when typing
            if (addressInput.isNotEmpty()) {
                IconButton(
                    onClick = { onAddressChange("") },
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("top_bar_clear_input_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear",
                        tint = BrowserThemeColors.secondaryIconTint(isDark),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Dynamic Submit Query Button (revealed when search bar is focused with query)
            AnimatedVisibility(
                visible = isInputFocused && addressInput.isNotBlank(),
                enter = fadeIn(animationSpec = tween(150)) + expandHorizontally(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkHorizontally(animationSpec = tween(120))
            ) {
                IconButton(
                    onClick = {
                        if (addressInput.isNotBlank()) {
                            onNavigate(addressInput)
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(36.dp)
                        .testTag("top_bar_submit_query_btn")
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Submit query",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Discovered Media Download Icon (dynamically shows number of available scrapped media options)
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDiscoveredMediaClick)
                    .testTag("top_bar_download_media_btn"),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.DownloadForOffline,
                    contentDescription = "View discovered media",
                    tint = if (discoveredCount > 0) MaterialTheme.colorScheme.primary else BrowserThemeColors.iconTint(isDark),
                    modifier = Modifier.size(24.dp)
                )
                if (discoveredCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary,
                        shadowElevation = 2.dp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-1).dp, y = 1.dp)
                            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                            .testTag("top_bar_media_count_badge")
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (discoveredCount > 99) "99+" else "$discoveredCount",
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 12.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // Three-dot Menu (Immediate Controls)
            Box(contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier
                        .size(42.dp)
                        .testTag("top_bar_more_menu_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More controls",
                        tint = BrowserThemeColors.iconTint(isDark),
                        modifier = Modifier.size(22.dp)
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier
                        .background(BrowserThemeColors.barSurface(isDark))
                        .widthIn(min = 210.dp)
                        .border(1.dp, BrowserThemeColors.barOutline(isDark), RoundedCornerShape(12.dp))
                ) {
                    DropdownMenuItem(
                        text = { Text("Home", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Home, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onHomeClick?.invoke() ?: onNewTabClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("New tab", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onNewTabClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Reload", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onReloadClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to Shortcuts", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onPinCurrentSite()
                        }
                    )
                    if (onSaveToPocket != null) {
                        DropdownMenuItem(
                            text = { Text("Save to Pocket", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(Icons.Default.Bookmark, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                            onClick = {
                                showMenu = false
                                onSaveToPocket()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share link", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onShareClick()
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Desktop site", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium)
                                if (isDesktopMode) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark), modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        leadingIcon = { Icon(Icons.Default.Computer, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onDesktopToggle(!isDesktopMode)
                        }
                    )
                    HorizontalDivider(color = BrowserThemeColors.divider(isDark))
                    DropdownMenuItem(
                        text = { Text("Browser settings", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onSettingsClick()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Clear browsing history", color = BrowserThemeColors.textPrimary(isDark), fontWeight = FontWeight.Medium) },
                        leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                        onClick = {
                            showMenu = false
                            onClearHistoryClick()
                        }
                    )
                    if (onCloseBrowser != null) {
                        HorizontalDivider(color = BrowserThemeColors.divider(isDark))
                        DropdownMenuItem(
                            text = { Text("Close browser", color = Color(0xFFEF4444), fontWeight = FontWeight.Medium) },
                            leadingIcon = { Icon(Icons.Default.Close, contentDescription = null, tint = Color(0xFFEF4444)) },
                            onClick = {
                                showMenu = false
                                onCloseBrowser()
                            }
                        )
                    }
                }
            }
        }
    }
}

// =========================================================================
// 2. BOTTOM NAVIGATION BAR (Downloads, History, Tabs with count, Browser)
// =========================================================================

@Composable
fun BrowserBottomNavBar(
    tabCount: Int = 0,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTabs: () -> Unit,
    onOpenBrowserSettings: () -> Unit,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = BrowserThemeColors.barSurface(isDark),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
        border = BorderStroke(1.2.dp, BrowserThemeColors.barOutline(isDark)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .navigationBarsPadding()
            .testTag("browser_monochrome_bottom_nav")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Downloads
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenDownloads)
                    .testTag("bottom_nav_downloads")
            ) {
                Icon(
                    imageVector = Icons.Outlined.FileDownload,
                    contentDescription = "Downloads",
                    tint = BrowserThemeColors.iconTint(isDark),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Downloads",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
            }

            // 2. History
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenHistory)
                    .testTag("bottom_nav_history")
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = "History",
                    tint = BrowserThemeColors.iconTint(isDark),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "History",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
            }

            // 3. Tabs (with count badge ONLY if tabCount > 0)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenTabs)
                    .testTag("bottom_nav_tabs")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Tab,
                        contentDescription = "Tabs",
                        tint = BrowserThemeColors.iconTint(isDark),
                        modifier = Modifier.size(24.dp)
                    )
                    if (tabCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-4).dp)
                                .size(15.dp)
                                .background(BrowserThemeColors.badgeBg(isDark), shape = CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$tabCount",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrowserThemeColors.badgeText(isDark)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Tabs",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
            }

            // 4. Browser Settings
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenBrowserSettings)
                    .testTag("bottom_nav_browser_settings")
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = "Browser settings",
                    tint = BrowserThemeColors.iconTint(isDark),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "Browser",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
            }
        }
    }
}

// =========================================================================
// 3. BROWSER HOME CONTENT (Shortcuts with flexible pinning & Recent history)
// If absent, keep completely blank.
// =========================================================================

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowserHomeContent(
    shortcuts: List<BrowserShortcut>,
    recentSites: List<BrowserSite>,
    onNavigate: (String) -> Unit,
    onAddShortcutClick: () -> Unit,
    onEditShortcutClick: (BrowserShortcut) -> Unit,
    onSeeAllHistoryClick: () -> Unit,
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    // If shortcuts and history are both absent, keep completely blank as instructed!
    if (shortcuts.isEmpty() && recentSites.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(BrowserThemeColors.background(isDark))
                .testTag("browser_empty_home_page")
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BrowserThemeColors.background(isDark))
            .verticalScroll(rememberScrollState())
            .padding(top = 18.dp, bottom = 24.dp)
    ) {
        // 1. SHORTCUTS ROW (only rendered when real shortcuts exist)
        if (shortcuts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.Start),
                verticalAlignment = Alignment.Top
            ) {
                // Render real shortcuts
                shortcuts.take(4).forEach { item ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .width(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .combinedClickable(
                                onClick = { onNavigate(item.url) },
                                onLongClick = { onEditShortcutClick(item) }
                            )
                            .padding(vertical = 4.dp)
                            .testTag("shortcut_${item.id}")
                    ) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = BrowserThemeColors.itemSurface(isDark),
                            border = BorderStroke(1.dp, BrowserThemeColors.itemOutline(isDark)),
                            modifier = Modifier.size(56.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                when (item.iconKey) {
                                    "youtube" -> YouTubeLogo()
                                    "wikipedia" -> WikipediaLogo()
                                    "github" -> GitHubLogo()
                                    "drive" -> GoogleDriveLogo()
                                    else -> BrandFavicon(item.url, item.title, isDark = isDark)
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = item.title,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Normal,
                            color = BrowserThemeColors.textPrimary(isDark),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Add button for user flexible pinning
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(64.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = onAddShortcutClick)
                        .padding(vertical = 4.dp)
                        .testTag("shortcut_add_button")
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = BrowserThemeColors.itemSurface(isDark),
                        border = BorderStroke(1.dp, BrowserThemeColors.itemOutline(isDark)),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add shortcut",
                                tint = BrowserThemeColors.iconTint(isDark),
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Add",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        color = BrowserThemeColors.textPrimary(isDark),
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
        }

        // 2. RECENT SECTION HEADER (only rendered when real history exists)
        if (recentSites.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = null,
                        tint = BrowserThemeColors.iconTint(isDark),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Recent",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = BrowserThemeColors.textPrimary(isDark)
                    )
                }

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onSeeAllHistoryClick)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "See all",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = BrowserThemeColors.textSecondary(isDark)
                    )
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "See all history",
                        tint = BrowserThemeColors.textSecondary(isDark),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 3. RECENT ITEMS CARD
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = BrowserThemeColors.cardSurface(isDark),
                border = BorderStroke(1.dp, BrowserThemeColors.cardOutline(isDark)),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .testTag("recent_history_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    val displayList = recentSites.take(5)
                    displayList.forEachIndexed { index, site ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = BrowserThemeColors.divider(isDark),
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(site.url) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                                .testTag("recent_item_$index"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BrandFavicon(
                                domain = site.domain,
                                title = site.title,
                                isDark = isDark
                            )

                            Spacer(Modifier.width(14.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = site.title,
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BrowserThemeColors.textPrimary(isDark),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = site.domain,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Normal,
                                    color = BrowserThemeColors.textSecondary(isDark),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(Modifier.width(8.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.AccessTime,
                                    contentDescription = null,
                                    tint = BrowserThemeColors.secondaryIconTint(isDark),
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = formatRelativeBrowserTime(site.lastVisitedMillis),
                                    fontSize = 12.sp,
                                    color = BrowserThemeColors.secondaryIconTint(isDark)
                                )
                                Spacer(Modifier.width(4.dp))
                                NorthEastArrowIcon(tint = BrowserThemeColors.secondaryIconTint(isDark))
                            }
                        }
                    }
                }
            }
        }
    }
}

// =========================================================================
// 4. DIALOGS & BOTTOM SHEETS
// =========================================================================

/**
 * Dialog for adding a new flexible pinned shortcut.
 */
@Composable
fun AddShortcutDialog(
    onDismiss: () -> Unit,
    onAdd: (title: String, url: String) -> Unit,
    isDark: Boolean = false
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BrowserThemeColors.cardSurface(isDark),
            border = BorderStroke(1.dp, BrowserThemeColors.cardOutline(isDark)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Pin New Shortcut",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name (e.g. Reddit)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        focusedBorderColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedBorderColor = BrowserThemeColors.cardOutline(isDark),
                        focusedLabelColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedLabelColor = BrowserThemeColors.textSecondary(isDark)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL (e.g. reddit.com)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        focusedBorderColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedBorderColor = BrowserThemeColors.cardOutline(isDark),
                        focusedLabelColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedLabelColor = BrowserThemeColors.textSecondary(isDark)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = BrowserThemeColors.textSecondary(isDark))
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotBlank() && url.isNotBlank()) {
                                onAdd(title, url)
                                onDismiss()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = BrowserThemeColors.textPrimary(isDark)),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("Pin", color = BrowserThemeColors.background(isDark))
                    }
                }
            }
        }
    }
}

/**
 * Dialog for editing or removing an existing shortcut.
 */
@Composable
fun EditShortcutDialog(
    shortcut: BrowserShortcut,
    onDismiss: () -> Unit,
    onUpdate: (title: String, url: String) -> Unit,
    onDelete: () -> Unit,
    isDark: Boolean = false
) {
    var title by remember { mutableStateOf(shortcut.title) }
    var url by remember { mutableStateOf(shortcut.url) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BrowserThemeColors.cardSurface(isDark),
            border = BorderStroke(1.dp, BrowserThemeColors.cardOutline(isDark)),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Edit Shortcut",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        focusedBorderColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedBorderColor = BrowserThemeColors.cardOutline(isDark),
                        focusedLabelColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedLabelColor = BrowserThemeColors.textSecondary(isDark)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedTextColor = BrowserThemeColors.textPrimary(isDark),
                        focusedBorderColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedBorderColor = BrowserThemeColors.cardOutline(isDark),
                        focusedLabelColor = BrowserThemeColors.textPrimary(isDark),
                        unfocusedLabelColor = BrowserThemeColors.textSecondary(isDark)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) {
                        Text("Unpin", color = Color(0xFFEF4444))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel", color = BrowserThemeColors.textSecondary(isDark))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (title.isNotBlank() && url.isNotBlank()) {
                                    onUpdate(title, url)
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = BrowserThemeColors.textPrimary(isDark)),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Save", color = BrowserThemeColors.background(isDark))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom sheet displaying full browsing history with instant search and clear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserHistoryBottomSheet(
    history: List<BrowserSite>,
    onNavigate: (String) -> Unit,
    onDeleteSite: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = false
) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(history, search) {
        if (search.isBlank()) history else {
            history.filter {
                it.title.contains(search, ignoreCase = true) || it.domain.contains(search, ignoreCase = true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrowserThemeColors.barSurface(isDark),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Browsing History",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = Color(0xFFEF4444))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Search bar
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Search history...", color = BrowserThemeColors.inputHint(isDark)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = BrowserThemeColors.iconTint(isDark)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = BrowserThemeColors.textPrimary(isDark),
                    unfocusedTextColor = BrowserThemeColors.textPrimary(isDark),
                    focusedBorderColor = BrowserThemeColors.textPrimary(isDark),
                    unfocusedBorderColor = BrowserThemeColors.cardOutline(isDark)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))

            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (search.isBlank()) "No browsing history" else "No matching results",
                        color = BrowserThemeColors.textSecondary(isDark),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.url }) { item ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BrowserThemeColors.itemSurface(isDark),
                            border = BorderStroke(1.dp, BrowserThemeColors.itemOutline(isDark)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onNavigate(item.url)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BrandFavicon(item.domain, item.title, isDark = isDark)
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = BrowserThemeColors.textPrimary(isDark),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.domain} • ${formatRelativeBrowserTime(item.lastVisitedMillis)}",
                                        fontSize = 12.sp,
                                        color = BrowserThemeColors.textSecondary(isDark),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteSite(item.url) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Delete entry",
                                        tint = BrowserThemeColors.secondaryIconTint(isDark),
                                        modifier = Modifier.size(16.dp)
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

/**
 * Bottom sheet displaying open tabs grid, allowing switching, closing, and opening new tabs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserTabsBottomSheet(
    tabs: List<BrowserTabItem>,
    currentUrl: String,
    activeTabId: String? = null,
    onSelectTab: (BrowserTabItem) -> Unit,
    onCloseTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = false
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrowserThemeColors.barSurface(isDark),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .testTag("browser_tabs_bottom_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Tabs (${tabs.size})",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = BrowserThemeColors.textPrimary(isDark)
                )
                Button(
                    onClick = {
                        onNewTab()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = BrowserThemeColors.textPrimary(isDark)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.testTag("browser_new_tab_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = BrowserThemeColors.background(isDark), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Tab", color = BrowserThemeColors.background(isDark))
                }
            }

            Spacer(Modifier.height(16.dp))

            if (tabs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No open tabs",
                        color = BrowserThemeColors.textSecondary(isDark),
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        val isSelected = if (!activeTabId.isNullOrBlank()) {
                            tab.id == activeTabId
                        } else {
                            tab.url.isNotBlank() && tab.url.equals(currentUrl, ignoreCase = true)
                        }

                        val domain = remember(tab.url) {
                            if (tab.url.isNotBlank()) {
                                try {
                                    java.net.URL(tab.url).host.removePrefix("www.")
                                } catch (_: Exception) {
                                    tab.url.substringAfter("://").substringBefore("/").removePrefix("www.")
                                }
                            } else ""
                        }
                        val faviconUrl = remember(domain) {
                            if (domain.isNotBlank()) "https://www.google.com/s2/favicons?domain=$domain&sz=64" else ""
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = BrowserThemeColors.itemSurface(isDark),
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) BrowserThemeColors.textPrimary(isDark) else BrowserThemeColors.itemOutline(isDark)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(128.dp)
                                .clickable {
                                    onSelectTab(tab)
                                    onDismiss()
                                }
                                .testTag("browser_tab_card_${tab.id}")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (faviconUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = faviconUrl,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp).clip(CircleShape)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = if (tab.url.isBlank()) Icons.Default.Home else Icons.Default.Public,
                                                contentDescription = null,
                                                tint = BrowserThemeColors.iconTint(isDark),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        Text(
                                            text = tab.title.ifBlank { if (tab.url.isBlank()) "Home" else "New Tab" },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BrowserThemeColors.textPrimary(isDark),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    IconButton(
                                        onClick = { onCloseTab(tab.id) },
                                        modifier = Modifier.size(24.dp).testTag("browser_tab_close_${tab.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Close tab",
                                            tint = BrowserThemeColors.iconTint(isDark),
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .background(BrowserThemeColors.cardSurface(isDark), RoundedCornerShape(10.dp))
                                        .border(1.dp, BrowserThemeColors.cardOutline(isDark), RoundedCornerShape(10.dp))
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab.url.ifBlank { "Home Page" },
                                        fontSize = 11.sp,
                                        color = BrowserThemeColors.textSecondary(isDark),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                if (isSelected) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = BrowserThemeColors.textPrimary(isDark),
                                        modifier = Modifier.align(Alignment.Start)
                                    ) {
                                        Text(
                                            text = "ACTIVE",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BrowserThemeColors.background(isDark),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
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
 * Bottom sheet for browser settings: ad-blocker, desktop mode, search engine, clearing cache.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserSettingsBottomSheet(
    isDesktopMode: Boolean,
    onDesktopModeChange: (Boolean) -> Unit,
    onClearCacheClick: () -> Unit,
    onDismiss: () -> Unit,
    isDark: Boolean = false
) {
    var adBlockEnabled by remember { mutableStateOf(true) }
    var jsEnabled by remember { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrowserThemeColors.barSurface(isDark),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Browser Settings",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = BrowserThemeColors.textPrimary(isDark)
            )

            Spacer(Modifier.height(16.dp))

            // Ad Block Engine Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Ad & Tracker Blocker", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BrowserThemeColors.textPrimary(isDark))
                    Text("Blocks intrusive scripts and ads", fontSize = 12.sp, color = BrowserThemeColors.textSecondary(isDark))
                }
                Switch(
                    checked = adBlockEnabled,
                    onCheckedChange = { adBlockEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrowserThemeColors.background(isDark),
                        checkedTrackColor = BrowserThemeColors.textPrimary(isDark)
                    )
                )
            }

            HorizontalDivider(color = BrowserThemeColors.divider(isDark))

            // Desktop Mode Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Request Desktop Site", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BrowserThemeColors.textPrimary(isDark))
                    Text("Forces desktop layout for sites", fontSize = 12.sp, color = BrowserThemeColors.textSecondary(isDark))
                }
                Switch(
                    checked = isDesktopMode,
                    onCheckedChange = onDesktopModeChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrowserThemeColors.background(isDark),
                        checkedTrackColor = BrowserThemeColors.textPrimary(isDark)
                    )
                )
            }

            HorizontalDivider(color = BrowserThemeColors.divider(isDark))

            // JavaScript Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("JavaScript Execution", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BrowserThemeColors.textPrimary(isDark))
                    Text("Required for interactive web apps", fontSize = 12.sp, color = BrowserThemeColors.textSecondary(isDark))
                }
                Switch(
                    checked = jsEnabled,
                    onCheckedChange = { jsEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BrowserThemeColors.background(isDark),
                        checkedTrackColor = BrowserThemeColors.textPrimary(isDark)
                    )
                )
            }

            HorizontalDivider(color = BrowserThemeColors.divider(isDark))

            // Clear Cache & Cookies
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onClearCacheClick()
                        onDismiss()
                    }
                    .padding(vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Clear Cache & Cookies", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color(0xFFEF4444))
                    Text("Frees storage and resets web sessions", fontSize = 12.sp, color = BrowserThemeColors.textSecondary(isDark))
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = BrowserThemeColors.textSecondary(isDark))
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}
