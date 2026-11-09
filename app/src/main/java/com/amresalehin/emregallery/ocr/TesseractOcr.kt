package com.amresalehin.emregallery.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.InputStream

enum class TesseractModel(
    val id: String,
    val title: String,
    val shortName: String,
    val tag: String,
    val description: String,
    val speedSummary: String,
    val accuracySummary: String,
    val memoryFootprint: String,
    val assetFolder: String,
    val storageDirName: String,
    val repoUrl: String
) {
    FAST(
        id = "fast",
        title = "tessdata_fast",
        shortName = "Fast (Quantized)",
        tag = "Recommended for Mobile",
        description = "Integer-quantized 8-bit LSTM neural network models. Optimized for high speed, low RAM footprint, and battery-friendly photo indexing.",
        speedSummary = "Up to 4x faster",
        accuracySummary = "High accuracy",
        memoryFootprint = "~4 MB per language",
        assetFolder = "tessdata_fast",
        storageDirName = "tesseract_fast",
        repoUrl = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main"
    ),
    BEST(
        id = "best",
        title = "tessdata_best",
        shortName = "Best (Full Precision)",
        tag = "Maximum Quality",
        description = "Full-precision floating-point LSTM neural network models directly from Tesseract training. Delivers maximum character recognition accuracy on complex documents, low-contrast text, and signs.",
        speedSummary = "Standard speed",
        accuracySummary = "Maximum accuracy",
        memoryFootprint = "~15 MB per language",
        assetFolder = "tessdata_best",
        storageDirName = "tesseract_best",
        repoUrl = "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main"
    );

    companion object {
        fun fromId(id: String?): TesseractModel =
            entries.find { it.id.equals(id, ignoreCase = true) || it.title.equals(id, ignoreCase = true) } ?: FAST
    }
}

/**
 * Fully on-device OCR using Tesseract (tesseract4android).
 * Traineddata models (tessdata_fast and tessdata_best) are bundled into APK assets
 * and extracted to model-specific internal storage directories, with on-demand download
 * fallback from official Tesseract GitHub repositories.
 */
