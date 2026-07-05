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
