import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("filament-kmp-module")
}

val filaVersion = project.property("filaVersion") as String
val libVersion = project.property("libVersion") as String

// Additional prebuilts needed by gltfio-c beyond what :kotlin:filament already embeds.
// (filament, backend, utils, filaflat, filabridge, zstd are covered by the filament module.)
val GLTFIO_PREBUILT_LIBS = listOf(
    "libgltfio_core.a",
    "libdracodec.a",
    "libbasis_transcoder.a",  // transitive dep of gltfio_core
    "libmikktspace.a",        // transitive dep of gltfio_core
    "libstb.a",
    "libimage.a",
    "libimageio-lite.a",
    "libktxreader.a",
    "libuberarchive.a",       // ubershader package (UBERARCHIVE_PACKAGE, uberz::*)
    "libuberzlib.a",          // ubershader archive helpers (transitive of uberarchive)
)

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kotlin:filament"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(project(":kotlin:test-support"))
        }
        androidMain.dependencies {
            implementation("com.google.android.filament:gltfio-android:$filaVersion")
        }
        jvmMain.dependencies {
            // Project Panama (FFM): the combined libfilament-c image + jextract-generated
            // FilamentC already cover the gltfio surface. Replaces the JNI :java:gltfio dep.
            api(project(":java"))
        }
        webMain.dependencies {
            implementation(project(":web"))
        }
    }

    // ── Embed test .glb assets into a generated commonTest source ────────────────
    // The committed .glb files in src/commonTest/glb are the source of truth.
    val generateEmbeddedGlb = registerEmbeddedTestResources(
        taskName = "generateEmbeddedGlb",
        inputDir = "src/commonTest/glb",
        fileExtension = ".glb",
        packageName = "io.github.erkko68.filament.gltfio.testutils",
        objectName = "EmbeddedGlb",
    )
    sourceSets.named("commonTest") {
        kotlin.srcDir(generateEmbeddedGlb)
    }

    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops {
            create("gltfio") {
                defFile(project.file("src/nativeInterop/cinterop/gltfio.def"))
                includeDirs(
                    project.file("../../c/gltfio/c"),
                    project.file("../../c/filament/c"),
                    project.file("../../include"),
                )
            }
        }
        applyFilamentNative(project, "gltfio", "gltfio-c", GLTFIO_PREBUILT_LIBS)
    }
}
