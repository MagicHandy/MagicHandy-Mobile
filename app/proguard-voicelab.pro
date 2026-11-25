# Phone-test builds only: the voice test page. sherpa-onnx's rule is in proguard-rules.pro, since
# voice input ships in releases.

# ML Kit finds its components through Firebase component registrars, which it creates by reflection.
# firebase-components 16.1.0's own rule keeps the registrar classes but, under R8's full mode, not
# their constructors, so ML Kit found no components and failed with a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# The test build measures ML Kit, and only a phone runs its on-device paths, so keep ML Kit exactly as
# shipped rather than find the next stripped reflection there.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_speech.** { *; }
