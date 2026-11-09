package com.amresalehin.emregallery.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.StatFs
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production face detection and recognition engine using InsightFace buffalo_l models:
 * - SCRFD-10GF for high-recall face detection and 5-point landmark localization.
 * - W600K_R50 ArcFace for 512-dimensional normalized embeddings.
 */
class BuffaloLFaceEngine private constructor(
    private val context: Context,
    private val detectorSession: OrtSession,
    private val recognitionSession: OrtSession,
    private val detectionThreshold: Float
) : FaceEngine {

    override val engineId: String get() = ENGINE_ID
    override val modelVersion: String get() = MODEL_VERSION
    override val embeddingDimension: Int get() = EMBEDDING_DIMENSION
    override val formatVersion: Int get() = FORMAT_VERSION

    private data class FaceDetection(val bounds: RectF, val landmarks: FloatArray, val score: Float)

    private data class DecodedImage(val bitmap: Bitmap, val origW: Int, val origH: Int)
    @Volatile private var cachedDecoded: Pair<Uri, DecodedImage>? = null

    private fun getOrDecode(uri: Uri): DecodedImage? {
        synchronized(this) {
            val cached = cachedDecoded
            if (cached != null && cached.first == uri && !cached.second.bitmap.isRecycled) {
                return cached.second
            }
        }
        val img = decodeOrientedImage(uri) ?: return null
        synchronized(this) {
            cachedDecoded?.second?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedDecoded = uri to img
        }
        return img
    }

    private fun releaseDecoded(uri: Uri) {
        synchronized(this) {
            val cached = cachedDecoded
            if (cached != null && cached.first == uri) {
                if (!cached.second.bitmap.isRecycled) cached.second.bitmap.recycle()
                cachedDecoded = null
            }
        }
    }

    override fun detectFaces(uri: Uri): List<RawFaceDetection> {
        val decoded = getOrDecode(uri)
            ?: error("Could not decode image at URI: $uri")
        val detections = detect(decoded.bitmap)
        val scaleX = decoded.origW.toFloat() / decoded.bitmap.width.toFloat().coerceAtLeast(1f)
        val scaleY = decoded.origH.toFloat() / decoded.bitmap.height.toFloat().coerceAtLeast(1f)

        val filtered = detections.filter {
            val w = it.bounds.width() * scaleX
            val h = it.bounds.height() * scaleY
            w >= MIN_FACE_SIZE && h >= MIN_FACE_SIZE
        }

        return filtered.mapIndexed { index, face ->
            val origBounds = RectF(
                face.bounds.left * scaleX,
                face.bounds.top * scaleY,
                face.bounds.right * scaleX,
                face.bounds.bottom * scaleY
            )
            val origLandmarks = FloatArray(face.landmarks.size)
            for (k in 0 until face.landmarks.size / 2) {
                origLandmarks[k * 2] = face.landmarks[k * 2] * scaleX
                origLandmarks[k * 2 + 1] = face.landmarks[k * 2 + 1] * scaleY
            }
            RawFaceDetection(index, origBounds, origLandmarks, face.score)
        }
    }

    override fun embedFaces(uri: Uri, detections: List<RawFaceDetection>): List<DetectedFaceEmbedding> {
        if (detections.isEmpty()) {
            releaseDecoded(uri)
            return emptyList()
        }
        val decoded = getOrDecode(uri)
            ?: error("Could not decode image at URI: $uri")
        return try {
            val bitmap = decoded.bitmap
            val scaleDownX = bitmap.width.toFloat() / decoded.origW.toFloat().coerceAtLeast(1f)
            val scaleDownY = bitmap.height.toFloat() / decoded.origH.toFloat().coerceAtLeast(1f)

            detections.mapIndexedNotNull { _, raw ->
                if (raw.bounds.width() < MIN_FACE_SIZE || raw.bounds.height() < MIN_FACE_SIZE) {
                    return@mapIndexedNotNull null
                }
                val localBounds = RectF(
                    raw.bounds.left * scaleDownX,
                    raw.bounds.top * scaleDownY,
                    raw.bounds.right * scaleDownX,
                    raw.bounds.bottom * scaleDownY
                )
                val rawLm = raw.landmarks
                val localLandmarks = if (rawLm != null && rawLm.size >= 10) {
                    FloatArray(rawLm.size) { i ->
                        if (i % 2 == 0) rawLm[i] * scaleDownX else rawLm[i] * scaleDownY
                    }
                } else {
                    floatArrayOf(
                        localBounds.left + localBounds.width() * 0.32f, localBounds.top + localBounds.height() * 0.38f,
                        localBounds.left + localBounds.width() * 0.68f, localBounds.top + localBounds.height() * 0.38f,
                        localBounds.centerX(), localBounds.top + localBounds.height() * 0.56f,
                        localBounds.left + localBounds.width() * 0.38f, localBounds.top + localBounds.height() * 0.73f,
                        localBounds.left + localBounds.width() * 0.62f, localBounds.top + localBounds.height() * 0.73f
                    )
                }

                val aligned = EnteFaceAligner.align(bitmap, localLandmarks, localBounds)
                try {
                    val embedding = inferEmbedding(aligned)
                    val laplacian = FaceQualityMetrics.calculateLaplacianVariance(aligned)
                    val metrics = FaceQualityMetrics.compute(
                        boundsWidth = raw.bounds.width(),
                        boundsHeight = raw.bounds.height(),
                        imageWidth = decoded.origW,
                        imageHeight = decoded.origH,
                        confidence = raw.score,
                        landmarks = raw.landmarks,
                        laplacianVariance = laplacian
                    )
                    DetectedFaceEmbedding(raw.faceIndex, RectF(raw.bounds), embedding, raw.score, metrics.overallQuality, metrics)
                } finally {
                    aligned.recycle()
                }
            }
        } finally {
            releaseDecoded(uri)
        }
    }

    override fun embed(uri: Uri): List<DetectedFaceEmbedding> {
        val detections = detectFaces(uri)
        if (detections.isEmpty()) {
            releaseDecoded(uri)
            return emptyList()
        }
        return embedFaces(uri, detections)
    }

    override fun embedBitmap(bitmap: Bitmap): List<DetectedFaceEmbedding> {
        val detections = detect(bitmap)
        val valid = detections.filter { it.bounds.width() >= MIN_FACE_SIZE && it.bounds.height() >= MIN_FACE_SIZE }
        return valid.mapIndexed { index, face ->
            val aligned = EnteFaceAligner.align(bitmap, face.landmarks, face.bounds)
            try {
                val embedding = inferEmbedding(aligned)
                val laplacian = FaceQualityMetrics.calculateLaplacianVariance(aligned)
                val metrics = FaceQualityMetrics.compute(
                    boundsWidth = face.bounds.width(),
                    boundsHeight = face.bounds.height(),
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    confidence = face.score,
                    landmarks = face.landmarks,
                    laplacianVariance = laplacian
                )
                DetectedFaceEmbedding(index, RectF(face.bounds), embedding, face.score, metrics.overallQuality, metrics)
            } finally {
                aligned.recycle()
            }
        }
    }

    private fun decodeOrientedImage(uri: Uri, maxDimension: Int = 1600): DecodedImage? {
        val result = FaceImageDecoder.decodeOrientedImage(context, uri, maxDimension) ?: return null
        return DecodedImage(result.bitmap, result.originalWidth, result.originalHeight)
    }


    /** SCRFD-10GF: 640px input, BGR channel order with (pixel - 127.5) / 128 normalization. */
    private fun detect(bitmap: Bitmap): List<FaceDetection> {
        val scale = min(DETECT_SIZE.toFloat() / bitmap.width, DETECT_SIZE.toFloat() / bitmap.height)
        val resizedWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        val padded = Bitmap.createBitmap(DETECT_SIZE, DETECT_SIZE, Bitmap.Config.ARGB_8888)
        try {
            Canvas(padded).apply {
                drawColor(Color.BLACK)
                drawBitmap(resized, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            val input = imageTensorData(padded, DETECT_SIZE, 127.5f, 128f)
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input),
                longArrayOf(1, 3, DETECT_SIZE.toLong(), DETECT_SIZE.toLong())
            )
            try {
                detectorSession.run(mapOf(detectorSession.inputNames.first() to tensor)).use { result ->
                    val outputs = detectorSession.outputNames.map { name ->
                        val info = detectorSession.outputInfo[name]?.info as? TensorInfo
                        val shape = info?.shape ?: longArrayOf()
                        val values = flatten(result.get(name).get().value)
                        Output(name, shape, values)
                    }
                    return decodeScrfd(outputs, bitmap.width, bitmap.height, scale)
                }
            } finally {
                tensor.close()
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
            padded.recycle()
        }
    }

    private data class Output(val name: String, val shape: LongArray, val values: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Output) return false
            if (name != other.name) return false
            if (!shape.contentEquals(other.shape)) return false
            if (!values.contentEquals(other.values)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + shape.contentHashCode()
            result = 31 * result + values.contentHashCode()
            return result
        }
    }

    private data class StrideOutputs(
        val scoreOutput: Output,
        val boxOutput: Output,
        val keypointOutput: Output?,
        val numAnchors: Int
    )

    private fun resolveStrideOutputs(stride: Int, outputs: List<Output>): StrideOutputs {
        val grid = DETECT_SIZE / stride
        val gridCells = grid * grid
        val anchorCount2A = gridCells * 2
        val anchorCount1A = gridCells * 1
        val strideIndex = STRIDES.indexOf(stride)

        // 1. Explicit semantic name matching
        val scoreByName = outputs.firstOrNull {
            it.name.contains("score_$stride", ignoreCase = true) ||
            it.name.contains("scores_$stride", ignoreCase = true)
        }
        val boxByName = outputs.firstOrNull {
            it.name.contains("bbox_$stride", ignoreCase = true) ||
            it.name.contains("box_$stride", ignoreCase = true)
        }
        val kpsByName = outputs.firstOrNull {
            it.name.contains("kps_$stride", ignoreCase = true) ||
            it.name.contains("landmark_$stride", ignoreCase = true)
        }
        if (scoreByName != null && boxByName != null) {
            val numAnchors = if (scoreByName.values.size == anchorCount2A) 2 else 1
            return StrideOutputs(scoreByName, boxByName, kpsByName, numAnchors)
        }

        // 2. InsightFace SCRFD standard Feature Map Context (FMC) layout:
        // Stride 8: score index 0, bbox index fmc, kps index 2*fmc
        // Stride 16: score index 1, bbox index 1+fmc, kps index 1+2*fmc
        // Stride 32: score index 2, bbox index 2+fmc, kps index 2+2*fmc
        if (outputs.size >= 9 && strideIndex in 0..2) {
            val fmc = if (outputs.size >= 15) 5 else 3
            val scoreCandidate = outputs.getOrNull(strideIndex)
            val boxCandidate = outputs.getOrNull(strideIndex + fmc)
            val kpsCandidate = outputs.getOrNull(strideIndex + fmc * 2)
            if (scoreCandidate != null && boxCandidate != null) {
                val scoreLen = scoreCandidate.values.size
                val boxLen = boxCandidate.values.size
                if ((scoreLen == anchorCount2A || scoreLen == anchorCount1A) && boxLen == scoreLen * 4) {
                    val numAnchors = if (scoreLen == anchorCount2A) 2 else 1
                    return StrideOutputs(scoreCandidate, boxCandidate, kpsCandidate, numAnchors)
                }
            }
        }

        // 3. Sequential grouping by stride: [score_8, box_8, kps_8, score_16, box_16, kps_16, ...]
        if (outputs.size >= 9 && strideIndex in 0..2) {
            val scoreCandidate = outputs.getOrNull(strideIndex * 3)
            val boxCandidate = outputs.getOrNull(strideIndex * 3 + 1)
            val kpsCandidate = outputs.getOrNull(strideIndex * 3 + 2)
            if (scoreCandidate != null && boxCandidate != null) {
                val scoreLen = scoreCandidate.values.size
                val boxLen = boxCandidate.values.size
                if ((scoreLen == anchorCount2A || scoreLen == anchorCount1A) && boxLen == scoreLen * 4) {
                    val numAnchors = if (scoreLen == anchorCount2A) 2 else 1
                    return StrideOutputs(scoreCandidate, boxCandidate, kpsCandidate, numAnchors)
                }
            }
        }

        // 4. Exact element count matching:
        // Try 2 anchors per cell first (standard SCRFD-10G)
        var scoreOut = outputs.firstOrNull { it.values.size == anchorCount2A && isScoreTensor(it) }
            ?: outputs.firstOrNull { it.values.size == anchorCount2A }
        var boxOut = outputs.firstOrNull { it.values.size == anchorCount2A * 4 }
        var kpsOut = outputs.firstOrNull { it.values.size == anchorCount2A * 10 }
        var numAnchors = 2

        // If not found, try 1 anchor per cell
        if (scoreOut == null || boxOut == null) {
            scoreOut = outputs.firstOrNull { it.values.size == anchorCount1A && isScoreTensor(it) }
                ?: outputs.firstOrNull { it.values.size == anchorCount1A }
            boxOut = outputs.firstOrNull { it.values.size == anchorCount1A * 4 }
            kpsOut = outputs.firstOrNull { it.values.size == anchorCount1A * 10 }
            numAnchors = 1
        }

        check(scoreOut != null && boxOut != null) {
            val summary = outputs.joinToString(prefix = "[", postfix = "]") {
                "${it.name}: shape=${it.shape.contentToString()}, len=${it.values.size}"
            }
            "Unsupported SCRFD output layout for stride $stride (expected anchor counts: 2A=$anchorCount2A, 1A=$anchorCount1A). Available outputs (${outputs.size}): $summary"
        }

        return StrideOutputs(scoreOut, boxOut, kpsOut, numAnchors)
    }

    private fun isScoreTensor(output: Output): Boolean {
        val last = output.shape.lastOrNull() ?: -1L
        return last == 1L || output.shape.size == 2
    }

    private fun decodeScrfd(
        outputs: List<Output>,
        originalWidth: Int,
        originalHeight: Int,
        scale: Float
    ): List<FaceDetection> {
        val candidates = mutableListOf<FaceDetection>()
        for (stride in STRIDES) {
            val resolved = resolveStrideOutputs(stride, outputs)
            val scoreOutput = resolved.scoreOutput
            val boxOutput = resolved.boxOutput
            val keypointOutput = resolved.keypointOutput
            val numAnchors = resolved.numAnchors

            val scores = scoreOutput.values
            val boxes = boxOutput.values
            val keypoints = keypointOutput?.values
            val count = min(scores.size, boxes.size / 4)
            val grid = DETECT_SIZE / stride
            for (i in 0 until count) {
                val score = scores[i]
                if (!score.isFinite() || score < detectionThreshold) continue
                val cell = i / numAnchors
                val cx = (cell % grid) * stride.toFloat()
                val cy = (cell / grid) * stride.toFloat()
                val bo = i * 4
                val bounds = RectF(
                    ((cx - boxes[bo] * stride) / scale).coerceIn(0f, originalWidth.toFloat()),
                    ((cy - boxes[bo + 1] * stride) / scale).coerceIn(0f, originalHeight.toFloat()),
                    ((cx + boxes[bo + 2] * stride) / scale).coerceIn(0f, originalWidth.toFloat()),
                    ((cy + boxes[bo + 3] * stride) / scale).coerceIn(0f, originalHeight.toFloat())
                )
                if (bounds.width() < 1f || bounds.height() < 1f) continue
                val points = FloatArray(10)
                val ko = i * 10
                if (keypoints != null && ko + 9 < keypoints.size) {
                    for (point in 0 until 5) {
                        points[point * 2] = ((cx + keypoints[ko + point * 2] * stride) / scale)
                            .coerceIn(0f, originalWidth.toFloat())
                        points[point * 2 + 1] = ((cy + keypoints[ko + point * 2 + 1] * stride) / scale)
                            .coerceIn(0f, originalHeight.toFloat())
                    }
                } else {
                    points[0] = bounds.left + bounds.width() * 0.32f
                    points[1] = bounds.top + bounds.height() * 0.38f
                    points[2] = bounds.left + bounds.width() * 0.68f
                    points[3] = bounds.top + bounds.height() * 0.38f
                    points[4] = bounds.centerX()
                    points[5] = bounds.top + bounds.height() * 0.56f
                    points[6] = bounds.left + bounds.width() * 0.38f
                    points[7] = bounds.top + bounds.height() * 0.73f
                    points[8] = bounds.left + bounds.width() * 0.62f
                    points[9] = bounds.top + bounds.height() * 0.73f
                }
                candidates += FaceDetection(bounds, points, score)
            }
        }
        return nonMaximumSuppression(candidates)
    }

    /** ArcFace W600K_R50: 112px input, BGR channel order with (pixel - 127.5) / 127.5 normalization. */
    private fun inferEmbedding(face: Bitmap): FloatArray {
        val input = imageTensorData(face, RECOGNITION_SIZE, 127.5f, 127.5f)
        val tensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(), FloatBuffer.wrap(input),
            longArrayOf(1, 3, RECOGNITION_SIZE.toLong(), RECOGNITION_SIZE.toLong())
        )
        try {
            recognitionSession.run(mapOf(recognitionSession.inputNames.first() to tensor)).use { result ->
                val embedding = flatten(result.get(recognitionSession.outputNames.first()).get().value)
                check(embedding.size == EMBEDDING_DIMENSION) {
                    "Unexpected Buffalo_L embedding size: ${embedding.size} (expected $EMBEDDING_DIMENSION)."
                }
                var normSquared = 0.0
                embedding.forEach { normSquared += it * it }
                val norm = sqrt(normSquared).toFloat().coerceAtLeast(1e-12f)
                return FloatArray(embedding.size) { embedding[it] / norm }
            }
        } finally {
            tensor.close()
        }
    }

    /** Prepares CHW RGB float array tensor from bitmap pixels. */
    private fun imageTensorData(bitmap: Bitmap, size: Int, mean: Float, std: Float): FloatArray {
        val scaled = if (bitmap.width == size && bitmap.height == size) bitmap
            else Bitmap.createScaledBitmap(bitmap, size, size, true)
        try {
            val pixels = IntArray(size * size)
            scaled.getPixels(pixels, 0, size, 0, 0, size, size)
            val plane = size * size
            val input = FloatArray(3 * plane)
            for (i in pixels.indices) {
                val pixel = pixels[i]
                input[i] = (Color.red(pixel) - mean) / std
                input[plane + i] = (Color.green(pixel) - mean) / std
                input[2 * plane + i] = (Color.blue(pixel) - mean) / std
            }
            return input
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    private fun flatten(value: Any): FloatArray {
        val result = ArrayList<Float>()
        fun visit(item: Any?) {
            when {
                item == null -> Unit
                item is FloatArray -> item.forEach(result::add)
                item is Number -> result.add(item.toFloat())
                item.javaClass.isArray -> {
                    for (i in 0 until java.lang.reflect.Array.getLength(item)) {
                        visit(java.lang.reflect.Array.get(item, i))
                    }
                }
            }
        }
        visit(value)
        return result.toFloatArray()
    }

    private fun nonMaximumSuppression(input: List<FaceDetection>): List<FaceDetection> {
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<FaceDetection>()
        while (sorted.isNotEmpty() && kept.size < MAX_FACES) {
            val best = sorted.removeAt(0)
            kept += best
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                if (iou(best.bounds, iterator.next().bounds) > NMS_THRESHOLD) iterator.remove()
            }
        }
        return kept.sortedBy { it.bounds.left }
    }

    private fun iou(a: RectF, b: RectF): Float {
        val intersection = RectF(a)
        if (!intersection.intersect(b)) return 0f
        val area = intersection.width() * intersection.height()
        val union = a.width() * a.height() + b.width() * b.height() - area
        return if (union <= 0f) 0f else area / union
    }

    override fun close() {
        synchronized(this) {
            cachedDecoded?.second?.bitmap?.let { if (!it.isRecycled) it.recycle() }
            cachedDecoded = null
        }
        runCatching { detectorSession.close() }
        runCatching { recognitionSession.close() }
    }

    companion object {
        const val ENGINE_ID = "buffalo_l"
        const val MODEL_VERSION = "w600k_r50"
        const val EMBEDDING_DIMENSION = 512
        const val FORMAT_VERSION = 1

        private const val DETECT_SIZE = 640
        private const val RECOGNITION_SIZE = 112
        private const val MIN_FACE_SIZE = 28
        private const val NMS_THRESHOLD = 0.4f
        private const val MAX_FACES = 100
        private const val MIN_FREE_SPACE_BYTES = 60L * 1024 * 1024
        private val STRIDES = intArrayOf(8, 16, 32)
        private const val MODEL_URL =
            "https://github.com/deepinsight/insightface/releases/download/v0.7/buffalo_l.zip"
        private val REQUIRED_MODELS = listOf(
            "det_10g.onnx", "w600k_r50.onnx"
        )

        fun isModelInstalled(context: Context): Boolean {
            val dir = File(context.filesDir, "face-models/buffalo_l")
            return REQUIRED_MODELS.all { name ->
                dir.resolve(name).let { it.isFile && it.length() > 1024 * 1024 }
            }
        }

        private fun openConnectionWithRedirects(initialUrl: String, existingBytes: Long = 0L): HttpURLConnection {
            var url = initialUrl
            var redirects = 0
            while (redirects < 6) {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 30_000
                conn.readTimeout = 120_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) EmreGallery/1.0")
                if (existingBytes > 0L) {
                    conn.setRequestProperty("Range", "bytes=$existingBytes-")
                }
                conn.connect()
                val code = conn.responseCode
                if (code in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, 307, 308)) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!location.isNullOrBlank()) {
                        url = location
                        redirects++
                        continue
                    }
                }
                return conn
            }
            error("Too many redirects connecting to $initialUrl")
        }

        @Synchronized
        fun installBundledModel(context: Context) {
            if (isModelInstalled(context)) return
            val modelDir = File(context.filesDir, "face-models/buffalo_l")
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                error("Cannot create face model directory.")
            }
            val availableBytes = StatFs(modelDir.absolutePath).availableBytes
            check(availableBytes >= MIN_FREE_SPACE_BYTES) {
                "At least 60 MB of free storage is required to install Buffalo_L model."
            }

            REQUIRED_MODELS.forEach { name ->
                val target = modelDir.resolve(name)
                if (target.isFile && target.length() > 1024 * 1024) return@forEach
                val temporary = modelDir.resolve("$name.installing")
                try {
                    context.assets.open("face-models/buffalo_l/$name").use { input ->
                        temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() > 1024 * 1024) {
                        "Bundled Buffalo_L model file is incomplete: $name"
                    }
                    if (target.exists()) target.delete()
                    if (!temporary.renameTo(target)) temporary.copyTo(target, overwrite = true)
                } finally {
                    temporary.delete()
                }
            }
        }

        suspend fun downloadModel(
            context: Context,
            force: Boolean = false,
            onProgress: (Long, Long) -> Unit = { _, _ -> },
            onStage: (String) -> Unit = {},
            awaitIfResumed: suspend () -> Unit = {},
            onDetailedProgress: (DetailedDownloadProgress) -> Unit = {}
        ) {
            val modelDir = File(context.filesDir, "face-models/buffalo_l")
            if (!modelDir.exists() && !modelDir.mkdirs()) error("Cannot create face model directory.")
            if (!force && isModelInstalled(context)) {
                onStage("ready")
                onDetailedProgress(DetailedDownloadProgress(288L * 1024 * 1024, 288L * 1024 * 1024, 0L, 0L, "ready"))
                return
            }
            val availableBytes = StatFs(modelDir.absolutePath).availableBytes
            check(availableBytes >= MIN_FREE_SPACE_BYTES) {
                "At least 60 MB of free storage is required to install Buffalo_L."
            }

            val archive = File(modelDir, "buffalo_l.zip.part")
            val staging = File(modelDir, "installing").apply { deleteRecursively(); mkdirs() }
            val existingBytes = if (archive.exists()) archive.length() else 0L

            onStage("connecting")
            val estimatedTotal = 288L * 1024 * 1024
            onDetailedProgress(DetailedDownloadProgress(existingBytes, estimatedTotal, 0L, 0L, "connecting"))

            val connection = openConnectionWithRedirects(MODEL_URL, existingBytes)
            var responseTotal = estimatedTotal
            try {
                val code = connection.responseCode
                val isPartial = code == 206
                val isOk = code in 200..299

                if (!isOk && !isPartial) {
                    if (code != 416) {
                        error("Could not download Buffalo_L model pack (HTTP $code).")
                    }
                }

                val appendMode = isPartial && existingBytes > 0L
                val serverContentLength = connection.contentLengthLong.coerceAtLeast(-1L)
                val total = if (appendMode) {
                    existingBytes + (if (serverContentLength > 0) serverContentLength else 0L)
                } else if (serverContentLength > 0) {
                    serverContentLength
                } else {
                    estimatedTotal
                }
                responseTotal = total
                onStage("downloading")

                val downloadStartTime = System.currentTimeMillis()
                var received = if (appendMode) existingBytes else 0L
                var sessionDownloaded = 0L

                if (code != 416) {
                    BufferedInputStream(connection.inputStream).use { input ->
                        java.io.FileOutputStream(archive, appendMode).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                awaitIfResumed()
                                if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                sessionDownloaded += count

                                val elapsedSec = ((System.currentTimeMillis() - downloadStartTime) / 1000.0).coerceAtLeast(0.1)
                                val speed = (sessionDownloaded / elapsedSec).toLong()
                                val remainingBytes = (total - received).coerceAtLeast(0L)
                                val etaSec = if (speed > 0) remainingBytes / speed else 0L

                                onProgress(received, total)
                                onDetailedProgress(
                                    DetailedDownloadProgress(
                                        bytesReceived = received,
                                        totalBytes = total,
                                        speedBytesPerSec = speed,
                                        etaSeconds = etaSec,
                                        stage = "downloading"
                                    )
                                )
                            }
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }

            try {
                onStage("verifying")
                onDetailedProgress(
                    DetailedDownloadProgress(responseTotal, responseTotal, 0L, 0L, "verifying")
                )
                if (!archive.exists() || archive.length() < 10L * 1024 * 1024) {
                    archive.delete()
                    error("Downloaded archive is invalid or incomplete.")
                }

                onStage("extracting")
                onDetailedProgress(
                    DetailedDownloadProgress(responseTotal, responseTotal, 0L, 0L, "extracting")
                )
                ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        awaitIfResumed()
                        if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
                        if (!entry.isDirectory) {
                            val name = entry.name.substringAfterLast('/')
                            if (name.endsWith(".onnx") || name.contains("LICENSE", true)) {
                                val destination = staging.resolve(name)
                                destination.outputStream().buffered().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        awaitIfResumed()
                                        if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        output.write(buffer, 0, count)
                                    }
                                }
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
                val missing = REQUIRED_MODELS.filter { name ->
                    staging.resolve(name).let { !it.isFile || it.length() < 1024 * 1024 }
                }
                if (missing.isNotEmpty()) error("Incomplete Buffalo_L archive; missing: " + missing.joinToString())

                onStage("installing")
                onDetailedProgress(
                    DetailedDownloadProgress(responseTotal, responseTotal, 0L, 0L, "installing")
                )
                REQUIRED_MODELS.forEach { name ->
                    awaitIfResumed()
                    if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
                    val source = staging.resolve(name)
                    val target = modelDir.resolve(name)
                    if (target.exists()) target.delete()
                    if (!source.renameTo(target)) source.copyTo(target, overwrite = true)
                }
                staging.listFiles()?.filter { it.isFile && it.name.contains("LICENSE", true) }?.forEach {
                    it.copyTo(modelDir.resolve(it.name), overwrite = true)
                }
                check(isModelInstalled(context)) { "Buffalo_L model installation failed validation." }
                if (responseTotal > 0L) onProgress(responseTotal, responseTotal)
                onStage("ready")
                onDetailedProgress(
                    DetailedDownloadProgress(responseTotal, responseTotal, 0L, 0L, "ready")
                )
            } finally {
                archive.delete()
                staging.deleteRecursively()
            }
        }

        fun open(
            context: Context,
            detectionThreshold: Float = FaceRecognitionSettings.DEFAULT_DETECTION_CONFIDENCE
        ): BuffaloLFaceEngine {
            if (!isModelInstalled(context)) {
                runCatching { installBundledModel(context) }
            }
            check(isModelInstalled(context)) {
                "Buffalo_L model is not installed. Please download it first."
            }
            val modelDir = File(context.filesDir, "face-models/buffalo_l")
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            }
            val env = OrtEnvironment.getEnvironment()
            val detector = env.createSession(modelDir.resolve("det_10g.onnx").absolutePath, options)
            try {
                val recognizer = env.createSession(modelDir.resolve("w600k_r50.onnx").absolutePath, options)
                return BuffaloLFaceEngine(
                    context.applicationContext,
                    detector,
                    recognizer,
                    detectionThreshold.coerceIn(0.30f, 0.90f)
                )
            } catch (error: Throwable) {
                detector.close()
                throw error
            }
        }
    }
}

