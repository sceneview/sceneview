import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("filament-kmp-module")
}

val filaVersion = project.property("filaVersion") as String
val libVersion = project.property("libVersion") as String

// Libraries that filament-c links against from the prebuilts directory.
// These are the direct dependencies of the C wrapper plus known transitive
// dependencies of libfilament.a that the linker must resolve.
val FILAMENT_PREBUILT_LIBS = listOf(
    "libfilament.a",
    "libbackend.a",
    "libutils.a",
    "libgeometry.a",
    "libibl-lite.a",       // CMakeLists: ibl-lite (NOT libibl.a)
    "libfilaflat.a",
    "libfilabridge.a",
    "libsmol-v.a",
    "libzstd.a",
    "libmeshoptimizer.a",  // transitive dep of libfilament.a
)

kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotlin:test-support"))
        }
        androidMain.dependencies {
            implementation("com.google.android.filament:filament-android:$filaVersion")
        }
        jvmMain.dependencies {
            // Project Panama (FFM) bindings: combined libfilament-c + jextract-generated
            // FilamentC class + loader + the FFM helpers (Ffm.kt). Replaces the JNI :java:filament dep.
            api(project(":java"))
        }
        webMain.dependencies {
            implementation(project(":web"))
        }
    }

    // ── Embed test .filamat materials into a generated commonTest source ──────────
    // Blobs compiled with `matc -p all -a all` (see the .mat sources); the committed
    // files in src/commonTest/materials are the source of truth.
    val generateEmbeddedMaterials = registerEmbeddedTestResources(
        taskName = "generateEmbeddedMaterials",
        inputDir = "src/commonTest/materials",
        fileExtension = ".filamat",
        packageName = "io.github.erkko68.filament.testutils",
        objectName = "EmbeddedMaterials",
    )
    sourceSets.named("commonTest") {
        kotlin.srcDir(generateEmbeddedMaterials)
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops {
            create("filament") {
                defFile(project.file("src/nativeInterop/cinterop/filament.def"))
                includeDirs(
                    project.file("../../c/filament/c"),
                    project.file("../../c/filamat/c"),
                    project.file("../../c/filament-utils/c"),
                    project.file("../../include"),
                )
            }
        }
        applyFilamentNative(project, "filament", "filament-c", FILAMENT_PREBUILT_LIBS)
    }
}
