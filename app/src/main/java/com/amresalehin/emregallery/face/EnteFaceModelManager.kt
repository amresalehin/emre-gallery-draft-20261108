package com.amresalehin.emregallery.face

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

enum class EnteModelState {
    NOT_DOWNLOADED,
    DOWNLOADING,
    INSTALLED_AND_VALID,
    CORRUPT_OR_INCOMPLETE,
    UNSUPPORTED_OR_RUNTIME_FAILURE,
    LICENSE_OR_SOURCE_BLOCKED
}

data class EnteModelArtifact(
    val filename: String,
    val expectedMinBytes: Long,
    val sha256Hex: String? = null,
    val downloadUrl: String? = null
)

/**
 * Isolated model manager for the Ente-style YOLO5Face + MobileFaceNet pipeline.
 * All files are managed in a private directory separate from Buffalo_L.
 */
object EnteFaceModelManager {

    val REQUIRED_ARTIFACTS = listOf(
        EnteModelArtifact(
            filename = EnteFaceConstants.DETECTOR_FILENAME,
            expectedMinBytes = 10_000_000L, // ~11.0MB for yolov5n_face
            sha256Hex = "eb244a06e36999db732b317c2b30fa113cd6cfc1a397eaf738f2d6f33c01f640",
            downloadUrl = "https://github.com/yakhyo/yolov5-face-onnx-inference/releases/download/weights/yolov5n_face.onnx"
        ),
        EnteModelArtifact(
            filename = EnteFaceConstants.EMBEDDER_FILENAME,
            expectedMinBytes = 10_000_000L, // ~13.6MB for MobileFaceNet ONNX
            sha256Hex = "9cc6e4a75f0e2bf0b1aed94578f144d15175f357bdc05e815e5c4a02b319eb4f",
            downloadUrl = "https://huggingface.co/deepghs/insightface/resolve/main/buffalo_s/w600k_mbf.onnx"
        )
    )

    fun getModelDir(context: Context): File {
        return File(context.filesDir, EnteFaceConstants.MODEL_SUBDIR)
    }

    fun getModelState(context: Context): EnteModelState {
        val dir = getModelDir(context)
        if (!dir.exists()) return EnteModelState.NOT_DOWNLOADED

        var hasAllFiles = true
        var isCorrupted = false

        for (artifact in REQUIRED_ARTIFACTS) {
            val file = File(dir, artifact.filename)
            if (!file.exists()) {
                hasAllFiles = false
            } else if (file.length() < artifact.expectedMinBytes) {
                isCorrupted = true
            } else if (artifact.sha256Hex != null) {
                val fileHash = computeSha256(file)
                if (!fileHash.equals(artifact.sha256Hex, ignoreCase = true)) {
                    isCorrupted = true
                }
            }
        }

        return when {
            isCorrupted -> EnteModelState.CORRUPT_OR_INCOMPLETE
            hasAllFiles -> EnteModelState.INSTALLED_AND_VALID
            else -> EnteModelState.NOT_DOWNLOADED
        }
    }

