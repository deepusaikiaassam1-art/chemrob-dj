# The app has no reflection of its own; activities and receivers are kept via the manifest.
# ML Kit and Play services ship their own consumer rules.
-keep class com.chemrob.medadherence.** { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.mlkit.**
