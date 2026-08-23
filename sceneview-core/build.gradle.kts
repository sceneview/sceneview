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
            testTask {
                // `karma.config.d/` is appended verbatim into the karma.conf.js
                // this task generates, so it changes what the run does — but
                // Gradle does not track it as an input on its own. Without this
                // line a broken launcher config gave `BUILD SUCCESSFUL` with
                // `jsBrowserTest UP-TO-DATE` against a stale generated config
                // (local only: the task is not cacheable, so CI is unaffected).
                inputs.dir(layout.projectDirectory.dir("karma.config.d"))
                    .withPropertyName("karmaConfigD")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // Pulls in karma-chrome-launcher, which
                // `karma.config.d/browser-hardening.js` extends with the
                // `--no-sandbox` / `--disable-dev-shm-usage` launcher and the
                // timeouts a shared CI runner needs (#3192).
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
