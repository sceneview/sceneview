plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "io.github.sceneview.demo.flutter"
    // NOT `flutter.compileSdkVersion` (#3385). The `flutter_sceneview` plugin
    // compiles against SDK 37, and Flutter's own Gradle plugin fails the app
    // build unless the host app compiles against at least the highest SDK any
    // plugin uses. Restore `flutter.compileSdkVersion` once the pinned Flutter
    // SDK's default reaches 37.
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = "io.github.sceneview.demo.flutter"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

// Espresso 3.2.0 ships TWO artifacts that declare the SAME manifest package
// (`androidx.test.espresso`): `espresso-core` and `espresso-idling-resource`.
// AGP 9 enforces unique library namespaces, so the debug build dies in
// `:app:processDebugMainManifest` with "Namespace 'androidx.test.espresso' is
// used in multiple modules and/or libraries" (#3440).
//
// It reaches this app transitively and not by our choice: Flutter's own
// `integration_test` plugin — a dev dependency the demo really uses, for
// `integration_test/screenshot_test.dart` — declares
// `api("androidx.test.espresso:espresso-core:3.2+")`, and `3.2+` can only ever
// resolve to 3.2.0. Espresso split the two namespaces in 3.5.1; verified by
// reading AndroidManifest.xml inside the published AARs rather than trusting
// release notes:
//
//   espresso-core 3.2.0             package="androidx.test.espresso"
//   espresso-idling-resource 3.2.0  package="androidx.test.espresso"   <- collision
//   espresso-core 3.7.0             package="androidx.test.espresso.core"
//   espresso-idling-resource 3.7.0  package="androidx.test.espresso.idling.resource"
//
// 3.7.0 rather than the minimum 3.5.1, to match the AndroidX Test generation
// the rest of the repo already builds against (`androidxTestRules 1.7.0`).
//
// REMOVE THIS BLOCK once Flutter's `integration_test` plugin raises its own
// espresso floor past 3.5.1 (flutter/flutter — the constraint lives in
// `packages/integration_test/android/build.gradle.kts`). The check that the
// removal is safe is that `flutter build apk --debug` still succeeds here.
configurations.configureEach {
    resolutionStrategy {
        force(
            "androidx.test.espresso:espresso-core:3.7.0",
            "androidx.test.espresso:espresso-idling-resource:3.7.0",
        )
    }
}

flutter {
    source = "../.."
}
