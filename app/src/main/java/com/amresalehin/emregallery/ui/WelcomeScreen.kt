package com.amresalehin.emregallery.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.FolderCopy
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amresalehin.emregallery.R
import com.amresalehin.emregallery.data.PreferencesRepository
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.EnteFaceConstants
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import com.amresalehin.emregallery.ocr.MlKitLanguage
import com.amresalehin.emregallery.ocr.TesseractOcr
import kotlinx.coroutines.launch

@Composable
fun WelcomeScreen(
    initialStep: Int = 0,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    val preferencesRepository = remember { PreferencesRepository(context.applicationContext) }

    var currentStep by remember { mutableIntStateOf(initialStep) }
    var mediaGranted by remember { mutableStateOf(hasMediaPermission(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var allFilesGranted by remember { mutableStateOf(hasAllFilesAccess()) }

    BackHandler(enabled = currentStep > 0) {
        currentStep--
    }

    // Re-check permissions when coming back to the app (e.g. from system settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaGranted = hasMediaPermission(context)
                notificationGranted = hasNotificationPermission(context)
                allFilesGranted = hasAllFilesAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        mediaGranted = hasMediaPermission(context)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        notificationGranted = isGranted
    }

    val allFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        allFilesGranted = hasAllFilesAccess()
    }

    fun requestMedia() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            Build.VERSION.SDK_INT >= 29 -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
            else -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
        mediaPermissionLauncher.launch(permissions)
    }

    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationGranted = true
        }
    }

    fun requestAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = try {
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
            allFilesLauncher.launch(intent)
        } else {
            allFilesGranted = true
        }
    }

    // Atmospheric mist/fog gradient matching Emre style (#F3F5F7 to #161A20)
    val mistGradient = Brush.verticalGradient(
        0.00f to Color(0xFFF3F5F7),
        0.28f to Color(0xFFE2E7EC),
        0.58f to Color(0xFF8F9BA8),
        0.82f to Color(0xFF38414D),
        1.00f to Color(0xFF161A20)
    )

    val grantedCount = (if (mediaGranted) 1 else 0) +
        (if (notificationGranted) 1 else 0) +
        (if (allFilesGranted) 1 else 0)
    val allPermissionsGranted = grantedCount == 3

    val buttonText = when {
        allPermissionsGranted -> "Enter Emre"
        grantedCount in 1..2 -> "Growing Wings"
        else -> "Liberate Emre"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(mistGradient)
            .testTag("welcome_screen")
    ) {
        AnimatedContent(
            targetState = currentStep,
            transitionSpec = {
                val forward = targetState > initialState
                if (forward) {
                    (fadeIn(animationSpec = tween(280)) + slideInHorizontally(animationSpec = tween(280)) { it / 3 })
                        .togetherWith(fadeOut(animationSpec = tween(220)) + slideOutHorizontally(animationSpec = tween(220)) { -it / 3 })
                } else {
                    (fadeIn(animationSpec = tween(280)) + slideInHorizontally(animationSpec = tween(280)) { -it / 3 })
                        .togetherWith(fadeOut(animationSpec = tween(220)) + slideOutHorizontally(animationSpec = tween(220)) { it / 3 })
                }
            },
            label = "welcome_flow"
        ) { step ->
            when (step) {
                0 -> {
                    // Screen 1: Minimalist Emre Splash for First Setup Run
                    // Centered large logo, no corner arrow, and subtle "Tap to continue" at the bottom
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { currentStep = 1 }
                            .testTag("welcome_intro_next_button")
                    ) {
                        // Centered large logo
                        Icon(
                            painter = painterResource(R.drawable.ic_app_logo),
                            contentDescription = "Emre Gallery",
                            modifier = Modifier
                                .size(168.dp)
                                .align(Alignment.Center)
                                .testTag("welcome_intro_logo"),
                            tint = Color(0xFF1D2228)
                        )

                        // Bottom "Tap to continue" in small font size
                        Text(
                            text = stringResource(R.string.welcome_tap_to_continue),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Normal,
                                letterSpacing = 0.3.sp,
                                color = Color.White.copy(alpha = 0.82f)
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 42.dp)
                                .testTag("welcome_intro_tap_to_continue")
                        )
                    }
                }
                1 -> {
                    // Screen 2: "Let's Personalize" Welcome Customization Screen
                    PersonalizeStep(
                        onSkip = {
                            currentStep = 2
                        },
                        onSave = { ocrEngine, mlKitLangs, ocrLangs, faceEngineOption ->
                            coroutineScope.launch {
                                // Save OCR configuration
                                if (ocrEngine == "mlkit") {
                                    preferencesRepository.setOcrEngine("mlkit")
                                    preferencesRepository.setMlKitLanguages(mlKitLangs)
                                } else {
                                    preferencesRepository.setOcrEngine("tesseract")
                                    preferencesRepository.setOcrLanguages(ocrLangs)
                                }

                                // Save Face Recognition configuration
                                when (faceEngineOption) {
                                    "disable" -> {
                                        FaceRecognitionSettings.preferences(context).edit()
                                            .putBoolean("face_recognition_enabled", false)
                                            .putString(FaceRecognitionSettings.KEY_ACTIVE_ENGINE, "disabled")
                                            .apply()
                                    }
                                    "yolo" -> {
                                        FaceRecognitionSettings.preferences(context).edit()
                                            .putBoolean("face_recognition_enabled", true)
                                            .apply()
                                        FaceRecognitionSettings.setActiveEngine(context, EnteFaceConstants.ENGINE_ID)
                                    }
                                    "buffalo" -> {
                                        FaceRecognitionSettings.preferences(context).edit()
                                            .putBoolean("face_recognition_enabled", true)
                                            .apply()
                                        FaceRecognitionSettings.setActiveEngine(context, BuffaloLFaceEngine.ENGINE_ID)
                                    }
                                }
                            }
                            currentStep = 2
                        }
                    )
                }
                2 -> {
                    // Screen 3: "Let's set things up" Permissions screen
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.height(36.dp))

                        // Soft circular halo with Emre logo
                        Surface(
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.50f),
                            modifier = Modifier.size(84.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_app_logo),
                                    contentDescription = null,
                                    modifier = Modifier.size(38.dp),
                                    tint = Color(0xFF161A20)
                                )
                            }
                        }

                        Spacer(Modifier.height(28.dp))

                        // Editorial Serif Headline
                        Text(
                            text = "Let’s set things up",
                            fontFamily = FontFamily.Serif,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Normal,
                            color = Color(0xFF14171B)
                        )

                        Spacer(Modifier.height(32.dp))

                        // Card 1: Photos and videos
                        PermissionCard(
                            icon = Icons.Outlined.Photo,
                            title = "Photos and videos",
                            isGranted = mediaGranted,
                            onClick = { requestMedia() },
                            testTag = "welcome_permission_media"
                        )

                        Spacer(Modifier.height(14.dp))

                        // Card 2: Notifications
                        PermissionCard(
                            icon = Icons.Outlined.Notifications,
                            title = "Notifications",
                            isGranted = notificationGranted,
                            onClick = { requestNotifications() },
                            testTag = "welcome_permission_notifications"
                        )

                        Spacer(Modifier.height(14.dp))

                        // Card 3: All files access
                        PermissionCard(
                            icon = Icons.Outlined.FolderCopy,
                            title = "All files access",
                            isGranted = allFilesGranted,
                            onClick = { requestAllFiles() },
                            testTag = "welcome_permission_all_files"
                        )

                        Spacer(Modifier.weight(1f))

                        // Dynamic Action Button:
                        // "Liberate Emre" (0 granted) -> "Growing Wings" (1 or 2 granted) -> "Enter Emre" (all 3 granted)
                        // Arrow appears ONLY on "Enter Emre"
                        Button(
                            onClick = {
                                if (allPermissionsGranted) {
                                    onComplete()
                                } else if (!mediaGranted) {
                                    requestMedia()
                                } else if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestNotifications()
                                } else if (!allFilesGranted) {
                                    requestAllFiles()
                                }
                            },
                            shape = RoundedCornerShape(32.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (allPermissionsGranted) Color.White else Color(0x33485666),
                                contentColor = if (allPermissionsGranted) Color(0xFF14171B) else Color.White
                            ),
                            border = BorderStroke(
                                1.dp,
                                if (allPermissionsGranted) Color.White else Color.White.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .padding(bottom = 4.dp)
                                .testTag("welcome_continue_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = buttonText,
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (allPermissionsGranted) Color(0xFF14171B) else Color.White
                                    )
                                )
                                if (allPermissionsGranted) {
                                    Spacer(Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color(0xFF14171B)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(28.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalizeStep(
    onSkip: () -> Unit,
    onSave: (ocrEngine: String, mlKitLangs: Set<String>, ocrLangs: Set<String>, faceEngineOption: String) -> Unit
) {
    var ocrExpanded by remember { mutableStateOf(false) }
    var faceExpanded by remember { mutableStateOf(false) }

    var selectedOcrEngine by remember { mutableStateOf("mlkit") } // "mlkit" or "tesseract"
    var selectedMlKitLanguages by remember { mutableStateOf(setOf("latin")) }
    var selectedOcrLanguages by remember { mutableStateOf(setOf("eng")) }
    var selectedFaceEngineOption by remember { mutableStateOf("disable") } // "disable", "yolo", "buffalo"

    val isAnyExpanded = ocrExpanded || faceExpanded
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("welcome_personalize_step")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .statusBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))

            // Soft circular halo with Emre logo
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.50f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(R.drawable.ic_app_logo),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = Color(0xFF161A20)
                    )
                }
            }

            Spacer(Modifier.height(22.dp))

            // Editorial Serif Headline
            Text(
                text = "Let’s Personalize",
                fontFamily = FontFamily.Serif,
                fontSize = 32.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF14171B)
            )

            Spacer(Modifier.height(26.dp))

            // Card 1: OCR (Expandable)
            PersonalizeOcrCard(
                isExpanded = ocrExpanded,
                onToggleExpand = {
                    val willExpand = !ocrExpanded
                    ocrExpanded = willExpand
                    if (willExpand) faceExpanded = false // Smart Accordion: keep one section open at a time
                },
                selectedEngine = selectedOcrEngine,
                onSelectEngine = { selectedOcrEngine = it },
                selectedMlKitLangs = selectedMlKitLanguages,
                onToggleMlKitLang = { langCode ->
                    selectedMlKitLanguages = if (langCode in selectedMlKitLanguages) {
                        if (selectedMlKitLanguages.size > 1) selectedMlKitLanguages - langCode else selectedMlKitLanguages
                    } else {
                        selectedMlKitLanguages + langCode
                    }
                },
                selectedOcrLangs = selectedOcrLanguages,
                onToggleOcrLang = { langCode ->
                    selectedOcrLanguages = if (langCode in selectedOcrLanguages) {
                        if (selectedOcrLanguages.size > 1) selectedOcrLanguages - langCode else selectedOcrLanguages
                    } else {
                        selectedOcrLanguages + langCode
                    }
                }
            )

            Spacer(Modifier.height(14.dp))

            // Card 2: Face Recognition (Expandable)
            PersonalizeFaceRecognitionCard(
                isExpanded = faceExpanded,
                onToggleExpand = {
                    val willExpand = !faceExpanded
                    faceExpanded = willExpand
                    if (willExpand) ocrExpanded = false // Smart Accordion: keep one section open at a time
                },
                selectedEngineOption = selectedFaceEngineOption,
                onSelectEngineOption = { selectedFaceEngineOption = it }
            )

            Spacer(Modifier.height(14.dp))

            // Card 3: AI Configuration (Disabled and greyed out per instructions)
            PersonalizeAiConfigCard()

            // Generous bottom spacer so that scrolled content never gets obscured behind buttons
            Spacer(Modifier.height(116.dp))
        }

        // Pinned Bottom Navigation Buttons with soft gradient scrim
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.32f to Color(0x99161A20),
                        1.0f to Color(0xF2161A20)
                    )
                )
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 24.dp)
        ) {
            if (isAnyExpanded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Skip Button
                    Button(
                        onClick = onSkip,
                        shape = RoundedCornerShape(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0x1F14171B),
                            contentColor = Color.White
                        ),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("welcome_personalize_skip_button")
                    ) {
                        Text(
                            text = "Skip",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                        )
                    }

                    // Save Button
                    Button(
                        onClick = {
                            onSave(
                                selectedOcrEngine,
                                selectedMlKitLanguages,
                                selectedOcrLanguages,
                                selectedFaceEngineOption
                            )
                        },
                        shape = RoundedCornerShape(32.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF14171B)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .testTag("welcome_personalize_save_button")
                    ) {
                        Text(
                            text = "Save",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF14171B)
                            )
                        )
                    }
                }
            } else {
                // Single Skip Button
                Button(
                    onClick = onSkip,
                    shape = RoundedCornerShape(32.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x1F14171B),
                        contentColor = Color.White
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("welcome_personalize_skip_button")
                ) {
                    Text(
                        text = "Skip",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonalizeOcrCard(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    selectedEngine: String,
    onSelectEngine: (String) -> Unit,
    selectedMlKitLangs: Set<String>,
    onToggleMlKitLang: (String) -> Unit,
    selectedOcrLangs: Set<String>,
    onToggleOcrLang: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color(0x75F5F7FA),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("welcome_personalize_ocr_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Always visible and clickable to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.60f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        OcrIcon(tint = Color(0xFF181C22))
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "OCR",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF14171A)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(22.dp),
                    tint = Color(0xFF38404A)
                )
            }

            // Expanded Content
            if (isExpanded) {
                HorizontalDivider(
                    color = Color(0x1F000000),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Engine Label
                    Text(
                        text = "Engine",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF14171B)
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Engine Segmented Buttons: ML Kit | Tesseract
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0x0F000000),
                        border = BorderStroke(1.dp, Color(0x1F000000)),
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
                            // ML Kit Option
                            SegmentButton(
                                text = "ML Kit",
                                isSelected = selectedEngine == "mlkit",
                                onClick = { onSelectEngine("mlkit") },
                                modifier = Modifier.weight(1f)
                            )

                            // Tesseract Option
                            SegmentButton(
                                text = "Tesseract",
                                isSelected = selectedEngine == "tesseract",
                                onClick = { onSelectEngine("tesseract") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Language(s) Label
                    Text(
                        text = "Language(s)",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF14171B)
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Language Chips Row
                    if (selectedEngine == "mlkit") {
                        val mlKitOptions = remember {
                            MlKitLanguage.entries.map { it.code to it.title }
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .testTag("welcome_mlkit_languages_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            mlKitOptions.forEach { (code, label) ->
                                val isSelected = code in selectedMlKitLangs
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) Color(0xFF161A20) else Color.White.copy(alpha = 0.35f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF161A20) else Color(0x33485666)
                                    ),
                                    modifier = Modifier
                                        .height(38.dp)
                                        .defaultMinSize(minWidth = 72.dp)
                                        .clickable { onToggleMlKitLang(code) }
                                        .testTag("welcome_mlkit_lang_$code")
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
                                                color = if (isSelected) Color.White else Color(0xFF14171B)
                                            )
                                        )
                                    }
                                }
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
                                .testTag("welcome_tesseract_languages_row"),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            tesseractOptions.forEach { (code, label) ->
                                val isSelected = code in selectedOcrLangs
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isSelected) Color(0xFF161A20) else Color.White.copy(alpha = 0.35f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSelected) Color(0xFF161A20) else Color(0x33485666)
                                    ),
                                    modifier = Modifier
                                        .height(38.dp)
                                        .defaultMinSize(minWidth = 72.dp)
                                        .clickable { onToggleOcrLang(code) }
                                        .testTag("welcome_ocr_lang_$code")
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
                                                color = if (isSelected) Color.White else Color(0xFF14171B)
                                            )
                                        )
                                    }
                                }
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
private fun PersonalizeFaceRecognitionCard(
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    selectedEngineOption: String,
    onSelectEngineOption: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color(0x75F5F7FA),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("welcome_personalize_face_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Clickable to expand/collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.60f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        FaceRecognitionIcon(tint = Color(0xFF181C22))
                    }
                }

                Spacer(Modifier.width(16.dp))

                Text(
                    text = "Face Recognition",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color(0xFF14171A)
                    ),
                    modifier = Modifier.weight(1f)
                )

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.ChevronRight,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.size(22.dp),
                    tint = Color(0xFF38404A)
                )
            }

            // Expanded Content
            if (isExpanded) {
                HorizontalDivider(
                    color = Color(0x1F000000),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Engine Label
                    Text(
                        text = "Engine",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF14171B)
                        )
                    )

                    Spacer(Modifier.height(8.dp))

                    // Engine Segmented Buttons: Disable | Yolo V5 | Buffalo L
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = Color(0x0F000000),
                        border = BorderStroke(1.dp, Color(0x1F000000)),
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
                            SegmentButton(
                                text = "Disable",
                                isSelected = selectedEngineOption == "disable",
                                onClick = { onSelectEngineOption("disable") },
                                modifier = Modifier.weight(1f)
                            )

                            SegmentButton(
                                text = "Yolo V5",
                                isSelected = selectedEngineOption == "yolo",
                                onClick = { onSelectEngineOption("yolo") },
                                modifier = Modifier.weight(1f)
                            )

                            SegmentButton(
                                text = "Buffalo L",
                                isSelected = selectedEngineOption == "buffalo",
                                onClick = { onSelectEngineOption("buffalo") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PersonalizeAiConfigCard() {
    // "The AI config should be disabled and greyed out."
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color(0x60F5F7FA),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.35f)),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(0.45f)
            .testTag("welcome_personalize_ai_config_card")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AiIcon(tint = Color(0xFF181C22))
                }
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = "AI Configuration",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF14171A)
                ),
                modifier = Modifier.weight(1f)
            )

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = Color(0xFF38404A)
            )
        }
    }
}

@Composable
private fun SegmentButton(
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
                .clickable(onClick = onClick)
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF14171B)
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

@Composable
private fun FaceRecognitionIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF181C22)) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.5.dp.toPx()
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
        Icon(
            imageVector = Icons.Default.Face,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
    }
}

@Composable
private fun AiIcon(modifier: Modifier = Modifier, tint: Color = Color(0xFF181C22)) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 1.6.dp.toPx()
            val cornerLength = 4.5.dp.toPx()
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
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = tint
        )
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Color(0x75F5F7FA),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.65f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.60f),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = Color(0xFF181C22)
                    )
                }
            }

            Spacer(Modifier.width(16.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF14171A)
                ),
                modifier = Modifier.weight(1f)
            )

            if (isGranted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Permission granted",
                    modifier = Modifier.size(22.dp),
                    tint = Color(0xFF14171A)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "Grant permission",
                    modifier = Modifier.size(22.dp),
                    tint = Color(0xFF38404A)
                )
            }
        }
    }
}
