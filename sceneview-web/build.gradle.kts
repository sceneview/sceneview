plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    // TypeScript declarations: `sceneview-web.d.ts` stays HAND-WRITTEN,
    // guarded by `.claude/scripts/check-web-dts.sh` (quality-gate +
    // repo-hygiene CI, #2736). Kotlin's `generateTypeScriptDefinitions()`
    // was evaluated and rejected: the published npm/browser surface is NOT
    // the `@JsExport` surface — Main.kt#main() assembles the `sceneview`
    // global namespace dynamically (`api["createViewer"] = ::jsCreateViewer`),
    // which the compiler cannot see, and the typings rely on
    // `export as namespace sceneview` + hand-shaped Promise signatures that
    // a generated ES-module d.ts cannot express. Generation would therefore
    // document the wrong surface; the deterministic guard keeps the manual
    // file honest instead.
    js(IR) {
        outputModuleName.set("sceneview")
        browser {
            commonWebpackConfig {
                outputFileName = "sceneview-web.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        // Use executable() for a single webpack-bundled JS file usable via <script>
        // The @JsExport APIs are registered on globalThis.sceneview by the Kotlin/JS runtime
        binaries.executable()
    }

    sourceSets {
        jsMain.dependencies {
            // SceneView KMP core (collision, math, geometry, animation, physics)
            api(project(":sceneview-core"))

            // Filament.js WASM renderer (same engine as Android)
            implementation(npm("filament", "1.52.3"))
        }

        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Task to copy the production webpack bundle to website-static
tasks.register<Copy>("copyToWebsite") {
    dependsOn("jsBrowserProductionWebpack")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable"))
    into("${rootProject.projectDir}/website-static/js")
    include("*.js", "*.js.map")
}
