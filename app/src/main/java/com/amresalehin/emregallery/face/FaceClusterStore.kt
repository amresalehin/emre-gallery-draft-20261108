package com.amresalehin.emregallery.face

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.RectF
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Event bus for notifying UI observers when media face records or clusters change.
 */
object FaceStoreEvents {
    private val _mediaUpdates = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val mediaUpdates = _mediaUpdates.asSharedFlow()

    fun notifyMediaUpdated(uri: String) {
        _mediaUpdates.tryEmit(uri)
    }

    private val _peopleUpdates = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val peopleUpdates = _peopleUpdates.asSharedFlow()

    fun notifyPeopleUpdated() { _peopleUpdates.tryEmit(Unit) }

    fun notifyAllUpdated() {
        _mediaUpdates.tryEmit("")
        notifyPeopleUpdated()
    }
}

data class EmbeddingProvenance(
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID,
    val modelVersion: String = BuffaloLFaceEngine.MODEL_VERSION,
    val dimension: Int = BuffaloLFaceEngine.EMBEDDING_DIMENSION,
    val formatVersion: Int = BuffaloLFaceEngine.FORMAT_VERSION
) {
    fun isCompatibleWith(other: EmbeddingProvenance): Boolean {
        return engineId == other.engineId &&
               modelVersion == other.modelVersion &&
               dimension == other.dimension &&
               formatVersion == other.formatVersion
    }

    companion object {
        val BUFFALO_L = EmbeddingProvenance(
            engineId = BuffaloLFaceEngine.ENGINE_ID,
            modelVersion = BuffaloLFaceEngine.MODEL_VERSION,
            dimension = BuffaloLFaceEngine.EMBEDDING_DIMENSION,
            formatVersion = BuffaloLFaceEngine.FORMAT_VERSION
        )
        val BUFFALO_L_512 = BUFFALO_L
    }
}

data class PersonSummary(
    val id: String,
    val displayName: String,
    val faceCount: Int,
    val sampleUri: String?,
    val isHidden: Boolean = false,
    val isNamed: Boolean = false,
    val clusterCount: Int = 1,
    val updatedAt: Long = 0L,
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID,
    val portraitUri: String? = null,
    val portraitFaceIndex: Int? = null
)

data class PersonMediaIndex(
    val faceCount: Int,
    val mediaUris: Set<String>
)

data class FaceClusterSummary(
    val id: String,
    val displayName: String,
    val faceCount: Int,
    val sampleUri: String?
)

