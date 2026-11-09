package com.amresalehin.emregallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.face.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class EnteFacePipelineTest {

    private lateinit var context: Context
    private lateinit var store: FaceClusterStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("face_index.db")
        store = FaceClusterStore(context)
        // Ensure registry is initialized
        FaceEngineRegistry.register(BuffaloLFaceEngineFactory)
        FaceEngineRegistry.register(EnteFaceEngineFactory)
    }

    private fun createSyntheticEmbedding(personIndex: Int, noise: Float = 0f, dim: Int = 512): FloatArray {
        val array = FloatArray(dim)
        val start = (personIndex * 64) % dim
        val end = (start + 64).coerceAtMost(dim)
        for (i in start until end) {
            array[i] = 1.0f + (noise * 0.02f * ((i - start) % 5))
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
    fun testPersonPortraitUsesOneDeterministicMasterFace() {
        val firstUri = "file:///portrait-first.jpg"
        val bestUri = "file:///portrait-best.jpg"
        val embedding = createSyntheticEmbedding(0)

        store.addFace(
            mediaUri = firstUri,
            faceIndex = 0,
            rawEmbedding = embedding,
            bounds = RectF(10f, 10f, 40f, 45f),
            quality = 0.45f,
            provenance = EmbeddingProvenance.BUFFALO_L
        )
        store.addFace(
            mediaUri = bestUri,
            faceIndex = 2,
            rawEmbedding = embedding,
            bounds = RectF(20f, 15f, 60f, 65f),
            quality = 0.95f,
            provenance = EmbeddingProvenance.BUFFALO_L
        )

        val person = store.personSummaries(engineId = BuffaloLFaceEngine.ENGINE_ID).single()
        assertEquals(bestUri, person.portraitUri)
        assertEquals(2, person.portraitFaceIndex)

        val anotherAppearance = store.facesForMedia(firstUri, BuffaloLFaceEngine.ENGINE_ID).single()
        assertEquals(bestUri, anotherAppearance.portraitMediaUri)
        assertEquals(2, anotherAppearance.portraitFaceIndex)
    }

    @Test
    fun testCompletedGroupingStatePreventsUnnecessaryRegrouping() {
        val uri = "file:///grouping-state.jpg"
        store.addFace(
            mediaUri = uri,
            faceIndex = 0,
            rawEmbedding = createSyntheticEmbedding(0),
            bounds = RectF(10f, 10f, 40f, 45f),
            provenance = EmbeddingProvenance.BUFFALO_L
        )
        val engine = BuffaloLFaceEngine.ENGINE_ID
        val model = BuffaloLFaceEngine.MODEL_VERSION
        assertTrue(store.isGroupingRequired(engine, model, 1))
        store.markGroupingCompleted(engine, model, 1, store.personCount(engineId = engine))
        assertFalse(store.isGroupingRequired(engine, model, 1))
    }

    @Test
    fun testFaceEngineRegistryDeterministicLookup() {
        val buffalo = FaceEngineRegistry.getFactory(BuffaloLFaceEngine.ENGINE_ID)
        val ente = FaceEngineRegistry.getFactory(EnteFaceConstants.ENGINE_ID)
        val unknown = FaceEngineRegistry.getFactory("invalid_engine_id")

        assertNotNull("Buffalo_L engine factory must be registered", buffalo)
        assertEquals(BuffaloLFaceEngine.ENGINE_ID, buffalo!!.engineId)

        assertNotNull("Ente engine factory must be registered", ente)
        assertEquals(EnteFaceConstants.ENGINE_ID, ente!!.engineId)

        assertNull("Unregistered engine ID must return null without Buffalo_L fallback", unknown)
    }

    @Test
    fun testFaceEngineRegistryListing() {
        val engines = FaceEngineRegistry.listEngines().map { it.engineId }
        assertTrue("Registry must list buffalo_l", engines.contains(BuffaloLFaceEngine.ENGINE_ID))
        assertTrue("Registry must list ente_yolo5face_mobilefacenet", engines.contains(EnteFaceConstants.ENGINE_ID))
    }

    @Test
    fun testEnteProvenanceConstants() {
        val prov = EnteFaceConstants.PROVENANCE
        assertEquals(EnteFaceConstants.ENGINE_ID, prov.engineId)
        assertEquals(EnteFaceConstants.MODEL_VERSION, prov.modelVersion)
        assertEquals(EnteFaceConstants.EMBEDDING_DIMENSION, prov.dimension)
        assertEquals(EnteFaceConstants.FORMAT_VERSION, prov.formatVersion)
        assertTrue(prov.isCompatibleWith(prov))
    }

    @Test
    fun testFaceQualityMetricsComputation() {
        val goodLandmarks = floatArrayOf(
            35f, 40f, // Left eye
            75f, 40f, // Right eye
            55f, 60f, // Nose
            40f, 80f, // Left mouth
            70f, 80f  // Right mouth
        )
        val metrics = FaceQualityMetrics.compute(
            boundsWidth = 100f,
            boundsHeight = 100f,
            imageWidth = 1000,
            imageHeight = 1000,
            confidence = 0.95f,
            landmarks = goodLandmarks
        )
        assertTrue("Pose score should be positive for frontal landmarks", metrics.poseScore > 0.5f)
        assertTrue("Size ratio should be positive", metrics.sizeRatio > 0.005f)
        assertTrue("Overall quality should be within [0, 1]", metrics.overallQuality in 0.0f..1.0f)
    }

    @Test
    fun testSaveAndRetrieveFaceQualityMetricsV2() {
        val metrics = FaceQualityMetrics(
            blurScore = 0.85f,
            poseScore = 0.92f,
            sizeRatio = 0.05f,
            aspectRatio = 1.0f,
            overallQuality = 0.88f
        )
        val prov = EnteFaceConstants.PROVENANCE
        val emb = createSyntheticEmbedding(0)

        store.addFace(
            mediaUri = "content://media/ente_photo_1",
            faceIndex = 0,
            rawEmbedding = emb,
            bounds = RectF(10f, 10f, 100f, 100f),
            confidence = 0.95f,
            quality = 0.88f,
            provenance = prov,
            qualityMetrics = metrics
        )

        val retrieved = store.getFaceMetrics("content://media/ente_photo_1", 0, EnteFaceConstants.ENGINE_ID)
        assertNotNull("Face metrics must be stored in face_metrics_v2", retrieved)
        assertEquals(0.85f, retrieved!!.blurScore, 0.001f)
        assertEquals(0.92f, retrieved.poseScore, 0.001f)
        assertEquals(0.05f, retrieved.sizeRatio, 0.001f)
        assertEquals(0.88f, retrieved.overallQuality, 0.001f)
    }

    @Test
    fun testEnteGroupingIsIdempotentAndCleansOnlyOrphanPeople() {
        val engineId=EnteFaceConstants.ENGINE_ID
        val provenance=EnteFaceConstants.PROVENANCE
        val uris=listOf("content://media/idempotent_alice_1","content://media/idempotent_alice_2","content://media/idempotent_bob")
        store.addRawFaceObservation(uris[0],0,createSyntheticEmbedding(0),RectF(0f,0f,50f,50f),provenance=provenance)
        store.addRawFaceObservation(uris[1],0,createSyntheticEmbedding(0,0.02f),RectF(0f,0f,50f,50f),provenance=provenance)
        store.addRawFaceObservation(uris[2],0,createSyntheticEmbedding(1),RectF(0f,0f,50f,50f),provenance=provenance)
        val progress=mutableListOf<Pair<Int,Int>>()
        EnteFaceClusteringStrategy.reclusterChronologically(store,maxDistance=0.40f,onProgress={d,t->progress.add(d to t)})
        val before=uris.associateWith { uri -> store.facesForMedia(uri,engineId).single().let { it.personId to it.clusterId } }
        assertEquals(2,store.personSummaries(includeHidden=true,engineId=engineId).size)
        val now=System.currentTimeMillis()
        store.writableDatabase.execSQL(
            "INSERT INTO people (id,name,is_hidden,engine_id,created_at,updated_at) VALUES (?,'',0,?,?,?)",
            arrayOf("legacy-empty-person",engineId,now,now)
        )
        progress.clear()
        EnteFaceClusteringStrategy.reclusterChronologically(store,maxDistance=0.40f,onProgress={d,t->progress.add(d to t)})
        val after=uris.associateWith { uri -> store.facesForMedia(uri,engineId).single().let { it.personId to it.clusterId } }
        assertEquals("Unchanged groups must keep person/cluster IDs",before,after)
        assertEquals(2,store.personSummaries(includeHidden=true,engineId=engineId).size)
        val orphanCount=store.readableDatabase.rawQuery("SELECT COUNT(*) FROM people WHERE id='legacy-empty-person'",null)
            .use { c -> if(c.moveToFirst()) c.getInt(0) else -1 }
        assertEquals(0,orphanCount)
        assertEquals(3,progress.last().first)
        assertEquals(3,progress.last().second)
    }

    @Test
    fun testGroupingFailureCanBeRetriedWithoutNewPhotoIndexing() {
        val engineId=EnteFaceConstants.ENGINE_ID
        val version=EnteFaceConstants.MODEL_VERSION
        store.addRawFaceObservation("content://media/grouping_retry",0,createSyntheticEmbedding(0),
            RectF(0f,0f,50f,50f),provenance=EnteFaceConstants.PROVENANCE)
        assertTrue(store.isGroupingRequired(engineId,version,EnteFaceClusteringStrategy.strategyVersion))
        store.markGroupingCompleted(engineId,version,EnteFaceClusteringStrategy.strategyVersion,1,1)
        assertFalse(store.isGroupingRequired(engineId,version,EnteFaceClusteringStrategy.strategyVersion))
        store.markGroupingFailed(engineId,version,EnteFaceClusteringStrategy.strategyVersion,"test failure",1)
        assertTrue(store.isGroupingRequired(engineId,version,EnteFaceClusteringStrategy.strategyVersion))
    }

    @Test
    fun testFailedMediaLogMatchesCurrentLibraryScope() {
        val engineId = EnteFaceConstants.ENGINE_ID
        val inLibrary = "content://media/current_library_failure"
        val outsideLibrary = "content://media/other_library_failure"
        store.markMediaFailed(inLibrary, engineId, "current library error")
        store.markMediaFailed(outsideLibrary, engineId, "outside library error")

        val scopedUris = listOf(inLibrary)
        assertEquals(1, store.failedMediaCount(scopedUris, engineId))
        assertEquals(
            listOf(inLibrary to "current library error"),
            store.getFailedMedia(scopedUris, engineId)
        )
    }

    @Test
    fun testEnteLinearIncrementalClustering() {
        val prov = EnteFaceConstants.PROVENANCE
        val aliceEmb1 = createSyntheticEmbedding(0, 0f)
        val aliceEmb2 = createSyntheticEmbedding(0, 0.02f)
        val bobEmb = createSyntheticEmbedding(1, 0f)

        // Store raw observations for Ente pipeline
        store.addRawFaceObservation(
            mediaUri = "content://media/photo_early",
            faceIndex = 0,
            rawEmbedding = aliceEmb1,
            bounds = RectF(10f, 10f, 80f, 80f),
            confidence = 0.92f,
            quality = 0.85f,
            provenance = prov
        )
        store.addRawFaceObservation(
            mediaUri = "content://media/photo_later",
            faceIndex = 0,
            rawEmbedding = aliceEmb2,
            bounds = RectF(15f, 15f, 85f, 85f),
            confidence = 0.94f,
            quality = 0.90f,
            provenance = prov
        )
        store.addRawFaceObservation(
            mediaUri = "content://media/photo_bob",
            faceIndex = 0,
            rawEmbedding = bobEmb,
            bounds = RectF(20f, 20f, 90f, 90f),
            confidence = 0.88f,
            quality = 0.80f,
            provenance = prov
        )

        // Run linear incremental clustering
        val clusterCount = EnteFaceClusteringStrategy.reclusterChronologically(store, maxDistance = 0.45f)
        assertEquals("Should form 2 distinct clusters", 2, clusterCount)

        val people = store.personSummaries(engineId = EnteFaceConstants.ENGINE_ID)
        assertEquals(2, people.size)

        val alice = people.firstOrNull { it.faceCount == 2 }
        assertNotNull("Alice cluster should have 2 photos", alice)

        val bob = people.firstOrNull { it.faceCount == 1 }
        assertNotNull("Bob cluster should have 1 photo", bob)
    }

    @Test
    fun testEnteAmbiguityGeneratesReviewSuggestion() {
        val prov = EnteFaceConstants.PROVENANCE
        val embAlice = FloatArray(512) { 0f }.apply { this[0] = 1.0f }
        // Similarity = 0.58f -> Cosine distance = 1.0 - 0.58 = 0.42f (between 0.40 and 0.46)
        val embBorderline = FloatArray(512) { 0f }.apply {
            this[0] = 0.58f
            this[1] = kotlin.math.sqrt(1f - 0.58f * 0.58f)
        }

        store.addRawFaceObservation(
            mediaUri = "content://media/alice_base",
            faceIndex = 0,
            rawEmbedding = embAlice,
            bounds = RectF(10f, 10f, 90f, 90f),
            confidence = 0.95f,
            quality = 1.0f,
            provenance = prov
        )
        store.addRawFaceObservation(
            mediaUri = "content://media/alice_borderline",
            faceIndex = 0,
            rawEmbedding = embBorderline,
            bounds = RectF(12f, 12f, 92f, 92f),
            confidence = 0.95f,
            quality = 1.0f,
            provenance = prov
        )

        EnteFaceClusteringStrategy.reclusterChronologically(store, maxDistance = 0.40f)

        val people = store.personSummaries(engineId = EnteFaceConstants.ENGINE_ID)
        val alice = people.firstOrNull { it.sampleUri == "content://media/alice_base" }
        assertNotNull(alice)

        // Give Alice a name to enable review suggestions
        store.renamePerson(alice!!.id, "Alice")

        // Recluster: borderline match to named person generates a review suggestion
        EnteFaceClusteringStrategy.reclusterChronologically(store, maxDistance = 0.40f)

        val suggestions = store.getPendingSuggestions(engineId = EnteFaceConstants.ENGINE_ID)
        assertTrue("Borderline match to named person should generate review suggestion", suggestions.isNotEmpty())
        assertEquals("Alice", suggestions.first().suggestedPersonName)
    }

    @Test
    fun testSimilarityTransformAligner() {
        val dummyBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val landmarks = floatArrayOf(
            60f, 70f,
            140f, 70f,
            100f, 110f,
            75f, 150f,
            125f, 150f
        )
        val aligned = EnteFaceAligner.align(
            sourceBitmap = dummyBmp,
            landmarks = landmarks,
            bounds = RectF(40f, 40f, 160f, 170f)
        )
        assertNotNull("Aligned bitmap must not be null", aligned)
        assertEquals(EnteFaceConstants.RECOGNITION_SIZE, aligned.width)
        assertEquals(EnteFaceConstants.RECOGNITION_SIZE, aligned.height)
    }

    @Test
    fun testSimilarityTransformDegenerateFallback() {
        val dummyBmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        // Degenerate (all zero) landmarks must safely fall back to bounding box crop
        val degenerate = FloatArray(10) { 0f }
        val aligned = EnteFaceAligner.align(
            sourceBitmap = dummyBmp,
            landmarks = degenerate,
            bounds = RectF(10f, 10f, 90f, 90f)
        )
        assertNotNull("Degenerate landmarks must fall back to bounding box crop without crashing", aligned)
        assertEquals(EnteFaceConstants.RECOGNITION_SIZE, aligned.width)
        assertEquals(EnteFaceConstants.RECOGNITION_SIZE, aligned.height)
    }

    @Test
    fun testMultiEngineIsolationBetweenBuffaloLAndEnte() {
        val photo = "content://media/multi_engine_iso"
        val provBuffalo = EmbeddingProvenance(BuffaloLFaceEngine.ENGINE_ID, BuffaloLFaceEngine.MODEL_VERSION, 512)
        val provEnte = EnteFaceConstants.PROVENANCE

        val embBuffalo = createSyntheticEmbedding(0)
        val embEnte = createSyntheticEmbedding(1)

        store.addFace(mediaUri = photo, faceIndex = 0, rawEmbedding = embBuffalo, bounds = RectF(0f, 0f, 50f, 50f), provenance = provBuffalo)
        store.addFace(mediaUri = photo, faceIndex = 0, rawEmbedding = embEnte, bounds = RectF(5f, 5f, 55f, 55f), provenance = provEnte)

        assertEquals(1, store.personSummaries(engineId = BuffaloLFaceEngine.ENGINE_ID).size)
        assertEquals(1, store.personSummaries(engineId = EnteFaceConstants.ENGINE_ID).size)

        // Reset clusters on Ente only
        store.resetClustersOnly(EnteFaceConstants.ENGINE_ID)

        // Buffalo_L person/cluster should remain unaffected!
        assertEquals("Buffalo_L people must remain untouched when Ente clusters are reset", 1, store.personSummaries(engineId = BuffaloLFaceEngine.ENGINE_ID).size)
        // Ente people are removed
        assertEquals("Ente people should be reset", 0, store.personSummaries(engineId = EnteFaceConstants.ENGINE_ID).size)
        // But Ente faces are preserved
        assertEquals("Ente stored faces must be preserved", 1, store.indexedFaceCount(EnteFaceConstants.ENGINE_ID))
    }
}
