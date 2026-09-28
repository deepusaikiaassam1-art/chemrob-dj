# The app has no reflection of its own; activities and receivers are kept via the manifest.
# ML Kit and Play services ship their own consumer rules.
-keep class com.chemrob.medadherence.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**
# TensorFlow Lite calls back into its Java classes from native code.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
