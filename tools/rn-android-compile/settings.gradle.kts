// Standalone Gradle build whose ONLY purpose is to compile the React Native
// module's Android Kotlin bridge (`react-native/react-native-sceneview/android`)
// against the PUBLISHED SceneView Maven artifacts it actually declares.
//
// WHY THIS EXISTS (#3042)
// -----------------------
// The RN Android bridge is not in the root `settings.gradle` and, until this
// build existed, was compiled by no CI job at all. It has the exact exposure
// that `rn-ios-compile.yml` was created for on the iOS side (#2067): its
// `build.gradle.kts` depends on `io.github.sceneview:sceneview:4.7.0` /
// `arsceneview:4.7.0` — the last PUBLISHED release, deliberately LAGGING the
// repo's `VERSION_NAME` — so an API that exists in repo source can be absent
// from the artifact the bridge really builds against, and nothing noticed.
//
// WHY A SEPARATE BUILD AND NOT THE ROOT `settings.gradle`
// ------------------------------------------------------
// Including the module in the root build would be wrong in both directions:
//   * it would resolve `io.github.sceneview:*` to the LOCAL project via
//     substitution or clash with it, which destroys the whole point — the
//     gate must compile against the published artifact, not repo source;
//   * it would drag `com.facebook.react:react-android` and a second Compose
//     BOM into every `./gradlew` invocation in the repo.
// So the module is included here, from its own settings file, as the single
// project of a throwaway build.
//
// HONEST LIMITATIONS
// ------------------
//   * This is a COMPILE gate, not a functional one. It proves every symbol the
//     bridge references exists in the artifacts it declares; it proves nothing
//     about runtime behaviour on a device.
//   * `com.facebook.react:react-android` is declared VERSIONLESS in the
//     module (a real host app gets the version from React Native's own Gradle
//     plugin, `com.facebook.react.rootproject`). This build has no host app,
//     so it pins the version itself — see `rnAndroidVersion` below. The
//     pin is the package's declared FLOOR (`peerDependencies.react-native
//     >= 0.72.0`), so the gate fails if the bridge starts using an RN API
//     newer than what the package claims to support.
//   * Plugin versions are pinned here for the same reason: the module's
//     `build.gradle.kts` requests `com.android.library` /
//     `org.jetbrains.kotlin.android` / `…kotlin.plugin.compose` WITHOUT
//     versions, expecting the host app's buildscript to supply them.

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }

    // The module requests these plugins with no version (the host RN app
    // supplies them). Keep aligned with `gradle/libs.versions.toml`
    // (`agp` / `kotlin`) so the gate uses the same toolchain the rest of the
    // repo is built with.
    plugins {
        id("com.android.library") version "9.4.0"
        id("org.jetbrains.kotlin.android") version "2.4.10"
        id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rn-android-compile"

include(":react-native-sceneview")
project(":react-native-sceneview").projectDir =
    file("../../react-native/react-native-sceneview/android")

// The `run { }` wrapper is load-bearing, not style: a top-level `val` in a
// settings script is a field of the script object, and a `beforeProject` action
// that captured one would fail to isolate ("cannot serialize Gradle script
// object references"). Inside `run { }` it is a plain local captured by value.
run {
    // React Native version the versionless `com.facebook.react:react-android`
    // dependency resolves to in this build. See "HONEST LIMITATIONS" above.
    val rnAndroidVersion = "0.72.6"
    val harnessDir = settingsDir

    gradle.lifecycle.beforeProject {
        // Keep every build output inside this harness. The module's own
        // `projectDir` lives under `react-native/react-native-sceneview/android`,
        // which npm SHIPS (`package.json` → `files: [… "android" …]`), so a
        // default `<projectDir>/build` would drop compiled classes straight into
        // the published tarball's source tree on any machine that ran this gate.
        layout.buildDirectory.set(File(harnessDir, "build/$name"))

        configurations.all {
            resolutionStrategy.eachDependency {
                if (requested.group == "com.facebook.react" && requested.version.isNullOrEmpty()) {
                    useVersion(rnAndroidVersion)
                    because(
                        "The module leaves React Native versionless for the host app's " +
                            "React Native Gradle plugin to resolve; this standalone " +
                            "compile gate has no host app, so it pins the declared floor."
                    )
                }
            }
        }
    }
}
