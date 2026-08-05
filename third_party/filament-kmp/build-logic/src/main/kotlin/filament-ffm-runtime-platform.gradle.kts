// Convention for the per-platform FFM native runtime modules (:java:runtime-<platform>-<arch>).
// Each publishes a resources-only jar with natives/<platform>-<arch>/libfilament-c.* (+ .sha256),
// staged by :java (host: local cmake build; other platforms: -PcArtifactsDir on CI).
// The project name encodes the platform: "runtime-macos-arm64" → "macos-arm64".

import java.util.zip.ZipFile

plugins {
    `java-library`
    id("filament-publish")
}

val platformArch = project.name.removePrefix("runtime-")

// The published natives are JDK-agnostic resources, but the module's GMM variants carry a
// jvm-version attribute derived from targetCompatibility — pin it to the FFM floor (22) so
// consumers on JDK 22 toolchains aren't rejected by attribute matching.
java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

val ffmNativesInput = configurations.create("ffmNativesInput") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Standalone usability: adding just this runtime module pulls the matching bindings.
    // Exclude the sibling platforms that :java's all-platforms default would otherwise
    // drag back in. Excludes (not dependency attributes: those force a different :java
    // variant per platform and conflict when several runtime modules share a graph) —
    // listed under both local project names and published artifact ids, exclusion is
    // exact-match and the two graphs name the module differently.
    api(project(":java")) {
        FfmRuntimePlatforms.published.filter { it != platformArch }.forEach { other ->
            exclude(group = project.group.toString(), module = "runtime-$other")
            exclude(group = project.group.toString(), module = "filament-ffm-runtime-$other")
        }
    }
    ffmNativesInput(project(mapOf("path" to ":java", "configuration" to FfmRuntimePlatforms.nativesConfigName(platformArch))))
}

tasks.named<Jar>("jar") {
    // The staged dir already has the natives/<platform>-<arch>/ layout FilamentLoader expects.
    from(ffmNativesInput)
}

// Off-host platforms stage nothing without -PcArtifactsDir (CI publish job), producing an
// empty jar — fine for a local `build`, silently broken on Maven Central. Fail the publish.
val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
tasks.withType<AbstractPublishToMaven>().configureEach {
    val archive = jarFile
    val pa = platformArch
    doFirst {
        ZipFile(archive.get().asFile).use { zip ->
            check(zip.entries().asIterator().asSequence().any { it.name.startsWith("natives/$pa/") && !it.isDirectory }) {
                "Refusing to publish an empty $pa runtime jar — natives were not staged " +
                    "(CI passes -PcArtifactsDir; this platform's libfilament-c is missing)."
            }
        }
    }
}
