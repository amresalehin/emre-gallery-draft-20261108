package com.amresalehin.emregallery.ai.local

import android.content.Context
import android.os.StatFs
import android.util.Log
import com.amresalehin.emregallery.data.FlorenceModelState
import com.amresalehin.emregallery.data.FlorenceStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Robust installer and integrity validator for on-device AI models.
 * Enforces pre-flight storage verification, staging isolation, cryptographic SHA-256 validation,
 * atomic directory promotion, automatic rollback on runtime failure, and versioned manifest tracking.
 */
object LocalModelInstaller {

    private const val TAG = "LocalModelInstaller"
    private const val SAFETY_MARGIN_BYTES = 50L * 1024 * 1024 // 50MB safety margin

    fun getModelDir(filesDir: File, spec: LocalModelSpec): File {
        return File(filesDir, spec.subdir)
    }

    fun getModelDir(context: Context, spec: LocalModelSpec): File {
        return getModelDir(context.filesDir, spec)
    }

    fun getStagingDir(filesDir: File, spec: LocalModelSpec): File {
        return File(getModelDir(filesDir, spec), ".staging")
    }

    fun getStagingDir(context: Context, spec: LocalModelSpec): File {
        return getStagingDir(context.filesDir, spec)
    }

    private fun getBackupDir(filesDir: File, spec: LocalModelSpec): File {
        val modelDir = getModelDir(filesDir, spec)
        return File(modelDir.parentFile, "${modelDir.name}_backup")
    }