    fun isInstalled(context: Context): Boolean {
        return getModelState(context) == EnteModelState.INSTALLED_AND_VALID
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(32 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun installFromAssetsIfPresent(context: Context): Boolean {
        val dir = getModelDir(context)
        if (!dir.exists() && !dir.mkdirs()) return false

        var allInstalled = true
        for (artifact in REQUIRED_ARTIFACTS) {
            val target = File(dir, artifact.filename)
            if (target.isFile && target.length() >= artifact.expectedMinBytes) continue

            val assetPath = "${EnteFaceConstants.MODEL_SUBDIR}/${artifact.filename}"
            val copied = runCatching {
                context.assets.open(assetPath).use { input ->
                    val temp = File(dir, "${artifact.filename}.installing")
                    try {
                        temp.outputStream().buffered().use { output -> input.copyTo(output) }
                        if (temp.length() >= artifact.expectedMinBytes) {
                            if (target.exists()) target.delete()
                            val renamed = temp.renameTo(target)
                            if (!renamed) {
                                temp.copyTo(target, overwrite = true)
                            }
                            true
                        } else {
                            temp.delete()
                            false
                        }
                    } finally {
                        temp.delete()
                    }
                }
            }.getOrDefault(false)

            if (!copied) allInstalled = false
        }
        return allInstalled && isInstalled(context)
    }

    suspend fun downloadOrInstallModel(
        context: Context,
        force: Boolean = false,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onStage: (String) -> Unit = {},
        awaitIfResumed: suspend () -> Unit = {},
        onDetailedProgress: (DetailedDownloadProgress) -> Unit = {}
    ) {
        val modelDir = getModelDir(context)
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            error("Cannot create model directory: ${modelDir.absolutePath}")
        }

        if (!force && isInstalled(context)) {
            onStage("ready")
            onDetailedProgress(DetailedDownloadProgress(25_000_000L, 25_000_000L, 0L, 0L, "ready"))
            return
        }

        // Check if bundled assets can be installed first
        if (installFromAssetsIfPresent(context) && !force) {
            onStage("ready")
            onDetailedProgress(DetailedDownloadProgress(25_000_000L, 25_000_000L, 0L, 0L, "ready"))
            return
        }

        val availableBytes = StatFs(modelDir.absolutePath).availableBytes
        check(availableBytes >= EnteFaceConstants.MIN_FREE_SPACE_BYTES) {
            "Insufficient disk space. At least 35MB required for Ente model installation."
        }

        val stagingDir = File(modelDir, "staging").apply {
            if (!exists()) mkdirs()
        }

        val totalBytesEstimated = 25L * 1024 * 1024 // ~25MB total across both models
        var cumulativeBytes = 0L

        try {
            onStage("connecting")
            onDetailedProgress(DetailedDownloadProgress(0L, totalBytesEstimated, 0L, 0L, "connecting"))

            for (artifact in REQUIRED_ARTIFACTS) {
                awaitIfResumed()
                if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")

                val target = File(modelDir, artifact.filename)
                if (!force && target.isFile && target.length() >= artifact.expectedMinBytes) {
                    cumulativeBytes += target.length()
                    onProgress(cumulativeBytes, totalBytesEstimated)
                    onDetailedProgress(
                        DetailedDownloadProgress(cumulativeBytes, totalBytesEstimated, 0L, 0L, "downloading")
                    )
                    continue
                }

                val downloadUrl = artifact.downloadUrl
                    ?: error("No download URL configured for artifact ${artifact.filename}")

                val stagingFile = File(stagingDir, "${artifact.filename}.part")
                val downloadStartTime = System.currentTimeMillis()
                var sessionDownloadedBytes = 0L

                downloadFile(
                    url = downloadUrl,
                    destination = stagingFile,
                    onBytes = { readChunk ->
                        cumulativeBytes += readChunk
                        sessionDownloadedBytes += readChunk
                        val elapsedSec = ((System.currentTimeMillis() - downloadStartTime) / 1000.0).coerceAtLeast(0.1)
                        val speed = (sessionDownloadedBytes / elapsedSec).toLong()
                        val remainingBytes = (totalBytesEstimated - cumulativeBytes).coerceAtLeast(0L)
                        val etaSec = if (speed > 0) remainingBytes / speed else 0L

                        onProgress(cumulativeBytes, totalBytesEstimated)
                        onDetailedProgress(
                            DetailedDownloadProgress(
                                bytesReceived = cumulativeBytes,
                                totalBytes = totalBytesEstimated,
                                speedBytesPerSec = speed,
                                etaSeconds = etaSec,
                                stage = "downloading"
                            )
                        )
                    },
                    onConnecting = {
                        onStage("connecting")
                        onDetailedProgress(
                            DetailedDownloadProgress(cumulativeBytes, totalBytesEstimated, 0L, 0L, "connecting")
                        )
                    },
                    onDownloading = {
                        onStage("downloading")
                    },
                    awaitIfResumed = awaitIfResumed
                )

                // Validate artifact size and SHA-256 checksum
                onStage("verifying")
                onDetailedProgress(
                    DetailedDownloadProgress(cumulativeBytes, totalBytesEstimated, 0L, 0L, "verifying")
                )

                if (stagingFile.length() < artifact.expectedMinBytes) {
                    stagingFile.delete()
                    error("Downloaded artifact ${artifact.filename} is incomplete (${stagingFile.length()} bytes).")
                }

                if (artifact.sha256Hex != null) {
                    val computedHash = computeSha256(stagingFile)
                    if (!computedHash.equals(artifact.sha256Hex, ignoreCase = true)) {
                        stagingFile.delete()
                        error("Cryptographic checksum mismatch for ${artifact.filename}: expected ${artifact.sha256Hex}, got $computedHash")
                    }
                }
            }

            onStage("installing")
            onDetailedProgress(
                DetailedDownloadProgress(totalBytesEstimated, totalBytesEstimated, 0L, 0L, "installing")
            )

            for (artifact in REQUIRED_ARTIFACTS) {
                val stagingFile = File(stagingDir, "${artifact.filename}.part")
                if (stagingFile.exists()) {
                    val target = File(modelDir, artifact.filename)
                    if (target.exists()) target.delete()
                    if (!stagingFile.renameTo(target)) {
                        stagingFile.copyTo(target, overwrite = true)
                        stagingFile.delete()
                    }
                }
            }

            check(isInstalled(context)) { "Ente face model verification failed after installation." }
            onProgress(totalBytesEstimated, totalBytesEstimated)
            onStage("ready")
            onDetailedProgress(
                DetailedDownloadProgress(totalBytesEstimated, totalBytesEstimated, 0L, 0L, "ready")
            )
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private suspend fun downloadFile(
        url: String,
        destination: File,
        onBytes: (Int) -> Unit,
        onConnecting: () -> Unit = {},
        onDownloading: () -> Unit = {},
        awaitIfResumed: suspend () -> Unit
    ) {
        var currentUrl = url
        var redirects = 0
        var connection: HttpURLConnection? = null
        val existingBytes = if (destination.exists()) destination.length() else 0L

        onConnecting()
        while (redirects < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
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
                    currentUrl = location
                    redirects++
                    continue
                }
            }
            connection = conn
            break
        }

        val activeConn = connection ?: error("Failed to establish connection to $url")
        try {
            val responseCode = activeConn.responseCode
            val isPartial = responseCode == 206
            val isOk = responseCode in 200..299

            if (!isOk && !isPartial) {
                if (responseCode == 416) {
                    // Range not satisfiable: destination may already be completely downloaded
                    return
                }
                error("HTTP error $responseCode while downloading $url")
            }

            onDownloading()
            val appendMode = isPartial && existingBytes > 0L
            val initialCredit = if (appendMode) existingBytes else 0L
            if (initialCredit > 0L) {
                onBytes(0) // signal resume
            }

            BufferedInputStream(activeConn.inputStream).use { input ->
                java.io.FileOutputStream(destination, appendMode).buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        awaitIfResumed()
                        if (Thread.currentThread().isInterrupted) throw CancellationException("Download cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        onBytes(count)
                    }
                }
            }
        } finally {
            activeConn.disconnect()
        }
    }
}
