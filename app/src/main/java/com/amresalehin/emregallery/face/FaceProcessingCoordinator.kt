package com.amresalehin.emregallery.face

import android.content.Context
import com.amresalehin.emregallery.IndexingForegroundService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

enum class FaceProcessingOperation {
    SCANNING,
    RESCANNING,
    EMBEDDING,
    GROUPING,
    MODEL_DOWNLOAD,
    FACE_DB_MUTATION
}

data class ActiveOperation(
    val id: String,
    val type: FaceProcessingOperation,
    val engineId: String = "",
    val description: String = "",
    val startTimeMillis: Long = System.currentTimeMillis()
)

interface ProcessingHandle : Closeable {
    val operation: ActiveOperation
    override fun close()
}

/**
 * App-wide coordinator that tracks face scanning, rescanning, embedding generation,
 * grouping/reclustering, model download/installation, and other face-DB mutations.
 *
 * Ensures that face-person navigation and model switching are strictly disabled
 * while operations are active, closing races between checking busy status and
 * starting new operations.
 */
typealias OperationType = FaceProcessingOperation

object FaceProcessingCoordinator {
    private val lock = Any()
    private val activeOperationsMap = ConcurrentHashMap<String, ActiveOperation>()

    private val _isProcessingActive = MutableStateFlow(false)
    val isProcessingActive: StateFlow<Boolean> = _isProcessingActive.asStateFlow()

    private val _activeOperationsFlow = MutableStateFlow<Set<ActiveOperation>>(emptySet())
    val activeOperationsFlow: StateFlow<Set<ActiveOperation>> = _activeOperationsFlow.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshState()
    }

    fun resetForTesting() {
        clearAllOperations()
    }

    /**
     * Checks if any face processing operation is active, either in-memory or in the foreground service.
     */
    fun isBusy(context: Context? = null): Boolean {
        synchronized(lock) {
            if (activeOperationsMap.isNotEmpty()) return true
            val ctx = context?.applicationContext ?: appContext
            if (ctx != null) {
                val prefs = ctx.getSharedPreferences(IndexingForegroundService.STATE_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(IndexingForegroundService.KEY_RUNNING, false)) {
                    val task = prefs.getString(IndexingForegroundService.KEY_TASK, "") ?: ""
                    if (task == "face" || task == "model") return true
                }
            }
            return false
        }
    }

    /**
     * Refreshes the isProcessingActive StateFlow based on current in-memory operations and service prefs.
     */
    fun refreshState() {
        synchronized(lock) {
            val hasOps = activeOperationsMap.isNotEmpty()
            var serviceRunning = false
            val ctx = appContext
            if (ctx != null) {
                val prefs = ctx.getSharedPreferences(IndexingForegroundService.STATE_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(IndexingForegroundService.KEY_RUNNING, false)) {
                    val task = prefs.getString(IndexingForegroundService.KEY_TASK, "") ?: ""
                    if (task == "face" || task == "model") {
                        serviceRunning = true
                    }
                }
            }
            val busy = hasOps || serviceRunning
            _isProcessingActive.value = busy
            _activeOperationsFlow.value = activeOperationsMap.values.toSet()
        }
    }

    fun beginOperation(
        type: FaceProcessingOperation,
        description: String = ""
    ): ProcessingHandle {
        val opId = "${type.name.lowercase()}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(6)}"
        return beginOperation(operationId = opId, type = type, description = description)
    }

    /**
     * Begins tracking a face processing operation. The returned ProcessingHandle MUST be closed
     * when the operation finishes (including in finally blocks covering grouping and cleanup).
     */
    fun beginOperation(
        operationId: String,
        type: FaceProcessingOperation,
        engineId: String = "",
        description: String = ""
    ): ProcessingHandle {
        val op = ActiveOperation(operationId, type, engineId, description)
        synchronized(lock) {
            activeOperationsMap[operationId] = op
            refreshState()
        }
        return object : ProcessingHandle {
            override val operation: ActiveOperation = op
            private var closed = false
            override fun close() {
                synchronized(lock) {
                    if (!closed) {
                        closed = true
                        activeOperationsMap.remove(operationId)
                        refreshState()
                    }
                }
            }
        }
    }

    inline fun <T> withProcessing(
        type: FaceProcessingOperation,
        description: String = "",
        block: () -> T
    ): T {
        val handle = beginOperation(type, description)
        try {
            return block()
        } finally {
            handle.close()
        }
    }

    /**
     * Convenience wrapper to run a block within an active processing lease.
     */
    inline fun <T> withProcessing(
        operationId: String,
        type: FaceProcessingOperation,
        engineId: String = "",
        description: String = "",
        block: () -> T
    ): T {
        val handle = beginOperation(operationId, type, engineId, description)
        try {
            return block()
        } finally {
            handle.close()
        }
    }

    /**
     * Closes the race between checking whether processing is active and starting a new operation.
     * Executes the block atomically under coordinator lock ONLY if no processing operation is active.
     * Returns null if processing is busy.
     */
    fun <T> runIfIdle(block: () -> T): T? {
        synchronized(lock) {
            if (isBusy()) {
                return null
            }
            return block()
        }
    }

    /**
     * Atomically attempts to switch the active engine.
     * Enforced under coordinator lock: if processing is active, returns false and does not change anything.
     */
    fun trySwitchEngine(context: Context, targetEngineId: String): Boolean {
        synchronized(lock) {
            if (isBusy(context)) {
                return false
            }
            FaceRecognitionSettings.preferences(context).edit()
                .putString(FaceRecognitionSettings.KEY_ACTIVE_ENGINE, targetEngineId)
                .apply()
            FaceEngineRegistry.setDirectActiveEngine(targetEngineId)
            FaceStoreEvents.notifyAllUpdated()
            return true
        }
    }

    /**
     * Clears all in-memory operations and resets state. Intended for test teardown.
     */
    fun clearAllOperations() {
        synchronized(lock) {
            activeOperationsMap.clear()
            refreshState()
        }
    }
}
