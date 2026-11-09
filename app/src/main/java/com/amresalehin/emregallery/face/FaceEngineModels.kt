package com.amresalehin.emregallery.face

/**
 * Canonical identifiers and configuration constants for face engines.
 */
object EnteFaceConstants {
    const val ENGINE_ID = "ente_yolo5face_mobilefacenet"
    const val DISPLAY_NAME = "Ente-Style (YOLO5Face + MobileFaceNet)"
    const val MODEL_VERSION = "yolov5n_mobilefacenet_v1"
    const val EMBEDDING_DIMENSION = 512
    const val FORMAT_VERSION = 1

    const val MODEL_SUBDIR = "face-models/ente_yolo5face_mobilefacenet"
    const val DETECTOR_FILENAME = "yolov5n-face.onnx"
    const val EMBEDDER_FILENAME = "mobilefacenet.onnx"

    const val DETECT_SIZE = 640
    const val RECOGNITION_SIZE = 112
    const val MIN_FACE_SIZE = 24
    const val DEFAULT_DETECTION_CONFIDENCE = 0.50f
    const val DEFAULT_NMS_IOU_THRESHOLD = 0.45f
    const val DEFAULT_RECOGNITION_DISTANCE = 0.55f

    // Min free disk space: 35MB
    const val MIN_FREE_SPACE_BYTES = 35L * 1024 * 1024

    val PROVENANCE = EmbeddingProvenance(
        engineId = ENGINE_ID,
        modelVersion = MODEL_VERSION,
        dimension = EMBEDDING_DIMENSION,
        formatVersion = FORMAT_VERSION
    )
}
