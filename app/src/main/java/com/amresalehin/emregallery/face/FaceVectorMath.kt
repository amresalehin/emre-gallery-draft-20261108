package com.amresalehin.emregallery.face

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Dedicated vector math algorithms for face embeddings:
 * normalization, cosine distance, Euclidean distance, and byte buffer serialization.
 */
object FaceVectorMath {

    fun normalize(values: FloatArray): FloatArray {
        val norm = sqrt(values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        if (norm <= 1e-12f) return values.copyOf()
        return FloatArray(values.size) { values[it] / norm }
    }

    fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.POSITIVE_INFINITY
        var similarity = 0f
        for (i in a.indices) similarity += a[i] * b[i]
        return (1f - similarity).coerceAtLeast(0f)
    }

    fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return Float.POSITIVE_INFINITY
        var sum = 0.0
        for (i in a.indices) {
            val diff = (a[i] - b[i]).toDouble()
            sum += diff * diff
        }
        return sqrt(sum).toFloat()
    }

    fun floatsToBytes(values: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        values.forEach(buffer::putFloat)
        return buffer.array()
    }

    fun bytesToFloats(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        return FloatArray(bytes.size / Float.SIZE_BYTES) { buffer.getFloat() }
    }
}
