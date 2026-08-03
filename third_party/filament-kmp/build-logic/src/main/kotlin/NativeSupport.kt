import java.io.File

// Shared host/cmake helpers for the Filament native builds (FilamentNative.kt drives the
// Kotlin/Native cinterop archives; FilamentJvmNative.kt drives the JVM/Panama shared lib).

/** Absolute path to a usable cmake, preferring Homebrew installs, else whatever is on PATH. */
internal fun resolveCmake(): String =
    listOf("/opt/homebrew/bin/cmake", "/usr/local/bin/cmake")
        .firstOrNull { File(it).exists() }
        ?: "cmake"

/** The host OS as the CMake `-DFILAMENT_PLATFORM` value: "macos" | "windows" | "linux". */
internal fun hostPlatform(): String {
    val os = System.getProperty("os.name")
    return when {
        os.startsWith("Mac", ignoreCase = true) || os.contains("Darwin", ignoreCase = true) -> "macos"
        os.startsWith("Windows", ignoreCase = true) -> "windows"
        else -> "linux"
    }
}

/** The host CPU as the CMake `-DFILAMENT_ARCH` value: "Arm64" | "X64". */
internal fun hostArch(): String {
    val arch = System.getProperty("os.arch").lowercase()
    return when {
        arch.contains("aarch64") || arch.contains("arm64") -> "Arm64"
        arch == "x64" || arch.contains("amd64") || arch.contains("x86_64") -> "X64"
        else -> error("Unsupported host arch '$arch'. Use arm64 or x64.")
    }
}
