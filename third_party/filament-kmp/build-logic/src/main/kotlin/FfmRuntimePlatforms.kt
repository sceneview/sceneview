/**
 * The FFM native runtime platform set, shared between :java (which stages the natives and
 * exposes them via consumable configurations) and the :java:runtime* modules (which jar and
 * publish them).
 *
 * `published` mirrors the CI publish matrix (.github/workflows/publish.yml build-natives).
 * A dev host outside that list (e.g. macos-x64) still gets a staging config so the fat
 * :java:runtime jar works locally — it just has no dedicated runtime-<platform> module.
 */
object FfmRuntimePlatforms {
    val published = listOf("macos-arm64", "linux-x64", "linux-arm64", "windows-x64")

    /** "{platform}-{arch}" of the build host, matching FilamentJvmNative's resource layout. */
    fun host(): String {
        val arch = if (hostArch() == "Arm64") "arm64" else "x64"
        return "${hostPlatform()}-$arch"
    }

    /** published + host, host first so local staging always exists. */
    fun all(): List<String> = (listOf(host()) + published).distinct()

    /** Name of :java's consumable configuration carrying the staged natives dir. */
    fun nativesConfigName(platformArch: String) = "ffmNatives-$platformArch"

    /** Gradle OperatingSystemFamily name for "{platform}-{arch}". */
    fun osFamily(platformArch: String): String = when (platformArch.substringBeforeLast('-')) {
        "macos" -> org.gradle.nativeplatform.OperatingSystemFamily.MACOS
        "linux" -> org.gradle.nativeplatform.OperatingSystemFamily.LINUX
        "windows" -> org.gradle.nativeplatform.OperatingSystemFamily.WINDOWS
        else -> error("Unknown platform in '$platformArch'")
    }

    /** Gradle MachineArchitecture name for "{platform}-{arch}". */
    fun machineArch(platformArch: String): String = when (platformArch.substringAfterLast('-')) {
        "arm64" -> org.gradle.nativeplatform.MachineArchitecture.ARM64
        "x64" -> org.gradle.nativeplatform.MachineArchitecture.X86_64
        else -> error("Unknown arch in '$platformArch'")
    }
}