object TesseractOcr {
    private fun cacheFile(context: Context, uri: Uri): File {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        val directory = File(context.filesDir, "ocr-results")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create OCR result cache.")
        }
        return File(directory, "${digest}.txt")
    }

    /** Returns true when an OCR result (including an empty result) has been cached for this URI. */
    fun hasCachedResult(context: Context, uri: Uri): Boolean =
        runCatching { cacheFile(context, uri).let { it.isFile && it.length() >= 0L } }.getOrDefault(false)

    /** Returns previously recognized text for this media URI, or null if not cached. */
    fun readCachedResult(context: Context, uri: Uri): String? =
        runCatching {
            cacheFile(context, uri).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()

    /** Atomically persists OCR output so it survives viewer navigation and app restarts. */
    fun saveCachedResult(context: Context, uri: Uri, text: String) {
        val target = cacheFile(context, uri)
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.writeText(text, Charsets.UTF_8)
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }
    }

    val supportedLanguages = linkedMapOf(
        "English" to "eng",
        "Hindi" to "hin",
        "Bengali" to "ben",
        "Arabic" to "ara",
        "Urdu" to "urd",
        "French" to "fra",
        "German" to "deu",
        "Spanish" to "spa",
        "Portuguese" to "por",
        "Russian" to "rus",
        "Chinese (Simplified)" to "chi_sim",
        "Japanese" to "jpn"
    )

    fun getBaseDir(context: Context, model: TesseractModel): File =
        File(context.filesDir, model.storageDirName)

    fun getTessdataDir(context: Context, model: TesseractModel): File =
        File(getBaseDir(context, model), "tessdata")

    /** Checks whether a traineddata binary is bundled directly in APK assets for the given model. */
    fun isLanguageBundledInAssets(context: Context, code: String, model: TesseractModel): Boolean {
        return runCatching {
            context.assets.open("${model.assetFolder}/$code.traineddata").use { true }
        }.getOrDefault(false)
    }

    /** Checks whether the language data is already extracted to disk and ready to use. */
    fun isLanguageInstalled(context: Context, code: String, model: TesseractModel): Boolean {
        val file = File(getTessdataDir(context, model), "$code.traineddata")
        return file.isFile && file.length() >= 1024
    }

    /** Returns the size in bytes of the installed language model on disk, or 0 if not installed. */
    fun getInstalledLanguageSizeBytes(context: Context, code: String, model: TesseractModel): Long {
        val file = File(getTessdataDir(context, model), "$code.traineddata")
        return if (file.isFile) file.length() else 0L
    }

    /** Returns total disk storage in bytes used by all extracted traineddata files for this model. */
    fun getModelStorageBytes(context: Context, model: TesseractModel): Long {
        val dir = getTessdataDir(context, model)
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(".traineddata") }
            ?.sumOf { it.length() } ?: 0L
    }

    /** Returns selected traineddata files that are already extracted and usable for the given model. */
    fun installedLanguages(context: Context, model: TesseractModel = TesseractModel.FAST): Set<String> {
        val directory = getTessdataDir(context, model)
        return supportedLanguages.values.filterTo(mutableSetOf()) { code ->
            File(directory, "$code.traineddata").let { it.isFile && it.length() >= 1024 }
        }
    }

    /** Extracts bundled language models or downloads missing ones to private storage before OCR. */
    fun prepareLanguages(
        context: Context,
        languageCodes: List<String>,
        model: TesseractModel = TesseractModel.FAST
    ) {
        val directory = getTessdataDir(context, model)
        if (!directory.exists() && !directory.mkdirs()) {
            error("Could not create OCR language-data directory for ${model.title}.")
        }
        languageCodes.distinct().filter { it in supportedLanguages.values }.forEach { code ->
            val destination = File(directory, "$code.traineddata")
            if (!destination.isFile || destination.length() < 1024) {
                copyBundledTrainedData(context, code, model, destination)
            }
        }
        // English is used as a fallback by the recognition engine.
        val english = File(directory, "eng.traineddata")
        if (!english.isFile || english.length() < 1024) {
            copyBundledTrainedData(context, "eng", model, english)
        }
    }

    fun cachedResultCount(context: Context): Int =
        File(context.filesDir, "ocr-results").listFiles()
            ?.count { it.isFile && it.extension == "txt" } ?: 0

    fun clearCachedResults(context: Context): Int {
        val directory = File(context.filesDir, "ocr-results")
        val files = directory.listFiles()?.filter { it.isFile && it.extension == "txt" }.orEmpty()
        var deleted = 0
        files.forEach { if (it.delete()) deleted++ }
        return deleted
    }

    fun deleteModelStorage(context: Context, model: TesseractModel): Boolean {
        val dir = getTessdataDir(context, model)
        return dir.deleteRecursively()
    }

    fun recognize(
        context: Context,
        uri: Uri,
        languageCodes: List<String>,
        model: TesseractModel = TesseractModel.FAST
    ): String {
        require(languageCodes.isNotEmpty()) { "Choose at least one OCR language." }
        val baseDir = getBaseDir(context, model)
        val tessdataDir = getTessdataDir(context, model)
        if (!tessdataDir.exists() && !tessdataDir.mkdirs()) {
            error("Could not create OCR language-data directory for ${model.title}.")
        }

        val validCodes = languageCodes.distinct().filter { it in supportedLanguages.values }
        require(validCodes.isNotEmpty()) { "No supported OCR languages selected." }

        validCodes.forEach { code ->
            val destination = File(tessdataDir, "$code.traineddata")
            if (!destination.exists() || destination.length() < 1024) {
                copyBundledTrainedData(context, code, model, destination)
            }
        }

        val englishData = File(tessdataDir, "eng.traineddata")
        if (!englishData.exists() || englishData.length() < 1024) {
            copyBundledTrainedData(context, "eng", model, englishData)
        }

        val bitmap = decodeBitmap(context, uri)
        val combinedLangs = validCodes.joinToString("+")
        val candidates = (listOf(combinedLangs) + validCodes + listOf("eng")).distinct()
        var activeEngine: TessBaseAPI? = null
        var lastInitError: String? = null
        try {
            // Use a fresh native engine for every attempt. A failed init can leave
            // the native instance unsuitable for another init call.
            for (candidate in candidates) {
                if (candidate.split("+").any { code ->
                        val file = File(tessdataDir, "$code.traineddata")
                        !file.isFile || file.length() < 1024
                    }) {
                    continue
                }
                val candidateEngine = TessBaseAPI()
                val initialized = runCatching {
                    // Both tessdata_fast and tessdata_best models are LSTM neural network models.
                    // OEM_LSTM_ONLY instructs Tesseract to use the neural network engine.
                    candidateEngine.init(
                        baseDir.absolutePath,
                        candidate,
                        TessBaseAPI.OEM_LSTM_ONLY
                    )
                }.onFailure { error ->
                    lastInitError = error.message ?: error.javaClass.simpleName
                }.getOrDefault(false)
                if (initialized) {
                    activeEngine = candidateEngine
                    break
                }
                candidateEngine.recycle()
            }

            val engine = activeEngine ?: error(
                "Tesseract could not load ${model.title} OCR data ($combinedLangs). " +
                    "The model files may be missing or corrupted. " +
                    (lastInitError?.let { "Details: $it. " } ?: "") +
                    "Please verify language files in OCR Settings and try again."
            )
            engine.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
            engine.setImage(bitmap)
            return engine.getUTF8Text().orEmpty().trim()
        } finally {
            activeEngine?.recycle()
            bitmap.recycle()
        }
    }

    private fun copyBundledTrainedData(
        context: Context,
        code: String,
        model: TesseractModel,
        destination: File
    ) {
        val parent = destination.parentFile ?: error("Invalid destination parent directory")
        if (!parent.exists() && !parent.mkdirs()) {
            error("Could not create directory for language: $code")
        }
        val temporary = File(parent, "$code.traineddata.tmp_${System.currentTimeMillis()}")
        try {
            var loaded = false
            // 1. Try model-specific asset folder (e.g. assets/tessdata_fast/$code.traineddata or assets/tessdata_best/$code.traineddata)
            try {
                context.assets.open("${model.assetFolder}/$code.traineddata").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                loaded = temporary.length() >= 1024
            } catch (_: Exception) {
                loaded = false
            }

            // 2. Fallback to generic assets/tessdata/$code.traineddata if available
            if (!loaded) {
                try {
                    context.assets.open("tessdata/$code.traineddata").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    loaded = temporary.length() >= 1024
                } catch (_: Exception) {
                    loaded = false
                }
            }

            // 3. Download on-demand from the official GitHub repository for this specific model
            if (!loaded) {
                val urlString = "${model.repoUrl}/$code.traineddata"
                val connection = java.net.URL(urlString).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                connection.instanceFollowRedirects = true
                try {
                    connection.connect()
                    if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                        connection.inputStream.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                    } else {
                        error("HTTP ${connection.responseCode} while fetching $urlString")
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (temporary.length() < 1024) error("Language data for '$code' (${model.title}) is incomplete.")
            if (destination.exists()) {
                destination.delete()
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (e: Exception) {
            temporary.delete()
            throw IllegalStateException(
                "OCR data for '$code' (${model.title}) could not be installed: ${e.message}",
                e
            )
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri): Bitmap {
        val decoded = com.amresalehin.emregallery.face.FaceImageDecoder.decodeOrientedImage(context, uri, maxDimension = 2200)
            ?: error("Could not decode image at URI: $uri. Check the file permissions and format.")
        val orientedBitmap = decoded.bitmap

        // Tesseract requires a software ARGB_8888 bitmap
        return if (orientedBitmap.config != Bitmap.Config.ARGB_8888) {
            val softwareBitmap = orientedBitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: run {
                    orientedBitmap.recycle()
                    error("Could not prepare this image for OCR. Try a different image.")
                }
            orientedBitmap.recycle()
            softwareBitmap
        } else {
            orientedBitmap
        }
    }

}