/**
 * Factory for Buffalo_L engine registered with FaceEngineRegistry.
 */
object BuffaloLFaceEngineFactory : FaceEngineFactory {
    override val engineId: String = BuffaloLFaceEngine.ENGINE_ID
    override val displayName: String = "Buffalo_L (InsightFace ONNX 512-d)"
    override val modelVersion: String = BuffaloLFaceEngine.MODEL_VERSION
    override val embeddingDimension: Int = BuffaloLFaceEngine.EMBEDDING_DIMENSION
    override val isExperimental: Boolean = false

    override fun isInstalled(context: Context): Boolean = BuffaloLFaceEngine.isModelInstalled(context)

    override fun create(context: Context, detectionThreshold: Float): FaceEngine =
        BuffaloLFaceEngine.open(context, detectionThreshold)

    override suspend fun downloadModel(
        context: Context,
        force: Boolean,
        onProgress: (Long, Long) -> Unit,
        onStage: (String) -> Unit,
        awaitIfResumed: suspend () -> Unit,
        onDetailedProgress: (DetailedDownloadProgress) -> Unit
    ) = BuffaloLFaceEngine.downloadModel(context, force, onProgress, onStage, awaitIfResumed, onDetailedProgress)

    init {
        FaceEngineRegistry.register(this)
    }
}
