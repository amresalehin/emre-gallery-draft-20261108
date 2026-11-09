package com.amresalehin.emregallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.face.BuffaloLFaceEngine
import com.amresalehin.emregallery.face.EnteFaceConstants
import com.amresalehin.emregallery.face.FaceClusterStore
import com.amresalehin.emregallery.face.FaceEngineRegistry
import com.amresalehin.emregallery.face.FaceProcessingCoordinator
import com.amresalehin.emregallery.face.FaceProcessingOperation
import com.amresalehin.emregallery.face.FaceRecognitionSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class FaceProcessingCoordinatorTest {

    private lateinit var context: Context
    private lateinit var store: FaceClusterStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("face_index.db")
        FaceProcessingCoordinator.init(context)
        FaceProcessingCoordinator.resetForTesting()
        FaceEngineRegistry.clearNonDefault()
        FaceEngineRegistry.register(com.amresalehin.emregallery.face.EnteFaceEngineFactory)
        FaceRecognitionSettings.setActiveEngine(context, BuffaloLFaceEngine.ENGINE_ID)
        store = FaceClusterStore(context)
    }

    @After
    fun tearDown() {
        FaceProcessingCoordinator.resetForTesting()
    }

    @Test
    fun testCoordinatorTracksAllOperationStages() {
        assertFalse("Coordinator should be idle initially", FaceProcessingCoordinator.isBusy(context))

        // Scanning
        val scanHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.SCANNING,
            "Scanning test"
        )
        assertTrue("Coordinator must report busy during scanning", FaceProcessingCoordinator.isBusy(context))
        assertTrue(FaceProcessingCoordinator.isProcessingActive.value)
        scanHandle.close()
        assertFalse(FaceProcessingCoordinator.isBusy(context))

        // Embedding
        val embeddingHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.EMBEDDING,
            "Embedding test"
        )
        assertTrue("Coordinator must report busy during embedding generation", FaceProcessingCoordinator.isBusy(context))
        embeddingHandle.close()
        assertFalse(FaceProcessingCoordinator.isBusy(context))

        // Grouping / Reclustering
        val groupingHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.GROUPING,
            "Grouping test"
        )
        assertTrue("Coordinator must report busy during grouping/reclustering", FaceProcessingCoordinator.isBusy(context))
        groupingHandle.close()
        assertFalse(FaceProcessingCoordinator.isBusy(context))

        // Model download
        val downloadHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.MODEL_DOWNLOAD,
            "Download test"
        )
        assertTrue("Coordinator must report busy during model download", FaceProcessingCoordinator.isBusy(context))
        downloadHandle.close()
        assertFalse(FaceProcessingCoordinator.isBusy(context))

        // Mutations
        val mutationHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.FACE_DB_MUTATION,
            "Mutation test"
        )
        assertTrue("Coordinator must report busy during DB mutation", FaceProcessingCoordinator.isBusy(context))
        mutationHandle.close()
        assertFalse(FaceProcessingCoordinator.isBusy(context))
    }

    @Test
    fun testNestedOperationsKeepCoordinatorBusyUntilAllFinish() {
        val scanHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.SCANNING,
            "Scanning batch"
        )
        val groupingHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.GROUPING,
            "Clustering faces"
        )

        assertTrue(FaceProcessingCoordinator.isBusy(context))

        // Close scanning first, grouping is still active
        scanHandle.close()
        assertTrue("Coordinator must remain busy until grouping finishes", FaceProcessingCoordinator.isBusy(context))

        // Close grouping
        groupingHandle.close()
        assertFalse("Coordinator must be idle after all nested operations finish", FaceProcessingCoordinator.isBusy(context))
    }

    @Test
    fun testModelSwitchingBlockedDuringProcessing() {
        assertEquals(BuffaloLFaceEngine.ENGINE_ID, FaceRecognitionSettings.getActiveEngine(context))

        val handle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.SCANNING,
            "Active indexing"
        )

        // Attempting to switch engine via FaceRecognitionSettings
        val switchSettingsResult = FaceRecognitionSettings.setActiveEngine(context, EnteFaceConstants.ENGINE_ID)
        assertFalse("FaceRecognitionSettings must reject engine switch while processing", switchSettingsResult)
        assertEquals("Active engine must remain Buffalo-L", BuffaloLFaceEngine.ENGINE_ID, FaceRecognitionSettings.getActiveEngine(context))

        // Attempting to switch engine via FaceEngineRegistry
        val switchRegistryResult = FaceEngineRegistry.setActiveEngine(EnteFaceConstants.ENGINE_ID)
        assertFalse("FaceEngineRegistry must reject engine switch while processing", switchRegistryResult)
        assertEquals("Active engine in registry must remain Buffalo-L", BuffaloLFaceEngine.ENGINE_ID, FaceEngineRegistry.activeId)

        // Now finish operation
        handle.close()

        // After completion, model switching must succeed
        val successfulSwitch = FaceRecognitionSettings.setActiveEngine(context, EnteFaceConstants.ENGINE_ID)
        assertTrue("Model switch must succeed after processing finishes", successfulSwitch)
        assertEquals(EnteFaceConstants.ENGINE_ID, FaceRecognitionSettings.getActiveEngine(context))
    }

    @Test
    fun testEnsurePersonForFaceBlockedDuringProcessing() {
        // First record cluster and face in DB without person assignment
        val db = store.writableDatabase
        val clusterCv = android.content.ContentValues().apply {
            put("id", "cluster_1")
            put("display_name", "Cluster 1")
            put("centroid", ByteArray(10))
            put("engine_id", BuffaloLFaceEngine.ENGINE_ID)
            put("model_version", "w600k_r50")
            put("updated_at", System.currentTimeMillis())
        }
        db.insertOrThrow("clusters", null, clusterCv)

        val cv = android.content.ContentValues().apply {
            put("face_id", "buffalo_l#content://media/photo1#0")
            put("media_uri", "content://media/photo1")
            put("face_index", 0)
            put("cluster_id", "cluster_1")
            put("engine_id", BuffaloLFaceEngine.ENGINE_ID)
            put("model_version", "w600k_r50")
            put("embedding", ByteArray(10))
            put("box_left", 10f)
            put("box_top", 10f)
            put("box_right", 50f)
            put("box_bottom", 50f)
            put("detection_confidence", 0.95f)
            put("quality_score", 0.95f)
        }
        db.insertOrThrow("faces", null, cv)

        // Start processing (e.g. indexing or grouping)
        val handle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.GROUPING,
            "Grouping faces"
        )

        // Attempting to ensure/create person for the face while processing
        val personIdWhileBusy = store.ensurePersonForFace("content://media/photo1", 0, BuffaloLFaceEngine.ENGINE_ID)
        assertEquals("ensurePersonForFace must refuse mutating/assigning person while busy", "", personIdWhileBusy)

        // Also test runIfIdle guard
        var runIfIdleExecuted = false
        val runResult = FaceProcessingCoordinator.runIfIdle {
            runIfIdleExecuted = true
            store.ensurePersonForFace("content://media/photo1", 0, BuffaloLFaceEngine.ENGINE_ID)
        }
        assertFalse("runIfIdle must not execute action while coordinator is busy", runIfIdleExecuted)
        assertNull(runResult)

        // Finish processing
        handle.close()

        // Now that processing is idle, ensurePersonForFace must work normally
        val personIdWhenIdle = store.ensurePersonForFace("content://media/photo1", 0, BuffaloLFaceEngine.ENGINE_ID)
        assertTrue("ensurePersonForFace must create person once idle", personIdWhenIdle.isNotBlank())
        assertEquals("Subsequent calls return the assigned person ID", personIdWhenIdle, store.ensurePersonForFace("content://media/photo1", 0, BuffaloLFaceEngine.ENGINE_ID))
    }

    @Test
    fun testRestorationAfterFailureOrCancellation() {
        // Test failure handling via withProcessing
        var caughtException = false
        try {
            runBlocking {
                FaceProcessingCoordinator.withProcessing(
                    FaceProcessingOperation.SCANNING,
                    "Failing operation"
                ) {
                    assertTrue("Coordinator must be busy inside withProcessing", FaceProcessingCoordinator.isBusy(context))
                    throw IllegalStateException("Simulated processing error")
                }
            }
        } catch (e: IllegalStateException) {
            caughtException = true
        }

        assertTrue(caughtException)
        assertFalse("Coordinator must restore idle state after exception", FaceProcessingCoordinator.isBusy(context))

        // Test cancellation handling via handle.close()
        val cancelHandle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.SCANNING,
            "Cancellable operation"
        )
        assertTrue(FaceProcessingCoordinator.isBusy(context))
        cancelHandle.close()
        assertFalse("Coordinator must restore idle state after cancellation", FaceProcessingCoordinator.isBusy(context))

        // Both functions must work normally again
        assertTrue("Model switching works after cancellation", FaceRecognitionSettings.setActiveEngine(context, EnteFaceConstants.ENGINE_ID))
    }

    @Test
    fun testAtomicRacePrevention() {
        // Test that trySwitchEngine and runIfIdle are atomic under the coordinator lock
        val handle = FaceProcessingCoordinator.beginOperation(
            FaceProcessingOperation.SCANNING,
            "Lock test"
        )

        // Direct call to trySwitchEngine returns false
        assertFalse(FaceProcessingCoordinator.trySwitchEngine(context, EnteFaceConstants.ENGINE_ID))

        handle.close()

        // Once released, trySwitchEngine returns true
        assertTrue(FaceProcessingCoordinator.trySwitchEngine(context, EnteFaceConstants.ENGINE_ID))
        assertEquals(EnteFaceConstants.ENGINE_ID, FaceRecognitionSettings.getActiveEngine(context))
    }
}