data class StoredFace(
    val mediaUri: String,
    val faceIndex: Int,
    val clusterId: String,
    val embedding: FloatArray,
    val personId: String? = null,
    val boxLeft: Float = 0f,
    val boxTop: Float = 0f,
    val boxRight: Float = 0f,
    val boxBottom: Float = 0f,
    val confidence: Float = 1f,
    val quality: Float = 1f,
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID,
    val modelVersion: String = BuffaloLFaceEngine.MODEL_VERSION
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoredFace) return false
        if (mediaUri != other.mediaUri) return false
        if (faceIndex != other.faceIndex) return false
        if (clusterId != other.clusterId) return false
        if (personId != other.personId) return false
        if (engineId != other.engineId) return false
        if (!embedding.contentEquals(other.embedding)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = mediaUri.hashCode()
        result = 31 * result + faceIndex
        result = 31 * result + clusterId.hashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + engineId.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

data class DetectedMediaFace(
    val mediaUri: String,
    val faceIndex: Int,
    val clusterId: String,
    val displayName: String,
    val personId: String? = null,
    val boxLeft: Float = 0f,
    val boxTop: Float = 0f,
    val boxRight: Float = 0f,
    val boxBottom: Float = 0f,
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID,
    val portraitMediaUri: String? = null,
    val portraitFaceIndex: Int? = null
)

data class ReviewSuggestion(
    val id: String,
    val faceId: String,
    val mediaUri: String,
    val faceIndex: Int,
    val suggestedPersonId: String,
    val suggestedPersonName: String,
    val distance: Float,
    val status: String = "pending",
    val engineId: String = BuffaloLFaceEngine.ENGINE_ID
)

data class EngineComparisonFace(
    val faceIndex: Int,
    val bounds: RectF,
    val confidence: Float,
    val quality: Float
)

data class EngineComparisonResult(
    val mediaUri: String,
    val engineA: String,
    val facesA: List<EngineComparisonFace>,
    val engineB: String,
    val facesB: List<EngineComparisonFace>,
    val matchingFaceCount: Int
)

/**
 * Modern, multi-engine face clustering and identity management store.
 *
 * Engines (production and experimental) operate with complete isolation:
 * - Engine-specific embeddings and model versions.
 * - Engine-partitioned face indexing status (indexing under one engine never causes another engine to skip a photo).
 * - Isolated clustering and people identities preventing experimental engines from corrupting production identities.
 * - Direct cross-engine comparison mechanism on shared photograph sets.
 */
class FaceClusterStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        FaceDbSchema.createAll(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        FaceDbSchema.upgrade(db, oldVersion, newVersion)
    }

    // --- Face Bounds Lookup ---

    @Synchronized
    fun getFaceBounds(mediaUri: String, faceIndex: Int, engineId: String = BuffaloLFaceEngine.ENGINE_ID): RectF? {
        return readableDatabase.rawQuery(
            "SELECT box_left, box_top, box_right, box_bottom FROM faces WHERE media_uri=? AND face_index=? AND engine_id=? LIMIT 1",
            arrayOf(mediaUri, faceIndex.toString(), engineId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val left = cursor.getFloat(0)
                val top = cursor.getFloat(1)
                val right = cursor.getFloat(2)
                val bottom = cursor.getFloat(3)
                if (right > left && bottom > top) RectF(left, top, right, bottom) else null
            } else null
        }
    }

    // --- Media Indexing Status Partitioned by Engine ---

    data class MediaIndexedInfo(
        val mediaUri: String,
        val engineId: String,
        val status: String,
        val faceCount: Int,
        val errorMessage: String?,
        val indexedAt: Long
    )

    @Synchronized
    fun isMediaIndexed(uri: String, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM indexed_media WHERE media_uri=? AND engine_id=? AND status='done' LIMIT 1",
            arrayOf(uri, engineId)
        ).use { it.moveToFirst() }

    @Synchronized
    fun getMediaIndexedInfo(uri: String, engineId: String = BuffaloLFaceEngine.ENGINE_ID): MediaIndexedInfo? {
        return readableDatabase.rawQuery(
            "SELECT media_uri, engine_id, status, face_count, error_message, indexed_at FROM indexed_media WHERE media_uri=? AND engine_id=? LIMIT 1",
            arrayOf(uri, engineId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val errorCol = cursor.getColumnIndex("error_message")
                val err = if (errorCol >= 0 && !cursor.isNull(errorCol)) cursor.getString(errorCol) else null
                MediaIndexedInfo(
                    mediaUri = cursor.getString(0),
                    engineId = cursor.getString(1),
                    status = cursor.getString(2),
                    faceCount = cursor.getInt(3),
                    errorMessage = err,
                    indexedAt = cursor.getLong(5)
                )
            } else null
        }
    }

    @Synchronized
    fun clearMediaFaces(mediaUri: String, engineId: String = BuffaloLFaceEngine.ENGINE_ID) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", "media_uri=? AND engine_id=?", arrayOf(mediaUri, engineId))
            db.delete("indexed_media", "media_uri=? AND engine_id=?", arrayOf(mediaUri, engineId))
            db.delete("review_suggestions", "media_uri=? AND engine_id=?", arrayOf(mediaUri, engineId))
            db.delete("face_metrics_v2", "media_uri=? AND engine_id=?", arrayOf(mediaUri, engineId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        FaceStoreEvents.notifyMediaUpdated(mediaUri)
    }

    @Synchronized
    fun getFailedMedia(engineId: String = BuffaloLFaceEngine.ENGINE_ID): List<Pair<String, String?>> {
        val list = mutableListOf<Pair<String, String?>>()
        readableDatabase.rawQuery(
            "SELECT media_uri, error_message FROM indexed_media WHERE engine_id=? AND status='failed'",
            arrayOf(engineId)
        ).use { cursor ->
            val errCol = cursor.getColumnIndex("error_message")
            while (cursor.moveToNext()) {
                val uri = cursor.getString(0)
                val err = if (errCol >= 0 && !cursor.isNull(errCol)) cursor.getString(errCol) else null
                list.add(uri to err)
            }
        }
        return list
    }

    /**
     * Returns failures scoped to the current media library, matching failedMediaCount(mediaUris, ...).
     */
    @Synchronized
    fun getFailedMedia(
        mediaUris: List<String>,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID
    ): List<Pair<String, String?>> {
        val uniqueUris = mediaUris.distinct()
        if (uniqueUris.isEmpty()) return emptyList()
        val list = mutableListOf<Pair<String, String?>>()
        for (batch in uniqueUris.chunked(800)) {
            val placeholders = batch.joinToString(",") { "?" }
            val args = arrayOf(engineId, *batch.toTypedArray())
            readableDatabase.rawQuery(
                "SELECT media_uri, error_message FROM indexed_media WHERE engine_id=? AND status='failed' AND media_uri IN ($placeholders)",
                args
            ).use { cursor ->
                val errorColumn = cursor.getColumnIndex("error_message")
                while (cursor.moveToNext()) {
                    val uri = cursor.getString(0)
                    val error = if (errorColumn >= 0 && !cursor.isNull(errorColumn)) cursor.getString(errorColumn) else null
                    list.add(uri to error)
                }
            }
        }
        return list
    }

    @Synchronized
    fun markMediaFailed(uri: String, engineId: String = BuffaloLFaceEngine.ENGINE_ID, errorMessage: String? = null) {
        val values = ContentValues().apply {
            put("media_uri", uri)
            put("engine_id", engineId)
            put("indexed_at", System.currentTimeMillis())
            put("face_count", 0)
            put("status", "failed")
            if (errorMessage != null) {
                put("error_message", errorMessage)
            }
        }
        writableDatabase.insertWithOnConflict("indexed_media", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        FaceStoreEvents.notifyMediaUpdated(uri)
    }

    @Synchronized
    fun markMediaIndexed(uri: String, detectedFaces: Int, engineId: String = BuffaloLFaceEngine.ENGINE_ID) {
        val values = ContentValues().apply {
            put("media_uri", uri)
            put("engine_id", engineId)
            put("indexed_at", System.currentTimeMillis())
            put("face_count", detectedFaces)
            put("status", "done")
            putNull("error_message")
        }
        writableDatabase.insertWithOnConflict("indexed_media", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        FaceStoreEvents.notifyMediaUpdated(uri)
    }

    @Synchronized
    fun indexedMediaCount(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='done'",
            arrayOf(engineId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun indexedMediaCount(mediaUris: List<String>, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int {
        val uniqueUris = mediaUris.distinct()
        if (uniqueUris.isEmpty()) return 0
        return uniqueUris.chunked(800).sumOf { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val args = arrayOf(engineId, *batch.toTypedArray())
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='done' AND media_uri IN ($placeholders)",
                args
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    @Synchronized
    fun mediaWithFacesCount(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='done' AND face_count > 0",
            arrayOf(engineId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun mediaWithFacesCount(mediaUris: List<String>, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int {
        val uniqueUris = mediaUris.distinct()
        if (uniqueUris.isEmpty()) return 0
        return uniqueUris.chunked(800).sumOf { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val args = arrayOf(engineId, *batch.toTypedArray())
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='done' AND face_count > 0 AND media_uri IN ($placeholders)",
                args
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    @Synchronized
    fun indexedFaceCount(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE engine_id=?", arrayOf(engineId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun getMediaUrisWithFaces(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Set<String> {
        val result = mutableSetOf<String>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT DISTINCT media_uri FROM faces WHERE engine_id=?",
                arrayOf(engineId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0))
                }
            }
            readableDatabase.rawQuery(
                "SELECT DISTINCT media_uri FROM indexed_media WHERE engine_id=? AND status='done' AND face_count > 0",
                arrayOf(engineId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getString(0))
                }
            }
        }
        return result
    }

    @Synchronized
    fun failedMediaCount(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='failed'",
            arrayOf(engineId)
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun failedMediaCount(mediaUris: List<String>, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int {
        val uniqueUris = mediaUris.distinct()
        if (uniqueUris.isEmpty()) return 0
        return uniqueUris.chunked(800).sumOf { batch ->
            val placeholders = batch.joinToString(",") { "?" }
            val args = arrayOf(engineId, *batch.toTypedArray())
            readableDatabase.rawQuery(
                "SELECT COUNT(*) FROM indexed_media WHERE engine_id=? AND status='failed' AND media_uri IN ($placeholders)",
                args
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    @Synchronized
    fun clusterCount(engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM clusters WHERE engine_id=?", arrayOf(engineId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized
    fun personCount(includeHidden: Boolean = false, engineId: String = BuffaloLFaceEngine.ENGINE_ID): Int {
        val clause = if (includeHidden) "WHERE engine_id=?" else "WHERE engine_id=? AND is_hidden = 0"
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM people $clause", arrayOf(engineId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // --- Face Ingestion & Clustering ---

    @Synchronized
    fun addFace(
        mediaUri: String,
        faceIndex: Int,
        rawEmbedding: FloatArray,
        maxDistance: Float = 0.60f,
        bounds: RectF? = null,
        confidence: Float = 1.0f,
        quality: Float = 1.0f,
        provenance: EmbeddingProvenance = EmbeddingProvenance.BUFFALO_L,
        qualityMetrics: FaceQualityMetrics? = null
    ): String {
        require(rawEmbedding.isNotEmpty()) { "Face embedding cannot be empty." }
        require(rawEmbedding.size == provenance.dimension) {
            "Embedding dimension mismatch: expected ${provenance.dimension}, got ${rawEmbedding.size}"
        }
        val embedding = normalize(rawEmbedding)
        val faceId = "${provenance.engineId}#${mediaUri}#${faceIndex}"
        val db = writableDatabase

        // 1. Check if observation already processed for this engine with current model version
        var priorClusterId: String? = null
        var priorPersonId: String? = null
        val existingRow = db.rawQuery(
            "SELECT cluster_id, person_id, model_version FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
            arrayOf(mediaUri, faceIndex.toString(), provenance.engineId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val cId = cursor.getString(0)
                val pId = if (cursor.isNull(1)) null else cursor.getString(1)
                val mVer = cursor.getString(2)
                Triple(cId, pId, mVer)
            } else null
        }
        if (existingRow != null) {
            priorClusterId = existingRow.first
            priorPersonId = existingRow.second
            if (existingRow.third == provenance.modelVersion) {
                return existingRow.first
            }
        }

        // 2. Check user feedback exclusions for this face in this engine
        val excludedPersonIds = mutableSetOf<String>()
        db.rawQuery(
            """SELECT target_id FROM face_feedback
               WHERE source_id=? AND engine_id=? AND feedback_type IN ('REJECTED_MATCH', 'REMOVE_FACE_FROM_PERSON')""",
            arrayOf(faceId, provenance.engineId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                excludedPersonIds.add(cursor.getString(0))
            }
        }

        // 3. Search best candidate among compatible clusters for this engine only
        var bestClusterId: String? = null
        var bestPersonId: String? = null
        var bestDistance = Float.POSITIVE_INFINITY
        var secondBestDistance = Float.POSITIVE_INFINITY

        db.rawQuery(
            """SELECT c.id, c.centroid, c.person_id
               FROM clusters c
               WHERE c.engine_id=? AND c.model_version=? AND c.embedding_dim=?""",
            arrayOf(provenance.engineId, provenance.modelVersion, provenance.dimension.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val clusterId = cursor.getString(0)
                val centroid = bytesToFloats(cursor.getBlob(1))
                val personId = if (cursor.isNull(2)) null else cursor.getString(2)

                if (personId != null && personId in excludedPersonIds) continue

                val distance = cosineDistance(embedding, centroid)
                if (distance < bestDistance) {
                    secondBestDistance = bestDistance
                    bestDistance = distance
                    bestClusterId = clusterId
                    bestPersonId = personId
                } else if (distance < secondBestDistance) {
                    secondBestDistance = distance
                }
            }
        }

        val effectiveThreshold = if (quality < 0.40f) maxDistance * 0.85f else maxDistance
        val isConfidentMatch = bestClusterId != null && bestDistance <= effectiveThreshold
        val isAmbiguous = isConfidentMatch && (secondBestDistance - bestDistance < 0.05f) && bestDistance > (effectiveThreshold * 0.8f)

        db.beginTransaction()
        try {
            val assignedClusterId: String
            val assignedPersonId: String

            if (priorClusterId != null) {
                assignedClusterId = priorClusterId
                assignedPersonId = priorPersonId ?: priorClusterId
            } else if (isConfidentMatch && !isAmbiguous && bestClusterId != null) {
                val clusterId = bestClusterId
                assignedClusterId = clusterId
                assignedPersonId = bestPersonId ?: clusterId
            } else {
                val newId = UUID.randomUUID().toString()
                assignedClusterId = newId
                assignedPersonId = newId

                val personValues = ContentValues().apply {
                    put("id", newId)
                    put("name", "")
                    put("cover_media_uri", mediaUri)
                    put("is_hidden", 0)
                    put("engine_id", provenance.engineId)
                    put("created_at", System.currentTimeMillis())
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertOrThrow("people", null, personValues)

                val clusterValues = ContentValues().apply {
                    put("id", newId)
                    put("person_id", newId)
                    put("display_name", "")
                    put("centroid", floatsToBytes(embedding))
                    put("embedding_dim", provenance.dimension)
                    put("engine_id", provenance.engineId)
                    put("model_version", provenance.modelVersion)
                    put("face_count", 0)
                    put("representative_face_id", faceId)
                    put("created_at", System.currentTimeMillis())
                    put("updated_at", System.currentTimeMillis())
                }
                db.insertOrThrow("clusters", null, clusterValues)

                if (bestPersonId != null && bestDistance <= maxDistance * 1.15f) {
                    val suggestionValues = ContentValues().apply {
                        put("id", UUID.randomUUID().toString())
                        put("face_id", faceId)
                        put("media_uri", mediaUri)
                        put("face_index", faceIndex)
                        put("engine_id", provenance.engineId)
                        put("suggested_person_id", bestPersonId)
                        put("distance", bestDistance)
                        put("status", "pending")
                        put("created_at", System.currentTimeMillis())
                    }
                    db.insertWithOnConflict("review_suggestions", null, suggestionValues, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }

            val faceValues = ContentValues().apply {
                put("media_uri", mediaUri)
                put("face_index", faceIndex)
                put("engine_id", provenance.engineId)
                put("face_id", faceId)
                put("cluster_id", assignedClusterId)
                put("person_id", assignedPersonId)
                put("embedding", floatsToBytes(embedding))
                put("model_version", provenance.modelVersion)
                put("embedding_dim", provenance.dimension)
                put("format_version", provenance.formatVersion)
                put("box_left", bounds?.left ?: 0f)
                put("box_top", bounds?.top ?: 0f)
                put("box_right", bounds?.right ?: 0f)
                put("box_bottom", bounds?.bottom ?: 0f)
                put("detection_confidence", confidence)
                put("quality_score", quality)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("faces", null, faceValues, SQLiteDatabase.CONFLICT_REPLACE)

            val metrics = qualityMetrics ?: FaceQualityMetrics(
                blurScore = quality,
                poseScore = quality,
                sizeRatio = if (bounds != null) (bounds.width() * bounds.height()) / 1_000_000f else 0.05f,
                aspectRatio = if (bounds != null && bounds.height() > 0) bounds.width() / bounds.height() else 0.85f,
                overallQuality = quality
            )
            saveFaceMetrics(db, mediaUri, faceIndex, provenance.engineId, metrics)

            updateCentroidIncremental(db, assignedClusterId, embedding, provenance.engineId, provenance.modelVersion)
            db.setTransactionSuccessful()
            return assignedClusterId
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Stores a raw detected face observation without immediately assigning a final cluster.
     * Used by modern incremental clustering pipelines.
     */
    @Synchronized
    fun addRawFaceObservation(
        mediaUri: String,
        faceIndex: Int,
        rawEmbedding: FloatArray,
        bounds: RectF? = null,
        confidence: Float = 1.0f,
        quality: Float = 1.0f,
        provenance: EmbeddingProvenance = EnteFaceConstants.PROVENANCE,
        qualityMetrics: FaceQualityMetrics? = null
    ) {
        require(rawEmbedding.isNotEmpty()) { "Face embedding cannot be empty." }
        require(rawEmbedding.size == provenance.dimension) {
            "Embedding dimension mismatch: expected ${provenance.dimension}, got ${rawEmbedding.size}"
        }
        val embedding = normalize(rawEmbedding)
        val faceId = "${provenance.engineId}#${mediaUri}#${faceIndex}"
        val db = writableDatabase

        db.beginTransaction()
        try {
            var existingClusterId: String? = null
            var existingPersonId: String? = null
            // Check if there was an existing face record with the same mediaUri and faceIndex
            db.rawQuery(
                "SELECT cluster_id, person_id FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
                arrayOf(mediaUri, faceIndex.toString(), provenance.engineId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    existingClusterId = if (cursor.isNull(0)) null else cursor.getString(0)
                    existingPersonId = if (cursor.isNull(1)) null else cursor.getString(1)
                }
            }

            // If not found at same index, check if an existing face in this photo overlaps spatially (IoU >= 0.40)
            if (existingPersonId == null && bounds != null) {
                db.rawQuery(
                    "SELECT cluster_id, person_id, box_left, box_top, box_right, box_bottom FROM faces WHERE media_uri=? AND engine_id=? AND person_id IS NOT NULL",
                    arrayOf(mediaUri, provenance.engineId)
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val prevBounds = RectF(cursor.getFloat(2), cursor.getFloat(3), cursor.getFloat(4), cursor.getFloat(5))
                        if (calculateIoU(bounds, prevBounds) >= 0.40f) {
                            existingClusterId = cursor.getString(0)
                            existingPersonId = cursor.getString(1)
                            break
                        }
                    }
                }
            }

            val faceValues = ContentValues().apply {
                put("media_uri", mediaUri)
                put("face_index", faceIndex)
                put("engine_id", provenance.engineId)
                put("face_id", faceId)
                if (existingClusterId != null) put("cluster_id", existingClusterId) else putNull("cluster_id")
                if (existingPersonId != null) put("person_id", existingPersonId) else putNull("person_id")
                put("embedding", floatsToBytes(embedding))
                put("model_version", provenance.modelVersion)
                put("embedding_dim", provenance.dimension)
                put("format_version", provenance.formatVersion)
                put("box_left", bounds?.left ?: 0f)
                put("box_top", bounds?.top ?: 0f)
                put("box_right", bounds?.right ?: 0f)
                put("box_bottom", bounds?.bottom ?: 0f)
                put("detection_confidence", confidence)
                put("quality_score", quality)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("faces", null, faceValues, SQLiteDatabase.CONFLICT_REPLACE)

            val metrics = qualityMetrics ?: FaceQualityMetrics(
                blurScore = quality,
                poseScore = quality,
                sizeRatio = if (bounds != null) (bounds.width() * bounds.height()) / 1_000_000f else 0.05f,
                aspectRatio = if (bounds != null && bounds.height() > 0) bounds.width() / bounds.height() else 0.85f,
                overallQuality = quality
            )
            saveFaceMetrics(db, mediaUri, faceIndex, provenance.engineId, metrics)
            val groupingPending = ContentValues().apply {
                put("engine_id", provenance.engineId)
                put("model_version", provenance.modelVersion)
                put("strategy_version", -1)
                put("status", "pending")
                put("grouped_people", 0)
                put("face_count", -1)
                putNull("error_message")
                put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("grouping_state", null, groupingPending, SQLiteDatabase.CONFLICT_REPLACE)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun saveFaceMetrics(
        mediaUri: String,
        faceIndex: Int,
        engineId: String,
        metrics: FaceQualityMetrics
    ) {
        saveFaceMetrics(writableDatabase, mediaUri, faceIndex, engineId, metrics)
    }

    private fun saveFaceMetrics(
        db: SQLiteDatabase,
        mediaUri: String,
        faceIndex: Int,
        engineId: String,
        metrics: FaceQualityMetrics
    ) {
        val vals = ContentValues().apply {
            put("media_uri", mediaUri)
            put("face_index", faceIndex)
            put("engine_id", engineId)
            put("blur_score", metrics.blurScore)
            put("pose_score", metrics.poseScore)
            put("size_ratio", metrics.sizeRatio)
            put("aspect_ratio", metrics.aspectRatio)
            put("overall_quality", metrics.overallQuality)
            put("version", metrics.version)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("face_metrics_v2", null, vals, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getFaceMetrics(
        mediaUri: String,
        faceIndex: Int,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID
    ): FaceQualityMetrics? {
        return readableDatabase.rawQuery(
            """SELECT blur_score, pose_score, size_ratio, aspect_ratio, overall_quality, version
               FROM face_metrics_v2
               WHERE media_uri=? AND face_index=? AND engine_id=? LIMIT 1""",
            arrayOf(mediaUri, faceIndex.toString(), engineId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                FaceQualityMetrics(
                    blurScore = cursor.getFloat(0),
                    poseScore = cursor.getFloat(1),
                    sizeRatio = cursor.getFloat(2),
                    aspectRatio = cursor.getFloat(3),
                    overallQuality = cursor.getFloat(4),
                    version = cursor.getInt(5)
                )
            } else null
        }
    }

    // --- People & Identity Management ---

    @Synchronized
    fun renamePerson(personId: String, name: String) {
        if (personId.isBlank()) return
        val cleanName = name.trim().take(80)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            var engineId = BuffaloLFaceEngine.ENGINE_ID
            db.rawQuery("SELECT engine_id FROM people WHERE id=?", arrayOf(personId)).use {
                if (it.moveToFirst() && !it.isNull(0)) engineId = it.getString(0)
            }

            val personVals = ContentValues().apply {
                put("name", cleanName)
                put("updated_at", now)
            }
            db.update("people", personVals, "id=?", arrayOf(personId))

            val clusterVals = ContentValues().apply {
                put("display_name", cleanName)
                put("updated_at", now)
            }
            db.update("clusters", clusterVals, "person_id=?", arrayOf(personId))

            recordFeedback(db, "RENAME_PERSON", personId, cleanName, engineId = engineId)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    @Synchronized
    fun renameCluster(clusterId: String, name: String) {
        val personId = getPersonIdForCluster(clusterId) ?: clusterId
        renamePerson(personId, name)
    }

    @Synchronized
    fun mergePeople(sourcePersonId: String, targetPersonId: String): Boolean {
        if (sourcePersonId == targetPersonId) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val sourceName = getPersonName(db, sourcePersonId)
            val targetName = getPersonName(db, targetPersonId)
            val finalName = if (targetName.isNotBlank()) targetName else sourceName
            val now = System.currentTimeMillis()

            var engineId = BuffaloLFaceEngine.ENGINE_ID
            db.rawQuery("SELECT engine_id FROM people WHERE id=?", arrayOf(targetPersonId)).use {
                if (it.moveToFirst() && !it.isNull(0)) engineId = it.getString(0)
            }

            val clusterVals = ContentValues().apply {
                put("person_id", targetPersonId)
                put("display_name", finalName)
                put("updated_at", now)
            }
            db.update("clusters", clusterVals, "person_id=?", arrayOf(sourcePersonId))

            val faceVals = ContentValues().apply {
                put("person_id", targetPersonId)
            }
            db.update("faces", faceVals, "person_id=?", arrayOf(sourcePersonId))

            val targetVals = ContentValues().apply {
                put("name", finalName)
                put("updated_at", now)
            }
            db.update("people", targetVals, "id=?", arrayOf(targetPersonId))

            recordFeedback(db, "MERGE_PEOPLE", sourcePersonId, targetPersonId, engineId = engineId)
            db.delete("people", "id=?", arrayOf(sourcePersonId))
            db.delete("review_suggestions", "suggested_person_id=?", arrayOf(sourcePersonId))

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    @Synchronized
    fun splitCluster(clusterId: String, newPersonName: String = ""): String {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val newPersonId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            var engineId = BuffaloLFaceEngine.ENGINE_ID
            db.rawQuery("SELECT engine_id FROM clusters WHERE id=?", arrayOf(clusterId)).use {
                if (it.moveToFirst() && !it.isNull(0)) engineId = it.getString(0)
            }

            val personVals = ContentValues().apply {
                put("id", newPersonId)
                put("name", newPersonName.trim().take(80))
                put("is_hidden", 0)
                put("engine_id", engineId)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insertOrThrow("people", null, personVals)

            val clusterVals = ContentValues().apply {
                put("person_id", newPersonId)
                put("display_name", newPersonName.trim().take(80))
                put("updated_at", now)
            }
            db.update("clusters", clusterVals, "id=?", arrayOf(clusterId))

            val faceVals = ContentValues().apply {
                put("person_id", newPersonId)
            }
            db.update("faces", faceVals, "cluster_id=?", arrayOf(clusterId))

            recordFeedback(db, "SPLIT_CLUSTER", clusterId, newPersonId, engineId = engineId)
            db.setTransactionSuccessful()
            return newPersonId
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    @Synchronized
    fun removeFaceFromPerson(
        mediaUri: String,
        faceIndex: Int,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID
    ): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var oldClusterId: String? = null
            var oldPersonId: String? = null

            db.rawQuery(
                "SELECT cluster_id, person_id FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
                arrayOf(mediaUri, faceIndex.toString(), engineId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    oldClusterId = cursor.getString(0)
                    oldPersonId = if (cursor.isNull(1)) null else cursor.getString(1)
                }
            }

            if (oldClusterId == null) return false

            val faceId = "${engineId}#${mediaUri}#${faceIndex}"
            val newClusterId = UUID.randomUUID().toString()
            val newPersonId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val personVals = ContentValues().apply {
                put("id", newPersonId)
                put("name", "")
                put("cover_media_uri", mediaUri)
                put("is_hidden", 0)
                put("engine_id", engineId)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insertOrThrow("people", null, personVals)

            var embBlob: ByteArray? = null
            var modelVersion = BuffaloLFaceEngine.MODEL_VERSION
            var dim = BuffaloLFaceEngine.EMBEDDING_DIMENSION
            db.rawQuery(
                "SELECT embedding, model_version, embedding_dim FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
                arrayOf(mediaUri, faceIndex.toString(), engineId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    embBlob = cursor.getBlob(0)
                    modelVersion = cursor.getString(1).orEmpty()
                    dim = cursor.getInt(2)
                }
            }

            val clusterVals = ContentValues().apply {
                put("id", newClusterId)
                put("person_id", newPersonId)
                put("display_name", "")
                put("centroid", embBlob ?: ByteArray(0))
                put("embedding_dim", dim)
                put("engine_id", engineId)
                put("model_version", modelVersion)
                put("face_count", 1)
                put("created_at", now)
                put("updated_at", now)
            }
            db.insertOrThrow("clusters", null, clusterVals)

            val faceVals = ContentValues().apply {
                put("cluster_id", newClusterId)
                put("person_id", newPersonId)
            }
            db.update("faces", faceVals, "media_uri=? AND face_index=? AND engine_id=?", arrayOf(mediaUri, faceIndex.toString(), engineId))

            if (oldPersonId != null) {
                recordFeedback(db, "REMOVE_FACE_FROM_PERSON", faceId, oldPersonId, engineId = engineId)
            }
            if (oldClusterId != null) {
                recomputeCentroid(db, oldClusterId, engineId, modelVersion)
            }

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    @Synchronized
    fun setPersonHidden(personId: String, isHidden: Boolean) {
        val vals = ContentValues().apply {
            put("is_hidden", if (isHidden) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update("people", vals, "id=?", arrayOf(personId))
        FaceStoreEvents.notifyAllUpdated()
    }

    @Synchronized
    fun setPersonCover(personId: String, mediaUri: String) {
        val db = writableDatabase
        val selectedFaceId = db.rawQuery(
            """SELECT face_id FROM faces
               WHERE person_id=? AND media_uri=?
               ORDER BY quality_score DESC, detection_confidence DESC,
                        ((box_right - box_left) * (box_bottom - box_top)) DESC,
                        face_index ASC
               LIMIT 1""".trimIndent(),
            arrayOf(personId, mediaUri)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        val vals = ContentValues().apply {
            put("cover_media_uri", mediaUri)
            // A cover photo is not necessarily a detected face. Only replace the stable
            // representative-face ID if that photo contains a face for this person.
            if (selectedFaceId != null) put("cover_face_id", selectedFaceId)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("people", vals, "id=?", arrayOf(personId))
        FaceStoreEvents.notifyAllUpdated()
    }



    @Synchronized
    fun unassignedFaceCount(engineId: String, modelVersion: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM faces WHERE engine_id=? AND model_version=? AND person_id IS NULL",
            arrayOf(engineId, modelVersion)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun faceEmbeddingCount(engineId: String, modelVersion: String): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM faces WHERE engine_id=? AND model_version=?", arrayOf(engineId, modelVersion))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun isGroupingRequired(engineId: String, modelVersion: String, strategyVersion: Int): Boolean {
        val count = faceEmbeddingCount(engineId, modelVersion)
        if (count == 0) return false
        return readableDatabase.rawQuery(
            "SELECT status, strategy_version, face_count FROM grouping_state WHERE engine_id=? AND model_version=? LIMIT 1",
            arrayOf(engineId, modelVersion)
        ).use { cursor ->
            !cursor.moveToFirst() || cursor.getString(0) != "completed" ||
                cursor.getInt(1) != strategyVersion || cursor.getInt(2) != count
        }
    }

    @Synchronized
    fun groupingFailure(engineId: String, modelVersion: String, strategyVersion: Int): String? =
        readableDatabase.rawQuery(
            "SELECT error_message FROM grouping_state WHERE engine_id=? AND model_version=? AND strategy_version=? AND status='failed' LIMIT 1",
            arrayOf(engineId, modelVersion, strategyVersion.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "Grouping failed"
        }

    @Synchronized
    fun markGroupingPending(engineId: String, modelVersion: String, strategyVersion: Int,
        faceCount: Int = faceEmbeddingCount(engineId, modelVersion)
    ) = saveGroupingState(engineId, modelVersion, strategyVersion, "pending", 0, faceCount, null)

    @Synchronized
    fun markGroupingCompleted(engineId: String, modelVersion: String, strategyVersion: Int, groupedPeople: Int,
        faceCount: Int = faceEmbeddingCount(engineId, modelVersion)
    ) = saveGroupingState(engineId, modelVersion, strategyVersion, "completed", groupedPeople, faceCount, null)

    @Synchronized
    fun markGroupingFailed(engineId: String, modelVersion: String, strategyVersion: Int, errorMessage: String,
        faceCount: Int = faceEmbeddingCount(engineId, modelVersion)
    ) = saveGroupingState(engineId, modelVersion, strategyVersion, "failed", 0, faceCount, errorMessage)

    private fun saveGroupingState(engineId: String, modelVersion: String, strategyVersion: Int, status: String,
        groupedPeople: Int, faceCount: Int, errorMessage: String?
    ) {
        val values = ContentValues().apply {
            put("engine_id", engineId); put("model_version", modelVersion)
            put("strategy_version", strategyVersion); put("status", status)
            put("grouped_people", groupedPeople); put("face_count", faceCount)
            if (errorMessage == null) putNull("error_message") else put("error_message", errorMessage)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("grouping_state", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Remove only empty unnamed people without a cluster, face, or manual-feedback reference. */
    internal fun pruneEmptyAutoPeople(db: SQLiteDatabase, engineId: String): Int =
        db.delete("people",
            """engine_id=? AND COALESCE(TRIM(name), '')='' AND is_hidden=0
               AND NOT EXISTS (SELECT 1 FROM faces f WHERE f.engine_id=people.engine_id AND f.person_id=people.id)
               AND NOT EXISTS (SELECT 1 FROM clusters c WHERE c.engine_id=people.engine_id AND c.person_id=people.id)
               AND NOT EXISTS (SELECT 1 FROM face_feedback fb WHERE fb.engine_id=people.engine_id
                   AND (fb.source_id=people.id OR fb.target_id=people.id))""".trimIndent(),
            arrayOf(engineId))

    /**
     * Keep a single representative face for each person. Explicit cover selection sets
     * cover_face_id directly; otherwise choose the highest-quality observation deterministically.
     */
    @Synchronized
    fun ensurePersonCovers(engineId: String) {
        val db = writableDatabase
        db.execSQL(
            """UPDATE people
               SET cover_face_id = (
                   SELECT best_face.face_id
                   FROM faces best_face
                   WHERE best_face.person_id = people.id
                     AND best_face.engine_id = people.engine_id
                   ORDER BY best_face.quality_score DESC,
                            best_face.detection_confidence DESC,
                            ((best_face.box_right - best_face.box_left) *
                             (best_face.box_bottom - best_face.box_top)) DESC,
                            best_face.media_uri ASC, best_face.face_index ASC
                   LIMIT 1
               )
               WHERE people.engine_id = ?
                 AND EXISTS (
                    SELECT 1 FROM faces f
                    WHERE f.person_id = people.id AND f.engine_id = people.engine_id
                 )
                 AND (
                    people.cover_face_id IS NULL OR
                    NOT EXISTS (
                        SELECT 1 FROM faces current_face
                        WHERE current_face.face_id = people.cover_face_id
                          AND current_face.person_id = people.id
                          AND current_face.engine_id = people.engine_id
                    )
                 )""".trimIndent(),
            arrayOf(engineId)
        )
    }

    // --- Query APIs ---

    @Synchronized
    fun personSummaries(
        includeHidden: Boolean = false,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID
    ): List<PersonSummary> {
        ensurePersonCovers(engineId)
        val result = mutableListOf<PersonSummary>()
        val hiddenClause = if (includeHidden) "WHERE p.engine_id = ?" else "WHERE p.engine_id = ? AND p.is_hidden = 0"
        readableDatabase.rawQuery(
            """SELECT p.id, p.name,
                      COUNT(DISTINCT f.media_uri || '#' || f.face_index) AS face_count,
                      COALESCE(NULLIF(p.cover_media_uri, ''),
                          (SELECT cover.media_uri FROM faces cover
                           WHERE cover.face_id = p.cover_face_id AND cover.person_id = p.id
                             AND cover.engine_id = p.engine_id LIMIT 1),
                          (SELECT f2.media_uri FROM faces f2
                           WHERE f2.person_id = p.id AND f2.engine_id = p.engine_id
                           ORDER BY f2.quality_score DESC, f2.detection_confidence DESC,
                                    ((f2.box_right - f2.box_left) * (f2.box_bottom - f2.box_top)) DESC,
                                    f2.media_uri ASC, f2.face_index ASC LIMIT 1)
                      ) AS sample_uri,
                      COALESCE(
                          (SELECT portrait.media_uri FROM faces portrait
                           WHERE portrait.face_id = p.cover_face_id AND portrait.person_id = p.id
                             AND portrait.engine_id = p.engine_id LIMIT 1),
                          (SELECT f2.media_uri FROM faces f2
                           WHERE f2.person_id = p.id AND f2.engine_id = p.engine_id
                           ORDER BY f2.quality_score DESC, f2.detection_confidence DESC,
                                    ((f2.box_right - f2.box_left) * (f2.box_bottom - f2.box_top)) DESC,
                                    f2.media_uri ASC, f2.face_index ASC LIMIT 1)
                      ) AS portrait_uri,
                      COALESCE(
                          (SELECT portrait.face_index FROM faces portrait
                           WHERE portrait.face_id = p.cover_face_id AND portrait.person_id = p.id
                             AND portrait.engine_id = p.engine_id LIMIT 1),
                          (SELECT f2.face_index FROM faces f2
                           WHERE f2.person_id = p.id AND f2.engine_id = p.engine_id
                           ORDER BY f2.quality_score DESC, f2.detection_confidence DESC,
                                    ((f2.box_right - f2.box_left) * (f2.box_bottom - f2.box_top)) DESC,
                                    f2.media_uri ASC, f2.face_index ASC LIMIT 1)
                      ) AS portrait_face_index,
                      p.is_hidden,
                      COUNT(DISTINCT c.id) AS cluster_count,
                      p.updated_at,
                      p.engine_id
               FROM people p
               LEFT JOIN clusters c ON c.person_id = p.id AND c.engine_id = p.engine_id
               LEFT JOIN faces f ON f.person_id = p.id AND f.engine_id = p.engine_id
               $hiddenClause
               GROUP BY p.id
               HAVING COUNT(DISTINCT f.media_uri || '#' || f.face_index) > 0
               ORDER BY face_count DESC, p.updated_at DESC""".trimIndent(),
            arrayOf(engineId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val rawName = cursor.getString(1).orEmpty()
                val faceCount = cursor.getInt(2)
                val sampleUri = if (cursor.isNull(3)) null else cursor.getString(3)
                val portraitUri = if (cursor.isNull(4)) null else cursor.getString(4)
                val portraitFaceIndex = if (cursor.isNull(5)) null else cursor.getInt(5)
                val isHidden = cursor.getInt(6) == 1
                val clusterCount = cursor.getInt(7)
                val updatedAt = cursor.getLong(8)
                val engId = cursor.getString(9).orEmpty()
                val isNamed = rawName.isNotBlank() && !rawName.equals("Unknown", ignoreCase = true)
                result += PersonSummary(
                    id = id,
                    displayName = rawName,
                    faceCount = faceCount,
                    sampleUri = sampleUri,
                    isHidden = isHidden,
                    isNamed = isNamed,
                    clusterCount = clusterCount,
                    updatedAt = updatedAt,
                    engineId = engId,
                    portraitUri = portraitUri,
                    portraitFaceIndex = portraitFaceIndex
                )
            }
        }
        return result
    }

    @Synchronized
    fun summaries(engineId: String = BuffaloLFaceEngine.ENGINE_ID): List<FaceClusterSummary> {
        val people = personSummaries(includeHidden = false, engineId = engineId)
        return people.map { p ->
            FaceClusterSummary(
                id = p.id,
                displayName = p.displayName,
                faceCount = p.faceCount,
                sampleUri = p.sampleUri
            )
        }
    }


    /** Lightweight per-person photo counts/URIs; never reads embedding BLOBs. */
    @Synchronized
    fun personMediaIndex(engineId: String, mediaUris: Collection<String>): Map<String, PersonMediaIndex> {
        if (mediaUris.isEmpty()) return emptyMap()
        val counts = linkedMapOf<String, Int>()
        val uris = linkedMapOf<String, MutableSet<String>>()
        for (chunk in mediaUris.toSet().toList().chunked(800)) {
            val marks = List(chunk.size) { "?" }.joinToString(",")
            readableDatabase.rawQuery(
                """SELECT person_id, media_uri, COUNT(*) FROM faces
                   WHERE engine_id=? AND person_id IS NOT NULL AND media_uri IN ($marks)
                   GROUP BY person_id, media_uri""".trimIndent(),
                arrayOf(engineId, *chunk.toTypedArray())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val personId = cursor.getString(0) ?: continue
                    val uri = cursor.getString(1) ?: continue
                    counts[personId] = (counts[personId] ?: 0) + cursor.getInt(2)
                    uris.getOrPut(personId) { linkedSetOf() }.add(uri)
                }
            }
        }
        return counts.mapValues { (id, count) -> PersonMediaIndex(count, uris[id].orEmpty().toSet()) }
    }

    @Synchronized
    fun facesForCluster(clusterOrPersonId: String, engineId: String? = null): List<StoredFace> {
        val result = mutableListOf<StoredFace>()
        val engineClause = if (engineId != null) "AND engine_id=?" else ""
        val args = if (engineId != null) arrayOf(clusterOrPersonId, clusterOrPersonId, engineId)
            else arrayOf(clusterOrPersonId, clusterOrPersonId)

        readableDatabase.rawQuery(
            """SELECT media_uri, face_index, cluster_id, embedding, person_id,
                      box_left, box_top, box_right, box_bottom, detection_confidence,
                      quality_score, engine_id, model_version
               FROM faces
               WHERE (person_id=? OR cluster_id=?) $engineClause""",
            args
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += StoredFace(
                    mediaUri = cursor.getString(0),
                    faceIndex = cursor.getInt(1),
                    clusterId = cursor.getString(2).orEmpty(),
                    embedding = bytesToFloats(cursor.getBlob(3)),
                    personId = if (cursor.isNull(4)) null else cursor.getString(4),
                    boxLeft = cursor.getFloat(5),
                    boxTop = cursor.getFloat(6),
                    boxRight = cursor.getFloat(7),
                    boxBottom = cursor.getFloat(8),
                    confidence = cursor.getFloat(9),
                    quality = cursor.getFloat(10),
                    engineId = cursor.getString(11).orEmpty(),
                    modelVersion = cursor.getString(12).orEmpty()
                )
            }
        }
        return result
    }

    @Synchronized
    fun facesForMedia(mediaUri: String, engineId: String = BuffaloLFaceEngine.ENGINE_ID): List<DetectedMediaFace> {
        ensurePersonCovers(engineId)
        val result = mutableListOf<DetectedMediaFace>()
        readableDatabase.rawQuery(
            """SELECT f.media_uri, f.face_index, f.cluster_id, COALESCE(NULLIF(TRIM(p.name), ''), ''),
                      COALESCE(f.person_id, c.person_id, NULLIF(f.cluster_id, '')), f.box_left, f.box_top, f.box_right, f.box_bottom, f.engine_id,
                      portrait.media_uri, portrait.face_index
               FROM faces f
               LEFT JOIN clusters c ON f.cluster_id = c.id AND f.engine_id = c.engine_id
               LEFT JOIN people p ON COALESCE(f.person_id, c.person_id, NULLIF(f.cluster_id, '')) = p.id AND f.engine_id = p.engine_id
               LEFT JOIN faces portrait ON portrait.face_id = p.cover_face_id
                    AND portrait.person_id = p.id AND portrait.engine_id = p.engine_id
               WHERE f.media_uri = ? AND f.engine_id = ?
               ORDER BY f.face_index""".trimIndent(),
            arrayOf(mediaUri, engineId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val boxLeft = cursor.getFloat(5)
                val boxTop = cursor.getFloat(6)
                val boxRight = cursor.getFloat(7)
                val boxBottom = cursor.getFloat(8)
                if (boxRight <= boxLeft || boxBottom <= boxTop) continue
                result += DetectedMediaFace(
                    mediaUri = cursor.getString(0),
                    faceIndex = cursor.getInt(1),
                    clusterId = cursor.getString(2).orEmpty(),
                    displayName = cursor.getString(3).orEmpty().trim(),
                    personId = if (cursor.isNull(4)) null else cursor.getString(4),
                    boxLeft = boxLeft,
                    boxTop = boxTop,
                    boxRight = boxRight,
                    boxBottom = boxBottom,
                    engineId = cursor.getString(9).orEmpty(),
                    portraitMediaUri = if (cursor.isNull(10)) null else cursor.getString(10),
                    portraitFaceIndex = if (cursor.isNull(11)) null else cursor.getInt(11)
                )
            }
        }
        return result
    }

    // --- Cross-Engine Detection Comparison Mechanism ---

    @Synchronized
    fun findCommonIndexedMediaUri(
        engineA: String = BuffaloLFaceEngine.ENGINE_ID,
        engineB: String = EnteFaceConstants.ENGINE_ID
    ): String? {
        val sql = """
            SELECT m1.media_uri 
            FROM indexed_media m1 
            INNER JOIN indexed_media m2 ON m1.media_uri = m2.media_uri 
            WHERE m1.engine_id = ? AND m2.engine_id = ? AND m1.status='done' AND m2.status='done'
            LIMIT 1
        """
        return readableDatabase.rawQuery(sql, arrayOf(engineA, engineB)).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    @Synchronized
    fun pruneStaleFacesForMedia(mediaUri: String, engineId: String, currentFaceCount: Int) {
        val db = writableDatabase
        db.delete(
            "faces",
            "media_uri=? AND engine_id=? AND face_index >= ?",
            arrayOf(mediaUri, engineId, currentFaceCount.toString())
        )
    }

    @Synchronized
    fun pruneStaleFacesForMedia(mediaUri: String, engineId: String, keptIndices: Collection<Int>) {
        val db = writableDatabase
        if (keptIndices.isEmpty()) {
            db.delete("faces", "media_uri=? AND engine_id=?", arrayOf(mediaUri, engineId))
        } else {
            val placeholders = keptIndices.joinToString(",") { "?" }
            val args = arrayOf(mediaUri, engineId) + keptIndices.map { it.toString() }.toTypedArray()
            db.delete("faces", "media_uri=? AND engine_id=? AND face_index NOT IN ($placeholders)", args)
        }
    }

    @Synchronized
    fun deleteMediaCompletely(mediaUris: Collection<String>) {
        if (mediaUris.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (uri in mediaUris) {
                db.delete("faces", "media_uri=?", arrayOf(uri))
                db.delete("indexed_media", "media_uri=?", arrayOf(uri))
                db.delete("review_suggestions", "media_uri=?", arrayOf(uri))
                db.delete("face_metrics_v2", "media_uri=?", arrayOf(uri))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        FaceStoreEvents.notifyAllUpdated()
    }

    @Synchronized
    fun compareEnginesOnMedia(
        mediaUri: String,
        engineA: String = BuffaloLFaceEngine.ENGINE_ID,
        engineB: String = EnteFaceConstants.ENGINE_ID,
        iouThreshold: Float = 0.40f,
        context: Context? = null
    ): EngineComparisonResult {
        fun loadFaces(eng: String): List<EngineComparisonFace> {
            val list = mutableListOf<EngineComparisonFace>()
            readableDatabase.rawQuery(
                """SELECT face_index, box_left, box_top, box_right, box_bottom, detection_confidence, quality_score
                   FROM faces WHERE media_uri=? AND engine_id=? ORDER BY face_index""",
                arrayOf(mediaUri, eng)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(
                        EngineComparisonFace(
                            faceIndex = cursor.getInt(0),
                            bounds = RectF(cursor.getFloat(1), cursor.getFloat(2), cursor.getFloat(3), cursor.getFloat(4)),
                            confidence = cursor.getFloat(5),
                            quality = cursor.getFloat(6)
                        )
                    )
                }
            }
            return list
        }

        var facesA = loadFaces(engineA)
        var facesB = loadFaces(engineB)

        // If stored detections are missing for one or both engines, and context is available, run on-demand inference
        if ((facesA.isEmpty() || facesB.isEmpty()) && context != null && mediaUri.isNotBlank()) {
            val factoryA = FaceEngineRegistry.getFactory(engineA)
            val factoryB = FaceEngineRegistry.getFactory(engineB)
            if (factoryA?.isInstalled(context) == true && factoryB?.isInstalled(context) == true) {
                runCatching {
                    val uri = Uri.parse(mediaUri)
                    if (facesA.isEmpty()) {
                        factoryA.create(context, FaceRecognitionSettings.detectionConfidence(context, engineA)).use { eng ->
                            facesA = eng.embed(uri).map {
                                EngineComparisonFace(it.faceIndex, it.bounds, it.score, it.quality)
                            }
                        }
                    }
                    if (facesB.isEmpty()) {
                        factoryB.create(context, FaceRecognitionSettings.detectionConfidence(context, engineB)).use { eng ->
                            facesB = eng.embed(uri).map {
                                EngineComparisonFace(it.faceIndex, it.bounds, it.score, it.quality)
                            }
                        }
                    }
                }
            }
        }

        // Calculate overlap count using IoU >= iouThreshold
        var matches = 0
        val matchedB = mutableSetOf<Int>()
        for (a in facesA) {
            for (b in facesB) {
                if (b.faceIndex in matchedB) continue
                if (calculateIoU(a.bounds, b.bounds) >= iouThreshold) {
                    matches++
                    matchedB.add(b.faceIndex)
                    break
                }
            }
        }

        return EngineComparisonResult(
            mediaUri = mediaUri,
            engineA = engineA,
            facesA = facesA,
            engineB = engineB,
            facesB = facesB,
            matchingFaceCount = matches
        )
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val interLeft = maxOf(a.left, b.left)
        val interTop = maxOf(a.top, b.top)
        val interRight = minOf(a.right, b.right)
        val interBottom = minOf(a.bottom, b.bottom)
        if (interRight <= interLeft || interBottom <= interTop) return 0f
        val interArea = (interRight - interLeft) * (interBottom - interTop)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    // --- Review Suggestions ---

    @Synchronized
    fun getPendingSuggestions(engineId: String = BuffaloLFaceEngine.ENGINE_ID): List<ReviewSuggestion> {
        val result = mutableListOf<ReviewSuggestion>()
        readableDatabase.rawQuery(
            """SELECT s.id, s.face_id, s.media_uri, s.face_index, s.suggested_person_id,
                      COALESCE(p.name, 'Person'), s.distance, s.engine_id
               FROM review_suggestions s
               JOIN people p ON s.suggested_person_id = p.id
               WHERE s.status = 'pending' AND s.engine_id = ?
               ORDER BY s.distance ASC LIMIT 50""".trimIndent(),
            arrayOf(engineId)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += ReviewSuggestion(
                    id = cursor.getString(0),
                    faceId = cursor.getString(1),
                    mediaUri = cursor.getString(2),
                    faceIndex = cursor.getInt(3),
                    suggestedPersonId = cursor.getString(4),
                    suggestedPersonName = cursor.getString(5),
                    distance = cursor.getFloat(6),
                    engineId = cursor.getString(7)
                )
            }
        }
        return result
    }

    @Synchronized
    fun acceptSuggestion(suggestionId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var faceId: String? = null
            var suggestedPersonId: String? = null
            var engineId = BuffaloLFaceEngine.ENGINE_ID

            db.rawQuery(
                "SELECT face_id, suggested_person_id, engine_id FROM review_suggestions WHERE id=?",
                arrayOf(suggestionId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    faceId = cursor.getString(0)
                    suggestedPersonId = cursor.getString(1)
                    engineId = cursor.getString(2)
                }
            }

            val fId = faceId
            val pId = suggestedPersonId
            if (fId == null || pId == null) return false

            val parts = fId.split("#")
            // parts: engineId#mediaUri#faceIndex or mediaUri#faceIndex
            val uri = if (parts.size >= 3) parts[1] else parts[0]
            val index = if (parts.size >= 3) parts[2].toIntOrNull() ?: 0 else parts.getOrNull(1)?.toIntOrNull() ?: 0

            val vals = ContentValues().apply { put("person_id", pId) }
            db.update("faces", vals, "media_uri=? AND face_index=? AND engine_id=?", arrayOf(uri, index.toString(), engineId))
            recordFeedback(db, "ASSIGN_FACE_TO_PERSON", fId, pId, engineId = engineId)

            val statusVal = ContentValues().apply { put("status", "accepted") }
            db.update("review_suggestions", statusVal, "id=?", arrayOf(suggestionId))

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    @Synchronized
    fun rejectSuggestion(suggestionId: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            var faceId: String? = null
            var suggestedPersonId: String? = null
            var engineId = BuffaloLFaceEngine.ENGINE_ID

            db.rawQuery(
                "SELECT face_id, suggested_person_id, engine_id FROM review_suggestions WHERE id=?",
                arrayOf(suggestionId)
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    faceId = cursor.getString(0)
                    suggestedPersonId = cursor.getString(1)
                    engineId = cursor.getString(2)
                }
            }

            val fId = faceId
            val pId = suggestedPersonId
            if (fId != null && pId != null) {
                recordFeedback(db, "REJECTED_MATCH", fId, pId, engineId = engineId)
            }

            val statusVal = ContentValues().apply { put("status", "rejected") }
            db.update("review_suggestions", statusVal, "id=?", arrayOf(suggestionId))

            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
            FaceStoreEvents.notifyAllUpdated()
        }
    }

    // --- Safe, Non-Destructive Reclustering (Engine-Isolated) ---

    @Synchronized
    fun reclusterAllSafely(
        maxDistance: Float = 0.60f,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID,
        modelVersion: String = BuffaloLFaceEngine.MODEL_VERSION
    ): Int = FaceProcessingCoordinator.withProcessing(
        operationId = "reclusterAllSafely_${System.nanoTime()}",
        type = FaceProcessingOperation.GROUPING,
        engineId = engineId,
        description = "Reclustering all faces"
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            data class FaceEntry(
                val mediaUri: String,
                val faceIndex: Int,
                val faceId: String,
                val personId: String?,
                val clusterId: String?,
                val embedding: FloatArray,
                val bounds: RectF,
                val confidence: Float,
                val quality: Float
            )

            val engineFaces = mutableListOf<FaceEntry>()
            db.rawQuery(
                """SELECT media_uri, face_index, face_id, person_id, cluster_id, embedding,
                          box_left, box_top, box_right, box_bottom, detection_confidence, quality_score
                   FROM faces
                   WHERE engine_id=? AND model_version=?""",
                arrayOf(engineId, modelVersion)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val mediaUri = cursor.getString(0)
                    val faceIndex = cursor.getInt(1)
                    val faceId = cursor.getString(2) ?: "${engineId}#${mediaUri}#${faceIndex}"
                    val personId = if (cursor.isNull(3)) null else cursor.getString(3)
                    val clusterId = if (cursor.isNull(4)) null else cursor.getString(4)
                    val emb = bytesToFloats(cursor.getBlob(5))
                    val bounds = RectF(cursor.getFloat(6), cursor.getFloat(7), cursor.getFloat(8), cursor.getFloat(9))
                    val conf = cursor.getFloat(10)
                    val qual = cursor.getFloat(11)
                    engineFaces += FaceEntry(mediaUri, faceIndex, faceId, personId, clusterId, emb, bounds, conf, qual)
                }
            }

            if (engineFaces.isEmpty()) {
                pruneEmptyAutoPeople(db, engineId)
                db.setTransactionSuccessful()
                return 0
            }

            // Identify faces and people locked by manual feedback or manual identity corrections
            val lockedFaceIds = mutableSetOf<String>()
            val lockedPersonIds = mutableSetOf<String>()

            db.rawQuery(
                "SELECT source_id, target_id, feedback_type FROM face_feedback WHERE engine_id=?",
                arrayOf(engineId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val src = cursor.getString(0)
                    val tgt = cursor.getString(1)
                    val type = cursor.getString(2)
                    when (type) {
                        "ASSIGN_FACE_TO_PERSON", "REMOVE_FACE_FROM_PERSON" -> {
                            lockedFaceIds.add(src)
                            if (tgt.isNotBlank()) lockedPersonIds.add(tgt)
                        }
                        "MERGE_PEOPLE" -> {
                            if (src.isNotBlank()) lockedPersonIds.add(src)
                            if (tgt.isNotBlank()) lockedPersonIds.add(tgt)
                        }
                        "SPLIT_CLUSTER" -> {
                            if (tgt.isNotBlank()) lockedPersonIds.add(tgt)
                        }
                    }
                }
            }

            val preservedPeopleNames = mutableMapOf<String, String>()
            val preservedPeopleHidden = mutableMapOf<String, Int>()
            val preservedPeopleCover = mutableMapOf<String, String?>()
            db.rawQuery("SELECT id, name, is_hidden, cover_media_uri FROM people WHERE engine_id=?", arrayOf(engineId)).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val isHidden = cursor.getInt(2)
                    val cover = if (cursor.isNull(3)) null else cursor.getString(3)
                    preservedPeopleNames[id] = name
                    preservedPeopleHidden[id] = isHidden
                    preservedPeopleCover[id] = cover
                    if (name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)) {
                        lockedPersonIds.add(id)
                    }
                    if (isHidden == 1) {
                        lockedPersonIds.add(id)
                    }
                }
            }

            class TempCluster(
                var id: String,
                var personId: String,
                var centroid: FloatArray,
                val faces: MutableList<FaceEntry>
            )

            val tempClusters = mutableListOf<TempCluster>()

            val (lockedOrNamedFaces, fluidFaces) = engineFaces.partition {
                it.faceId in lockedFaceIds || (it.personId != null && it.personId in lockedPersonIds)
            }

            val groupedByPerson = lockedOrNamedFaces.groupBy { it.personId ?: UUID.randomUUID().toString() }
            for ((personId, faces) in groupedByPerson) {
                val clusterId = UUID.randomUUID().toString()
                val dim = faces.first().embedding.size
                val mean = FloatArray(dim)
                faces.forEach { f ->
                    val norm = normalize(f.embedding)
                    for (i in 0 until dim) mean[i] += norm[i]
                }
                val centroid = normalize(mean)
                tempClusters.add(TempCluster(clusterId, personId, centroid, faces.toMutableList()))
            }

            for (face in fluidFaces) {
                val normVec = normalize(face.embedding)
                var best: TempCluster? = null
                var bestDist = Float.POSITIVE_INFINITY
                for (c in tempClusters) {
                    val dist = cosineDistance(normVec, c.centroid)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = c
                    }
                }

                val effThresh = if (face.quality < 0.40f) maxDistance * 0.85f else maxDistance
                if (best != null && bestDist <= effThresh) {
                    best.faces.add(face)
                    val dim = normVec.size
                    val mean = FloatArray(dim)
                    for (f in best.faces) {
                        val v = normalize(f.embedding)
                        for (i in 0 until dim) mean[i] += v[i]
                    }
                    best.centroid = normalize(mean)
                } else {
                    val newClusterId = UUID.randomUUID().toString()
                    val newPersonId = UUID.randomUUID().toString()
                    tempClusters.add(TempCluster(newClusterId, newPersonId, normVec.copyOf(), mutableListOf(face)))
                }
            }

            // Keep prior IDs for unchanged unnamed groups, but don't recycle a locked identity.
            val usedPersonIds = mutableSetOf<String>()
            val usedClusterIds = mutableSetOf<String>()
            for (cluster in tempClusters) {
                if (cluster.personId !in lockedPersonIds) {
                    val priorId = cluster.faces.mapNotNull { it.personId }.groupingBy { it }.eachCount().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .firstOrNull { it.key !in lockedPersonIds && it.key !in usedPersonIds }?.key
                    if (priorId != null) cluster.personId = priorId
                }
                if (!usedPersonIds.add(cluster.personId)) {
                    cluster.personId = UUID.randomUUID().toString()
                    usedPersonIds.add(cluster.personId)
                }
                val priorCluster = cluster.faces.mapNotNull { it.clusterId }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull { it.key !in usedClusterIds }?.key
                if (priorCluster != null) cluster.id = priorCluster
                if (!usedClusterIds.add(cluster.id)) {
                    cluster.id = UUID.randomUUID().toString()
                    usedClusterIds.add(cluster.id)
                }
            }

            // Only delete clusters belonging to this specific engine
            db.delete("clusters", "engine_id=? AND model_version=?", arrayOf(engineId, modelVersion))

            val now = System.currentTimeMillis()
            val dim = engineFaces.first().embedding.size
            for (c in tempClusters) {
                val existingName = preservedPeopleNames[c.personId].orEmpty()
                val existingHidden = preservedPeopleHidden[c.personId] ?: 0
                val existingCover = preservedPeopleCover[c.personId] ?: c.faces.firstOrNull()?.mediaUri
                val personVals = ContentValues().apply {
                    put("id", c.personId)
                    put("name", existingName)
                    put("is_hidden", existingHidden)
                    put("cover_media_uri", existingCover)
                    put("engine_id", engineId)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertWithOnConflict("people", null, personVals, SQLiteDatabase.CONFLICT_REPLACE)

                val clusterVals = ContentValues().apply {
                    put("id", c.id)
                    put("person_id", c.personId)
                    put("display_name", existingName)
                    put("centroid", floatsToBytes(c.centroid))
                    put("face_count", c.faces.size)
                    put("embedding_dim", dim)
                    put("engine_id", engineId)
                    put("model_version", modelVersion)
                    put("representative_face_id", c.faces.firstOrNull()?.faceId)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertOrThrow("clusters", null, clusterVals)

                for (f in c.faces) {
                    val faceVals = ContentValues().apply {
                        put("cluster_id", c.id)
                        put("person_id", c.personId)
                    }
                    db.update(
                        "faces",
                        faceVals,
                        "media_uri=? AND face_index=? AND engine_id=?",
                        arrayOf(f.mediaUri, f.faceIndex.toString(), engineId)
                    )
                }
            }
            pruneEmptyAutoPeople(db, engineId)

            db.setTransactionSuccessful()
            return tempClusters.size
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun reclusterAll(maxDistance: Float = 0.60f): Int = reclusterAllSafely(maxDistance)

    @Synchronized
    fun resetClustersOnly(engineId: String = BuffaloLFaceEngine.ENGINE_ID) = FaceProcessingCoordinator.withProcessing(
        operationId = "resetClustersOnly_${System.nanoTime()}",
        type = FaceProcessingOperation.FACE_DB_MUTATION,
        engineId = engineId,
        description = "Resetting face clusters"
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("clusters", "engine_id=?", arrayOf(engineId))
            val faceReset = ContentValues().apply {
                putNull("cluster_id")
                putNull("person_id")
            }
            db.update("faces", faceReset, "engine_id=?", arrayOf(engineId))
            db.delete("people", "engine_id=?", arrayOf(engineId))
            db.delete("review_suggestions", "engine_id=?", arrayOf(engineId))
            db.delete("grouping_state", "engine_id=?", arrayOf(engineId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        FaceStoreEvents.notifyAllUpdated()
    }

    @Synchronized
    fun clearAllFaceData() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("faces", null, null)
            db.delete("clusters", null, null)
            db.delete("people", null, null)
            db.delete("face_feedback", null, null)
            db.delete("review_suggestions", null, null)
            db.delete("indexed_media", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // --- Internal Helpers ---

    private fun updateCentroidIncremental(
        db: SQLiteDatabase,
        clusterId: String,
        newEmbedding: FloatArray,
        engineId: String,
        modelVersion: String
    ) {
        var updated = false
        db.rawQuery("SELECT centroid, face_count FROM clusters WHERE id=?", arrayOf(clusterId)).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                val oldCentroid = bytesToFloats(cursor.getBlob(0))
                val oldCount = cursor.getInt(1)
                if (oldCount > 0 && oldCentroid.size == newEmbedding.size) {
                    val newCount = oldCount + 1
                    val newCentroidRaw = FloatArray(newEmbedding.size)
                    for (i in newCentroidRaw.indices) {
                        newCentroidRaw[i] = oldCentroid[i] * oldCount + newEmbedding[i]
                    }
                    val updatedCentroid = normalize(newCentroidRaw)
                    val vals = ContentValues().apply {
                        put("centroid", floatsToBytes(updatedCentroid))
                        put("face_count", newCount)
                        put("updated_at", System.currentTimeMillis())
                    }
                    db.update("clusters", vals, "id=?", arrayOf(clusterId))
                    updated = true
                }
            }
        }
        if (!updated) {
            recomputeCentroid(db, clusterId, engineId, modelVersion)
        }
    }

    private fun recomputeCentroid(
        db: SQLiteDatabase,
        clusterId: String,
        engineId: String,
        modelVersion: String
    ) {
        val vectors = mutableListOf<FloatArray>()
        db.rawQuery(
            "SELECT embedding FROM faces WHERE cluster_id=? AND engine_id=? AND model_version=?",
            arrayOf(clusterId, engineId, modelVersion)
        ).use { cursor ->
            while (cursor.moveToNext()) vectors += bytesToFloats(cursor.getBlob(0))
        }

        if (vectors.isEmpty()) {
            db.delete("clusters", "id=?", arrayOf(clusterId))
            return
        }

        val dimensions = vectors.first().size
        val mean = FloatArray(dimensions)
        vectors.forEach { vector ->
            if (vector.size == dimensions) {
                for (i in vector.indices) mean[i] += vector[i]
            }
        }
        val centroid = normalize(mean)
        val vals = ContentValues().apply {
            put("centroid", floatsToBytes(centroid))
            put("face_count", vectors.size)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("clusters", vals, "id=?", arrayOf(clusterId))
    }

    private fun recordFeedback(
        db: SQLiteDatabase,
        type: String,
        sourceId: String,
        targetId: String,
        notes: String? = null,
        engineId: String = BuffaloLFaceEngine.ENGINE_ID
    ) {
        val vals = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("feedback_type", type)
            put("source_id", sourceId)
            put("target_id", targetId)
            put("engine_id", engineId)
            put("notes", notes)
            put("created_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("face_feedback", null, vals, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun getPersonIdForCluster(clusterId: String): String? {
        return readableDatabase.rawQuery("SELECT person_id FROM clusters WHERE id=?", arrayOf(clusterId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
    }

    @Synchronized
    fun getPersonName(personId: String): String {
        return getPersonName(readableDatabase, personId)
    }

    @Synchronized
    fun ensurePersonForFace(mediaUri: String, faceIndex: Int, engineId: String): String {
        if (FaceProcessingCoordinator.isBusy()) {
            val db = readableDatabase
            val existingPersonId = db.rawQuery(
                "SELECT person_id FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
                arrayOf(mediaUri, faceIndex.toString(), engineId)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
            if (!existingPersonId.isNullOrBlank()) return existingPersonId
            return ""
        }
        val db = writableDatabase
        db.rawQuery(
            "SELECT person_id, cluster_id FROM faces WHERE media_uri=? AND face_index=? AND engine_id=?",
            arrayOf(mediaUri, faceIndex.toString(), engineId)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val pId = if (!cursor.isNull(0)) cursor.getString(0) else null
                if (!pId.isNullOrBlank()) return pId
                val cId = if (!cursor.isNull(1)) cursor.getString(1) else null
                if (!cId.isNullOrBlank()) {
                    val clusterPerson = getPersonIdForCluster(cId)
                    if (!clusterPerson.isNullOrBlank()) {
                        db.execSQL(
                            "UPDATE faces SET person_id=? WHERE media_uri=? AND face_index=? AND engine_id=?",
                            arrayOf(clusterPerson, mediaUri, faceIndex.toString(), engineId)
                        )
                        return clusterPerson
                    }
                    val now = System.currentTimeMillis()
                    val pVals = ContentValues().apply {
                        put("id", cId)
                        put("name", "")
                        put("engine_id", engineId)
                        put("created_at", now)
                        put("updated_at", now)
                    }
                    db.insertWithOnConflict("people", null, pVals, SQLiteDatabase.CONFLICT_IGNORE)
                    db.execSQL(
                        "UPDATE faces SET person_id=? WHERE media_uri=? AND face_index=? AND engine_id=?",
                        arrayOf(cId, mediaUri, faceIndex.toString(), engineId)
                    )
                    db.execSQL("UPDATE clusters SET person_id=? WHERE id=? AND engine_id=?", arrayOf(cId, cId, engineId))
                    FaceStoreEvents.notifyAllUpdated()
                    return cId
                }
            }
        }
        val newPersonId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pVals = ContentValues().apply {
            put("id", newPersonId)
            put("name", "")
            put("engine_id", engineId)
            put("created_at", now)
            put("updated_at", now)
        }
        db.insertWithOnConflict("people", null, pVals, SQLiteDatabase.CONFLICT_IGNORE)
        val fVals = ContentValues().apply {
            put("person_id", newPersonId)
        }
        db.update(
            "faces",
            fVals,
            "media_uri=? AND face_index=? AND engine_id=?",
            arrayOf(mediaUri, faceIndex.toString(), engineId)
        )
        FaceStoreEvents.notifyAllUpdated()
        return newPersonId
    }

    private fun getPersonName(db: SQLiteDatabase, personId: String): String {
        return db.rawQuery("SELECT name FROM people WHERE id=?", arrayOf(personId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else "" }
    }

    fun normalize(values: FloatArray): FloatArray = FaceVectorMath.normalize(values)
    fun cosineDistance(a: FloatArray, b: FloatArray): Float = FaceVectorMath.cosineDistance(a, b)
    fun floatsToBytes(values: FloatArray): ByteArray = FaceVectorMath.floatsToBytes(values)
    fun bytesToFloats(bytes: ByteArray): FloatArray = FaceVectorMath.bytesToFloats(bytes)

    companion object {
        const val DATABASE_NAME = FaceDbSchema.DATABASE_NAME
        const val DATABASE_VERSION = FaceDbSchema.DATABASE_VERSION

        fun normalize(values: FloatArray): FloatArray = FaceVectorMath.normalize(values)
        fun cosineDistance(a: FloatArray, b: FloatArray): Float = FaceVectorMath.cosineDistance(a, b)
        fun floatsToBytes(values: FloatArray): ByteArray = FaceVectorMath.floatsToBytes(values)
        fun bytesToFloats(bytes: ByteArray): FloatArray = FaceVectorMath.bytesToFloats(bytes)
    }
}
