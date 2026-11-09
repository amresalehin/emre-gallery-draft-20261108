package com.amresalehin.emregallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.face.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class FaceFixesRegressionTest {

    private lateinit var context: Context
    private lateinit var store: FaceClusterStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("face_index.db")
        store = FaceClusterStore(context)
        FaceEngineRegistry.register(BuffaloLFaceEngineFactory)
        FaceEngineRegistry.register(EnteFaceEngineFactory)
    }

    private fun createSyntheticEmbedding(seed: Int, dim: Int = 512): FloatArray {
        val array = FloatArray(dim)
        val start = (seed * 64) % dim
        val end = (start + 64).coerceAtMost(dim)
        for (i in start until end) {
            array[i] = 1.0f + 0.01f * ((i - start) % 7)
        }
        var norm = 0f
        for (v in array) norm += v * v
        norm = kotlin.math.sqrt(norm)
        if (norm > 0f) {
            for (i in array.indices) array[i] /= norm
        }
        return array
    }

    @Test
    fun testEnteYoloV5FaceTensorDecodingOffsets() {
        // Prepare 1 anchor with stride 16
        // [cx, cy, w, h, obj_conf, l0_x, l0_y, l1_x, l1_y, l2_x, l2_y, l3_x, l3_y, l4_x, l4_y, class_conf]
        val stride = 16
        val flatValues = FloatArray(stride)
        flatValues[0] = 320f // cx
        flatValues[1] = 240f // cy
        flatValues[2] = 100f // w
        flatValues[3] = 120f // h
        flatValues[4] = 0.90f // objConf
        // 5 landmark pairs at offsets 5..14
        flatValues[5] = 300f // left eye x
        flatValues[6] = 220f // left eye y
        flatValues[7] = 340f // right eye x
        flatValues[8] = 220f // right eye y
        flatValues[9] = 320f // nose x
        flatValues[10] = 240f // nose y
        flatValues[11] = 310f // mouth left x
        flatValues[12] = 270f // mouth left y
        flatValues[13] = 330f // mouth right x
        flatValues[14] = 270f // mouth right y
        flatValues[15] = 0.80f // classConf

        val detections = EnteFaceDetector.Companion.decodeTensorOutput(
            flatValues = flatValues,
            numAnchors = 1,
            stridePerAnchor = stride,
            originalWidth = 640,
            originalHeight = 640,
            scale = 1.0f,
            padX = 0f,
            padY = 0f,
            confidenceThreshold = 0.5f,
            nmsIoUThreshold = 0.4f
        )

        assertEquals("Should decode 1 candidate", 1, detections.size)
        val d = detections[0]

        // Score must be objConf * classConf (0.90 * 0.80 = 0.72)
        assertEquals("Confidence score should be objConf * classConf (offset 15)", 0.72f, d.score, 0.001f)

        // Landmarks must match offsets 5..14
        assertEquals("Landmark 0 X (left eye)", 300f, d.landmarks[0], 0.1f)
        assertEquals("Landmark 0 Y (left eye)", 220f, d.landmarks[1], 0.1f)
        assertEquals("Landmark 1 X (right eye)", 340f, d.landmarks[2], 0.1f)
        assertEquals("Landmark 1 Y (right eye)", 220f, d.landmarks[3], 0.1f)
        assertEquals("Landmark 2 X (nose)", 320f, d.landmarks[4], 0.1f)
        assertEquals("Landmark 2 Y (nose)", 240f, d.landmarks[5], 0.1f)
        assertEquals("Landmark 3 X (mouth left)", 310f, d.landmarks[6], 0.1f)
        assertEquals("Landmark 3 Y (mouth left)", 270f, d.landmarks[7], 0.1f)
        assertEquals("Landmark 4 X (mouth right)", 330f, d.landmarks[8], 0.1f)
        assertEquals("Landmark 4 Y (mouth right)", 270f, d.landmarks[9], 0.1f)

        // Bounding box centered at 320, 240 with w=100, h=120 -> left=270, top=180, right=370, bottom=300
        assertEquals(270f, d.bounds.left, 0.1f)
        assertEquals(180f, d.bounds.top, 0.1f)
        assertEquals(370f, d.bounds.right, 0.1f)
        assertEquals(300f, d.bounds.bottom, 0.1f)
    }

    @Test
    fun testIdentityPreservationAcrossReclustering() {
        val engineId = "buffalo_l"
        val prov = EmbeddingProvenance(engineId, "w600k_r50", 512)

        val embAlice = createSyntheticEmbedding(0)
        val embBob = createSyntheticEmbedding(1)

        store.addFace("content://media/photo_alice", 0, embAlice, bounds = RectF(10f, 10f, 50f, 50f), provenance = prov)
        store.addFace("content://media/photo_bob", 0, embBob, bounds = RectF(20f, 20f, 60f, 60f), provenance = prov)

        var people = store.personSummaries(engineId = engineId)
        assertEquals(2, people.size)

        val alice = people.first { it.sampleUri == "content://media/photo_alice" }
        val bob = people.first { it.sampleUri == "content://media/photo_bob" }

        // Set Alice's name
        store.renamePerson(alice.id, "Alice Smith")
        // Hide Bob
        store.setPersonHidden(bob.id, true)
        // Set custom cover for Alice
        store.setPersonCover(alice.id, "content://media/custom_alice_cover")

        // Trigger safe reclustering
        store.reclusterAllSafely(maxDistance = 0.45f, engineId = engineId)

        // Verify that custom name, hidden status, and custom cover survive reclustering!
        val allPeople = store.personSummaries(engineId = engineId, includeHidden = true)
        val visiblePeople = store.personSummaries(engineId = engineId, includeHidden = false)

        val preservedAlice = allPeople.firstOrNull { it.id == alice.id }
        assertNotNull("Alice person record should survive reclustering", preservedAlice)
        assertEquals("Alice Smith", preservedAlice!!.displayName)
        assertEquals("content://media/custom_alice_cover", preservedAlice.sampleUri)

        val preservedBob = allPeople.firstOrNull { it.id == bob.id }
        assertNotNull("Bob person record should survive reclustering", preservedBob)
        assertTrue("Bob must remain hidden after reclustering", preservedBob!!.isHidden)

        // Visible people list should only contain Alice
        assertEquals("Visible people should only have 1 (Alice)", 1, visiblePeople.size)
        assertEquals("Alice Smith", visiblePeople.first().displayName)
    }

    @Test
    fun testIdentityPreservationAcrossEnteLinearClustering() {
        val engineId = EnteFaceConstants.ENGINE_ID
        val prov = EnteFaceConstants.PROVENANCE

        val emb1 = createSyntheticEmbedding(0)
        val emb2 = createSyntheticEmbedding(0, 512)

        store.addRawFaceObservation("content://media/ente_diana_1", 0, emb1, bounds = RectF(0f, 0f, 50f, 50f), provenance = prov)
        store.addRawFaceObservation("content://media/ente_diana_2", 0, emb2, bounds = RectF(5f, 5f, 55f, 55f), provenance = prov)

        EnteFaceClusteringStrategy.reclusterChronologically(store, maxDistance = 0.40f)

        val peopleBefore = store.personSummaries(engineId = engineId)
        assertEquals(1, peopleBefore.size)
        val diana = peopleBefore[0]

        // User names the person
        store.renamePerson(diana.id, "Diana Prince")
        store.setPersonCover(diana.id, "content://media/ente_diana_cover")

        // Add another observation and recluster
        val emb3 = createSyntheticEmbedding(0, 512)
        store.addRawFaceObservation("content://media/ente_diana_3", 0, emb3, bounds = RectF(10f, 10f, 60f, 60f), provenance = prov)

        EnteFaceClusteringStrategy.reclusterChronologically(store, maxDistance = 0.40f)

        val peopleAfter = store.personSummaries(engineId = engineId)
        val dianaAfter = peopleAfter.firstOrNull { it.id == diana.id }
        assertNotNull("Diana Prince cluster must not be dissolved", dianaAfter)
        assertEquals("Diana Prince", dianaAfter!!.displayName)
        assertEquals("content://media/ente_diana_cover", dianaAfter.sampleUri)
    }

    @Test
    fun testPruneStaleFacesOnRescan() {
        val photoUri = "content://media/photo_rescan_test"
        val engineId = "buffalo_l"
        val prov = EmbeddingProvenance(engineId, "w600k_r50", 512)
        val emb = createSyntheticEmbedding(0)

        // First scan detects 3 faces: index 0, index 1, index 2
        store.addFace(photoUri, 0, emb, bounds = RectF(10f, 10f, 50f, 50f), provenance = prov)
        store.addFace(photoUri, 1, emb, bounds = RectF(60f, 10f, 100f, 50f), provenance = prov)
        store.addFace(photoUri, 2, emb, bounds = RectF(110f, 10f, 150f, 50f), provenance = prov)

        var faces = store.facesForMedia(photoUri, engineId = engineId)
        assertEquals(3, faces.size)

        // Rescan finds only 1 face: index 0
        // Prune stale faces >= 1
        store.pruneStaleFacesForMedia(photoUri, engineId, currentFaceCount = 1)

        faces = store.facesForMedia(photoUri, engineId = engineId)
        assertEquals("Should only retain 1 face after pruning", 1, faces.size)
        assertEquals(0, faces[0].faceIndex)
    }

    @Test
    fun testFaceRemovalPreservesOtherFacesOnSamePhoto() {
        val photoUri = "content://media/group_photo"
        val engineId = "buffalo_l"
        val prov = EmbeddingProvenance(engineId, "w600k_r50", 512)

        val embAlice = createSyntheticEmbedding(0)
        val embBob = createSyntheticEmbedding(1)

        store.addFace(photoUri, 0, embAlice, bounds = RectF(10f, 10f, 50f, 50f), provenance = prov)
        store.addFace(photoUri, 1, embBob, bounds = RectF(100f, 10f, 140f, 50f), provenance = prov)

        val people = store.personSummaries(engineId = engineId)
        assertEquals(2, people.size)

        val facesBefore = store.facesForMedia(photoUri, engineId = engineId)
        assertEquals(2, facesBefore.size)
        val face0Before = facesBefore.first { it.faceIndex == 0 }
        val face1Before = facesBefore.first { it.faceIndex == 1 }
        val personAId = face0Before.personId
        val personBId = face1Before.personId
        assertNotNull(personAId)
        assertNotNull(personBId)
        assertNotEquals(personAId, personBId)

        // Remove Face 0 from Person A
        store.removeFaceFromPerson(mediaUri = photoUri, faceIndex = 0, engineId = engineId)

        val facesAfter = store.facesForMedia(photoUri, engineId = engineId)
        assertEquals(2, facesAfter.size)

        val face0 = facesAfter.first { it.faceIndex == 0 }
        val face1 = facesAfter.first { it.faceIndex == 1 }

        // Face 0 should no longer belong to personAId
        assertNotEquals("Face 0 should no longer be assigned to Person A", personAId, face0.personId)
        // Face 1 MUST still belong to personBId!
        assertEquals("Face 1 must remain assigned to Person B", personBId, face1.personId)
    }

    @Test
    fun testFindCommonIndexedMediaUri() {
        val photoOnlyBuffalo = "content://media/photo_buffalo_only"
        val photoBoth = "content://media/photo_both_engines"

        store.markMediaIndexed(photoOnlyBuffalo, 1, BuffaloLFaceEngine.ENGINE_ID)
        store.markMediaIndexed(photoBoth, 2, BuffaloLFaceEngine.ENGINE_ID)
        store.markMediaIndexed(photoBoth, 2, EnteFaceConstants.ENGINE_ID)

        val commonUri = store.findCommonIndexedMediaUri(BuffaloLFaceEngine.ENGINE_ID, EnteFaceConstants.ENGINE_ID)
        assertEquals("Common indexed photo should be returned for comparison", photoBoth, commonUri)
    }

    @Test
    fun testFaceStoreEventsNotification() {
        var notifiedUri: String? = null
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            FaceStoreEvents.mediaUpdates.collect { uri ->
                notifiedUri = uri
            }
        }

        store.markMediaIndexed("content://media/test_event_photo", 2, BuffaloLFaceEngine.ENGINE_ID)
        assertEquals("content://media/test_event_photo", notifiedUri)

        store.markMediaFailed("content://media/test_event_photo_fail", BuffaloLFaceEngine.ENGINE_ID, "Some error")
        assertEquals("content://media/test_event_photo_fail", notifiedUri)

        job.cancel()
    }

    @Test
    fun testScopedFailedMediaCount() {
        val uri1 = "content://media/failed_1"
        val uri2 = "content://media/failed_2"
        val uri3 = "content://media/indexed_3"

        store.markMediaFailed(uri1, BuffaloLFaceEngine.ENGINE_ID, "Error 1")
        store.markMediaFailed(uri2, EnteFaceConstants.ENGINE_ID, "Error 2")
        store.markMediaIndexed(uri3, 1, BuffaloLFaceEngine.ENGINE_ID)

        val buffaloFailed = store.failedMediaCount(listOf(uri1, uri2, uri3), BuffaloLFaceEngine.ENGINE_ID)
        val enteFailed = store.failedMediaCount(listOf(uri1, uri2, uri3), EnteFaceConstants.ENGINE_ID)

        assertEquals("Buffalo_L failed count should only count its own failures", 1, buffaloFailed)
        assertEquals("Ente failed count should only count its own failures", 1, enteFailed)
    }

    @Test
    fun testPruneStaleFacesWithGaps() {
        val photoUri = "content://media/photo_with_gaps"
        val engineId = BuffaloLFaceEngine.ENGINE_ID
        val prov = EmbeddingProvenance(engineId, "w600k_r50", 512)
        val emb = createSyntheticEmbedding(1)

        store.addFace(photoUri, 0, emb, bounds = RectF(10f, 10f, 50f, 50f), provenance = prov)
        store.addFace(photoUri, 1, emb, bounds = RectF(60f, 10f, 100f, 50f), provenance = prov)
        store.addFace(photoUri, 2, emb, bounds = RectF(110f, 10f, 150f, 50f), provenance = prov)

        assertEquals(3, store.facesForMedia(photoUri, engineId).size)

        // Rescan kept only face 0 and face 2 (e.g., face 1 was filtered out as too small)
        store.pruneStaleFacesForMedia(photoUri, engineId, keptIndices = listOf(0, 2))

        val facesAfter = store.facesForMedia(photoUri, engineId)
        assertEquals("Faces 0 and 2 must be preserved, while face 1 is deleted", 2, facesAfter.size)
        val indices = facesAfter.map { it.faceIndex }.toSet()
        assertTrue(indices.contains(0))
        assertTrue(indices.contains(2))
        assertFalse(indices.contains(1))
    }

    @Test
    fun testCascadingDeletionForMedia() {
        val photoUri = "content://media/photo_to_delete"
        val engineId = BuffaloLFaceEngine.ENGINE_ID
        val prov = EmbeddingProvenance(engineId, "w600k_r50", 512)
        val emb = createSyntheticEmbedding(2)

        store.addFace(photoUri, 0, emb, bounds = RectF(10f, 10f, 50f, 50f), provenance = prov)
        store.markMediaIndexed(photoUri, 1, engineId)

        assertEquals(1, store.facesForMedia(photoUri, engineId).size)
        assertEquals(1, store.indexedMediaCount(listOf(photoUri), engineId))

        // Cascade delete
        store.deleteMediaCompletely(listOf(photoUri))

        assertEquals(0, store.facesForMedia(photoUri, engineId).size)
        assertEquals(0, store.indexedMediaCount(listOf(photoUri), engineId))
    }

    @Test
    fun testBuffaloModelUpdatePreservesPersonAndCover() {
        val photoUri = "content://media/photo_model_upgrade"
        val engineId = BuffaloLFaceEngine.ENGINE_ID
        val provV1 = EmbeddingProvenance(engineId, "w600k_r50_v1", 512)
        val embV1 = createSyntheticEmbedding(3)

        val clusterId = store.addFace(photoUri, 0, embV1, bounds = RectF(10f, 10f, 50f, 50f), provenance = provV1)
        val people = store.personSummaries(engineId = engineId)
        assertEquals(1, people.size)
        val personId = people.first().id
        store.renamePerson(personId, "Charlie")
        store.setPersonCover(personId, photoUri)

        // Upgrade model to v2 and re-ingest face
        val provV2 = EmbeddingProvenance(engineId, "w600k_r50_v2", 512)
        val embV2 = createSyntheticEmbedding(4) // updated vector
        val updatedClusterId = store.addFace(photoUri, 0, embV2, bounds = RectF(12f, 12f, 52f, 52f), provenance = provV2)

        val peopleAfter = store.personSummaries(engineId = engineId)
        val charlie = peopleAfter.firstOrNull { it.id == personId }
        assertNotNull("Person record Charlie must be preserved across model version updates", charlie)
        assertEquals("Charlie", charlie!!.displayName)
        assertEquals(photoUri, charlie.sampleUri)
    }

    @Test
    fun testLaplacianVarianceCalculations() {
        // Uniform bitmap: variance should be 0
        val uniformBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        uniformBitmap.eraseColor(Color.GRAY)
        val uniformVar = FaceQualityMetrics.calculateLaplacianVariance(uniformBitmap)
        assertEquals(0f, uniformVar, 0.001f)

        // Checkerboard / high frequency pattern: variance should be > 0
        val patternBitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        for (y in 0 until 20) {
            for (x in 0 until 20) {
                val color = if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE
                patternBitmap.setPixel(x, y, color)
            }
        }
        val patternVar = FaceQualityMetrics.calculateLaplacianVariance(patternBitmap)
        assertTrue("High contrast edge pattern must have substantial Laplacian variance", patternVar > 100f)
    }

    @Test
    fun testEnteFaceAligner5PointSimilarity() {
        val testBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        testBitmap.eraseColor(Color.WHITE)
        val landmarks = floatArrayOf(
            70f, 80f,   // Left eye
            130f, 80f,  // Right eye
            100f, 110f, // Nose
            75f, 140f,  // Mouth Left
            125f, 140f  // Mouth Right
        )
        val bounds = RectF(50f, 50f, 150f, 160f)
        val aligned = EnteFaceAligner.align(testBitmap, landmarks, bounds)

        assertNotNull(aligned)
        assertEquals(112, aligned.width)
        assertEquals(112, aligned.height)
    }

    @Test
    fun testMediaWithFacesCountAccurateStatistics() {
        val engineId = BuffaloLFaceEngine.ENGINE_ID
        // Photo 1: Scanned with 0 faces
        store.markMediaIndexed("content://media/photo1.jpg", 0, engineId)
        // Photo 2: Scanned with 2 faces
        store.markMediaIndexed("content://media/photo2.jpg", 2, engineId)
        // Photo 3: Failed scan
        store.markMediaFailed("content://media/photo3.jpg", engineId, "Error")

        val allUris = listOf("content://media/photo1.jpg", "content://media/photo2.jpg", "content://media/photo3.jpg")
        assertEquals(2, store.indexedMediaCount(allUris, engineId))
        assertEquals(1, store.failedMediaCount(allUris, engineId))
        // Critical: mediaWithFacesCount must ONLY count photos with face_count > 0, NOT photo1
        assertEquals(1, store.mediaWithFacesCount(allUris, engineId))
    }

    @Test
    fun testQueryEngineInfoDoesNotReturnPrematureErrorWhenNotInstalled() {
        val (buffaloName, buffaloFailure) = FaceIndexingRepository.queryEngineInfo(context, BuffaloLFaceEngineFactory)
        assertEquals(BuffaloLFaceEngineFactory.displayName, buffaloName)
        assertNull("Uninstalled model must not report a premature error", buffaloFailure)

        val (enteName, enteFailure) = FaceIndexingRepository.queryEngineInfo(context, EnteFaceEngineFactory)
        assertEquals(EnteFaceEngineFactory.displayName, enteName)
        assertNull("Uninstalled Ente model must not report a premature error", enteFailure)
    }

    @Test
    fun testFaceImageDecoderFromFileUri() {
        val testFile = java.io.File(context.cacheDir, "test_sample.png")
        val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.BLUE)
        testFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        val uri = android.net.Uri.fromFile(testFile)
        val decoded = FaceImageDecoder.decodeOrientedImage(context, uri, maxDimension = 400)
        assertNotNull("Image should be successfully decoded", decoded)
        assertEquals("Original width must be preserved", 800, decoded!!.originalWidth)
        assertEquals("Original height must be preserved", 600, decoded.originalHeight)
        assertTrue("Decoded width must be scaled down", decoded.bitmap.width <= 400)
        assertEquals(Bitmap.Config.ARGB_8888, decoded.bitmap.config)

        testFile.delete()
    }

    @Test
    fun testFaceImageDecoderNonExistentUriReturnsNull() {
        val fakeUri = android.net.Uri.parse("content://media/external/images/media/999999999")
        val decoded = FaceImageDecoder.decodeOrientedImage(context, fakeUri)
        assertNull("Non-existent URI should return null safely without throwing", decoded)
    }
}

