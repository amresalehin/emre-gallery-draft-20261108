package com.amresalehin.emregallery.face

import android.graphics.Bitmap

/**
 * Typed, versioned quality signals for a detected face observation.
 *
 * All scores are normalized to [0.0, 1.0], where higher values indicate higher quality.
 *
 * @property blurScore Metric for sharpness / lack of blur (0.0 = severe blur, 1.0 = crystal sharp).
 * @property poseScore Metric for frontal face orientation and landmark symmetry (0.0 = extreme profile/tilt, 1.0 = direct frontal).
 * @property sizeRatio Ratio of face bounding box area to full image area (e.g. 0.001 to 1.0).
 * @property aspectRatio Bounding box width / height (standard human face is ~0.8 to 0.85).
 * @property overallQuality Bounded composite quality score utilized during clustering.
 * @property version Metrics calculation algorithm version.
 */
data class FaceQualityMetrics(
    val blurScore: Float = 1.0f,
    val poseScore: Float = 1.0f,
    val sizeRatio: Float = 0.05f,
    val aspectRatio: Float = 0.85f,
    val overallQuality: Float = 1.0f,
    val version: Int = VERSION
) {
    init {
        require(blurScore in 0.0f..1.0f) { "blurScore must be in [0.0, 1.0]" }
        require(poseScore in 0.0f..1.0f) { "poseScore must be in [0.0, 1.0]" }
        require(overallQuality in 0.0f..1.0f) { "overallQuality must be in [0.0, 1.0]" }
    }

    companion object {
        const val VERSION = 1

        val DEFAULT = FaceQualityMetrics(
            blurScore = 1.0f,
            poseScore = 1.0f,
            sizeRatio = 0.05f,
            aspectRatio = 0.85f,
            overallQuality = 1.0f,
            version = VERSION
        )

        /**
         * Computes face quality metrics from face detection geometry and cropped face properties.
         */
        fun compute(
            boundsWidth: Float,
            boundsHeight: Float,
            imageWidth: Int,
            imageHeight: Int,
            confidence: Float,
            landmarks: FloatArray? = null,
            laplacianVariance: Float? = null
        ): FaceQualityMetrics {
            val faceArea = (boundsWidth * boundsHeight).coerceAtLeast(1f)
            val imgArea = (imageWidth.toFloat() * imageHeight.toFloat()).coerceAtLeast(1f)
            val size = (faceArea / imgArea).coerceIn(0.0001f, 1.0f)

            val aspect = if (boundsHeight > 0f) boundsWidth / boundsHeight else 1.0f
            // Standard human face width-to-height ratio is ~0.85
            val aspectScore = (1.0f - kotlin.math.abs(aspect - 0.85f).coerceAtMost(0.85f)).coerceIn(0.1f, 1.0f)

            // Blur score based on Laplacian variance or high frequency contrast
            // Variance < 100 is typically blurry; >= 500 is sharp.
            val blur = if (laplacianVariance != null && laplacianVariance.isFinite()) {
                (laplacianVariance / 400.0f).coerceIn(0.05f, 1.0f)
            } else {
                // If not supplied, approximate from confidence and resolution
                (confidence * 0.7f + (boundsWidth / 80f).coerceIn(0.1f, 0.3f)).coerceIn(0.2f, 1.0f)
            }

            // Pose score based on 5 landmarks: (le_x, le_y, re_x, re_y, nose_x, nose_y, ml_x, ml_y, mr_x, mr_y)
            var pose = 0.8f
            if (landmarks != null && landmarks.size >= 10) {
                val leX = landmarks[0]
                val leY = landmarks[1]
                val reX = landmarks[2]
                val reY = landmarks[3]
                val noseX = landmarks[4]
                val noseY = landmarks[5]

                // 1. Eye level tilt (horizontal symmetry)
                val eyeDx = kotlin.math.abs(reX - leX).coerceAtLeast(1f)
                val eyeDy = kotlin.math.abs(reY - leY)
                val eyeTiltScore = (1.0f - (eyeDy / eyeDx)).coerceIn(0.1f, 1.0f)

                // 2. Nose horizontal centering between eyes (frontal yaw)
                val eyeMidX = (leX + reX) / 2.0f
                val noseDeviation = kotlin.math.abs(noseX - eyeMidX) / eyeDx
                val noseCenteringScore = (1.0f - noseDeviation * 2.0f).coerceIn(0.1f, 1.0f)

                pose = (eyeTiltScore * 0.5f + noseCenteringScore * 0.5f).coerceIn(0.1f, 1.0f)
            }

            // Overall quality: weighted combination
            val overall = (confidence * 0.35f + blur * 0.35f + pose * 0.20f + kotlin.math.min(size * 10f, 0.10f))
                .coerceIn(0.05f, 1.0f)

            return FaceQualityMetrics(
                blurScore = blur,
                poseScore = pose,
                sizeRatio = size,
                aspectRatio = aspect,
                overallQuality = overall,
                version = VERSION
            )
        }

        /**
         * Calculates the variance of the 3x3 Laplacian operator over the bitmap's luminance.
         * Higher values indicate high frequency edges and sharpness, while lower values indicate blur.
         */
        fun calculateLaplacianVariance(bitmap: Bitmap): Float {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 3 || height < 3) return 0f

            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val gray = FloatArray(width * height)
            for (i in pixels.indices) {
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                gray[i] = 0.299f * r + 0.587f * g + 0.114f * b
            }

            var sum = 0.0
            var sumSq = 0.0
            var count = 0
            for (y in 1 until height - 1) {
                val yOffset = y * width
                for (x in 1 until width - 1) {
                    val center = gray[yOffset + x]
                    val lap = gray[yOffset + x + 1] + gray[yOffset + x - 1] +
                              gray[yOffset + width + x] + gray[yOffset - width + x] - 4.0f * center
                    sum += lap
                    sumSq += lap * lap
                    count++
                }
            }
            if (count == 0) return 0f
            val mean = sum / count
            val variance = (sumSq / count) - (mean * mean)
            return variance.toFloat().coerceAtLeast(0f)
        }
    }
}