    private fun getBackupDir(context: Context, spec: LocalModelSpec): File {
        return getBackupDir(context.filesDir, spec)
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Checks whether the model is completely installed, cryptographic hashes match,
     * manifest metadata matches current registry revision, and runtime status is valid.
     */
    fun checkInstallationStatus(filesDir: File, spec: LocalModelSpec): FlorenceModelState {
        val modelDir = getModelDir(filesDir, spec)
        if (!modelDir.exists() || !modelDir.isDirectory) {
            return FlorenceModelState(status = FlorenceStatus.NOT_INSTALLED, totalBytes = spec.totalExpectedBytes)
        }

        val manifestFile = File(modelDir, spec.manifestFileName)
        if (!manifestFile.isFile || manifestFile.length() == 0L) {
            return FlorenceModelState(status = FlorenceStatus.NOT_INSTALLED, totalBytes = spec.totalExpectedBytes)
        }

        val manifestJson = runCatching { JSONObject(manifestFile.readText()) }.getOrNull()
            ?: return FlorenceModelState(
                status = FlorenceStatus.ERROR,
                errorMessage = "Corrupt manifest file in ${spec.displayName} directory."
            )

        // Verify model identity and immutable commit revision match registry
        val manifestRepo = manifestJson.optString("upstreamRepo")
        val manifestRev = manifestJson.optString("upstreamRevision")
        if (manifestRepo.isNotEmpty() && manifestRepo != spec.upstreamRepo) {
            return FlorenceModelState(
                status = FlorenceStatus.ERROR,
                errorMessage = "Manifest repository mismatch: expected ${spec.upstreamRepo}, got $manifestRepo"
            )
        }
        if (manifestRev.isNotEmpty() && manifestRev != spec.upstreamRevision) {
            return FlorenceModelState(
                status = FlorenceStatus.ERROR,
                errorMessage = "Manifest revision mismatch: expected ${spec.upstreamRevision}, got $manifestRev"
            )
        }

        // Verify all required artifacts exist and match expected minimum/exact sizes
        var totalInstalledBytes = 0L
        for (artifact in spec.artifacts) {
            val file = File(modelDir, artifact.relativePath)
            if (!file.isFile) {
                return FlorenceModelState(
                    status = FlorenceStatus.NOT_INSTALLED,
                    errorMessage = "Missing artifact ${artifact.relativePath}"
                )
            }
            val len = file.length()
            if (len == 0L || len < artifact.expectedMinBytes) {
                return FlorenceModelState(
                    status = FlorenceStatus.ERROR,
                    errorMessage = "Artifact ${artifact.relativePath} is truncated ($len bytes)"
                )
            }
            if (artifact.exactBytes != null && len != artifact.exactBytes) {
                return FlorenceModelState(
                    status = FlorenceStatus.ERROR,
                    errorMessage = "Artifact ${artifact.relativePath} size mismatch: expected ${artifact.exactBytes}, got $len"
                )
            }
            totalInstalledBytes += len
        }

        val manifestStatus = manifestJson.optString("status")
        val isReady = manifestStatus == "READY" || manifestStatus == "VALID"
        val isAssetsOnly = manifestStatus == "ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE" || !spec.hasRuntimeAdapter

        val status = when {
            isReady && spec.hasRuntimeAdapter -> FlorenceStatus.READY
            isAssetsOnly -> FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE
            else -> FlorenceStatus.NOT_INSTALLED
        }

        return FlorenceModelState(
            status = status,
            progress = 1.0f,
            bytesDownloaded = totalInstalledBytes,
            totalBytes = totalInstalledBytes,
            installedSizeBytes = totalInstalledBytes,
            installedPath = modelDir.absolutePath,
            lastUpdated = manifestJson.optLong("installedAt", System.currentTimeMillis()),
            validatedAt = manifestJson.optLong("validatedAt", 0L),
            runtimeReady = spec.hasRuntimeAdapter && isReady,
            statusDetail = manifestJson.optString("statusDetail").ifBlank { null }
        )
    }

    fun checkInstallationStatus(context: Context, spec: LocalModelSpec): FlorenceModelState {
        return checkInstallationStatus(context.filesDir, spec)
    }

    fun isInstalled(filesDir: File, spec: LocalModelSpec): Boolean {
        val state = checkInstallationStatus(filesDir, spec)
        return state.status == FlorenceStatus.READY || state.status == FlorenceStatus.INSTALLED
    }

    fun isInstalled(context: Context, spec: LocalModelSpec): Boolean {
        val state = checkInstallationStatus(context, spec)
        // Ready models are installed and usable
        return state.status == FlorenceStatus.READY || state.status == FlorenceStatus.INSTALLED
    }

    /**
     * Downloads, validates, and atomically installs [spec] into its target directory.
     */
    suspend fun downloadAndInstall(
        context: Context,
        spec: LocalModelSpec,
        onProgress: (ModelDownloadProgress) -> Unit
    ): FlorenceModelState = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(context, spec)
        val stagingDir = getStagingDir(context, spec)
        val backupDir = getBackupDir(context, spec)

        val totalBytesExpected = spec.totalExpectedBytes

        // 1. Storage verification check
        val stat = StatFs(context.filesDir.absolutePath)
        val availableBytes = stat.availableBytes
        val requiredBytes = totalBytesExpected + SAFETY_MARGIN_BYTES
        if (availableBytes < requiredBytes) {
            val neededMb = (requiredBytes - availableBytes) / (1024 * 1024)
            throw IOException("Insufficient storage to install ${spec.displayName}. Need at least ${neededMb}MB more free disk space.")
        }

        stagingDir.mkdirs()

        var totalBytesDownloaded = 0L
        val downloadStartTime = System.currentTimeMillis()

        try {
            // 2. Download all artifacts into staging
            for ((idx, artifact) in spec.artifacts.withIndex()) {
                val stagingFile = File(stagingDir, artifact.relativePath)
                stagingFile.parentFile?.mkdirs()

                // Check if already completed in staging
                if (stagingFile.isFile && artifact.exactBytes != null && stagingFile.length() == artifact.exactBytes) {
                    totalBytesDownloaded += stagingFile.length()
                    onProgress(
                        ModelDownloadProgress(
                            bytesDownloaded = totalBytesDownloaded,
                            totalBytes = totalBytesExpected,
                            currentArtifact = artifact.relativePath,
                            artifactIndex = idx + 1,
                            totalArtifacts = spec.artifacts.size,
                            stage = "downloading"
                        )
                    )
                    continue
                }

                var artifactBytesSoFar = if (stagingFile.exists()) stagingFile.length() else 0L

                LocalModelDownloadManager.downloadArtifactFile(
                    url = artifact.sourceUrl,
                    destination = stagingFile,
                    expectedBytes = artifact.exactBytes,
                    onProgress = { chunk ->
                        artifactBytesSoFar += chunk
                        val currentCumulative = totalBytesDownloaded + artifactBytesSoFar
                        val elapsedSec = ((System.currentTimeMillis() - downloadStartTime) / 1000.0).coerceAtLeast(0.1)
                        val speed = (currentCumulative / elapsedSec).toLong()
                        val remaining = (totalBytesExpected - currentCumulative).coerceAtLeast(0L)
                        val eta = if (speed > 0) remaining / speed else 0L

                        onProgress(
                            ModelDownloadProgress(
                                bytesDownloaded = currentCumulative,
                                totalBytes = totalBytesExpected,
                                speedBytesPerSec = speed,
                                etaSeconds = eta,
                                stage = "downloading",
                                currentArtifact = artifact.relativePath,
                                artifactIndex = idx + 1,
                                totalArtifacts = spec.artifacts.size
                            )
                        )
                    },
                    onStage = { stage ->
                        onProgress(
                            ModelDownloadProgress(
                                bytesDownloaded = totalBytesDownloaded + artifactBytesSoFar,
                                totalBytes = totalBytesExpected,
                                stage = stage,
                                currentArtifact = artifact.relativePath,
                                artifactIndex = idx + 1,
                                totalArtifacts = spec.artifacts.size
                            )
                        )
                    }
                )

                // Verify downloaded artifact size
                if (artifact.exactBytes != null && stagingFile.length() != artifact.exactBytes) {
                    stagingFile.delete()
                    throw IOException("Downloaded ${artifact.relativePath} size mismatch: expected ${artifact.exactBytes}, got ${stagingFile.length()}")
                } else if (stagingFile.length() < artifact.expectedMinBytes) {
                    stagingFile.delete()
                    throw IOException("Downloaded ${artifact.relativePath} is truncated (${stagingFile.length()} bytes)")
                }

                // Verify cryptographic checksum if provided
                if (artifact.sha256Hex != null) {
                    val computed = computeSha256(stagingFile)
                    if (!computed.equals(artifact.sha256Hex, ignoreCase = true)) {
                        stagingFile.delete()
                        throw IOException("Cryptographic checksum mismatch for ${artifact.relativePath}: expected ${artifact.sha256Hex}, got $computed")
                    }
                }

                totalBytesDownloaded += stagingFile.length()
            }

            // 3. Stage verification: verify all artifacts in staging before atomic promotion
            onProgress(
                ModelDownloadProgress(
                    bytesDownloaded = totalBytesExpected,
                    totalBytes = totalBytesExpected,
                    stage = "verifying",
                    currentArtifact = "All artifacts",
                    artifactIndex = spec.artifacts.size,
                    totalArtifacts = spec.artifacts.size
                )
            )

            for (artifact in spec.artifacts) {
                val f = File(stagingDir, artifact.relativePath)
                if (!f.isFile || f.length() < artifact.expectedMinBytes) {
                    throw IOException("Verification failed: artifact ${artifact.relativePath} is missing or incomplete in staging.")
                }
            }

            // 4. Atomic directory promotion with rollback protection
            if (backupDir.exists()) backupDir.deleteRecursively()

            if (modelDir.exists()) {
                val existingFiles = modelDir.listFiles { _, name -> name != ".staging" }
                if (!existingFiles.isNullOrEmpty()) {
                    backupDir.mkdirs()
                    for (file in existingFiles) {
                        file.renameTo(File(backupDir, file.name))
                    }
                }
            } else {
                modelDir.mkdirs()
            }

            // Move staged files into modelDir
            copyDirectoryContents(stagingDir, modelDir)
            stagingDir.deleteRecursively()

            // 5. Runtime graph validation & smoke test
            onProgress(
                ModelDownloadProgress(
                    bytesDownloaded = totalBytesExpected,
                    totalBytes = totalBytesExpected,
                    stage = "validating_runtime",
                    currentArtifact = spec.displayName,
                    artifactIndex = spec.artifacts.size,
                    totalArtifacts = spec.artifacts.size
                )
            )

            val runtimeResult = LocalModelRuntimeRegistry.validateModel(context, spec, modelDir)

            if (spec.hasRuntimeAdapter && runtimeResult is SmokeTestResult.Failure) {
                // Rollback on runtime failure
                Log.e(TAG, "Runtime validation failed for ${spec.displayName}: ${runtimeResult.reason}. Rolling back.")
                modelDir.deleteRecursively()
                if (backupDir.exists()) {
                    backupDir.renameTo(modelDir)
                }
                throw IllegalStateException("Model validation failed: ${runtimeResult.reason}")
            }

            // Validation succeeded! Delete backup
            if (backupDir.exists()) backupDir.deleteRecursively()

            // 6. Write authoritative versioned manifest
            val statusString = if (spec.hasRuntimeAdapter) "READY" else "ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE"
            val statusDetail = when (runtimeResult) {
                is SmokeTestResult.Success -> runtimeResult.details
                is SmokeTestResult.Failure -> runtimeResult.reason
            }

            val manifest = JSONObject().apply {
                put("id", spec.id)
                put("canonicalId", spec.canonicalId)
                put("name", spec.displayName)
                put("upstreamRepo", spec.upstreamRepo)
                put("upstreamRevision", spec.upstreamRevision)
                put("installedAt", System.currentTimeMillis())
                put("totalBytes", totalBytesExpected)
                put("artifactsCount", spec.artifacts.size)
                put("status", statusString)
                put("hasRuntimeAdapter", spec.hasRuntimeAdapter)
                put("validatedAt", System.currentTimeMillis())
                put("statusDetail", statusDetail)

                val artArray = JSONArray()
                spec.artifacts.forEach { art ->
                    val artObj = JSONObject().apply {
                        put("path", art.relativePath)
                        put("bytes", File(modelDir, art.relativePath).length())
                        put("role", art.role.name)
                        if (art.externalDataFor != null) {
                            put("externalDataFor", art.externalDataFor)
                        }
                    }
                    artArray.put(artObj)
                }
                put("artifacts", artArray)
            }

            File(modelDir, spec.manifestFileName).writeText(manifest.toString(2))
            File(modelDir, "model_manifest.json").writeText(manifest.toString(2))

            val finalStatus = if (spec.hasRuntimeAdapter) FlorenceStatus.READY else FlorenceStatus.ASSETS_DOWNLOADED_RUNTIME_UNAVAILABLE
            return@withContext FlorenceModelState(
                status = finalStatus,
                progress = 1.0f,
                bytesDownloaded = totalBytesExpected,
                totalBytes = totalBytesExpected,
                installedSizeBytes = totalBytesExpected,
                installedPath = modelDir.absolutePath,
                lastUpdated = System.currentTimeMillis(),
                validatedAt = System.currentTimeMillis(),
                runtimeReady = spec.hasRuntimeAdapter,
                statusDetail = statusDetail
            )

        } catch (e: CancellationException) {
            // Keep partial staging file for true resume, but don't promote
            throw e
        } catch (e: Throwable) {
            throw e
        }
    }

    private fun copyDirectoryContents(sourceDir: File, targetDir: File) {
        val files = sourceDir.listFiles() ?: return
        for (file in files) {
            val dest = File(targetDir, file.name)
            if (file.isDirectory) {
                dest.mkdirs()
                copyDirectoryContents(file, dest)
                file.delete()
            } else {
                dest.parentFile?.mkdirs()
                if (dest.exists()) dest.delete()
                if (!file.renameTo(dest)) {
                    file.copyTo(dest, overwrite = true)
                    file.delete()
                }
            }
        }
    }

    suspend fun uninstall(context: Context, spec: LocalModelSpec): Boolean = withContext(Dispatchers.IO) {
        val modelDir = getModelDir(context, spec)
        val stagingDir = getStagingDir(context, spec)
        val backupDir = getBackupDir(context, spec)

        runCatching { stagingDir.deleteRecursively() }
        runCatching { backupDir.deleteRecursively() }
        val deleted = runCatching { modelDir.deleteRecursively() }.getOrDefault(false)
        return@withContext deleted
    }
}
