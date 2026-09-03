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
// RealityKit through SceneViewSwift, desktop to filament-kmp on Maven (FFM, JDK 22+;
// see docs/docs/desktop-filament.md).
//
// Scope is the VIEWER SUBSET only — see docs/docs/compose-multiplatform.md and
// this module's README. AR, materials and post-processing stay platform-native.
//
// Guardrail: no renderer type may appear in the public API. `explicitApi()` plus
// the committed `.api` dump (binary-compatibility-validator, configured in the
// root build) make a violation a reviewable diff rather than a silent leak.
kotlin {
    explicitApi()
    // Desktop filament-kmp is FFM (JDK 22+). Android still emits JVM 21 bytecode.
    jvmToolchain(22)

    androidTarget {
        publishLibraryVariants("release")
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    // Desktop/JVM. Filament via filament-kmp FFM — JDK 22+ at compile and run.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
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
            // `api`, because these ARE the public surface: `SceneViewer` takes an
            // `androidx.compose.ui.Modifier` and, being @Composable, carries an
            // `androidx.compose.runtime.Composer` in its compiled signature. Declaring
            // them `implementation` would publish them at `runtime` scope only, so the
            // POM would understate what a consumer needs to compile against. (This is
            // the mirror image of the `api` hazard documented for `:sceneview` below —
            // there the type is an implementation detail, here it is the contract.)
            api(compose.runtime)
            api(compose.ui)

            // Stays `implementation`: used only by the internal placeholder composable,
            // never in a public signature.
            implementation(compose.foundation)

            // Portable math (Position / Rotation / Direction) shared with the
            // Android and Apple APIs, so a value written against one reads the
            // same here.
            api(libs.kotlin.math)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // JVM family only — HttpURLConnection + the shared orbit math. iOS cannot
        // see this set. Both androidMain and desktopMain compile it into their target.
        val androidAndDesktopMain by creating {
            dependsOn(commonMain.get())
        }

        val desktopMain by getting {
            dependsOn(androidAndDesktopMain)
            dependencies {
                // Renderer only — never `api`. Same guardrail as androidMain.
                implementation(libs.filament.kmp.compose)
                implementation(libs.filament.kmp.gltfio)
            }
        }

        val androidMain by getting {
            dependsOn(androidAndDesktopMain)
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
    compileSdk = 37

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
