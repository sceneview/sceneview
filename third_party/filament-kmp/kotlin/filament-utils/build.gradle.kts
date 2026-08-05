import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("filament-kmp-module")
}

val filaVersion = project.property("filaVersion") as String
val libVersion = project.property("libVersion") as String

// Additional prebuilts needed by filament-utils-c beyond what :kotlin:filament already embeds.
// (zstd, utils, filaflat, filabridge are covered by the filament module.)
val FILAMENT_UTILS_PREBUILT_LIBS = listOf(
    "libfilament-iblprefilter.a",
    "libcamutils.a",
    "libimage.a",
    "libimageio-lite.a",
    "libktxreader.a",
)

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kotlin:filament"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotlin:test-support"))
        }
        androidMain.dependencies {
            implementation("com.google.android.filament:filament-utils-android:$filaVersion")
        }
        jvmMain.dependencies {
            // Project Panama (FFM): the combined libfilament-c image + jextract-generated
            // FilamentC already cover the filament-utils surface. Replaces :java:filament-utils.
            api(project(":java"))
        }
        webMain.dependencies {
            implementation(project(":web"))
        }
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops {
            create("filament_utils") {
                defFile(project.file("src/nativeInterop/cinterop/filament-utils.def"))
                includeDirs(
                    project.file("../../c/filament-utils/c"),
                    project.file("../../c/filament/c"),
                    project.file("../../include"),
                )
            }
        }
        applyFilamentNative(project, "filament_utils", "filament-utils-c", FILAMENT_UTILS_PREBUILT_LIBS)
    }
}
