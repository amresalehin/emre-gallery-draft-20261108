package com.amresalehin.emregallery.ai.local

import android.content.Context
import com.amresalehin.emregallery.data.LocalModelState
import com.amresalehin.emregallery.data.LocalModelStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Observable, thread-safe state store managing the download, verification,
 * and readiness lifecycle for all local AI models.
 */
object LocalModelStateStore {

    private val _allModelStates = MutableStateFlow<Map<String, LocalModelState>>(emptyMap())
    val allModelStates: StateFlow<Map<String, LocalModelState>> = _allModelStates.asStateFlow()

    private val _modelState = MutableStateFlow(LocalModelState())
    val modelState: StateFlow<LocalModelState> = _modelState.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val modelLocks = ConcurrentHashMap<String, Mutex>()

    private fun getLock(modelId: String): Mutex =
        modelLocks.computeIfAbsent(modelId) { Mutex() }

    fun refreshAll(context: Context): Map<String, LocalModelState> {
        val updated = mutableMapOf<String, LocalModelState>()
        for (spec in LocalModelRegistry.ALL_MODELS) {
            val currentJob = activeJobs[spec.id]
            // If actively downloading in background, don't overwrite with static check
            if (currentJob != null && currentJob.isActive) {
                val current = _allModelStates.value[spec.id]
                if (current != null) {
                    updated[spec.id] = current
                    continue
                }
            }

            val state = LocalModelInstaller.checkInstallationStatus(context, spec)
            updated[spec.id] = state
            // Also map legacy aliases
            spec.legacyAliases.forEach { alias ->
                updated[alias] = state
            }
        }
        _allModelStates.value = updated
        updated[LocalModelRegistry.MINI_CPM_V46_ID]?.let { _modelState.value = it }
        return updated
    }

    fun getState(modelId: String): LocalModelState {
        return _allModelStates.value[modelId] ?: LocalModelState()
    }

    fun updateState(modelId: String, state: LocalModelState) {
        val map = _allModelStates.value.toMutableMap()
        map[modelId] = state
        val spec = LocalModelRegistry.findSpec(modelId)
        if (spec != null) {
            map[spec.id] = state
            map[spec.canonicalId] = state
            spec.legacyAliases.forEach { alias -> map[alias] = state }
        }
        _allModelStates.value = map

        if (spec?.id == LocalModelRegistry.MINI_CPM_V46_ID || modelId == "minicpm_v_4_6") {
            _modelState.value = state
        }
    }

    suspend fun downloadAndInstall(
        context: Context,
        modelId: String,
        onProgress: ((progress: Float, downloaded: Long, total: Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val spec = LocalModelRegistry.getSpec(modelId)
        val lock = getLock(spec.id)

        // Prevent concurrent installs of the same model
        if (!lock.tryLock()) {
            return@withContext false
        }

        val job = coroutineContext[Job]
        if (job != null) {
            activeJobs[spec.id] = job
            activeJobs[modelId] = job
        }

        updateState(
            spec.id,
            LocalModelState(
                status = LocalModelStatus.QUEUED,
                totalBytes = spec.totalExpectedBytes,
                currentStage = "queued",
                lastUpdated = System.currentTimeMillis()
            )
        )

        try {
            val finalState = LocalModelInstaller.downloadAndInstall(
                context = context,
                spec = spec,
                onProgress = { p ->
                    val status = when (p.stage) {
                        "verifying" -> LocalModelStatus.VERIFYING
                        "validating_runtime" -> LocalModelStatus.LOADING
                        else -> LocalModelStatus.DOWNLOADING
                    }
                    val currentUpdate = LocalModelState(
                        status = status,
                        progress = p.fraction,
                        bytesDownloaded = p.bytesDownloaded,
                        totalBytes = p.totalBytes,
                        speedBytesPerSec = p.speedBytesPerSec,
                        etaSeconds = p.etaSeconds,
                        currentStage = p.stage,
                        currentArtifact = p.currentArtifact,
                        lastUpdated = System.currentTimeMillis()
                    )
                    updateState(spec.id, currentUpdate)
                    onProgress?.invoke(p.fraction, p.bytesDownloaded, p.totalBytes)
                }
            )

            updateState(spec.id, finalState)
            onProgress?.invoke(1.0f, finalState.bytesDownloaded, finalState.totalBytes)
            return@withContext true

        } catch (e: CancellationException) {
            refreshAll(context)
            throw e
        } catch (e: Throwable) {
            val errState = LocalModelState(
                status = LocalModelStatus.ERROR,
                errorMessage = e.localizedMessage ?: "Installation failed: ${e.javaClass.simpleName}",
                lastUpdated = System.currentTimeMillis()
            )
            updateState(spec.id, errState)
            return@withContext false
        } finally {
            activeJobs.remove(spec.id)
            activeJobs.remove(modelId)
            lock.unlock()
        }
    }

    suspend fun uninstall(context: Context, modelId: String): Boolean = withContext(Dispatchers.IO) {
        val spec = LocalModelRegistry.getSpec(modelId)
        // A cancelled coroutine must finish closing its file streams before any deletion.
        activeJobs[spec.id]?.cancelAndJoin()
        val lock = getLock(spec.id)
        lock.withLock {
            LocalModelRuntimeRegistry.closeRuntime(spec.id)
            val uninstalled = LocalModelInstaller.uninstall(context, spec)
            val resetState = LocalModelState(
                status = LocalModelStatus.NOT_INSTALLED,
                progress = 0f,
                bytesDownloaded = 0L,
                totalBytes = spec.totalExpectedBytes,
                installedSizeBytes = 0L,
                installedPath = null,
                lastUpdated = System.currentTimeMillis()
            )
            updateState(spec.id, resetState)
            uninstalled
        }
    }

    fun cancelDownload(context: Context, modelId: String) {
        val spec = LocalModelRegistry.getSpec(modelId)
        // Keep the job mapping until its finally block runs, avoiding a refresh race.
        activeJobs[spec.id]?.cancel()
    }
}
