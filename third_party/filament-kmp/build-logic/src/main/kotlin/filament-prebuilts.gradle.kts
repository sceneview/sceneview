// Registers the Filament prebuilt/header download tasks on the root project so
// they are shared across :kotlin:* and :java:*. Pure JVM implementation
// (FilamentDownloads.kt) — replaced the scripts/gradle/*.py Python scripts.
//
// Targets correspond to:
//   • iosArm64 / iosSimulatorArm64 — Kotlin/Native iOS targets.
//   • macosArm64                   — JVM/Panama host (:java:*); macOS uses
//                                     the JVM build, not Kotlin/Native. (No
//                                     macosX64: upstream releases stopped
//                                     shipping mac x86_64 libs.)
//   • linuxX64 / linuxArm64 / mingwX64 — JVM/Panama host on Linux/Windows.
//   • web                          — Filament.js + WASM for the :web module;
//                                     output goes to prebuilts/web/ (no lib/ subdir).

val filaVersion = project.property("filaVersion") as String
val prebuiltsCacheDir = layout.projectDirectory.dir(".gradle/filament-prebuilts-cache")

val prebuiltTargets = listOf(
    "iosArm64",
    "iosSimulatorArm64",
    "macosArm64",
    "linuxX64",
    "linuxArm64",
    "mingwX64",
    "web",
)

prebuiltTargets.forEach { targetName ->
    tasks.register<DownloadFilamentPrebuiltsTask>("downloadPrebuilts_$targetName") {
        group = "filament"
        description = "Downloads Filament $filaVersion prebuilt libraries for $targetName."
        filamentVersion.set(filaVersion)
        target.set(targetName)
        // Web prebuilts land directly in prebuilts/web/; all others in prebuilts/<target>/lib/.
        outputDir.set(
            if (targetName == "web") layout.projectDirectory.dir("prebuilts/web")
            else layout.projectDirectory.dir("prebuilts/$targetName/lib")
        )
        cacheDir.set(prebuiltsCacheDir)
    }
}

tasks.register<DownloadFilamentIncludesTask>("downloadIncludes") {
    group = "filament"
    description = "Downloads Filament $filaVersion public headers into include/."
    filamentVersion.set(filaVersion)
    includeDir.set(layout.projectDirectory.dir("include"))
    cacheDir.set(prebuiltsCacheDir)
    stampFile.set(layout.projectDirectory.file("include/.filament-version"))
}

tasks.register("downloadPrebuilts") {
    group = "filament"
    description = "Downloads Filament $filaVersion prebuilt libraries + headers for all targets."
    dependsOn(prebuiltTargets.map { "downloadPrebuilts_$it" })
    dependsOn("downloadIncludes")
}
