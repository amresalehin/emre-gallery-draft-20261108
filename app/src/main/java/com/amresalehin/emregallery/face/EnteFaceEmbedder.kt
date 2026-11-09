package com.amresalehin.emregallery.face

import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * MobileFaceNet face embedding inferrer for Ente-style pipeline.
 * - Input: 112x112 aligned face crop.
 * - Preprocessing: (pixel - 127.5) / 128.0 normalization.
 * - Output: L2-normalized feature vector verified against model metadata.
 */
class EnteFaceEmbedder(
    private val session: OrtSession,
    val expectedDimension: Int = EnteFaceConstants.EMBEDDING_DIMENSION
) {
    val actualDimension: Int

    init {
        val outputName = session.outputNames.first()
        val info = session.outputInfo[outputName]?.info as? TensorInfo
        val shape = info?.shape
        actualDimension = if (shape != null && shape.isNotEmpty()) {
            shape.last().toInt().takeIf { it > 0 } ?: expectedDimension
        } else {
            expectedDimension
        }
    }

    fun infer(alignedFace: Bitmap): FloatArray {
        check(alignedFace.width == EnteFaceConstants.RECOGNITION_SIZE && alignedFace.height == EnteFaceConstants.RECOGNITION_SIZE) {
            "Aligned face bitmap must be ${EnteFaceConstants.RECOGNITION_SIZE}x${EnteFaceConstants.RECOGNITION_SIZE}"
        }

        val input = preprocess(alignedFace)
        val tensor = OnnxTensor.createTensor(
            OrtEnvironment.getEnvironment(),
            FloatBuffer.wrap(input),
            longArrayOf(1, 3, EnteFaceConstants.RECOGNITION_SIZE.toLong(), EnteFaceConstants.RECOGNITION_SIZE.toLong())
        )

        try {
            val inputName = session.inputNames.first()
            session.run(mapOf(inputName to tensor)).use { results ->
                val outputName = session.outputNames.first()
                val rawOutput = flatten(results.get(outputName).get().value)

                check(rawOutput.size == actualDimension) {
                    "MobileFaceNet output dimension mismatch: expected $actualDimension, got ${rawOutput.size}"
                }

                // L2 normalization
                var sumSq = 0.0
                for (v in rawOutput) {
                    check(v.isFinite()) { "MobileFaceNet returned non-finite embedding component." }
                    sumSq += v * v
                }
                val norm = sqrt(sumSq).toFloat().coerceAtLeast(1e-12f)
                val normalized = FloatArray(rawOutput.size) { rawOutput[it] / norm }
                return normalized
            }
        } finally {
            tensor.close()
        }
    }

    companion object {
        fun preprocess(bitmap: Bitmap): FloatArray {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

            val floatData = FloatArray(3 * w * h)
            val planeSize = w * h

            for (i in pixels.indices) {
                val p = pixels[i]
                // MobileFaceNet standard normalization: (x - 127.5) / 128.0
                val r = (((p shr 16) and 0xFF) - 127.5f) / 128.0f
                val g = (((p shr 8) and 0xFF) - 127.5f) / 128.0f
                val b = ((p and 0xFF) - 127.5f) / 128.0f

                floatData[i] = r
                floatData[planeSize + i] = g
                floatData[planeSize * 2 + i] = b
            }
            return floatData
        }

        fun flatten(value: Any): FloatArray {
            return when (value) {
                is FloatArray -> value
                is Array<*> -> {
                    val list = ArrayList<Float>()
                    fun recurse(arr: Array<*>) {
                        for (item in arr) {
                            when (item) {
                                is Float -> list.add(item)
                                is FloatArray -> item.forEach { list.add(it) }
                                is Array<*> -> recurse(item)
                            }
                        }
                    }
                    recurse(value)
                    list.toFloatArray()
                }
                else -> FloatArray(0)
            }
        }
    }
}
