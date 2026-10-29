import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // JDK 21: the desktop floor of sceneview-compose (see its build file — filament-kmp's
    // JNI binding needs 17, kotlin-math's JDK 21 bytecode sets the floor, #4423).
    jvmToolchain(21)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":sceneview-compose"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.sceneview.desktop.resources"
    publicResClass = true
}

compose.desktop {
    application {
        mainClass = "io.github.sceneview.desktop.MainKt"
        // No `--enable-native-access=ALL-UNNAMED`: filament-kmp 0.7+ loads its natives
        // through JNI, not FFM, and the app runs on the JDK 21 below, which needs no
        // opt-in for `System.load` (JDK 24+ only warns about it).
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        }.get().metadata.installationPath.asFile.absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "sceneview-desktop"
            packageVersion = "1.0.0"

            macOS {
                bundleID = "io.github.sceneview.desktop"
            }
            windows {
                menuGroup = "SceneView"
            }
            linux {
                packageName = "sceneview-desktop"
            }
        }
    }
}
