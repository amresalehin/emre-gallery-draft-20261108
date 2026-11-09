package com.amresalehin.emregallery

import android.content.Context
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.amresalehin.emregallery.face.EmbeddingProvenance
import com.amresalehin.emregallery.face.FaceClusterStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config
class FaceArchitectureTest {

    private lateinit var context: Context
    private lateinit var store: FaceClusterStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("face_index.db")
        store = FaceClusterStore(context)
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
    fun testEmbeddingProvenanceCompatibility() {
        val buffalo1 = EmbeddingProvenance("buffalo_l", "w600k_r50", 512, 1)
        val buffalo2 = EmbeddingProvenance("buffalo_l", "w600k_r50", 512, 1)
        val experimentalEngine = EmbeddingProvenance("experimental_engine", "v1.0", 512, 1)
        val wrongDimension = EmbeddingProvenance("buffalo_l", "w600k_r50", 256, 1)
        val wrongVersion = EmbeddingProvenance("buffalo_l", "w600k_r100", 512, 1)

        assertTrue("Identical Buffalo_L provenance must be compatible", buffalo1.isCompatibleWith(buffalo2))
        assertFalse("Buffalo_L must reject different engine ID", buffalo1.isCompatibleWith(experimentalEngine))
        assertFalse("Buffalo_L must reject mismatched dimension", buffalo1.isCompatibleWith(wrongDimension))
        assertFalse("Buffalo_L must reject mismatched model version", buffalo1.isCompatibleWith(wrongVersion))
    }

    @Test
    fun testSeparateEngineIndexingStatus() {
        val photoUri = "content://media/photo_multi_engine_1"
        val engineA = "buffalo_l"
        val engineB = "experimental_arcface_v2"

        // Initially neither engine has indexed the photo
        assertFalse("Engine A should report photo unindexed", store.isMediaIndexed(photoUri, engineA))
        assertFalse("Engine B should report photo unindexed", store.isMediaIndexed(photoUri, engineB))

        // Index under Engine A
        store.markMediaIndexed(photoUri, detectedFaces = 2, engineId = engineA)

        // Engine A sees done, Engine B still sees unindexed (cannot skip photo)
        assertTrue("Engine A should report photo indexed", store.isMediaIndexed(photoUri, engineA))
        assertFalse("Engine B must not skip photograph indexed only by Engine A", store.isMediaIndexed(photoUri, engineB))
        assertEquals(1, store.indexedMediaCount(engineA))
        assertEquals(0, store.indexedMediaCount(engineB))

        // Now index under Engine B independently
        store.markMediaIndexed(photoUri, detectedFaces = 3, engineId = engineB)
        assertTrue("Engine B should now report photo indexed", store.isMediaIndexed(photoUri, engineB))
        assertEquals(1, store.indexedMediaCount(engineB))
    }

    @Test
    fun testMultiEngineDataIsolation() {
        val photoUri = "content://media/photo_isolation_test"
        val engineA = "buffalo_l"
        val engineB = "experimental_engine"

        val embA = createSyntheticEmbedding(0, 0f, 512)
        val embB = createSyntheticEmbedding(1, 0f, 512)

        val provA = EmbeddingProvenance(engineId = engineA, modelVersion = "w600k_r50", dimension = 512)
        val provB = EmbeddingProvenance(engineId = engineB, modelVersion = "exp_v1", dimension = 512)

        store.addFace(
            mediaUri = photoUri,
            faceIndex = 0,
            rawEmbedding = embA,
            bounds = RectF(10f, 10f, 100f, 100f),
            provenance = provA
        )

        store.addFace(
            mediaUri = photoUri,
            faceIndex = 0,
            rawEmbedding = embB,
            bounds = RectF(12f, 12f, 98f, 98f),
            provenance = provB
        )

        // Faces queries must only return faces for the requested engine
        val facesA = store.facesForMedia(photoUri, engineId = engineA)
        val facesB = store.facesForMedia(photoUri, engineId = engineB)

        assertEquals("Engine A should have 1 detected face", 1, facesA.size)
        assertEquals("Engine B should have 1 detected face", 1, facesB.size)
        assertEquals(engineA, facesA.first().engineId)
        assertEquals(engineB, facesB.first().engineId)

        // People summaries must be partitioned by engine
        val peopleA = store.personSummaries(engineId = engineA)
        val peopleB = store.personSummaries(engineId = engineB)

        assertEquals(1, peopleA.size)
        assertEquals(1, peopleB.size)
        assertEquals(engineA, peopleA.first().engineId)
        assertEquals(engineB, peopleB.first().engineId)
        assertNotEquals("Clusters/People for different engines must have distinct identities", peopleA.first().id, peopleB.first().id)
    }

