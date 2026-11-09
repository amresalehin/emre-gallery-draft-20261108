package com.amresalehin.emregallery.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import com.amresalehin.emregallery.data.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockedFolderScreen(
    items: List<MediaItem>,
    availableGalleryMedia: List<MediaItem>,
    isUnlocked: Boolean,
    hasPinSet: Boolean,
    onBack: () -> Unit,
    onOpen: (MediaItem) -> Unit,
    onUnlockItem: (MediaItem) -> Unit,
    onUnlockBatch: (Set<String>) -> Unit,
    onDeleteBatchToBin: (Set<String>) -> Unit,
    onAddItemsToLocked: (Set<String>) -> Unit,
    onSetPin: (String) -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onUnlockSuccess: () -> Unit,
    onLockAgain: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showAddPhotosSheet by remember { mutableStateOf(false) }
    var confirmBatchMoveToBin by remember { mutableStateOf(false) }

    // Intercept back to exit
    BackHandler {
        onBack()
    }

    if (!isUnlocked) {
        LockedFolderAuthView(
            hasPinSet = hasPinSet,
            onBack = onBack,
            onSetPin = onSetPin,
            onVerifyPin = onVerifyPin,
            onUnlockSuccess = onUnlockSuccess
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Header Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .destinationTopBarEntrance(LocalViewerCoordinatedProgress.current)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 8.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("locked_folder_back_button")
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back to Albums")
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Locked folder",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            "${items.size} ${if (items.size == 1) "item" else "items"} · Hidden from main gallery",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    FilledTonalButton(
                        onClick = { showAddPhotosSheet = true },
                        modifier = Modifier.padding(end = 4.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add photos", style = MaterialTheme.typography.labelMedium)
                    }

                    IconButton(
                        onClick = onLockAgain,
                        modifier = Modifier.testTag("locked_folder_lock_now_button")
                    ) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Lock folder now",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))

            // Multi-selection bar if items are selected
            AnimatedVisibility(
                visible = selectedUris.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selectedUris = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection")
                        }
                        Text(
                            "${selectedUris.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        FilledTonalButton(
                            onClick = {
                                val targets = selectedUris
                                selectedUris = emptySet()
                                onUnlockBatch(targets)
                            },
                            modifier = Modifier.padding(end = 6.dp)
                        ) {
                            Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Move out")
                        }
                        IconButton(
                            onClick = {
                                val targets = selectedUris
                                selectedUris = emptySet()
                                onDeleteBatchToBin(targets)
                            }
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Move to Bin", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(88.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(44.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                        Text(
                            "Locked folder is empty",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Photos and videos in your Locked folder stay private and will not appear in your gallery, albums, or search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(24.dp))
                        Button(
                            onClick = { showAddPhotosSheet = true },
                            modifier = Modifier.testTag("locked_folder_empty_add_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add photos to Locked folder")
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    gridItems(items, key = { it.uri }) { item ->
                        val isSelected = item.uri in selectedUris
                        LockedMediaCard(
                            item = item,
                            isSelected = isSelected,
                            selectionMode = selectedUris.isNotEmpty(),
                            onOpen = {
                                if (selectedUris.isNotEmpty()) {
                                    selectedUris = if (isSelected) selectedUris - item.uri else selectedUris + item.uri
                                } else {
                                    onOpen(item)
                                }
                            },
                            onLongPress = {
                                selectedUris = selectedUris + item.uri
                            },
                            onUnlock = { onUnlockItem(item) }
                        )
                    }
                }
            }
        }
    }



    // Modal Sheet for adding photos from gallery to locked folder
    if (showAddPhotosSheet) {
        AddPhotosToLockedSheet(
            availableMedia = availableGalleryMedia,
            onDismiss = { showAddPhotosSheet = false },
            onAddSelected = { selected ->
                showAddPhotosSheet = false
                onAddItemsToLocked(selected)
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LockedMediaCard(
    item: MediaItem,
    isSelected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onUnlock: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = onLongPress
                    )
            ) {
                val context = LocalContext.current
                AsyncImage(
                    model = remember(item.uri, context) {
                        ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                    },
                    contentDescription = item.name,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = if (ThumbnailBoundsTracker.activeViewerUri == item.uri) 0f else 1f
                    },
                    contentScale = ContentScale.Crop
                )
                if (item.isVideo) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = "Video",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp),
                        tint = Color.White
                    )
                }

                // Lock badge
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f)
                ) {
                    Icon(
                        Icons.Default.Lock,
                        contentDescription = "In locked folder",
                        modifier = Modifier
                            .padding(6.dp)
                            .size(14.dp),
                        tint = Color.White
                    )
                }

                if (selectionMode) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = CircleShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)
                    ) {
                        Icon(
                            imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = if (isSelected) "Selected" else "Not selected",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(4.dp)
                                .size(18.dp)
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = onUnlock,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Move out", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun LockedFolderAuthView(
    hasPinSet: Boolean,
    onBack: () -> Unit,
    onSetPin: (String) -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var enteredPin by remember { mutableStateOf("") }
    var isConfirmingSetup by remember { mutableStateOf(false) }
    var firstPinEntry by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isVerifying by remember { mutableStateOf(false) }
    var hasAutoPromptedBiometric by remember { mutableStateOf(false) }

    val biometricAvailable = remember(context) { BiometricAuthHelper.isBiometricAvailable(context) }

    // Trigger biometric authentication safely
    val launchBiometricAuth = {
        val activity = context as? FragmentActivity
        if (activity != null) {
            BiometricAuthHelper.authenticate(
                activity = activity,
                title = "Unlock Locked Folder",
                subtitle = "Authenticate to access private media",
                onSuccess = { onUnlockSuccess() },
                onError = { err -> errorMessage = err }
            )
        }
    }

    // Auto-launch biometric prompt ONLY ONCE when initially opening the Locked Folder with an existing PIN
    LaunchedEffect(Unit) {
        if (hasPinSet && biometricAvailable && !hasAutoPromptedBiometric) {
            hasAutoPromptedBiometric = true
            delay(300)
            launchBiometricAuth()
        }
    }

    val onDigitClick: (String) -> Unit = { digit ->
        if (enteredPin.length < 6 && !isVerifying) {
            val newPin = enteredPin + digit
            enteredPin = newPin

            if (hasPinSet && newPin.length == 6) {
                // PIN verification mode (normal unlock)
                isVerifying = true
                coroutineScope.launch {
                    val verified = onVerifyPin(newPin)
                    isVerifying = false
                    if (verified) {
                        onUnlockSuccess()
                    } else {
                        errorMessage = "Incorrect PIN. Try again."
                        enteredPin = ""
                    }
                }
            } else if (!hasPinSet && isConfirmingSetup) {
                // Real-time mismatch check on every typed digit
                if (!firstPinEntry.startsWith(newPin)) {
                    errorMessage = "Digits do not match"
                } else {
                    errorMessage = null
                }
            } else {
                errorMessage = null
            }
        }
    }

    val onBackspaceClick: () -> Unit = {
        if (enteredPin.isNotEmpty() && !isVerifying) {
            val newPin = enteredPin.dropLast(1)
            enteredPin = newPin
            if (!hasPinSet && isConfirmingSetup) {
                if (!firstPinEntry.startsWith(newPin)) {
                    errorMessage = "Digits do not match"
                } else {
                    errorMessage = null
                }
            } else {
                errorMessage = null
            }
        }
    }

    // Determine special action in the in-built keyboard undo button's slot
    val specialAction: PinSpecialAction? = when {
        !hasPinSet && !isConfirmingSetup && enteredPin.length == 6 -> PinSpecialAction.REENTER
        !hasPinSet && isConfirmingSetup && enteredPin.length == 6 && enteredPin == firstPinEntry -> PinSpecialAction.CONFIRM
        else -> null
    }

    val onSpecialActionClick: () -> Unit = {
        when (specialAction) {
            PinSpecialAction.REENTER -> {
                firstPinEntry = enteredPin
                enteredPin = ""
                isConfirmingSetup = true
                errorMessage = null
            }
            PinSpecialAction.CONFIRM -> {
                onSetPin(enteredPin)
                onUnlockSuccess()
            }
            null -> {}
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top App Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                "Locked folder",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.weight(0.5f))

        // Shield / Lock Icon
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(76.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = when {
                !hasPinSet && !isConfirmingSetup -> "Set up Locked Folder PIN"
                !hasPinSet && isConfirmingSetup -> "Confirm your 6-digit PIN"
                else -> "Enter PIN"
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = when {
                !hasPinSet && !isConfirmingSetup -> "Choose a 6-digit PIN to secure your private items"
                !hasPinSet && isConfirmingSetup -> "Re-enter the same 6-digit PIN to confirm"
                else -> "Enter your 6-digit PIN to access locked photos"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(Modifier.height(24.dp))

        // 6 PIN Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 6) {
                val filled = i < enteredPin.length
                val isMismatch = !hasPinSet && isConfirmingSetup && errorMessage != null && i == enteredPin.length - 1
                val dotColor = when {
                    isMismatch -> MaterialTheme.colorScheme.error
                    filled -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
        }

        if (!hasPinSet && isConfirmingSetup) {
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = {
                    isConfirmingSetup = false
                    enteredPin = ""
                    firstPinEntry = ""
                    errorMessage = null
                }
            ) {
                Text("Start over with a different PIN", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(Modifier.height(12.dp))

        // Error message text if any
        AnimatedVisibility(
            visible = errorMessage != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Text(
                text = errorMessage.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(Modifier.weight(1f))

        // Number Keypad with Re-enter / Confirm appearing in place of built-in undo button
        PinKeypad(
            onDigitClick = onDigitClick,
            onBackspaceClick = onBackspaceClick,
            showBiometric = hasPinSet && biometricAvailable,
            onBiometricClick = { launchBiometricAuth() },
            specialAction = specialAction,
            onSpecialActionClick = onSpecialActionClick,
            enteredPinNotEmpty = enteredPin.isNotEmpty()
        )

        Spacer(Modifier.height(24.dp))
    }
}

private enum class PinSpecialAction {
    REENTER,
    CONFIRM
}

@Composable
private fun PinKeypad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    showBiometric: Boolean,
    onBiometricClick: () -> Unit,
    specialAction: PinSpecialAction? = null,
    onSpecialActionClick: () -> Unit = {},
    enteredPinNotEmpty: Boolean = false
) {
    val keys = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("action", "0", "backspace")
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 44.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        keys.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                row.forEach { key ->
                    when (key) {
                        "action" -> {
                            if (showBiometric) {
                                IconButton(
                                    onClick = onBiometricClick,
                                    modifier = Modifier.size(68.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Fingerprint,
                                        contentDescription = "Unlock with biometrics",
                                        modifier = Modifier.size(34.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            } else {
                                Spacer(Modifier.size(68.dp))
                            }
                        }
                        "backspace" -> {
                            when (specialAction) {
                                PinSpecialAction.REENTER -> {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        onClick = onSpecialActionClick,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .testTag("pin_reenter_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Re-enter",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimary
                                                )
                                                Text(
                                                    text = "Re-enter",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                                PinSpecialAction.CONFIRM -> {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primary,
                                        onClick = onSpecialActionClick,
                                        modifier = Modifier
                                            .size(68.dp)
                                            .testTag("pin_confirm_button")
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Confirm",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = MaterialTheme.colorScheme.onPrimary
                                                )
                                                Text(
                                                    text = "Confirm",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                    fontWeight = FontWeight.Bold,
                                                    color = MaterialTheme.colorScheme.onPrimary
                                                )
                                            }
                                        }
                                    }
                                }
                                null -> {
                                    IconButton(
                                        onClick = onBackspaceClick,
                                        enabled = enteredPinNotEmpty,
                                        modifier = Modifier.size(68.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Backspace,
                                            contentDescription = "Delete digit",
                                            modifier = Modifier.size(26.dp),
                                            tint = if (enteredPinNotEmpty) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                        else -> {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                onClick = { onDigitClick(key) },
                                modifier = Modifier.size(68.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = key,
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPhotosToLockedSheet(
    availableMedia: List<MediaItem>,
    onDismiss: () -> Unit,
    onAddSelected: (Set<String>) -> Unit
) {
    var selectedForLock by remember { mutableStateOf<Set<String>>(emptySet()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Add photos to Locked folder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${selectedForLock.size} selected to hide",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(
                    onClick = { onAddSelected(selectedForLock) },
                    enabled = selectedForLock.isNotEmpty()
                ) {
                    Text("Move to Locked")
                }
            }

            Spacer(Modifier.height(14.dp))

            if (availableMedia.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No other photos found in gallery", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    gridItems(availableMedia, key = { it.uri }) { item ->
                        val isSelected = item.uri in selectedForLock
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable {
                                    selectedForLock = if (isSelected) selectedForLock - item.uri else selectedForLock + item.uri
                                }
                        ) {
                            val context = LocalContext.current
                            AsyncImage(
                                model = remember(item.uri, context) {
                                    ThumbnailUtils.buildThumbnailRequest(context, item.uri)
                                },
                                contentDescription = item.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp),
                                shape = CircleShape,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f)
                            ) {
                                Icon(
                                    imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier
                                        .padding(3.dp)
                                        .size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
