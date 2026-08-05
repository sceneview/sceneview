import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import java.io.File

// jextract major version. Pinned to 22 (not the toolchain's 25) so the generated bindings
// target the JDK 22 FFM API — i.e. they use find().orElseThrow() rather than the
// JDK 23+ SymbolLookup.findOrThrow(), keeping the consumer floor at JDK 22 (release 22).
// The build auto-downloads jextract via the downloadJextract task (DownloadJextractTask),
// which pins the exact early-access build coordinates and caches the tarball.
private const val JEXTRACT_MAJOR = "22"

/**
 * Result of wiring the JVM/Panama native build for one Kotlin module.
 *
 * @property dylibDir         build/cmake dir that holds the freshly built libfilament-c.{dylib,so,dll}
 * @property generatedJavaDir build/generated/jextract — jextract output, added to the jvm Java source set
 * @property platformArch     "{platform}-{arch}", e.g. "macos-arm64", used as the natives/ resource subdir
 * @property buildType        CMake config, "Release" or "Debug" (-Pfilament.debug=true)
 * @property cmakeBuild       the task producing the dylib (depend on it from processResources)
 * @property jextract         the task producing the generated Java (depend on it from the jvm compile tasks)
 */
data class FilamentJvmNative(
    val dylibDir: Provider<Directory>,
    val generatedJavaDir: Provider<Directory>,
    val platformArch: String,
    val buildType: String,
    val cmakeBuild: TaskProvider<Exec>,
    val jextract: TaskProvider<Exec>,
)

/**
 * Registers the native build for the Project Panama (FFM) JVM bindings:
 *   1. builds the combined SHARED library via c/CMakeLists.txt (-DFILAMENT_BUILD_SHARED=ON), and
 *   2. runs jextract over the C headers to generate the low-level binding classes.
 *
 * Mirrors the per-host detection in java/filament/build.gradle.kts (which drives the JNI build)
 * so the same prebuilts tree feeds both. Reusable across the kotlin JVM modules.
 *
 * @param headerDirs  C header dirs to extract (e.g. c/filament/c). All *.h within are #included
 *                    into one umbrella header passed to jextract.
 * @param includeDirs -I dirs for jextract/clang (header dirs + Filament include/).
 * @param ffmPackage  Java package for the generated bindings (e.g. io.github.erkko68.filament.ffm).
 * @param headerClassName  name of the generated top-level functions class (e.g. FilamentC).
 */
