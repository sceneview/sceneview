import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream

/**
 * Download + extraction of Filament release artifacts, in pure JVM/Gradle
 * (replaced scripts/gradle/download_filament_*.py — no Python toolchain needed).
 *
 * Tarballs are cached under <repo>/.gradle/filament-prebuilts-cache/ so they
 * survive `./gradlew clean` and are shared across all targets (and with the
 * includes task) that use the same asset.
 */
object FilamentDownloads {

    /** target → (tarball-asset-suffix, path-prefix-inside-tarball OR "xcf:<slice>") */
    val TARGETS = mapOf(
        // iOS tarballs ship xcframeworks (since 1.71.4): each library lives at
        // filament/lib/lib<name>.xcframework/<slice>/lib<name>.a. Both simulator
        // targets point at the same fat simulator slice.
        "iosArm64"          to ("ios"       to "xcf:ios-arm64"),
        "iosSimulatorArm64" to ("ios"       to "xcf:ios-arm64_x86_64-simulator"),
        "iosX64"            to ("ios"       to "xcf:ios-arm64_x86_64-simulator"),
        // No macosX64: upstream releases stopped shipping mac x86_64 libs.
        "macosArm64"        to ("mac"       to "filament/lib/arm64"),
        "linuxX64"          to ("linux"     to "filament/lib/x86_64"),
        "linuxArm64"        to ("arm-linux" to "filament/lib/aarch64"),
        // /MT (static CRT) variant — the JVM's own msvcp140.dll conflicts with /MD.
        "mingwX64"          to ("windows"   to "lib/x86_64/mt"),
        // Filament.js + WASM bundle, extracted flat into prebuilts/web/.
        "web"               to ("web"       to ""),
    )

    private val NATIVE_EXTS = listOf(".a", ".lib")
    // filament.d.ts documents the JS surface (see :web); it lags jsbindings.cpp
    // but we keep the upstream copy alongside the js/wasm bundle.
    private val WEB_EXTS = listOf(".js", ".wasm", ".d.ts")

    fun releaseTarball(cacheDir: File, version: String, suffix: String, logger: Logger): File =
        downloadToCache(
            cacheDir, "filament-v$version-$suffix.tgz",
            "https://github.com/google/filament/releases/download/v$version/filament-v$version-$suffix.tgz",
            logger,
        )

    fun sourceTarball(cacheDir: File, version: String, logger: Logger): File =
        downloadToCache(
            cacheDir, "filament-src-v$version.tar.gz",
            "https://github.com/google/filament/archive/refs/tags/v$version.tar.gz",
            logger,
        )

    fun downloadJextractTarball(cacheDir: File, name: String, url: String, logger: Logger): File =
        downloadToCache(cacheDir, name, url, logger)

