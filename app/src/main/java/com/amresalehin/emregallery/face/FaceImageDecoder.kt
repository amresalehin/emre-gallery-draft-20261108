package com.amresalehin.emregallery.face

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

data class DecodedOrientedImage(
    val bitmap: Bitmap,
    val originalWidth: Int,
    val originalHeight: Int
)

/**
 * Robust image decoder for Android MediaStore content URIs, file URIs, and local files.
 * Handles Scoped Storage, ParcelFileDescriptors, modern ImageDecoder, seekable stream buffering,
 * and EXIF orientation normalization.
 */
object FaceImageDecoder {
    private const val TAG = "FaceImageDecoder"

    fun decodeOrientedImage(context: Context, uri: Uri, maxDimension: Int = 1600): DecodedOrientedImage? {
        val targetUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_CONTENT) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

        // 1. Direct local file support
        val scheme = uri.scheme
        if (scheme == null || scheme == ContentResolver.SCHEME_FILE || scheme == "file") {
            val path = uri.path ?: uri.toString()
            val file = File(path)
            if (file.exists() && file.canRead()) {
                val direct = decodeFromFile(file, maxDimension)
                if (direct != null) return direct
            }
        }

        // 2. Modern ImageDecoder on Android 9+ (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = if (scheme == null || scheme == ContentResolver.SCHEME_FILE || scheme == "file") {
                    val file = File(uri.path ?: uri.toString())
                    if (file.exists() && file.canRead()) {
                        ImageDecoder.createSource(file)
                    } else {
                        ImageDecoder.createSource(context.contentResolver, targetUri)
                    }
                } else {
                    ImageDecoder.createSource(context.contentResolver, targetUri)
                }
                var origW = 0
                var origH = 0
                val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    origW = info.size.width
                    origH = info.size.height
                    var sampleSize = 1
                    val maxDim = maxOf(info.size.width, info.size.height)
                    while ((maxDim / sampleSize) > maxDimension) {
                        sampleSize *= 2
                    }
                    if (sampleSize > 1) {
                        decoder.setTargetSampleSize(sampleSize)
                    }
                    // Software allocator ensures pixel access for ONNX Runtime and OpenCV models
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }

                val finalBmp = if (bmp.config != Bitmap.Config.ARGB_8888) {
                    val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
                    bmp.recycle()
                    copy
                } else {
                    bmp
                }

                if (finalBmp != null && finalBmp.width > 0 && finalBmp.height > 0) {
                    DecodedOrientedImage(
                        bitmap = finalBmp,
                        originalWidth = if (origW > 0) origW else finalBmp.width,
                        originalHeight = if (origH > 0) origH else finalBmp.height
                    )
                } else null
            }.onFailure { err ->
                Log.d(TAG, "ImageDecoder skipped for URI $uri (${err.message}); trying FileDescriptor fallback")
            }.getOrNull()

            if (decoded != null) return decoded
        }

        // 3. Try ParcelFileDescriptor with seekable decodeFileDescriptor
        val fdDecoded = runCatching {
            val pfd = runCatching { context.contentResolver.openFileDescriptor(targetUri, "r") }.getOrNull()
                ?: runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
            pfd?.use { decodeFromFd(it, maxDimension) }
        }.onFailure { err ->
            Log.d(TAG, "FileDescriptor decode failed for $uri: ${err.message}")
        }.getOrNull()

        if (fdDecoded != null) return fdDecoded

        // 4. Fallback to buffered byte array stream decoding
        val streamDecoded = runCatching {
            val stream = runCatching { context.contentResolver.openInputStream(targetUri) }.getOrNull()
                ?: runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                ?: openFileStream(uri)
            stream?.use { input ->
                val bytes = input.readBytes()
                if (bytes.isNotEmpty()) {
                    decodeFromByteArray(bytes, maxDimension)
                } else null
            }
        }.onFailure { err ->
            Log.e(TAG, "Buffered stream decode failed for $uri", err)
        }.getOrNull()

        if (streamDecoded != null) return streamDecoded

        Log.e(TAG, "All decoding strategies exhausted for image at URI: $uri")
        return null
    }

    private fun decodeFromFile(file: File, maxDimension: Int): DecodedOrientedImage? {
        return runCatching {
            val orientation = runCatching {
                ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            val isTransposed = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                    orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                    orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                    orientation == ExifInterface.ORIENTATION_TRANSVERSE
            val origW = if (isTransposed) rawH else rawW
            val origH = if (isTransposed) rawW else rawH

            var inSampleSize = 1
            while ((rawW / inSampleSize) > maxDimension || (rawH / inSampleSize) > maxDimension) {
                inSampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            val oriented = applyExifOrientation(decoded, orientation)
            DecodedOrientedImage(oriented, origW, origH)
        }.getOrNull()
    }

    private fun decodeFromFd(pfd: ParcelFileDescriptor, maxDimension: Int): DecodedOrientedImage? {
        val fd = pfd.fileDescriptor
        val orientation = runCatching {
            ExifInterface(fd).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        runCatching { Os.lseek(fd, 0, OsConstants.SEEK_SET) }

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val isTransposed = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val origW = if (isTransposed) rawH else rawW
        val origH = if (isTransposed) rawW else rawH

        var inSampleSize = 1
        while ((rawW / inSampleSize) > maxDimension || (rawH / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }

        runCatching { Os.lseek(fd, 0, OsConstants.SEEK_SET) }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions) ?: return null
        val oriented = applyExifOrientation(decoded, orientation)
        return DecodedOrientedImage(oriented, origW, origH)
    }

    private fun decodeFromByteArray(bytes: ByteArray, maxDimension: Int): DecodedOrientedImage? {
        val orientation = runCatching {
            ByteArrayInputStream(bytes).use { stream ->
                ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, boundsOptions)
        val rawW = boundsOptions.outWidth
        val rawH = boundsOptions.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        val isTransposed = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
                orientation == ExifInterface.ORIENTATION_TRANSPOSE ||
                orientation == ExifInterface.ORIENTATION_TRANSVERSE
        val origW = if (isTransposed) rawH else rawW
        val origH = if (isTransposed) rawW else rawH

        var inSampleSize = 1
        while ((rawW / inSampleSize) > maxDimension || (rawH / inSampleSize) > maxDimension) {
            inSampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions) ?: return null
        val oriented = applyExifOrientation(decoded, orientation)
        return DecodedOrientedImage(oriented, origW, origH)
    }

    private fun openFileStream(uri: Uri): InputStream? {
        val path = uri.path ?: uri.toString()
        val file = File(path)
        return if (file.exists() && file.canRead()) file.inputStream() else null
    }

    private fun applyExifOrientation(decoded: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        return if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        } else {
            decoded
        }
    }
}
