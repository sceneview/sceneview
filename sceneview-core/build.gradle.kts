plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.publish)
}

kotlin {
    // Android target (JVM-based, consumed by the Android sceneview module)
    jvm("android")

    // iOS targets
    iosArm64()
    iosSimulatorArm64()
    iosX64()

    // Web target (JS/Browser, consumed by the sceneview-web module)
    js(IR) {
        browser {
            // `useChromeHeadless()` is what pulls in karma-chrome-launcher, which
            // `karma.config.d/browser-hardening.js` extends with `base:
            // "ChromeHeadless"`. Until #3192 this was a bare `browser()`: no
            // launcher flags, no timeouts, and a crash that captured no browser
            // output at all — which is how `Build web targets` went red five
            // times on #3189 with zero test results and nothing to diagnose.
            testTask {
                // `karma.config.d/` is appended verbatim into the karma.conf.js
                // that this task generates, so it changes what the run DOES —
                // but Gradle does not track it as an input on its own. Measured
                // on this worktree: pointing `config.browsers` at a launcher
                // that does not exist and re-running gave `BUILD SUCCESSFUL`,
                // `:sceneview-core:jsBrowserTest UP-TO-DATE`, against a STALE
                // generated config; the identical break under `--rerun-tasks`
                // fails in 4 s with `Cannot load browser … it is not
                // registered!`. With this line the same break re-runs and fails
                // without `--rerun-tasks`, which is the whole point.
                //
                // SCOPE, measured rather than assumed: this is a LOCAL false
                // green, not a CI one. `jsBrowserTest` is not cacheable —
                // `--info` says `Caching disabled for task … because: Caching
                // has been disabled for the task` — so the Gradle build cache
                // CI restores cannot carry a stale result across runs, and a
                // fresh CI checkout has no up-to-date state to reuse. What it
                // does break is the only way anyone verifies this file at all:
                // #3192 workstream 1 exists BECAUSE the fix needs local Gradle,
                // and the default local run silently did not test it.
                inputs.dir(layout.projectDirectory.dir("karma.config.d"))
                    .withPropertyName("karmaConfigD")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.library()
    }

    // WASM target for Compose Multiplatform Web
    // BLOCKED: kotlin-math (dev.romainguy:kotlin-math) does not publish a wasmJs variant.
    // Uncomment when kotlin-math adds wasmJs support.
    // @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    // wasmJs {
    //     browser()
    //     binaries.library()
    // }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib")
            api(libs.kotlin.math)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
