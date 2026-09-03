pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            file("local.properties").inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // AGP 9.4.0 to match the repo root (#3440). Not cosmetic: CI runs this build
    // with the REPO-ROOT wrapper (`./gradlew -p samples/flutter-demo/android`,
    // because the Flutter tool generates and gitignores this project's own
    // `gradlew`), and Gradle 9.6+ cannot load AGP 8 at all.
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")
