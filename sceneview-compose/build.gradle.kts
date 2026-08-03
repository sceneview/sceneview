import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.publish)
}

// ── sceneview-compose ────────────────────────────────────────────────────────
// A thin Compose Multiplatform façade over the per-platform renderers. One API,
// several renderers: Android delegates to the Filament `SceneView { }`, iOS to
// RealityKit through SceneViewSwift, desktop to the vendored Filament binding in
// third_party/filament-kmp/.
//
// Scope is the VIEWER SUBSET only — see docs/docs/compose-multiplatform.md and
// this module's README. AR, materials and post-processing stay platform-native.
//
// Guardrail: no renderer type may appear in the public API. `explicitApi()` plus
// the committed `.api` dump (binary-compatibility-validator, configured in the
// root build) make a violation a reviewable diff rather than a silent leak.
kotlin {
    explicitApi()

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Desktop/JVM. The desktop `actual` is not wired yet — it lands with the
    // vendored Filament binding, which carries a JDK 22+ floor of its own
    // (Project Panama / FFM). Until then this target compiles the common API
    // against a not-yet-implemented expect, which keeps the seam honest.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Device + Apple-silicon simulator only. `iosX64` (the Intel simulator) is
    // deliberately absent: Compose Multiplatform 1.11.1 publishes no iosX64
    // variant, so declaring it fails dependency resolution for every Compose
    // artifact. `sceneview-core` still targets iosX64 because it has no Compose
    // dependency — the two are not inconsistent.
    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.ui)

            // Portable math (Position / Rotation / Direction) shared with the
            // Android and Apple APIs, so a value written against one reads the
            // same here.
            api(libs.kotlin.math)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        androidMain.dependencies {
            // The Android actual delegates to the existing Filament renderer.
            // Nothing in `sceneview` changes: this module only consumes it.
            //
            // `implementation`, NEVER `api`: an `api` dependency puts the whole
            // io.github.sceneview.* + com.google.android.filament.* surface on every
            // Android consumer's compile classpath at `compile` scope in the POM. That
            // is exactly the renderer leak this module's guardrail forbids, through a
            // channel binary-compatibility-validator cannot see — it dumps declarations,
            // not dependency scopes. It is also a one-way door: narrowing `api` to
            // `implementation` after publishing is a source-breaking change.
            implementation(project(":sceneview"))
        }
    }
}

android {
    namespace = "io.github.sceneview.compose"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