fun Project.applyFilamentJvmNative(
    headerDirs: List<File>,
    includeDirs: List<File>,
    ffmPackage: String,
    headerClassName: String,
): FilamentJvmNative {
    // ── Host platform / arch (shared with FilamentNative.kt via NativeSupport.kt) ──
    val platform = hostPlatform()
    val arch = hostArch()
    val resArch = if (arch == "Arm64") "arm64" else "x64"
    val platformArch = "$platform-$resArch"
    val prebuiltsTarget = when (platform) {
        // Upstream releases no longer ship mac x86_64 libs — Apple Silicon only.
        "macos" -> if (arch == "Arm64") "macosArm64" else error("macOS x86_64 is not supported: Filament releases stopped shipping mac x86_64 prebuilts")
        "linux" -> if (arch == "Arm64") "linuxArm64" else "linuxX64"
        "windows" -> "mingwX64"
        else -> error("Unsupported platform '$platform'")
    }

    val cmakePath = resolveCmake()

    val cmakeSourceDir = rootProject.file("c")
    val cmakeBuildDir = layout.buildDirectory.dir("cmake").get().asFile
    val generatedDir = layout.buildDirectory.dir("generated/jextract").get().asFile

    // Debug wrapper build via -Pfilament.debug=true. Prebuilts stay Release; on Windows the
    // /MTd↔/MT CRT mismatch makes a Debug link against them fail — use macOS/Linux for this.
    val buildType = if (findProperty("filament.debug") == "true") "Debug" else "Release"
    // FILAMENT_PREBUILTS_DIR points the link at a locally built Filament (layout mirrors
    // prebuilts/: <target>/lib) and skips the download task for that target.
    val localPrebuilts = providers.environmentVariable("FILAMENT_PREBUILTS_DIR").orNull

    val downloadPrebuilts = rootProject.tasks.named("downloadPrebuilts_$prebuiltsTarget")
    val downloadIncludes = rootProject.tasks.named("downloadIncludes")

    // ── CMake: configure + build the combined SHARED library ──────────────────
    val cmakeConfigure = tasks.register("cmakeConfigureFilamentCJvm", Exec::class.java) {
        if (localPrebuilts == null) dependsOn(downloadPrebuilts)
        dependsOn(downloadIncludes)
        doFirst { cmakeBuildDir.mkdirs() }
        workingDir(cmakeBuildDir)
        val args = mutableListOf(
            cmakePath, cmakeSourceDir.absolutePath.replace('\\', '/'),
            "-DFILAMENT_BUILD_SHARED=ON",
            "-DFILAMENT_PLATFORM=$platform",
            "-DFILAMENT_ARCH=$arch",
            "-DCMAKE_BUILD_TYPE=$buildType",
        )
        if (localPrebuilts != null) {
            args += "-DFILAMENT_LIB_DIR=${File(localPrebuilts, "$prebuiltsTarget/lib").absolutePath.replace('\\', '/')}"
        }
        if (platform == "macos") {
            args += "-DCMAKE_OSX_SYSROOT=macosx"
            args += "-DCMAKE_OSX_ARCHITECTURES=${if (arch == "Arm64") "arm64" else "x86_64"}"
        }
        commandLine(args)
    }

    val cmakeBuild = tasks.register("cmakeBuildFilamentCJvm", Exec::class.java) {
        dependsOn(cmakeConfigure)
        workingDir(cmakeBuildDir)
        // No output declarations: cmakeConfigure writes CMakeCache.txt into this same dir, so
        // declaring it as an output here would let Gradle wipe the cache before the build runs.
        // CMake's own incremental build keeps rebuilds cheap. (Matches java/filament.)
        commandLine(cmakePath, "--build", ".", "--target", "filament-c-jvm", "--config", buildType)
    }

    // ── jextract: download the tool, then generate the bindings ──────────────
    val jextractBin = rootProject.file(
        ".gradle/jextract/jextract-$JEXTRACT_MAJOR/bin/" +
            if (platform == "windows") "jextract.bat" else "jextract",
    )

    // Self-bootstrap jextract via the pure-JVM download task. Registered once on the root
    // project (find-or-register, so reusing this helper from other modules won't double-register
    // or race on the .gradle/jextract dir). Output-tracked + onlyIf: downloads on first build,
    // skipped/up-to-date after.
    val downloadJextract = rootProject.run {
        tasks.findByName("downloadJextract")?.let { tasks.named("downloadJextract", DownloadJextractTask::class.java) }
            ?: tasks.register("downloadJextract", DownloadJextractTask::class.java) {
                group = "build setup"
                description = "Downloads the pinned jextract $JEXTRACT_MAJOR tool (one-time, cached)."
                major.set(JEXTRACT_MAJOR)
                cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/jextract-cache"))
                extractDir.set(rootProject.layout.projectDirectory.dir(".gradle/jextract"))
                binary.set(jextractBin)
                onlyIf { !jextractBin.exists() }
            }
    }

    val absHeaderDirs = headerDirs.map { it.absolutePath }
    val absIncludeDirs = includeDirs.map { it.absolutePath }
    val headerFiles = headerDirs.flatMap { dir ->
        dir.listFiles { f -> f.extension == "h" }?.toList() ?: emptyList()
    }.sortedBy { it.name }

    val jextract = tasks.register("jextractFilamentC", Exec::class.java) {
        dependsOn(downloadIncludes, downloadJextract)
        inputs.files(headerFiles)
        outputs.dir(generatedDir)
        doFirst {
            check(jextractBin.exists()) {
                "jextract $JEXTRACT_MAJOR missing at $jextractBin after downloadJextract — " +
                    "run: ./gradlew downloadJextract"
            }
            generatedDir.deleteRecursively()
            generatedDir.mkdirs()
            // jextract takes a single header; build an umbrella that #includes every C header.
            val umbrella = File(cmakeBuildDir.parentFile, "filament_c_all.h")
            umbrella.parentFile.mkdirs()
            umbrella.writeText(headerFiles.joinToString("\n") { "#include \"${it.name}\"" } + "\n")
        }
        val umbrella = File(cmakeBuildDir.parentFile, "filament_c_all.h")
        val cmd = mutableListOf(
            jextractBin.absolutePath,
            "--output", generatedDir.absolutePath,
            "-t", ffmPackage,
            "--header-class-name", headerClassName,
        )
        absHeaderDirs.forEach { cmd += listOf("-I", it) }
        absIncludeDirs.forEach { cmd += listOf("-I", it) }
        cmd += umbrella.absolutePath
        commandLine(cmd)
    }

    return FilamentJvmNative(
        dylibDir = layout.buildDirectory.dir("cmake"),
        generatedJavaDir = layout.buildDirectory.dir("generated/jextract"),
        platformArch = platformArch,
        buildType = buildType,
        cmakeBuild = cmakeBuild,
        jextract = jextract,
    )
}
