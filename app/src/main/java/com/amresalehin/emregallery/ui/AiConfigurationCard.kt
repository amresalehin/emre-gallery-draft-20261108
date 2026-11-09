package com.amresalehin.emregallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amresalehin.emregallery.data.CloudAiProvider
import com.amresalehin.emregallery.data.CloudProviderStatus
import com.amresalehin.emregallery.data.FlorenceModelManager
import com.amresalehin.emregallery.data.FlorenceModelState
import com.amresalehin.emregallery.data.FlorenceStatus
import com.amresalehin.emregallery.data.PreferencesRepository
import kotlinx.coroutines.launch

private enum class AiModeTab {
    CLOUD,
    LOCAL
}

/**
 * Sleek, minimal AI Configuration card designed to match the Welcome Screen cards aesthetic.
 * Features rounded 26.dp corners, frosted/clean surface styling, circular avatar halo with Emre's
 * signature corner-bracket icon, segmented button controls, and a minimal layout free of redundant
 * explanatory subtitles. Cloud setup is collapsed by default.
 */
@Composable
fun AiConfigurationCard(
    modifier: Modifier = Modifier,
    prefs: PreferencesRepository,
    cardBackground: Color? = null,
    cardBorder: BorderStroke? = null,
    onShowSnackbar: ((String) -> Unit)? = null,
    initiallyExpanded: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cloudProviders by prefs.cloudProviders.collectAsState(initial = emptyList())
    val activeProvider by prefs.activeCloudProvider.collectAsState(initial = null)
    val florenceState by prefs.florenceModelState.collectAsState(initial = FlorenceModelState())
    val allModelStates by FlorenceModelManager.allModelStates.collectAsState()

    var isExpanded by remember { mutableStateOf(initiallyExpanded) }
    LaunchedEffect(initiallyExpanded) {
        if (initiallyExpanded) {
            isExpanded = true
        }
    }
    var selectedTab by remember { mutableStateOf(AiModeTab.CLOUD) }

    var providerName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }

    var isTestingCloud by remember { mutableStateOf(false) }
    var cloudTestFeedback by remember { mutableStateOf<String?>(null) }
    var selectedModelForDetails by remember { mutableStateOf<FlorenceModelManager.LocalModelSpec?>(null) }

    // Synchronize initial Local models state on mount
    LaunchedEffect(Unit) {
        FlorenceModelManager.refreshAllStates(context)
    }

    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val effectiveCardBackground = cardBackground ?: if (isDark) {
        MaterialTheme.colorScheme.surfaceContainer
    } else {
        Color(0x75F5F7FA)
    }

    val effectiveCardBorder = cardBorder ?: BorderStroke(
        1.dp,
        if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
        else Color.White.copy(alpha = 0.65f)
    )

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = effectiveCardBackground,
        border = effectiveCardBorder,
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize()
            .testTag("ai_configuration_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row (Clickable to expand / collapse)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(horizontal = 18.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular Halo with Emre AI Signature Icon
                Surface(
                    shape = CircleShape,
                    color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    else Color.White.copy(alpha = 0.60f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        AiSignatureIcon(tint = MaterialTheme.colorScheme.onSurface)
                    }
                }

                Spacer(Modifier.width(16.dp))

                // Clean title without explanatory text below it
                Text(
                    text = "AI Configuration",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier = Modifier.weight(1f)
                )

                // Header status badge / test pill when collapsed or expanded
                DynamicCloudStatusIndicator(
                    activeProvider = activeProvider,
                    isTesting = isTestingCloud,
                    isDark = isDark,
                    onClick = {
                        if (activeProvider != null) {
                            scope.launch {
                                isTestingCloud = true
                                cloudTestFeedback = "Testing connection..."
                                val status = prefs.testActiveCloudProvider()
                                isTestingCloud = false
                                cloudTestFeedback = if (status == CloudProviderStatus.CONNECTED) {
                                    "Connected successfully"
                                } else {
                                    activeProvider?.statusMessage ?: "Connection test failed"
                                }
                                onShowSnackbar?.invoke(cloudTestFeedback ?: "")
                            }
                        } else {
                            isExpanded = true
                            selectedTab = AiModeTab.CLOUD
                            onShowSnackbar?.invoke("Please configure a Cloud provider below")
                        }
                    }
                )

                Spacer(Modifier.width(6.dp))

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier
                        .size(22.dp)
                        .testTag("ai_expand_toggle"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expanded Content
            if (isExpanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    // Segmented Button: Cloud | Local
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
                            AiSegmentOption(
                                text = "Cloud",
                                isSelected = selectedTab == AiModeTab.CLOUD,
                                isDark = isDark,
                                onClick = { selectedTab = AiModeTab.CLOUD },
                                modifier = Modifier.weight(1f)
                            )

                            VerticalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .fillMaxHeight(0.6f)
                                    .width(1.dp)
                            )

                            AiSegmentOption(
                                text = "Local",
                                isSelected = selectedTab == AiModeTab.LOCAL,
                                isDark = isDark,
                                onClick = { selectedTab = AiModeTab.LOCAL },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // ==========================================
                    // TAB 1: CLOUD
                    // ==========================================
                    if (selectedTab == AiModeTab.CLOUD) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. Provider name input
                            MinimalAiInputField(
                                value = providerName,
                                onValueChange = { providerName = it },
                                placeholder = "Provider name (e.g. OpenAI)",
                                icon = Icons.Outlined.Label,
                                testTag = "ai_provider_name_input"
                            )

                            // 2. Base URL input
                            MinimalAiInputField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                placeholder = "Base URL (e.g. https://api.openai.com/v1)",
                                icon = Icons.Outlined.Link,
                                testTag = "ai_base_url_input"
                            )

                            // 3. API key input
                            MinimalAiInputField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                placeholder = "API key",
                                icon = Icons.Outlined.Key,
                                isPassword = true,
                                showPassword = showApiKey,
                                onToggleShowPassword = { showApiKey = !showApiKey },
                                testTag = "ai_api_key_input"
                            )

                            // 4. Model ID input
                            MinimalAiInputField(
                                value = modelId,
                                onValueChange = { modelId = it },
                                placeholder = "Model ID (e.g. gpt-4o)",
                                icon = Icons.Outlined.Memory,
                                testTag = "ai_model_id_input"
                            )

                            Spacer(Modifier.height(2.dp))

                            // "Add Provider" Button matching Welcome Screen button style
                            Button(
                                onClick = {
                                    val name = providerName.trim()
                                    val url = baseUrl.trim()
                                    val model = modelId.trim()
                                    if (name.isBlank() || url.isBlank() || model.isBlank()) {
                                        onShowSnackbar?.invoke("Please fill in Provider name, Base URL, and Model ID")
                                        return@Button
                                    }

                                    val newProvider = CloudAiProvider(
                                        name = name,
                                        baseUrl = url,
                                        apiKey = apiKey.trim(),
                                        modelId = model,
                                        isActive = true,
                                        status = CloudProviderStatus.UNCONFIGURED
                                    )

                                    scope.launch {
                                        prefs.saveCloudProvider(newProvider)
                                        onShowSnackbar?.invoke("Provider '$name' added")
                                        providerName = ""
                                        baseUrl = ""
                                        apiKey = ""
                                        modelId = ""
                                        isTestingCloud = true
                                        prefs.testCloudProvider(newProvider.id)
                                        isTestingCloud = false
                                    }
                                },
                                    shape = RoundedCornerShape(32.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20),
                                        contentColor = if (isDark) MaterialTheme.colorScheme.onPrimary else Color.White
                                    ),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("ai_add_provider_button")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Add,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Add Provider",
                                            style = MaterialTheme.typography.titleSmall.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.sp
                                            )
                                        )
                                    }
                                }
                            }

                        // Configured Providers List
                        if (cloudProviders.isNotEmpty()) {
                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Configured Providers",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )

                            Spacer(Modifier.height(8.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                cloudProviders.forEach { provider ->
                                    Surface(
                                        shape = RoundedCornerShape(18.dp),
                                        color = if (provider.isActive) {
                                            if (isDark) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                            else Color.White.copy(alpha = 0.85f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (provider.isActive) {
                                                if (isDark) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                                else Color(0xFF161A20).copy(alpha = 0.6f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                            }
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                scope.launch { prefs.setActiveCloudProvider(provider.id) }
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                RadioButton(
                                                    selected = provider.isActive,
                                                    onClick = {
                                                        scope.launch { prefs.setActiveCloudProvider(provider.id) }
                                                    },
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column {
                                                    Text(
                                                        text = provider.name,
                                                        style = MaterialTheme.typography.bodyMedium.copy(
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    )
                                                    Text(
                                                        text = "${provider.modelId} • ${provider.baseUrl.removePrefix("https://").removePrefix("http://").take(24)}",
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 11.5.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    )
                                                }
                                            }

                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            prefs.testCloudProvider(provider.id)
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.Refresh,
                                                        contentDescription = "Test connection",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }

                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            prefs.deleteCloudProvider(provider.id)
                                                            onShowSnackbar?.invoke("Provider '${provider.name}' removed")
                                                        }
                                                    },
                                                    modifier = Modifier.size(32.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.DeleteOutline,
                                                        contentDescription = "Remove provider",
                                                        tint = MaterialTheme.colorScheme.error,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ==========================================
                    // TAB 2: LOCAL AI MODELS
                    // ==========================================
                    if (selectedTab == AiModeTab.LOCAL) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            FlorenceModelManager.SUPPORTED_MODELS.forEach { model ->
                                val modelState = allModelStates[model.id] ?: FlorenceModelState()

                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.70f),
                                    border = BorderStroke(
                                        1.dp,
                                        if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.85f)
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        // Header row without any icon, clean and minimal
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontSize = 15.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                ),
                                                modifier = Modifier.weight(1f)
                                            )

                                            DynamicFlorenceStatusButton(
                                                state = modelState,
                                                testTagPrefix = model.testTagPrefix,
                                                isDark = isDark,
                                                onInstall = {
                                                    scope.launch {
                                                        FlorenceModelManager.downloadAndInstallModel(context, model.id)
                                                    }
                                                },
                                                onCancel = {
                                                    FlorenceModelManager.cancelDownload(context, model.id)
                                                },
                                                onDetails = {
                                                    selectedModelForDetails = model
                                                }
                                            )
                                        }

                                        // Progress bar when queued, downloading, verifying, or validating runtime
                                        val isBusy = modelState.status == FlorenceStatus.DOWNLOADING ||
                                                modelState.status == FlorenceStatus.VERIFYING ||
                                                modelState.status == FlorenceStatus.LOADING ||
                                                modelState.status == FlorenceStatus.QUEUED

                                        if (isBusy) {
                                            Spacer(Modifier.height(14.dp))
                                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                LinearProgressIndicator(
                                                    progress = { modelState.progress.coerceIn(0f, 1f) },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .clip(CircleShape),
                                                    color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20),
                                                    trackColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color(0x1F000000)
                                                )
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween
                                                ) {
                                                    val statusLabel = when (modelState.status) {
                                                        FlorenceStatus.QUEUED -> "Queued..."
                                                        FlorenceStatus.VERIFYING -> "Verifying integrity..."
                                                        FlorenceStatus.LOADING -> "Validating ONNX runtime..."
                                                        else -> {
                                                            val downloaded = formatBytes(modelState.bytesDownloaded)
                                                            val total = formatBytes(modelState.totalBytes)
                                                            if (modelState.speedBytesPerSec > 0) {
                                                                "$downloaded / $total • ${formatBytes(modelState.speedBytesPerSec)}/s"
                                                            } else if (modelState.totalBytes > 0) {
                                                                "$downloaded / $total"
                                                            } else {
                                                                "Connecting..."
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = statusLabel,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontSize = 10.5.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    val percentOrEta = if (modelState.etaSeconds > 0 && modelState.status == FlorenceStatus.DOWNLOADING) {
                                                        "${(modelState.progress * 100).toInt()}% • ETA ${modelState.etaSeconds}s"
                                                    } else {
                                                        "${(modelState.progress * 100).toInt()}%"
                                                    }
                                                    Text(
                                                        text = percentOrEta,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 10.5.sp,
                                                        color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
                                                    )
                                                }
                                            }
                                        }

                                        // Error text preview if in error state
                                        if (modelState.status == FlorenceStatus.ERROR && !modelState.errorMessage.isNullOrBlank()) {
                                            Spacer(Modifier.height(8.dp))
                                            Text(
                                                text = modelState.errorMessage,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    color = MaterialTheme.colorScheme.error,
                                                    fontSize = 11.sp
                                                ),
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.clickable { selectedModelForDetails = model }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }

    // Local Model Details / Options Dialog
    selectedModelForDetails?.let { model ->
        val state = allModelStates[model.id] ?: FlorenceModelState()
        AlertDialog(
            onDismissRequest = { selectedModelForDetails = null },
            title = {
                Text(
                    "${model.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Status Badge
                    val statusText = when (state.status) {
                        FlorenceStatus.READY, FlorenceStatus.INSTALLED ->
                            if (model.hasRuntimeAdapter) "Ready — Validated & usable" else "Assets downloaded — runtime unavailable"
                        FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE ->
                            "Assets downloaded — runtime unavailable"
                        FlorenceStatus.DOWNLOADING, FlorenceStatus.VERIFYING, FlorenceStatus.LOADING ->
                            "In progress: ${state.currentStage ?: "Downloading"} (${(state.progress * 100).toInt()}%)"
                        FlorenceStatus.ERROR ->
                            "Error: ${state.errorMessage ?: "Installation failed"}"
                        FlorenceStatus.QUEUED ->
                            "Queued for download"
                        FlorenceStatus.PAUSED ->
                            "Paused"
                        FlorenceStatus.UNINSTALLING ->
                            "Uninstalling..."
                        FlorenceStatus.NOT_INSTALLED ->
                            "Not installed"
                    }

                    Text(
                        text = "Status: $statusText",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when (state.status) {
                            FlorenceStatus.READY, FlorenceStatus.INSTALLED -> if (model.hasRuntimeAdapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE -> MaterialTheme.colorScheme.tertiary
                            FlorenceStatus.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )

                    if (state.status == FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE ||
                        ((state.status == FlorenceStatus.READY || state.status == FlorenceStatus.INSTALLED) && !model.hasRuntimeAdapter)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Notice: All declared model artifacts and external weights were successfully downloaded and verified, but an on-device inference adapter has not yet been implemented for this architecture family.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (state.status == FlorenceStatus.ERROR && !state.errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.errorMessage,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Text(
                        text = "Description: ${model.description}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Task: ${model.taskType}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (model.upstreamRepo.isNotBlank()) {
                        Text(
                            text = "Upstream: ${model.upstreamRepo}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (model.upstreamRevision.isNotBlank()) {
                        Text(
                            text = "Revision: ${model.upstreamRevision.take(12)}...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    val requiredBytes = model.artifacts.sumOf { it.exactBytes ?: it.estimatedSize }
                    Text(
                        text = "Total package size: ${formatBytes(requiredBytes)} (${model.artifacts.size} files)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.installedSizeBytes > 0) {
                        Text(
                            text = "Storage on disk: ${formatBytes(state.installedSizeBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    state.installedPath?.let { path ->
                        Text(
                            text = "Directory: $path",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { selectedModelForDetails = null }) {
                    Text("Done")
                }
            },
            dismissButton = {
                val isInstalledOrAssets = state.status == FlorenceStatus.READY ||
                        state.status == FlorenceStatus.INSTALLED ||
                        state.status == FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE ||
                        state.installedSizeBytes > 0

                if (isInstalledOrAssets) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                FlorenceModelManager.uninstallModel(context, model.id)
                                selectedModelForDetails = null
                                onShowSnackbar?.invoke("${model.displayName} uninstalled")
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Uninstall")
                    }
                } else if (state.status == FlorenceStatus.ERROR) {
                    TextButton(
                        onClick = {
                            selectedModelForDetails = null
                            scope.launch {
                                FlorenceModelManager.downloadAndInstallModel(context, model.id)
                            }
                        }
                    ) {
                        Text("Retry")
                    }
                }
            }
        )
    }
}

/**
 * Custom AI Signature Icon with Emre's iconic 4 viewfinder corners and central sparkle.
 */
@Composable
private fun AiSignatureIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color(0xFF181C22)
) {
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

/**
 * Sleek Segment Option button matching Welcome Screen's SegmentButton.
 */
@Composable
private fun AiSegmentOption(
    text: String,
    isSelected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isSelected) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20),
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
                        color = if (isDark) MaterialTheme.colorScheme.onPrimary else Color.White
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

/**
 * Minimalist input field with rounded capsule styling matching Welcome Screen elements.
 */
@Composable
private fun MinimalAiInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    showPassword: Boolean = false,
    onToggleShowPassword: (() -> Unit)? = null,
    testTag: String = ""
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (isPassword && value.isNotEmpty()) {
                IconButton(
                    onClick = { onToggleShowPassword?.invoke() },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide API key" else "Show API key",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Dynamic Status-Indicator Button for Cloud State with sleek capsule styling.
 */
@Composable
private fun DynamicCloudStatusIndicator(
    activeProvider: CloudAiProvider?,
    isTesting: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    val status = when {
        isTesting -> CloudProviderStatus.CHECKING
        activeProvider == null -> CloudProviderStatus.UNCONFIGURED
        else -> activeProvider.status
    }

    val (bg, border, contentColor) = when (status) {
        CloudProviderStatus.CONNECTED -> Triple(
            if (isDark) Color(0xFF1B3822) else Color(0xFFE8F5E9),
            if (isDark) Color(0xFF2E7D32) else Color(0xFF81C784),
            if (isDark) Color(0xFF81C784) else Color(0xFF2E7D32)
        )
        CloudProviderStatus.CHECKING -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            MaterialTheme.colorScheme.primary
        )
        CloudProviderStatus.ERROR -> Triple(
            if (isDark) Color(0xFF381E1E) else Color(0xFFFFEBEE),
            if (isDark) Color(0xFFC62828) else Color(0xFFEF9A9A),
            if (isDark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        )
        CloudProviderStatus.UNCONFIGURED -> Triple(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = bg,
        border = BorderStroke(1.dp, border),
        modifier = Modifier
            .height(36.dp)
            .defaultMinSize(minWidth = 84.dp)
            .testTag("ai_cloud_status_button")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (status) {
                CloudProviderStatus.CONNECTED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Cloud Connected",
                        modifier = Modifier.size(15.dp),
                        tint = contentColor
                    )
                    Text(
                        text = activeProvider?.name?.ifBlank { "Connected" } ?: "Connected",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = contentColor
                    )
                }
                CloudProviderStatus.CHECKING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = contentColor
                    )
                    Text(
                        text = "Testing...",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = contentColor
                    )
                }
                CloudProviderStatus.ERROR -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Cloud Error",
                        modifier = Modifier.size(15.dp),
                        tint = contentColor
                    )
                    Text(
                        text = "Offline",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = contentColor
                    )
                }
                CloudProviderStatus.UNCONFIGURED -> {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = "Cloud Not Configured",
                        modifier = Modifier.size(15.dp),
                        tint = contentColor
                    )
                    Text(
                        text = "Not set",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        color = contentColor
                    )
                }
            }
        }
    }
}

/**
 * Dynamic Status Button for Florence-2 Local Model matching Welcome Screen capsule buttons
 * and app monochrome theme tokens.
 */
@Composable
private fun DynamicFlorenceStatusButton(
    state: FlorenceModelState,
    isDark: Boolean,
    testTagPrefix: String = "florence",
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDetails: () -> Unit
) {
    when (state.status) {
        FlorenceStatus.NOT_INSTALLED -> {
            Surface(
                onClick = onInstall,
                shape = CircleShape,
                color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0x0F000000),
                border = BorderStroke(
                    1.dp,
                    if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f) else Color(0x1F000000)
                ),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("${testTagPrefix}_install_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.FileDownload,
                        contentDescription = "Install Model",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Install",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }

        FlorenceStatus.DOWNLOADING, FlorenceStatus.VERIFYING, FlorenceStatus.LOADING, FlorenceStatus.QUEUED, FlorenceStatus.PAUSED, FlorenceStatus.UNINSTALLING -> {
            Surface(
                shape = CircleShape,
                color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color(0x0F000000),
                border = BorderStroke(
                    1.dp,
                    if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f) else Color(0x26000000)
                ),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("${testTagPrefix}_downloading_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    CircularProgressIndicator(
                        progress = { state.progress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
                    )
                    val label = when (state.status) {
                        FlorenceStatus.QUEUED -> "Queued"
                        FlorenceStatus.VERIFYING -> "Verifying"
                        FlorenceStatus.LOADING -> "Checking"
                        FlorenceStatus.UNINSTALLING -> "Removing"
                        else -> "${(state.progress * 100).toInt()}%"
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
                        )
                    )
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel download",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        FlorenceStatus.READY, FlorenceStatus.INSTALLED -> {
            val bg = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
            val border = if (isDark) MaterialTheme.colorScheme.primary else Color(0xFF161A20)
            val contentColor = if (isDark) MaterialTheme.colorScheme.onPrimary else Color.White

            Surface(
                onClick = onDetails,
                shape = CircleShape,
                color = bg,
                border = BorderStroke(1.dp, border),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("${testTagPrefix}_installed_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Ready",
                        modifier = Modifier.size(14.dp),
                        tint = contentColor
                    )
                    Text(
                        text = if (state.runtimeReady) "Ready" else "Installed",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                    )
                }
            }
        }

        FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE -> {
            val bg = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f) else Color(0x14000000)
            val border = if (isDark) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f) else Color(0x26000000)
            val contentColor = MaterialTheme.colorScheme.onSurface

            Surface(
                onClick = onDetails,
                shape = CircleShape,
                color = bg,
                border = BorderStroke(1.dp, border),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("${testTagPrefix}_installed_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Inventory2,
                        contentDescription = "Assets downloaded",
                        modifier = Modifier.size(14.dp),
                        tint = contentColor
                    )
                    Text(
                        text = "Downloaded",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                    )
                }
            }
        }

        FlorenceStatus.ERROR -> {
            val bg = if (isDark) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
            val border = MaterialTheme.colorScheme.error.copy(alpha = 0.45f)
            val contentColor = if (isDark) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onErrorContainer

            Surface(
                onClick = onInstall,
                shape = CircleShape,
                color = bg,
                border = BorderStroke(1.dp, border),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("${testTagPrefix}_retry_button")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry download",
                        modifier = Modifier.size(14.dp),
                        tint = contentColor
                    )
                    Text(
                        text = "Retry",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )
                    )
                }
            }
        }
    }
}