    @Test
    fun testEngineComparisonOnMedia() {
        val photoUri = "content://media/photo_compare_1"
        val engineA = "buffalo_l"
        val engineB = "experimental_detector"

        val provA = EmbeddingProvenance(engineId = engineA, modelVersion = "w600k_r50", dimension = 512)
        val provB = EmbeddingProvenance(engineId = engineB, modelVersion = "exp_v1", dimension = 512)

        val emb = createSyntheticEmbedding(0, 0f, 512)

        // Engine A detects 2 faces
        store.addFace(photoUri, 0, emb, bounds = RectF(10f, 10f, 60f, 60f), provenance = provA)
        store.addFace(photoUri, 1, emb, bounds = RectF(100f, 100f, 160f, 160f), provenance = provA)

        // Engine B detects 1 overlapping face and 1 different face
        store.addFace(photoUri, 0, emb, bounds = RectF(12f, 11f, 62f, 59f), provenance = provB) // Overlaps with 0
        store.addFace(photoUri, 1, emb, bounds = RectF(200f, 200f, 260f, 260f), provenance = provB) // New

        val comparison = store.compareEnginesOnMedia(photoUri, engineA, engineB)
        assertEquals(photoUri, comparison.mediaUri)
        assertEquals(2, comparison.facesA.size)
        assertEquals(2, comparison.facesB.size)
        assertEquals(1, comparison.matchingFaceCount)
    }

    @Test
    fun testFaceIngestionAndClustering() {
        val embAlice = createSyntheticEmbedding(0, 0f)
        val embAliceSimilar = createSyntheticEmbedding(0, 0.05f)
        val embBob = createSyntheticEmbedding(1, 0f)

        val cId1 = store.addFace(
            mediaUri = "content://media/1",
            faceIndex = 0,
            rawEmbedding = embAlice,
            bounds = RectF(10f, 10f, 100f, 100f),
            confidence = 0.95f,
            quality = 0.90f
        )
        val cId2 = store.addFace(
            mediaUri = "content://media/2",
            faceIndex = 0,
            rawEmbedding = embAliceSimilar,
            bounds = RectF(20f, 20f, 110f, 110f),
            confidence = 0.92f,
            quality = 0.88f
        )
        val cId3 = store.addFace(
            mediaUri = "content://media/3",
            faceIndex = 0,
            rawEmbedding = embBob,
            bounds = RectF(15f, 15f, 105f, 105f),
            confidence = 0.94f,
            quality = 0.89f
        )

        assertNotNull(cId1)
        assertNotNull(cId2)
        assertNotNull(cId3)

        // Similar faces (Alice) should be assigned to the same cluster
        assertEquals("Similar face embeddings must share cluster ID", cId1, cId2)
        // Distinct face (Bob) should be in a separate cluster
        assertNotEquals("Distinct face embeddings must have different cluster IDs", cId1, cId3)

        val people = store.personSummaries(includeHidden = false)
        assertEquals("Should have 2 distinct people", 2, people.size)
    }

    @Test
    fun testRejectionOfIncompatibleEmbeddings() {
        val invalidDimEmbedding = FloatArray(128) { 0.5f } // 128 instead of 512

        var exceptionThrown = false
        try {
            store.addFace(
                mediaUri = "content://media/invalid",
                faceIndex = 0,
                rawEmbedding = invalidDimEmbedding,
                bounds = RectF(0f, 0f, 50f, 50f)
            )
        } catch (e: IllegalArgumentException) {
            exceptionThrown = true
        }
        assertTrue("Store must throw IllegalArgumentException for wrong embedding dimensions", exceptionThrown)
    }

    @Test
    fun testPersonRenameAndNoUnknownLabel() {
        val emb = createSyntheticEmbedding(2, 0f)
        store.addFace(
            mediaUri = "content://media/p1",
            faceIndex = 0,
            rawEmbedding = emb,
            bounds = RectF(0f, 0f, 50f, 50f)
        )

        val peopleBefore = store.personSummaries(includeHidden = false)
        assertEquals(1, peopleBefore.size)
        val initialPerson = peopleBefore.first()
        assertFalse("Initial person should not be marked as named", initialPerson.isNamed)
        // Never force 'Unknown'
        assertNotEquals("Unknown", initialPerson.displayName)

        // Rename person
        store.renamePerson(initialPerson.id, "Elena Rostova")
        val peopleAfter = store.personSummaries(includeHidden = false)
        val renamedPerson = peopleAfter.first()
        assertTrue("Person should now be marked as named", renamedPerson.isNamed)
        assertEquals("Elena Rostova", renamedPerson.displayName)
    }

