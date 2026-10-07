# QALQON release ProGuard/R8 rules.
#
# Stage 10 (Privacy & Security Hardening) ENABLES R8 minification + resource shrinking
# for release (see `app/build.gradle.kts`). This file holds the *minimum* keep rules the
# app's reflective / runtime-loaded surfaces need on top of what the libraries already
# ship through their own consumer ProGuard rules.
#
# What already ships its own consumer rules (do NOT duplicate broadly):
#   - AndroidX (Core, Lifecycle, Activity, Compose, Navigation, DataStore)
#   - Hilt / Dagger (`dagger.hilt.*` keeps the @HiltAndroidApp / @AndroidEntryPoint graph)
#   - Room (keeps the generated `*_Impl`)
#   - Kotlin + kotlinx-coroutines
#   - Google Play Billing
#
# ML Kit's face detection and the ML Kit GenAI prompt client load their models and run
# their recognition pipeline through their own AARs and consumer rules; no extra keep is
# required for their public entry points (FaceDetection.getClient / FaceDetector.process).
#
# Everything below is deliberately narrow. A blanket `-keep class ** { *; }` would
# silently defeat shrinking AND obfuscation the moment it was added, so none is used.

# ---------------------------------------------------------------------------
# TFLite (MobileFaceNet). The `org.tensorflow.lite` Java API is referenced directly,
# but the native runtime is loaded by name and the Interpreter resolves its options
# reflectively, so keep the public interpreter surface.
# ---------------------------------------------------------------------------
-keep class org.tensorflow.lite.Interpreter { *; }
-keep class org.tensorflow.lite.InterpreterApi { *; }
-keep class org.tensorflow.lite.InterpreterApi$Options { *; }
-keep class org.tensorflow.lite.Tensor { *; }
-keep class org.tensorflow.lite.nnapi.** { *; }
-dontwarn org.tensorflow.lite.**

# ---------------------------------------------------------------------------
# ML Kit GenAI (Gemini Nano). Capability detection only, but the client is created by
# a factory that resolves implementations reflectively; keep the public GenAI surface
# so the capability probe cannot be stripped into a false "unsupported" answer.
# ---------------------------------------------------------------------------
-keep class com.google.mlkit.genai.prompt.** { *; }
-dontwarn com.google.mlkit.genai.**

# ---------------------------------------------------------------------------
# Android components referenced from the manifest by name. R8 keeps manifest-referenced
# classes, but they are listed explicitly so a future refactor that stops referencing
# them from code (an accessibility service, a boot receiver) cannot be stripped.
# ---------------------------------------------------------------------------
-keep class uz.faceguard.app.FaceGuardApp { *; }
-keep class uz.faceguard.app.MainActivity { *; }
-keep class uz.faceguard.app.core.protection.ProtectionForegroundService { *; }
-keep class uz.faceguard.app.core.protection.ProtectionBootReceiver { *; }
-keep class uz.faceguard.app.core.accessibility.ProtectionAccessibilityService { *; }

# ---------------------------------------------------------------------------
# Enums are read by name at runtime (DataStore persists `EntitlementState`/`ScanMode`/
# `BlockPolicy` by `name` and parses them back with `valueOf`). R8 must not rename the
# enum constants away from the values already written to disk, or a stored settings row
# would fail to parse after an update.
# ---------------------------------------------------------------------------
-keepclassmembers enum uz.faceguard.app.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static **[] entries();
}

# ---------------------------------------------------------------------------
# Security surfaces whose behaviour is asserted by the test suites (reached reflectively
# from the instrumented/JVM suites); keep them so a minified build cannot strip a
# security-relevant implementation.
# ---------------------------------------------------------------------------
-keep class uz.faceguard.app.core.security.** { *; }
-keep class uz.faceguard.app.core.billing.** { *; }

# Keep source line numbers for readable crash/Play-vitals stack traces; do not keep the
# source file name (smaller, and the file name is not needed to debug a frame).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin metadata is required for Kotlin reflection (kotlin-reflect is not bundled, but
# coroutines/Compose inspect it); dropping it causes subtle runtime failures.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,Signature
