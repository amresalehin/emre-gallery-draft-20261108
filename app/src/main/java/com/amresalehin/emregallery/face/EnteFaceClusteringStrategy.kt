package com.amresalehin.emregallery.face

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF
import java.util.UUID

/**
 * Ente-style Linear Incremental Face Clustering Strategy.
 *
 * Characteristics:
 * 1. Linear: Evaluates faces in strict chronological order (oldest photos to newest photos)
 *    to align with natural human identity progression and keep clustering deterministic.
 * 2. Incremental: New observations are matched against established clusters; reprocessing
 *    yields stable clusters without dismantling existing, user-corrected groups.
 * 3. Quality-Aware: Utilizes FaceQualityMetrics (blur, pose, size) to prevent blurry/ambiguous
 *    faces from polluting clean identities. Low-quality or borderline-distance faces generate
 *    review suggestions instead of being forced into confident person clusters.
 * 4. User-Feedback Preserving: Strictly honors manual names, splits, merges, and face exclusions.
 * 5. Strict Provenance Isolation: Only processes embeddings matching [EnteFaceConstants.ENGINE_ID]
 *    and [EnteFaceConstants.MODEL_VERSION].
 */
object EnteFaceClusteringStrategy : FaceClusteringStrategy {

    override val strategyId: String = "ente_linear_incremental_v1"
    override val strategyVersion: Int = 1

    const val DEFAULT_THRESHOLD = EnteFaceConstants.DEFAULT_RECOGNITION_DISTANCE // 0.55f
    const val AMBIGUITY_MARGIN = 0.06f
    const val MIN_QUALITY_FOR_CLUSTER_ANCHOR = 0.60f

    override fun clusterFaces(
        store: FaceClusterStore,
        mediaUris: List<String>,
        engineId: String,
        modelVersion: String,
        embeddingDimension: Int,
        maxDistance: Float,
        onProgress: (processedFaces: Int, totalFaces: Int) -> Unit
    ): Int {
        check(engineId == EnteFaceConstants.ENGINE_ID) {
            "EnteFaceClusteringStrategy must only be run with engine ${EnteFaceConstants.ENGINE_ID}"
        }
        return reclusterChronologically(store, maxDistance, engineId, modelVersion, onProgress)
    }

    data class FaceObservation(
        val mediaUri: String,
        val faceIndex: Int,
        val faceId: String,
        val personId: String?,
        val clusterId: String?,
        val embedding: FloatArray,
        val bounds: RectF,
        val confidence: Float,
        val quality: Float,
        val timestamp: Long
    )

    private data class WorkingCluster(
        var clusterId: String,
        var personId: String,
        var representativeEmbedding: FloatArray,
        var centroid: FloatArray,
        var maxFaceQuality: Float,
        val faces: MutableList<FaceObservation>
    )