    @Test
    fun testMergePeople() {
        val emb1 = createSyntheticEmbedding(3, 0f)
        val emb2 = createSyntheticEmbedding(4, 0f)

        store.addFace("content://media/m1", 0, emb1, bounds = RectF(0f, 0f, 50f, 50f))
        store.addFace("content://media/m2", 0, emb2, bounds = RectF(0f, 0f, 50f, 50f))

        val peopleInitial = store.personSummaries(includeHidden = false)
        assertEquals(2, peopleInitial.size)

        val p1 = peopleInitial[0]
        val p2 = peopleInitial[1]
        store.renamePerson(p1.id, "Primary Person")

        // Merge p2 into p1
        val mergeSuccess = store.mergePeople(sourcePersonId = p2.id, targetPersonId = p1.id)
        assertTrue("Merge operation must succeed", mergeSuccess)

        val peopleAfterMerge = store.personSummaries(includeHidden = false)
        assertEquals("There should now be exactly 1 person", 1, peopleAfterMerge.size)
        val mergedPerson = peopleAfterMerge.first()
        assertEquals(p1.id, mergedPerson.id)
        assertEquals("Primary Person", mergedPerson.displayName)
        assertEquals("Faces from both persons should be united under target person", 2, mergedPerson.faceCount)
    }

    @Test
    fun testRemoveFaceFromPersonAndFeedbackExclusion() {
        val emb1 = createSyntheticEmbedding(5, 0f)
        val emb2 = createSyntheticEmbedding(5, 0.05f)

        store.addFace("content://media/ex1", 0, emb1, bounds = RectF(0f, 0f, 50f, 50f))
        store.addFace("content://media/ex2", 0, emb2, bounds = RectF(0f, 0f, 50f, 50f))

        val people = store.personSummaries(includeHidden = false)
        assertEquals(1, people.size)
        val person = people.first()
        assertEquals(2, person.faceCount)

        // Remove media/ex2 face from this person
        val removed = store.removeFaceFromPerson("content://media/ex2", 0)
        assertTrue("Remove face from person should succeed", removed)

        val peopleAfter = store.personSummaries(includeHidden = false)
        val originalPerson = peopleAfter.firstOrNull { it.id == person.id }
        assertNotNull(originalPerson)
        assertEquals("Original person should now only have 1 face", 1, originalPerson!!.faceCount)

        // The removed face is placed into a new independent person
        assertEquals("Total people count should now be 2", 2, peopleAfter.size)
    }

    @Test
    fun testHideAndRestorePerson() {
        val emb = createSyntheticEmbedding(6, 0f)
        store.addFace("content://media/hide1", 0, emb, bounds = RectF(0f, 0f, 50f, 50f))

        val person = store.personSummaries(includeHidden = true).first()
        assertFalse(person.isHidden)

        store.setPersonHidden(person.id, true)
        assertTrue(store.personSummaries(includeHidden = true).first().isHidden)
        assertEquals("Active people list should exclude hidden people", 0, store.personSummaries(includeHidden = false).size)

        store.setPersonHidden(person.id, false)
        assertFalse(store.personSummaries(includeHidden = true).first().isHidden)
        assertEquals("Active people list should include restored person", 1, store.personSummaries(includeHidden = false).size)
    }

    @Test
    fun testSafeNonDestructiveReclustering() {
        val embAlice1 = createSyntheticEmbedding(7, 0f)
        val embAlice2 = createSyntheticEmbedding(7, 0.05f)
        val embBob = createSyntheticEmbedding(8, 0f)

        store.addFace("content://media/rec1", 0, embAlice1, bounds = RectF(0f, 0f, 50f, 50f))
        store.addFace("content://media/rec2", 0, embAlice2, bounds = RectF(0f, 0f, 50f, 50f))
        store.addFace("content://media/rec3", 0, embBob, bounds = RectF(0f, 0f, 50f, 50f))

        val people = store.personSummaries(includeHidden = false)
        val alice = people.first { it.faceCount == 2 }
        store.renamePerson(alice.id, "Alice Smith")

        // Trigger safe non-destructive reclustering
        store.reclusterAllSafely()

        val peopleAfter = store.personSummaries(includeHidden = false)
        val preservedAlice = peopleAfter.firstOrNull { it.id == alice.id }
        assertNotNull("Named person identity must be preserved across reclustering", preservedAlice)
        assertEquals("Alice Smith", preservedAlice!!.displayName)
        assertTrue("Person must remain marked as named", preservedAlice.isNamed)
    }

    @Test
    fun testSplitClusterToNewPerson() {
        val emb = createSyntheticEmbedding(9, 0f)
        val clusterId = store.addFace("content://media/split1", 0, emb, bounds = RectF(0f, 0f, 50f, 50f))
        val peopleBefore = store.personSummaries(includeHidden = false)
        assertEquals(1, peopleBefore.size)

        val newPersonId = store.splitCluster(clusterId, "Splitted Person")
        assertNotNull(newPersonId)

        val peopleAfter = store.personSummaries(includeHidden = false)
        val splittedPerson = peopleAfter.firstOrNull { it.id == newPersonId }
        assertNotNull(splittedPerson)
        assertEquals("Splitted Person", splittedPerson!!.displayName)
        assertTrue(splittedPerson.isNamed)
    }
}
