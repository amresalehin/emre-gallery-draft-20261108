package com.amresalehin.emregallery.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

data class EnteDetection(
    val bounds: RectF,
    val landmarks: FloatArray,
    val score: Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnteDetection) return false
        if (bounds != other.bounds) return false
        if (!landmarks.contentEquals(other.landmarks)) return false
        if (score != other.score) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bounds.hashCode()
        result = 31 * result + landmarks.contentHashCode()
        result = 31 * result + score.hashCode()
        return result
    }
}

/**
 * YOLO5Face detector supporting YOLOv5n-face ONNX models:
 * - 640x640 input resolution with aspect-ratio letterboxing.
 * - RGB channel order normalized to [0.0, 1.0].
 * - Decodes bounding boxes and 5 facial landmarks.
 * - Non-Maximum Suppression with deterministic tie-breaking.
 */
class EnteFaceDetector(
    private val session: OrtSession,
    private val detectionThreshold: Float = EnteFaceConstants.DEFAULT_DETECTION_CONFIDENCE,
    private val nmsThreshold: Float = EnteFaceConstants.DEFAULT_NMS_IOU_THRESHOLD
) {

    companion object {
        const val INPUT_SIZE = EnteFaceConstants.DETECT_SIZE // 640
        private const val MAX_CANDIDATES = 500
        private const val MAX_OUTPUT_FACES = 100

        /**
         * Decodes a flat float array from a YOLO5Face output tensor [1, N, 16].
         */
        fun decodeTensorOutput(
            flatValues: FloatArray,
            numAnchors: Int,
            stridePerAnchor: Int = 16,
            originalWidth: Int,
            originalHeight: Int,
            scale: Float,
            padX: Float,
            padY: Float,
            confidenceThreshold: Float,
            nmsIoUThreshold: Float
        ): List<EnteDetection> {
            val candidates = ArrayList<EnteDetection>()
            val maxRows = min(numAnchors, flatValues.size / stridePerAnchor)

            for (i in 0 until maxRows) {
                val offset = i * stridePerAnchor
                val objConf = flatValues[offset + 4]
                val classConf = if (stridePerAnchor >= 16) flatValues[offset + 15] else if (stridePerAnchor > 5) flatValues[offset + 5] else 1.0f
                val score = objConf * classConf

                if (!score.isFinite() || score < confidenceThreshold) continue

                val cx = flatValues[offset + 0]
                val cy = flatValues[offset + 1]
                val w = flatValues[offset + 2]
                val h = flatValues[offset + 3]

                if (w <= 0f || h <= 0f || !cx.isFinite() || !cy.isFinite()) continue

                val left640 = cx - w / 2.0f
                val top640 = cy - h / 2.0f
                val right640 = cx + w / 2.0f
                val bottom640 = cy + h / 2.0f

                val left = ((left640 - padX) / scale).coerceIn(0f, originalWidth.toFloat())
                val top = ((top640 - padY) / scale).coerceIn(0f, originalHeight.toFloat())
                val right = ((right640 - padX) / scale).coerceIn(0f, originalWidth.toFloat())
                val bottom = ((bottom640 - padY) / scale).coerceIn(0f, originalHeight.toFloat())

                if (right - left < 2f || bottom - top < 2f) continue
                val bounds = RectF(left, top, right, bottom)

                val landmarks = FloatArray(10)
                if (stridePerAnchor >= 16) {
                    for (k in 0 until 5) {
                        val lx640 = flatValues[offset + 5 + k * 2]
                        val ly640 = flatValues[offset + 5 + k * 2 + 1]
                        landmarks[k * 2] = ((lx640 - padX) / scale).coerceIn(0f, originalWidth.toFloat())
                        landmarks[k * 2 + 1] = ((ly640 - padY) / scale).coerceIn(0f, originalHeight.toFloat())
                    }
                } else {
                    // Approximate landmarks from bounds if model does not export them
                    landmarks[0] = bounds.left + bounds.width() * 0.32f
                    landmarks[1] = bounds.top + bounds.height() * 0.38f
                    landmarks[2] = bounds.left + bounds.width() * 0.68f
                    landmarks[3] = bounds.top + bounds.height() * 0.38f
                    landmarks[4] = bounds.centerX()
                    landmarks[5] = bounds.top + bounds.height() * 0.56f
                    landmarks[6] = bounds.left + bounds.width() * 0.38f
                    landmarks[7] = bounds.top + bounds.height() * 0.73f
                    landmarks[8] = bounds.left + bounds.width() * 0.62f
                    landmarks[9] = bounds.top + bounds.height() * 0.73f
                }

                candidates.add(EnteDetection(bounds, landmarks, score))
            }

            return applyNms(candidates, nmsIoUThreshold)
        }

        fun applyNms(detections: List<EnteDetection>, iouThreshold: Float): List<EnteDetection> {
            if (detections.isEmpty()) return emptyList()

            // Deterministic sort: score descending, then coordinates for exact tie breaking
            val sorted = detections.sortedWith(
                compareByDescending<EnteDetection> { it.score }
                    .thenBy { it.bounds.left }
                    .thenBy { it.bounds.top }
                    .thenBy { it.bounds.right }
                    .thenBy { it.bounds.bottom }
            )

            val accepted = ArrayList<EnteDetection>()
            for (candidate in sorted) {
                var overlaps = false
                for (chosen in accepted) {
                    if (calculateIoU(candidate.bounds, chosen.bounds) >= iouThreshold) {
                        overlaps = true
                        break
                    }
                }
                if (!overlaps) {
                    accepted.add(candidate)
                    if (accepted.size >= MAX_OUTPUT_FACES) break
                }
            }
            return accepted
        }

        fun calculateIoU(a: RectF, b: RectF): Float {
            val intersectionLeft = max(a.left, b.left)
            val intersectionTop = max(a.top, b.top)
            val intersectionRight = min(a.right, b.right)
            val intersectionBottom = min(a.bottom, b.bottom)

            val intersectionWidth = max(0f, intersectionRight - intersectionLeft)
            val intersectionHeight = max(0f, intersectionBottom - intersectionTop)
            val intersectionArea = intersectionWidth * intersectionHeight

            val areaA = a.width() * a.height()
            val areaB = b.width() * b.height()
            val unionArea = areaA + areaB - intersectionArea

            return if (unionArea > 0f) intersectionArea / unionArea else 0f
        }
    }

    fun detect(bitmap: Bitmap): List<EnteDetection> {
        val origW = bitmap.width
        val origH = bitmap.height

        val scale = min(INPUT_SIZE.toFloat() / origW, INPUT_SIZE.toFloat() / origH)
        val newW = (origW * scale).toInt().coerceAtLeast(1)
        val newH = (origH * scale).toInt().coerceAtLeast(1)
        val padX = (INPUT_SIZE - newW) / 2.0f
        val padY = (INPUT_SIZE - newH) / 2.0f

        val letterboxed = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.BLACK)

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        canvas.drawBitmap(scaled, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
        if (scaled !== bitmap) scaled.recycle()

        try {
            val inputBuffer = preprocessToRgbNormalized(letterboxed)
            val tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(),
                FloatBuffer.wrap(inputBuffer),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
            )

            try {
                val inputName = session.inputNames.first()
                session.run(mapOf(inputName to tensor)).use { results ->
                    val outputName = session.outputNames.first()
                    val outputValue = results.get(outputName).get().value
                    val flatFloats = flattenFloatArray(outputValue)

                    val numAnchors = 25200 // Default YOLOv5 640x640 anchor count
                    check(flatFloats.size >= numAnchors * 16) {
                        "Unexpected YOLOv5Face output tensor size: ${flatFloats.size}, expected at least ${numAnchors * 16} (25,200 anchors x 16 attributes)"
                    }
                    val stride = 16

                    return decodeTensorOutput(
                        flatValues = flatFloats,
                        numAnchors = numAnchors,
                        stridePerAnchor = stride,
                        originalWidth = origW,
                        originalHeight = origH,
                        scale = scale,
                        padX = padX,
                        padY = padY,
                        confidenceThreshold = detectionThreshold,
                        nmsIoUThreshold = nmsThreshold
                    )
                }
            } finally {
                tensor.close()
            }
        } finally {
            letterboxed.recycle()
        }
    }

    private fun preprocessToRgbNormalized(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val floatData = FloatArray(3 * w * h)
        val planeSize = w * h

        for (i in pixels.indices) {
            val p = pixels[i]
            // YOLOv5 standard expects RGB in [0.0, 1.0]
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f

            floatData[i] = r
            floatData[planeSize + i] = g
            floatData[planeSize * 2 + i] = b
        }
        return floatData
    }

    private fun flattenFloatArray(value: Any): FloatArray {
        return when (value) {
            is FloatArray -> value
            is Array<*> -> {
                var total = 0
                fun count(arr: Array<*>) {
                    for (item in arr) {
                        when (item) {
                            is Float -> total++
                            is FloatArray -> total += item.size
                            is Array<*> -> count(item)
                        }
                    }
                }
                count(value)
                val out = FloatArray(total)
                var idx = 0
                fun copy(arr: Array<*>) {
                    for (item in arr) {
                        when (item) {
                            is Float -> out[idx++] = item
                            is FloatArray -> {
                                System.arraycopy(item, 0, out, idx, item.size)
                                idx += item.size
                            }
                            is Array<*> -> copy(item)
                        }
                    }
                }
                copy(value)
                out
            }
            else -> FloatArray(0)
        }
    }
}
