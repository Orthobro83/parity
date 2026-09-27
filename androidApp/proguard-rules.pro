# ML Kit discovers its components by reflection and needs their no-argument constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }

# Tesseract and Leptonica call back into these classes from native code.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }
