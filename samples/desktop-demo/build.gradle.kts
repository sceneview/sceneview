import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    // filament-kmp (via sceneview-compose desktop) is FFM — JDK 22+.
    jvmToolchain(22)

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_22)
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
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(22))
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
