plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.x requires the Compose Compiler Gradle plugin. The version is
    // supplied by the host React Native app's Kotlin Gradle plugin (it ships
    // with the same coordinates), so it is applied without an explicit version
    // here — exactly like `org.jetbrains.kotlin.android` above. The legacy
    // `composeOptions { kotlinCompilerExtensionVersion }` block is obsolete
    // under Kotlin 2.x and has been removed.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.sceneview.reactnative"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    // Explicit and REQUIRED — not boilerplate. Without it AGP defaults Java to
    // 1.8 while the Kotlin plugin picks the toolchain JDK, and the build dies
    // with "Inconsistent JVM-target compatibility detected for tasks
    // 'compileReleaseJavaWithJavac' (1.8) and 'compileReleaseKotlin' (22)".
    // A host RN app does NOT fix this for us: React Native's Gradle plugin
    // supplies plugin versions, never a library module's JVM target. 17 matches
    // the Flutter plugin (`flutter/sceneview_flutter/android/build.gradle`) and
    // React Native's own JDK 17 requirement. Caught by the standalone compile
    // gate (`tools/rn-android-compile`, #3042) the first time this module was
    // ever compiled by CI.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // SceneView — track the last PUBLISHED release on Maven Central (4.7.0).
    // The bridge Kotlin already targets the 4.x API surface (the `SceneView { }`
    // composable, `rememberEngine`, the `SceneScope` node DSL); only these
    // coordinates lagged behind on the year-old 3.6.0. Keep this aligned with
    // the latest published artifact, NOT the in-development `VERSION_NAME`
    // (see #1494).
    implementation("io.github.sceneview:sceneview:4.7.0")
    implementation("io.github.sceneview:arsceneview:4.7.0")

    // React Native
    implementation("com.facebook.react:react-android")

    // Compose — BOM aligned with the repo's `gradle/libs.versions.toml`
    // (`composeBom = 2026.05.00`) to avoid an ABI mismatch with the SceneView
    // library, which is compiled against that same BOM.
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")

    // Local JVM unit tests (#3062). Until this existed, the bridge's pure-Kotlin
    // derivations — the tap payload's `nodeName` above all — were exercised by
    // NOTHING: `tools/rn-android-compile` is a compile gate, and the RN demo has
    // never been built end-to-end. A string derivation that silently reports the
    // wrong value is exactly the class a compiler cannot catch, and #3071 was one.
    // These run on the JVM with no device and no emulator, in the same standalone
    // harness that already compiles the module.
    testImplementation("junit:junit:4.13.2")
}