    private fun downloadToCache(cacheDir: File, name: String, url: String, logger: Logger): File {
        val cached = cacheDir.resolve(name)
        if (cached.exists()) {
            logger.lifecycle("  cached: $name")
            return cached
        }
        cacheDir.mkdirs()
        logger.lifecycle("  download: $url")
        // Unique temp + atomic move: parallel tasks wanting the same tarball never
        // observe a partial file (worst case both download, one wins).
        val tmp = File(cacheDir, "$name.${ProcessHandle.current().pid()}.${Thread.currentThread().threadId()}.part")
        try {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            Files.move(tmp.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
        return cached
    }

    private inline fun forEachTarFile(tarball: File, action: (TarArchiveEntry, TarArchiveInputStream) -> Unit) {
        TarArchiveInputStream(GZIPInputStream(tarball.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                if (entry.isFile) action(entry, tar)
                entry = tar.nextEntry
            }
        }
    }

    /** Extracts .a/.lib (native) or .js/.wasm/.d.ts (web) files found under [prefix], flat into [outDir]. */
    fun extractFlat(tarball: File, prefix: String, outDir: File): Int {
        outDir.mkdirs()
        val dirPrefix = if (prefix.isEmpty()) "" else prefix.trimEnd('/') + "/"
        var n = 0
        forEachTarFile(tarball) { entry, tar ->
            if (dirPrefix.isEmpty() || entry.name.startsWith(dirPrefix)) {
                val base = entry.name.substringAfterLast('/')
                if ((NATIVE_EXTS + WEB_EXTS).any { base.endsWith(it) }) {
                    outDir.resolve(base).outputStream().use { tar.copyTo(it) }
                    n++
                }
            }
        }
        return n
    }

    /**
     * Extracts a whole tar.gz into [destDir], preserving POSIX executable bits and
     * symlinks (the jextract distribution is a JDK-style tree that needs both).
     */
    fun extractAll(tarball: File, destDir: File) {
        destDir.mkdirs()
        val destPath = destDir.toPath().toAbsolutePath().normalize()
        TarArchiveInputStream(GZIPInputStream(tarball.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val out = destPath.resolve(entry.name).normalize()
                check(out.startsWith(destPath)) { "Tar entry escapes destination: ${entry.name}" }
                when {
                    entry.isDirectory -> Files.createDirectories(out)
                    entry.isSymbolicLink -> {
                        Files.createDirectories(out.parent)
                        Files.deleteIfExists(out)
                        Files.createSymbolicLink(out, java.nio.file.Paths.get(entry.linkName))
                    }
                    entry.isFile -> {
                        Files.createDirectories(out.parent)
                        out.toFile().outputStream().use { tar.copyTo(it) }
                        // Tar mode 0o100 = owner-execute; enough to decide the exec bit.
                        if (entry.mode and 0b001_000_000 != 0) out.toFile().setExecutable(true, false)
                    }
                }
                entry = tar.nextEntry
            }
        }
    }

    /** Extracts .a files from an xcframework slice: filament/lib/lib<name>.xcframework/<slice>/<file>. */
    fun extractXcframework(tarball: File, slice: String, outDir: File): Int {
        outDir.mkdirs()
        var n = 0
        forEachTarFile(tarball) { entry, tar ->
            val parts = entry.name.split("/")
            if (parts.size == 5 && parts[1] == "lib" &&
                parts[2].endsWith(".xcframework") && parts[3] == slice &&
                (parts[4].endsWith(".a") || parts[4].endsWith(".lib"))
            ) {
                outDir.resolve(parts[4]).outputStream().use { tar.copyTo(it) }
                n++
            }
        }
        return n
    }
}

/**
 * Downloads and extracts the Filament prebuilt static libraries (or the web
 * js/wasm bundle) for one target into prebuilts/<target>/lib (web: prebuilts/web).
 *
 * A `.prebuilt-source` stamp ("version|prefix") inside the output directory
 * forces re-extraction when the version bumps OR the in-tarball prefix changes
 * (e.g. the Windows CRT variant mt<->md) — a plain "dir non-empty" check would
 * silently reuse a stale extraction.
 */
abstract class DownloadFilamentPrebuiltsTask : DefaultTask() {
    @get:Input abstract val filamentVersion: Property<String>
    @get:Input abstract val target: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    // Not an output: shared across tasks and deliberately clean-surviving.
    @get:Internal abstract val cacheDir: DirectoryProperty

    @TaskAction
    fun run() {
        val version = filamentVersion.get()
        val targetName = target.get()
        val (suffix, prefix) = FilamentDownloads.TARGETS[targetName]
            ?: error("Unknown Filament prebuilt target '$targetName'. Known: ${FilamentDownloads.TARGETS.keys}")
        val outDir = outputDir.get().asFile

        val stampFile = outDir.resolve(".prebuilt-source")
        val stamp = "$version|$prefix"
        if (outDir.exists() && (outDir.list()?.isNotEmpty() == true) &&
            stampFile.isFile && stampFile.readText().trim() == stamp
        ) {
            logger.lifecycle("[$targetName] up-to-date ($outDir)")
            return
        }

        // Wipe any previous extraction so different variants/versions never mix.
        outDir.listFiles()?.forEach { it.deleteRecursively() }

        logger.lifecycle("[$targetName]")
        val tarball = FilamentDownloads.releaseTarball(cacheDir.get().asFile, version, suffix, logger)
        val count = if (prefix.startsWith("xcf:")) {
            FilamentDownloads.extractXcframework(tarball, prefix.removePrefix("xcf:"), outDir)
        } else {
            FilamentDownloads.extractFlat(tarball, prefix, outDir)
        }
        check(count > 0) { "No files extracted for '$targetName' from ${tarball.name} (prefix '$prefix')" }
        stampFile.writeText(stamp + "\n")
        logger.lifecycle("  extracted $count files -> $outDir")
    }
}

/**
 * Downloads and installs the pinned jextract early-access build (used to generate
 * the JVM/Panama bindings) into <repo>/.gradle/jextract/jextract-<major>/.
 *
 * jextract is published per JDK feature release at https://jdk.java.net/jextract/ ;
 * the generated code targets the finalized java.lang.foreign API, so any
 * jextract >= 22 works on JDK 22+. Coordinates are pinned per major below.
 */
abstract class DownloadJextractTask : DefaultTask() {
    @get:Input abstract val major: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:Internal abstract val extractDir: DirectoryProperty
    @get:OutputFile abstract val binary: org.gradle.api.file.RegularFileProperty

    companion object {
        /** major → (build, revision) forming openjdk-<major>-jextract+<build>-<rev>. */
        val COORDS = mapOf(
            "22" to (6 to 47),
            "25" to (2 to 4),
        )

        /** `<os>-<arch>` token; jextract only publishes windows-x64 (no Windows aarch64). */
        fun hostToken(): String {
            val osName = System.getProperty("os.name").lowercase()
            val os = when {
                osName.contains("mac") -> "macos"
                osName.contains("win") -> "windows"
                else -> "linux"
            }
            val arch = if (os != "windows" &&
                System.getProperty("os.arch").lowercase() in listOf("arm64", "aarch64")
            ) "aarch64" else "x64"
            return "$os-$arch"
        }
    }

    @TaskAction
    fun run() {
        val majorVersion = major.get()
        val bin = binary.get().asFile
        if (bin.exists()) {
            logger.lifecycle("jextract $majorVersion already installed: $bin")
            return
        }
        val (build, rev) = COORDS[majorVersion]
            ?: error("Unknown jextract major '$majorVersion'. Known: ${COORDS.keys}")
        val name = "openjdk-$majorVersion-jextract+$build-${rev}_${hostToken()}_bin.tar.gz"
        val tarball = FilamentDownloads.downloadJextractTarball(
            cacheDir.get().asFile, name,
            "https://download.java.net/java/early_access/jextract/$majorVersion/$build/$name",
            logger,
        )
        val dest = extractDir.get().asFile
        logger.lifecycle("Extracting $name -> $dest")
        FilamentDownloads.extractAll(tarball, dest)
        check(bin.exists()) { "jextract binary not found after extraction at $bin" }
        logger.lifecycle("Installed jextract $majorVersion: $bin")
    }
}

/**
 * Syncs Filament's public headers into <repo>/include/ from the platform-neutral
 * GitHub *source* tarball (per-platform release tarballs carry generated blobs and
 * platform-only helpers we don't include). The header tree must match the prebuilt
 * .a files exactly — types have had ABI breaks across patch versions.
 *
 * Also synthesizes include/gltfio/materials/uberarchive.h: upstream generates it
 * per platform with a hardcoded UBERARCHIVE_DEFAULT_SIZE, so we read each
 * platform's size out of its release tarball and emit one header that picks the
 * right value via preprocessor branches.
 */
abstract class DownloadFilamentIncludesTask : DefaultTask() {
    @get:Input abstract val filamentVersion: Property<String>
    @get:Internal abstract val includeDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputFile abstract val stampFile: org.gradle.api.file.RegularFileProperty

    init {
        // Skip when include/ is already stamped at this version (survives output-file
        // timestamp churn; declared here, not in the plugin script, so the spec stays
        // configuration-cache serializable).
        outputs.upToDateWhen {
            val stamp = stampFile.get().asFile
            stamp.exists() && stamp.readText().trim() == filamentVersion.get()
        }
    }

    /** Source-tarball sub-trees whose *contents* are mirrored under include/. */
    private val includeSources = listOf(
        "libs/utils/include/", "libs/filamat/include/", "libs/camutils/include/",
        "libs/filabridge/include/", "libs/filaflat/include/", "libs/gltfio/include/",
        "libs/ibl/include/", "libs/image/include/", "libs/imageio-lite/include/",
        "libs/ktxreader/include/", "libs/mathio/include/", "libs/uberz/include/",
        "libs/viewer/include/", "libs/geometry/include/", "libs/math/include/",
        "libs/iblprefilter/include/", "libs/filameshio/include/",
        "libs/generatePrefilterMipmap/include/",
        "filament/include/", "filament/backend/include/",
        // Third-party headers that filament's public API transitively includes.
        "third_party/robin-map/include/", "third_party/mikktspace/include/",
        "third_party/getopt/include/",
    )

    /** tarball-suffix → uberarchive.h path inside that release tarball. */
    private val uberarchivePaths = mapOf(
        "mac" to "filament/include/gltfio/materials/uberarchive.h",
        "ios" to "filament/include/gltfio/materials/uberarchive.h",
        "linux" to "filament/include/gltfio/materials/uberarchive.h",
        "windows" to "include/gltfio/materials/uberarchive.h",
    )

    @TaskAction
    fun run() {
        val version = filamentVersion.get()
        val outDir = includeDir.get().asFile
        val cache = cacheDir.get().asFile
        logger.lifecycle("[includes v$version]")

        val tarball = FilamentDownloads.sourceTarball(cache, version, logger)
        var count = 0
        var rootPrefix: String? = null
        TarArchiveInputStream(GZIPInputStream(tarball.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                // GitHub source tarballs wrap everything in filament-<version>/.
                if (rootPrefix == null) rootPrefix = entry.name.substringBefore('/') + "/"
                if (entry.isFile) {
                    val src = includeSources.firstOrNull { entry.name.startsWith(rootPrefix + it) }
                    if (src != null) {
                        val rel = entry.name.removePrefix(rootPrefix + src)
                        if (rel.isNotEmpty()) {
                            val dest = outDir.resolve(rel)
                            dest.parentFile.mkdirs()
                            dest.outputStream().use { tar.copyTo(it) }
                            count++
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        check(count > 0) { "No headers extracted from ${tarball.name}" }

        synthesizeUberarchive(version, outDir, cache)
        stampFile.get().asFile.writeText(version + "\n")
        logger.lifecycle("  extracted $count headers -> $outDir")
    }

    private fun synthesizeUberarchive(version: String, outDir: File, cache: File) {
        logger.lifecycle("  uberarchive.h (per-platform sizes):")
        val sizeRe = Regex("""#define\s+UBERARCHIVE_DEFAULT_SIZE\s+(\d+)""")
        val sizes = uberarchivePaths.mapValues { (suffix, path) ->
            val tarball = FilamentDownloads.releaseTarball(cache, version, suffix, logger)
            var size: Int? = null
            TarArchiveInputStream(GZIPInputStream(tarball.inputStream().buffered())).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    if (entry.isFile && entry.name == path) {
                        size = sizeRe.find(tar.readBytes().toString(Charsets.UTF_8))?.groupValues?.get(1)?.toInt()
                        break
                    }
                    entry = tar.nextEntry
                }
            }
            val s = size ?: error("UBERARCHIVE_DEFAULT_SIZE not found at $path in ${tarball.name}")
            logger.lifecycle("    %-8s %d".format(suffix, s))
            s
        }
        val dst = outDir.resolve("gltfio/materials/uberarchive.h")
        dst.parentFile.mkdirs()
        dst.writeText(
            """
            // Generated by the downloadIncludes Gradle task (build-logic) — do not edit.
            // Sizes are extracted from each platform's per-release tarball.
            #ifndef UBERARCHIVE_H_
            #define UBERARCHIVE_H_

            #include <stdint.h>

            #if defined(__APPLE__)
            #include <TargetConditionals.h>
            #endif

            extern "C" {
                extern const uint8_t UBERARCHIVE_PACKAGE[];
            }

            #define UBERARCHIVE_DEFAULT_OFFSET 0
            #if defined(__APPLE__) && TARGET_OS_IPHONE
            #define UBERARCHIVE_DEFAULT_SIZE ${sizes["ios"]}
            #elif defined(__APPLE__)
            #define UBERARCHIVE_DEFAULT_SIZE ${sizes["mac"]}
            #elif defined(_WIN32) || defined(_WIN64)
            #define UBERARCHIVE_DEFAULT_SIZE ${sizes["windows"]}
            #else
            #define UBERARCHIVE_DEFAULT_SIZE ${sizes["linux"]}
            #endif
            #define UBERARCHIVE_DEFAULT_DATA (UBERARCHIVE_PACKAGE + UBERARCHIVE_DEFAULT_OFFSET)

            #endif
            """.trimIndent() + "\n"
        )
    }
}
