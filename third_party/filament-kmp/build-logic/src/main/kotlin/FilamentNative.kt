import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget

// Returns true if the konan target can be built on the current host machine.
// We avoid Kotlin's HostManager here because older embedded versions don't
// recognise some hosts (e.g. linux aarch64) and throw at instantiation time.
private fun isTargetBuildableOnHost(target: KonanTarget): Boolean =
    when (target.family) {
        Family.OSX, Family.IOS, Family.TVOS, Family.WATCHOS -> hostPlatform() == "macos"
        Family.MINGW                                        -> hostPlatform() == "windows"
        Family.LINUX                                        -> hostPlatform() == "linux"
        else                                                -> true
    }

private data class FilamentPlatform(
    val cmakePlatform: String,
    val cmakeArch: String,
    val prebuiltDir: String,
)

// macOS desktop runs on the JVM (see :java:* modules and the desktopApp sample),
// so MACOS_ARM64 is intentionally not declared as a Kotlin/Native target here.
private fun KonanTarget.filamentPlatform(): FilamentPlatform? = when (this) {
    KonanTarget.IOS_ARM64           -> FilamentPlatform("ios",           "arm64", "iosArm64")
    KonanTarget.IOS_SIMULATOR_ARM64 -> FilamentPlatform("ios-simulator", "arm64", "iosSimulatorArm64")
    KonanTarget.LINUX_X64           -> FilamentPlatform("linux",         "x64",   "linuxX64")
    KonanTarget.LINUX_ARM64         -> FilamentPlatform("linux",         "arm64", "linuxArm64")
    KonanTarget.MINGW_X64           -> FilamentPlatform("windows",       "x64",   "mingwX64")
    else                            -> null
}

/**
 * Wires up the Filament C-wrapper CMake build and cinterop static-library packing for one
 * native target.  Call this inside a `targets.withType<KotlinNativeTarget>().configureEach`
 * block, AFTER the cinterop has been created with `create(cinteropName) { ... }`.
 *
 * @param project        The Gradle project (pass `project`).
 * @param cinteropName   Name used in `create(...)` — e.g. "filament", "filamat", "filament_utils".
 * @param cmakeTarget    CMake library target to build — e.g. "filament-c", "gltfio-c".
 *                       Also used as the filename stem of the produced archive (lib<cmakeTarget>.a).
 * @param prebuiltLibs   Prebuilt Filament static-library filenames to embed in the klib.
 *                       List only the libraries that this module adds beyond its KMP dependencies.
 */
fun KotlinNativeTarget.applyFilamentNative(
    project: Project,
    cinteropName: String,
    cmakeTarget: String,
    prebuiltLibs: List<String>,
) {
    val platform = konanTarget.filamentPlatform() ?: return

    // Skip targets that can't be built on this host (e.g. iOS on Windows/Linux).
    // Kotlin/Native disables them anyway; bailing here also avoids spurious
    // "prebuilts not found" errors during configuration.
    if (!isTargetBuildableOnHost(konanTarget)) return

    if (konanTarget.family == Family.IOS) {
        binaries.all {
            freeCompilerArgs += "-Xoverride-konan-properties=apple.sdk.min.version=15.0"
        }
    }

    // Windows Kotlin Native uses .lib / no prefix; everything else uses lib*.a
    val isWindows  = konanTarget == KonanTarget.MINGW_X64
    val libPrefix  = if (isWindows) "" else "lib"
    val libSuffix  = if (isWindows) ".lib" else ".a"

    // Per-(target, cmakeTarget) build dir: when multiple modules run cmake concurrently for the
    // same konan target, sharing one dir causes them to clobber each other's libfilament-c.a.
    val cmakeSourceDir = project.file("../../c")
    val buildDir       = project.file("../../c/build/$name/$cmakeTarget")
    // Debug wrapper build via -Pfilament.debug=true; FILAMENT_PREBUILTS_DIR swaps in a locally
    // built Filament (layout mirrors prebuilts/: <target>/lib) and skips the download task.
    val buildType      = if (project.findProperty("filament.debug") == "true") "Debug" else "Release"
    val localPrebuilts = project.providers.environmentVariable("FILAMENT_PREBUILTS_DIR").orNull
    val prebuiltDir    = if (localPrebuilts != null) {
        java.io.File(localPrebuilts, "${platform.prebuiltDir}/lib")
    } else {
        project.file("../../prebuilts/${platform.prebuiltDir}/lib")
    }

    // Prebuilt static libs are downloaded by a root-level task (see kotlin/build.gradle.kts).
    val downloadTask = project.rootProject.tasks.named("downloadPrebuilts_$name")
    // Headers must match the prebuilts version (ABI breaks across patch versions).
    val downloadIncludesTask = project.rootProject.tasks.named("downloadIncludes")

    // CMake: configure + build the C wrapper in one step
    val cmake = resolveCmake()
    val cmakeTask = project.tasks.register("buildCWrapper_${cmakeTarget}_$name", Exec::class.java) {
        if (localPrebuilts == null) dependsOn(downloadTask)
        dependsOn(downloadIncludesTask)
        workingDir(buildDir)
        commandLine(
            "sh", "-c",
            "$cmake ${cmakeSourceDir.absolutePath} -DFILAMENT_PLATFORM=${platform.cmakePlatform}" +
            " -DFILAMENT_ARCH=${platform.cmakeArch}" +
            " -DCMAKE_BUILD_TYPE=$buildType" +
            " -DFILAMENT_LIB_DIR=${prebuiltDir.absolutePath}" +
            " && $cmake --build . --target $cmakeTarget",
        )
        val targetName = name
        val cmakePlatform = platform.cmakePlatform
        val cmakeArch = platform.cmakeArch
        doFirst {
            buildDir.mkdirs()
            logger.lifecycle("[$targetName] CMake: building $cmakeTarget ($cmakePlatform/$cmakeArch)")
        }
    }

    // Kotlin compilation and the generated cinterop task both depend on the C wrapper being built.
    compilations.getByName("main").compileTaskProvider.configure { dependsOn(cmakeTask) }

    // Gradle names the cinterop task cinterop<CinteropName><TargetName> (first char capitalised only).
    val cinteropTask = "cinterop" +
        cinteropName.replaceFirstChar { it.uppercaseChar() } +
        name.replaceFirstChar { it.uppercaseChar() }
    project.tasks.matching { it.name == cinteropTask }.configureEach { dependsOn(cmakeTask) }

    // Pack all static libraries into the klib so they are embedded for final binary linking.
    compilations.getByName("main").cinterops.getByName(cinteropName) {
        extraOpts("-libraryPath", prebuiltDir.absolutePath)
        extraOpts("-libraryPath", buildDir.absolutePath)

        val resolved = if (prebuiltDir.exists()) {
            project.fileTree(prebuiltDir) { include(prebuiltLibs) }.files.map { it.name }
        } else {
            project.logger.error(
                "\nFilament prebuilts not found for target '$name' at: ${prebuiltDir.absolutePath}" +
                "\nRun ./gradlew downloadPrebuilts to fetch them.\n",
            )
            emptyList()
        }

        // Prebuilt Filament libs + the freshly compiled C-wrapper archive
        (resolved + "$libPrefix$cmakeTarget$libSuffix").forEach { lib ->
            extraOpts("-staticLibrary", lib)
        }
    }
}
