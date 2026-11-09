# Proguard rules for Emre Gallery

# ONNX Runtime JNI
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Tesseract 4 Android JNI
-keep class com.googlecode.tesseract.android.** { *; }
-keep class cz.adaptech.tesseract4android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
-dontwarn cz.adaptech.tesseract4android.**

# OSMDroid
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Coil
-dontwarn coil.**

# Media3 ExoPlayer - rely on Media3 consumer proguard rules and keep reflective extractors/renderers
-keepclassmembers class androidx.media3.exoplayer.** { *; }
-dontwarn androidx.media3.**

# R8 Optimization flags
-allowaccessmodification
-repackageclasses ''
