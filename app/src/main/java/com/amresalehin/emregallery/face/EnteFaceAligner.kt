package com.amresalehin.emregallery.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.sqrt

/**
 * 5-point landmark based similarity-transform face alignment for MobileFaceNet.
 * Standardizes face crops to 112x112 canonical reference geometry:
 * - Landmark order: Left Eye, Right Eye, Nose, Left Mouth Corner, Right Mouth Corner.
 * - Reference points based on standard InsightFace / MobileFaceNet 112x112 alignment template.
 */
object EnteFaceAligner {

    const val TARGET_SIZE = 112

    // Canonical 112x112 reference landmarks (InsightFace standard)
    val REFERENCE_LANDMARKS = floatArrayOf(
        38.2946f, 51.6963f, // Left eye
        73.5318f, 51.5014f, // Right eye
        56.0252f, 71.7366f, // Nose tip
        41.5493f, 92.3655f, // Left mouth corner
        70.7299f, 92.2041f  // Right mouth corner
    )

    /**
     * Estimates similarity transform mapping source landmarks to reference landmarks.
     * Produces a 3x3 affine matrix for drawing onto a 112x112 canvas.
     */
    fun estimateSimilarityTransform(sourceLandmarks: FloatArray): Matrix? {
        if (sourceLandmarks.size < 10) return null

        val numPoints = 5
        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f

        for (i in 0 until numPoints) {
            srcMeanX += sourceLandmarks[i * 2]
            srcMeanY += sourceLandmarks[i * 2 + 1]
            dstMeanX += REFERENCE_LANDMARKS[i * 2]
            dstMeanY += REFERENCE_LANDMARKS[i * 2 + 1]
        }
        srcMeanX /= numPoints
        srcMeanY /= numPoints
        dstMeanX /= numPoints
        dstMeanY /= numPoints

        var varSrc = 0.0
        var numA = 0.0
        var numB = 0.0

        for (i in 0 until numPoints) {
            val sx = (sourceLandmarks[i * 2] - srcMeanX).toDouble()
            val sy = (sourceLandmarks[i * 2 + 1] - srcMeanY).toDouble()
            val dx = (REFERENCE_LANDMARKS[i * 2] - dstMeanX).toDouble()
            val dy = (REFERENCE_LANDMARKS[i * 2 + 1] - dstMeanY).toDouble()

            varSrc += sx * sx + sy * sy
            numA += sx * dx + sy * dy
            numB += sx * dy - sy * dx
        }

        if (varSrc < 1e-6) {
            // Degenerate points (collinear or zero variance)
            return null
        }

        val a = (numA / varSrc).toFloat()
        val b = (numB / varSrc).toFloat()
        val scale = sqrt(a * a + b * b)

        if (scale < 1e-4f || !scale.isFinite()) return null

        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)

        val matrix = Matrix()
        matrix.setValues(
            floatArrayOf(
                a, -b, tx,
                b,  a, ty,
                0f, 0f, 1f
            )
        )
        return matrix
    }

    /**
     * Aligns a face from [sourceBitmap] into a new 112x112 [Bitmap] using landmark alignment.
     * If alignment fails due to degenerate landmarks, falls back to an aspect-preserving bounding box crop.
     */
    fun align(sourceBitmap: Bitmap, landmarks: FloatArray, bounds: RectF? = null): Bitmap {
        val matrix = estimateSimilarityTransform(landmarks)
        val alignedBitmap = Bitmap.createBitmap(TARGET_SIZE, TARGET_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alignedBitmap)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        if (matrix != null) {
            canvas.drawBitmap(sourceBitmap, matrix, paint)
        } else if (bounds != null && bounds.width() > 1f && bounds.height() > 1f) {
            // Fallback: centered crop from bounds expanded by 20%
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            val size = (kotlin.math.max(bounds.width(), bounds.height()) * 1.2f).coerceAtLeast(16f)

            val srcLeft = (cx - size / 2f).coerceIn(0f, sourceBitmap.width.toFloat())
            val srcTop = (cy - size / 2f).coerceIn(0f, sourceBitmap.height.toFloat())
            val srcRight = (cx + size / 2f).coerceIn(0f, sourceBitmap.width.toFloat())
            val srcBottom = (cy + size / 2f).coerceIn(0f, sourceBitmap.height.toFloat())

            val srcRect = android.graphics.Rect(srcLeft.toInt(), srcTop.toInt(), srcRight.toInt(), srcBottom.toInt())
            val dstRect = android.graphics.Rect(0, 0, TARGET_SIZE, TARGET_SIZE)
            canvas.drawBitmap(sourceBitmap, srcRect, dstRect, paint)
        }

        return alignedBitmap
    }
}