    @Synchronized
    fun reclusterChronologically(
        store: FaceClusterStore,
        maxDistance: Float = DEFAULT_THRESHOLD,
        engineId: String = EnteFaceConstants.ENGINE_ID,
        modelVersion: String = EnteFaceConstants.MODEL_VERSION,
        onProgress: (processedFaces: Int, totalFaces: Int) -> Unit = { _, _ -> }
    ): Int {
        val db = store.writableDatabase
        db.beginTransaction()
        try {
            // 1. Fetch all faces for this engine with photo date ordering (chronological)
            val observations = mutableListOf<FaceObservation>()
            db.rawQuery(
                """SELECT f.media_uri, f.face_index, f.face_id, f.person_id, f.cluster_id,
                          f.embedding, f.box_left, f.box_top, f.box_right, f.box_bottom,
                          f.detection_confidence, f.quality_score,
                          COALESCE(m.indexed_at, f.created_at) AS sort_time
                   FROM faces f
                   LEFT JOIN indexed_media m ON f.media_uri = m.media_uri AND m.engine_id = f.engine_id
                   WHERE f.engine_id=? AND f.model_version=?
                   ORDER BY sort_time ASC, f.media_uri ASC, f.face_index ASC""",
                arrayOf(engineId, modelVersion)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val uri = cursor.getString(0)
                    val idx = cursor.getInt(1)
                    val faceId = cursor.getString(2) ?: "${engineId}#${uri}#${idx}"
                    val personId = if (cursor.isNull(3)) null else cursor.getString(3)
                    val clusterId = if (cursor.isNull(4)) null else cursor.getString(4)
                    val emb = FaceClusterStore.bytesToFloats(cursor.getBlob(5))
                    val bounds = RectF(cursor.getFloat(6), cursor.getFloat(7), cursor.getFloat(8), cursor.getFloat(9))
                    val conf = cursor.getFloat(10)
                    val qual = cursor.getFloat(11)
                    val time = cursor.getLong(12)
                    observations.add(
                        FaceObservation(uri, idx, faceId, personId, clusterId, emb, bounds, conf, qual, time)
                    )
                }
            }

            if (observations.isEmpty()) {
                store.pruneEmptyAutoPeople(db, engineId)
                db.setTransactionSuccessful()
                onProgress(0, 0)
                return 0
            }

            // 2. Load manual feedback exclusions: (source_id -> excluded target person IDs)
            val exclusions = mutableMapOf<String, MutableSet<String>>()
            db.rawQuery(
                """SELECT source_id, target_id FROM face_feedback
                   WHERE engine_id=? AND feedback_type IN ('REJECTED_MATCH', 'REMOVE_FACE_FROM_PERSON')""",
                arrayOf(engineId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val src = cursor.getString(0)
                    val tgt = cursor.getString(1)
                    exclusions.getOrPut(src) { mutableSetOf() }.add(tgt)
                }
            }

            // 3. Load user-assigned person names, hidden flags, and covers
            val userNamedPeople = mutableMapOf<String, String>()
            val hiddenPeople = mutableSetOf<String>()
            val userCoverMedia = mutableMapOf<String, String>()
            val lockedPeople = mutableSetOf<String>()

            db.rawQuery("SELECT id, name, is_hidden, cover_media_uri FROM people WHERE engine_id=?", arrayOf(engineId)).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1).orEmpty()
                    val hidden = cursor.getInt(2) == 1
                    val cover = if (cursor.isNull(3)) null else cursor.getString(3)
                    if (name.isNotBlank() && !name.equals("Unknown", ignoreCase = true)) {
                        userNamedPeople[id] = name
                        lockedPeople.add(id)
                    }
                    if (hidden) {
                        hiddenPeople.add(id)
                        lockedPeople.add(id)
                    }
                    if (!cover.isNullOrBlank()) {
                        userCoverMedia[id] = cover
                    }
                }
            }

            // 4. Locked faces: faces explicitly assigned by user, merged, split, or in named/hidden people
            val explicitlyAssignedFaceToPerson = mutableMapOf<String, String>()
            db.rawQuery(
                "SELECT source_id, target_id, feedback_type FROM face_feedback WHERE engine_id=?",
                arrayOf(engineId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val src = cursor.getString(0)
                    val tgt = cursor.getString(1)
                    val type = cursor.getString(2)
                    when (type) {
                        "ASSIGN_FACE_TO_PERSON" -> {
                            explicitlyAssignedFaceToPerson[src] = tgt
                            if (tgt.isNotBlank()) lockedPeople.add(tgt)
                        }
                        "REMOVE_FACE_FROM_PERSON" -> {
                            if (tgt.isNotBlank()) lockedPeople.add(tgt)
                        }
                        "MERGE_PEOPLE" -> {
                            if (src.isNotBlank()) lockedPeople.add(src)
                            if (tgt.isNotBlank()) lockedPeople.add(tgt)
                        }
                        "SPLIT_CLUSTER" -> {
                            if (tgt.isNotBlank()) lockedPeople.add(tgt)
                        }
                    }
                }
            }

            val workingClusters = mutableListOf<WorkingCluster>()

            // Partition locked faces from fluid faces
            val (lockedObs, fluidObs) = observations.partition { obs ->
                explicitlyAssignedFaceToPerson.containsKey(obs.faceId) ||
                (obs.personId != null && obs.personId in lockedPeople)
            }

            // Seed working clusters from user-named / explicitly locked persons
            val lockedByPerson = lockedObs.groupBy { obs ->
                explicitlyAssignedFaceToPerson[obs.faceId] ?: obs.personId ?: "unknown_${obs.faceId}"
            }

            for ((pId, faces) in lockedByPerson) {
                val clusterId = UUID.randomUUID().toString()
                val dim = faces.first().embedding.size
                val centroid = computeMeanNormalized(faces.map { it.embedding }, dim)
                val highestQualFace = faces.maxByOrNull { it.quality } ?: faces.first()
                workingClusters.add(
                    WorkingCluster(
                        clusterId = clusterId,
                        personId = pId,
                        representativeEmbedding = FaceClusterStore.normalize(highestQualFace.embedding),
                        centroid = centroid,
                        maxFaceQuality = highestQualFace.quality,
                        faces = faces.toMutableList()
                    )
                )
            }

            // 5. Linear Incremental Pass on remaining faces in chronological order
            val suggestionsToInsert = mutableListOf<ContentValues>()

            onProgress(lockedObs.size, observations.size)
            for ((facePosition, face) in fluidObs.withIndex()) {
                val normEmb = FaceClusterStore.normalize(face.embedding)
                val excludedForFace = exclusions[face.faceId] ?: emptySet()

                var bestCluster: WorkingCluster? = null
                var bestDistance = Float.POSITIVE_INFINITY
                var secondBestDistance = Float.POSITIVE_INFINITY

                for (c in workingClusters) {
                    if (c.personId in excludedForFace) continue

                    // In Ente pipeline, compare with cluster representative & centroid
                    val distRep = FaceClusterStore.cosineDistance(normEmb, c.representativeEmbedding)
                    val distCentroid = FaceClusterStore.cosineDistance(normEmb, c.centroid)
                    val effectiveDist = minOf(distRep, distCentroid)

                    if (effectiveDist < bestDistance) {
                        secondBestDistance = bestDistance
                        bestDistance = effectiveDist
                        bestCluster = c
                    } else if (effectiveDist < secondBestDistance) {
                        secondBestDistance = effectiveDist
                    }
                }

                // Dynamic threshold based on face quality:
                // Lower quality faces require stricter proximity to prevent noise pollution.
                val qualityScale = (0.75f + 0.25f * face.quality.coerceIn(0f, 1f))
                val effectiveThresh = maxDistance * qualityScale

                val isMatch = bestCluster != null && bestDistance <= effectiveThresh
                val isAmbiguous = isMatch && ((secondBestDistance - bestDistance) < AMBIGUITY_MARGIN)

                if (bestCluster != null && isMatch && !isAmbiguous) {
                    val cluster = bestCluster
                    cluster.faces.add(face)
                    // Update cluster centroid and representative if new face has higher quality
                    cluster.centroid = computeMeanNormalized(cluster.faces.map { it.embedding }, normEmb.size)
                    if (face.quality > cluster.maxFaceQuality) {
                        cluster.representativeEmbedding = normEmb
                        cluster.maxFaceQuality = face.quality
                    }
                } else if (face.quality < MIN_QUALITY_FOR_CLUSTER_ANCHOR) {
                    // Quality is below anchor threshold; do not mint a brand-new identity.
                    // Generate review suggestion if a candidate cluster exists.
                    if (bestCluster != null && bestDistance <= (maxDistance * 1.5f)) {
                        val suggestion = ContentValues().apply {
                            put("id", UUID.randomUUID().toString())
                            put("face_id", face.faceId)
                            put("media_uri", face.mediaUri)
                            put("face_index", face.faceIndex)
                            put("engine_id", engineId)
                            put("suggested_person_id", bestCluster.personId)
                            put("distance", bestDistance)
                            put("status", "pending")
                            put("created_at", System.currentTimeMillis())
                        }
                        suggestionsToInsert.add(suggestion)
                    }
                } else {
                    // Confident, high-quality face: create a new cluster / person identity
                    val newClusterId = UUID.randomUUID().toString()
                    val newPersonId = UUID.randomUUID().toString()
                    val newCluster = WorkingCluster(
                        clusterId = newClusterId,
                        personId = newPersonId,
                        representativeEmbedding = normEmb.copyOf(),
                        centroid = normEmb.copyOf(),
                        maxFaceQuality = face.quality,
                        faces = mutableListOf(face)
                    )
                    workingClusters.add(newCluster)

                    // If borderline or ambiguous, generate a review suggestion for the best match
                    if (bestCluster != null && bestDistance <= (maxDistance * 1.15f)) {
                        val suggestion = ContentValues().apply {
                            put("id", UUID.randomUUID().toString())
                            put("face_id", face.faceId)
                            put("media_uri", face.mediaUri)
                            put("face_index", face.faceIndex)
                            put("engine_id", engineId)
                            put("suggested_person_id", bestCluster.personId)
                            put("distance", bestDistance)
                            put("status", "pending")
                            put("created_at", System.currentTimeMillis())
                        }
                        suggestionsToInsert.add(suggestion)
                    }
                }
                val processedFaces = lockedObs.size + facePosition + 1
                val interval = maxOf(1, observations.size / 100)
                if (processedFaces % interval == 0 || processedFaces == observations.size) {
                    onProgress(processedFaces, observations.size)
                }
            }

            // Preserve identities for unchanged unnamed groups; locked identity records always win.
            val usedPersonIds = mutableSetOf<String>()
            val usedClusterIds = mutableSetOf<String>()
            for (cluster in workingClusters) {
                if (cluster.personId !in lockedPeople) {
                    val priorId = cluster.faces.mapNotNull { it.personId }.groupingBy { it }.eachCount().entries
                        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                        .firstOrNull { it.key !in lockedPeople && it.key !in usedPersonIds }?.key
                    if (priorId != null) cluster.personId = priorId
                }
                if (!usedPersonIds.add(cluster.personId)) {
                    cluster.personId = UUID.randomUUID().toString()
                    usedPersonIds.add(cluster.personId)
                }
                val priorCluster = cluster.faces.mapNotNull { it.clusterId }.groupingBy { it }.eachCount().entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                    .firstOrNull { it.key !in usedClusterIds }?.key
                if (priorCluster != null) cluster.clusterId = priorCluster
                if (!usedClusterIds.add(cluster.clusterId)) {
                    cluster.clusterId = UUID.randomUUID().toString()
                    usedClusterIds.add(cluster.clusterId)
                }
            }

            // 6. Persist Updated Clusters and Faces for this engine
            // Remove previous non-locked clusters for this engine only
            db.delete("clusters", "engine_id=? AND model_version=?", arrayOf(engineId, modelVersion))

            val now = System.currentTimeMillis()
            val dim = observations.first().embedding.size

            for (c in workingClusters) {
                val preservedName = userNamedPeople[c.personId].orEmpty()
                val isHidden = if (hiddenPeople.contains(c.personId)) 1 else 0
                val coverMedia = userCoverMedia[c.personId] ?: (c.faces.maxByOrNull { it.quality }?.mediaUri ?: c.faces.first().mediaUri)

                val personVals = ContentValues().apply {
                    put("id", c.personId)
                    put("name", preservedName)
                    put("cover_media_uri", coverMedia)
                    put("is_hidden", isHidden)
                    put("engine_id", engineId)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertWithOnConflict("people", null, personVals, SQLiteDatabase.CONFLICT_REPLACE)

                val clusterVals = ContentValues().apply {
                    put("id", c.clusterId)
                    put("person_id", c.personId)
                    put("display_name", preservedName)
                    put("centroid", FaceClusterStore.floatsToBytes(c.centroid))
                    put("embedding_dim", dim)
                    put("engine_id", engineId)
                    put("model_version", modelVersion)
                    put("face_count", c.faces.size)
                    put("representative_face_id", c.faces.firstOrNull()?.faceId)
                    put("created_at", now)
                    put("updated_at", now)
                }
                db.insertOrThrow("clusters", null, clusterVals)

                for (f in c.faces) {
                    val faceVals = ContentValues().apply {
                        put("cluster_id", c.clusterId)
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

            // Clean up old pending suggestions for this engine and insert new ones
            db.delete("review_suggestions", "engine_id=? AND status='pending'", arrayOf(engineId))
            for (s in suggestionsToInsert) {
                db.insertWithOnConflict("review_suggestions", null, s, SQLiteDatabase.CONFLICT_REPLACE)
            }
            store.pruneEmptyAutoPeople(db, engineId)

            db.setTransactionSuccessful()
            return workingClusters.size
        } finally {
            db.endTransaction()
        }
    }

    private fun computeMeanNormalized(embeddings: List<FloatArray>, dim: Int): FloatArray {
        val sum = FloatArray(dim)
        for (emb in embeddings) {
            val norm = FaceClusterStore.normalize(emb)
            for (i in 0 until dim) sum[i] += norm[i]
        }
        return FaceClusterStore.normalize(sum)
    }
}
