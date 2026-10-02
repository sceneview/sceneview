# SceneView Demo ProGuard Rules

# ── Filament JNI ──────────────────────────────────────────────────────────────
-keep class com.google.android.filament.** { *; }
-keepclassmembers class com.google.android.filament.** { *; }

# ── ARCore ────────────────────────────────────────────────────────────────────
-keep class com.google.ar.** { *; }
-keepclassmembers class com.google.ar.** { *; }

# ── Play Core (in-app updates) ────────────────────────────────────────────────
-keep class com.google.android.play.core.** { *; }
-keep interface com.google.android.play.core.** { *; }

# ── SceneView ─────────────────────────────────────────────────────────────────
-keep class io.github.sceneview.** { *; }
-keepclassmembers class io.github.sceneview.** { *; }

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ── Jetpack Compose ───────────────────────────────────────────────────────────
-dontwarn androidx.compose.**

# ── AndroidX ──────────────────────────────────────────────────────────────────
-keep class androidx.lifecycle.** { *; }
-keep class androidx.navigation.** { *; }

# ── Suppress known harmless warnings ─────────────────────────────────────────
-dontwarn com.google.android.filament.**
-dontwarn com.google.ar.**

# ── Nearby Connections (compile-time-only) ───────────────────────────────────
# arsceneview's `NearbyCollaborativeTransport` reference implementation (#2008)
# references `com.google.android.gms.nearby.connection.**` via a `compileOnly`
# dependency, so those classes are NOT on this app's runtime/minify classpath.
# The demo uses `LoopbackCollaborativeTransport`, never the Nearby transport, so
# R8 shrinks `NearbyCollaborativeTransport` away — but without this rule R8 aborts
# on the unresolved references before it can (broke the 4.19.0 Play Store AAB).
# Apps that actually use Nearby add `implementation(libs.play.services.nearby)`.
-dontwarn com.google.android.gms.nearby.**

# ── AutoValue / javax.lang.model (compile-time-only) ─────────────────────────
# MediaPipe's tasks-vision POM drags com.google.auto.value:auto-value (the full
# annotation processor + a shaded JavaPoet) onto the runtime/minify classpath.
# AutoValue and JavaPoet reference compile-time-only JDK classes
# (javax.lang.model.**) that don't exist on Android. The build.gradle exclude
# already drops auto-value, but these -dontwarn rules — exactly what R8's
# missing_rules.txt generates — keep the AAB build safe if any transitive
# reference survives. None of these classes are used at runtime (#2106).
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**

# ── ML Kit: component registrars (object detection, GenAI Prompt) ────────────
# ML Kit wires itself up at process start: `MlKitInitProvider` hands the
# `ComponentRegistrar` class names listed in the merged manifest to
# `ComponentDiscovery`, which instantiates each one reflectively through its
# no-arg constructor. The manifest keeps the class *names*, but R8 full mode
# drops the constructors, nothing references them in code. Every
# registrar then fails with `NoSuchMethodException: <init> []`, the ML Kit
# context comes up empty, and the first client built on it throws:
# `ObjectDetection.getClient` a NullPointerException that crashed ML Kit
# Object Labels on open (#4025), `Generation.getClient` another one that the
# Point & Ask demo reported as "Gemini Nano isn't available" (#4028). Seen on a
# minified build on the emulator; the debug build never shrinks, so it never showed.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }

# ── MediaPipe Tasks (Pose Landmarker) ────────────────────────────────────────
# tasks-vision ships no consumer rules. Its native graph code finds Java classes
# by name over JNI, and its options travel as protobuf-lite messages whose
# schema is read reflectively by field name. Shrunk, `PoseLandmarker
# .createFromOptions` threw "Field platform_ for <obfuscated> not found"
# (`MediaPipeLoggingProto$SystemInfo`), so the AR Body Tracker only ever
# showed "Body tracking is unavailable" in release builds (#4027).
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
# MediaPipe logs through Flogger, which finds its caller by walking the stack
# for FluentLogger's own frame. R8 renamed FluentLogger and outlined the stack
# walk into a synthetic class, so `Graph.<clinit>` threw "no caller found on the
# stack" and PoseLandmarker still failed to start once the protos were kept.
-keep class com.google.common.flogger.** { *; }
# Keeping all of MediaPipe also keeps two framework APIs (graph profiler, graph
# templates) whose protos tasks-vision does not ship. The demo never calls them.
-dontwarn com.google.mediapipe.proto.CalculatorProfileProto$CalculatorProfile
-dontwarn com.google.mediapipe.proto.GraphTemplateProto$CalculatorGraphTemplate

# ── Stack traces: file and line attributes ───────────────────────────────────
# Without these, R8 drops both attributes and every JVM frame in a Crashlytics
# or Play Console report reads `Unknown Source`. Only the mapping can then put
# the source back, and when it is missing or does not match the build, the
# frames stay blank. Keeping the line table means retrace always has real lines
# to map. The file name is the same `SourceFile` everywhere, so no source path
# leaks into the dex.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
