# Filament — JNI bridge classes must not be renamed or stripped
-keep class com.google.android.filament.** { *; }

# ARCore — JNI bridge
-keep class com.google.ar.core.** { *; }

# Kotlin-Math — used reflectively for transform operations
-keep class dev.romainguy.kotlin.math.** { *; }

# SceneView collision system — uses reflection for shape intersection
-keep class io.github.sceneview.collision.** { *; }

# Google Play Services Location — reflectively linked by ARCore's Geospatial /
# Terrain / Rooftop / Streetscape paths when Config.GeospatialMode.ENABLED is
# set. Without these keeps, R8 in release builds strips the FusedLocationProvider
# classes (no static call from consumer code) and ARCore raises:
#   "The Google Fused Location Provider for Android classes must be linked
#    into the app's binary when calling Session.configure() with Geospatial
#    mode enabled (Config.GeospatialMode.ENABLED)."
# Pixel 9 v4.3.0 production reproduces this — see issue #1178.
-keep class com.google.android.gms.location.** { *; }
-keep class com.google.android.gms.common.api.** { *; }
-keep class com.google.android.gms.tasks.** { *; }

# Nearby Connections — `compileOnly` (see build.gradle). Only the optional
# `io.github.sceneview.ar.collaborative.NearbyCollaborativeTransport` references
# these types, and apps that never use it don't have them on their classpath.
# When an app's keep rules reach that class (e.g. `-keep class
# io.github.sceneview.** { *; }`), R8 aborts `minifyReleaseWithR8` with
# "Missing class com.google.android.gms.nearby…". The three Play Services
# classes below come with Nearby too and are referenced by the same transport;
# this list is exactly R8's generated missing_rules.txt for an app that depends
# on arsceneview alone. Apps that use the transport add
# `implementation(libs.play.services.nearby)` and are unaffected.
-dontwarn com.google.android.gms.nearby.**
-dontwarn com.google.android.gms.common.api.Status
-dontwarn com.google.android.gms.tasks.OnFailureListener
-dontwarn com.google.android.gms.tasks.Task
