import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.attributes.java.TargetJvmVersion
import org.gradle.nativeplatform.MachineArchitecture
import org.gradle.nativeplatform.OperatingSystemFamily
import java.security.MessageDigest

plugins {
    `java-library`
    kotlin("jvm")
    id("filament-publish")
}

// JVM/Panama (FFM) bindings module. Drives the combined CMake build at c/CMakeLists.txt
// (-DFILAMENT_BUILD_SHARED=ON → one libfilament-c shared lib with the Filament static
// archives linked exactly once), runs jextract over the C headers to generate the
// low-level binding classes, packages the dylib into this module's resources, and ships
// a loader that System.loads it so jextract's loaderLookup resolves the symbols. It also
// hosts the shared FFM helpers (src/main/kotlin Ffm.kt) the kotlin:* jvm actuals build on.
//
// kotlin:filament's jvmMain depends on this module and writes the idiomatic Kotlin
// actuals on top of the generated FilamentC class.

// Directory is java/ but the published artifact keeps its id `filament-ffm`,
// set via `maven.artifactId` in java/gradle.properties (read by filament-publish
// before it finalizes coordinates).

// FFM was finalized in JDK 22. The Gradle daemon runs on JDK 25 (gradle/gradle-daemon-jvm.properties),
// so we just pin the JVM release floor (libs.versions.toml `jvm-target`) to keep the
// published artifact usable on any JDK 22+.
val jvmRelease = libs.versions.jvm.target.get()
tasks.withType<JavaCompile>().configureEach {
    options.release.set(jvmRelease.toInt())
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(jvmRelease))
}

// ── Native build + jextract generation (see build-logic/FilamentJvmNative.kt) ─
// Extract the full C surface — filament + filamat + filament-utils + gltfio — into one
// FilamentC class. The combined libfilament-c shared library already links all four wrappers
// (one Filament image → one EntityManager), so a single jextract run over every header keeps
// the generated FilaTypes structs consistent and lets all four kotlin JVM modules share it.
val ffm = applyFilamentJvmNative(
    headerDirs = listOf(
        rootProject.file("c/filament/c"),
        rootProject.file("c/filamat/c"),
        rootProject.file("c/filament-utils/c"),
        rootProject.file("c/gltfio/c"),
    ),
    includeDirs = listOf(
        rootProject.file("c/filament/c"),
        rootProject.file("c/filamat/c"),
        rootProject.file("c/filament-utils/c"),
        rootProject.file("c/gltfio/c"),
        rootProject.file("include"),
    ),
    ffmPackage = "io.github.erkko68.filament.ffm",
    headerClassName = "FilamentC",
)

// Generated jextract sources compile alongside the hand-written loader. Adding the
// jextract task (not just its output dir) as the source dir makes Gradle infer the
// dependency for every consumer — compileJava, compileKotlin, sourcesJar — so none
// of them can run before the bindings are generated.
sourceSets.named("main") {
    java.srcDir(ffm.jextract)
}

// ── Stage the natives per platform (consumed by the :java:runtime* modules) ───
//
// The natives no longer ship inside this module's jar: each platform's
// libfilament-c (+ .sha256 stamp — FilamentLoader keys its extraction cache dir by it)
// is staged into build/ffm-natives/<platform-arch>/natives/<platform-arch>/ and exposed
// through a consumable `ffmNatives-<platform-arch>` configuration. :java:runtime-<pa>
// jars one platform; :java:runtime jars them all (fat fallback).
//
// Sources: the host platform comes from the local cmake build; other platforms from
// -PcArtifactsDir=<path> (CI publish job), whose {platform}-{arch}/ subdirs each hold the
// combined libfilament-c for that platform. The host may appear in both — local wins.
val cArtifactsDir = (project.findProperty("cArtifactsDir") as? String)?.let { file(it) }
FfmRuntimePlatforms.all().forEach { pa ->
    val stage = tasks.register<Sync>("stageFfmNatives_$pa") {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        into(layout.buildDirectory.dir("ffm-natives/$pa"))
        if (pa == ffm.platformArch) {
            dependsOn(ffm.cmakeBuild)
            // Single-config generators (Make / Ninja) put libs directly in build/cmake/;
            // multi-config ones (Visual Studio) in build/cmake/<config>/.
            from(ffm.dylibDir) {
                include("*.dylib", "*.so", "*.dll")
                into("natives/$pa")
            }
            from(ffm.dylibDir.map { it.dir(ffm.buildType) }) {
                include("*.dll")
                into("natives/$pa")
            }
        }
        if (cArtifactsDir != null) {
            from(File(cArtifactsDir, pa)) {
                include("*.dylib", "*.so", "*.dll")
                into("natives/$pa")
            }
        }
        doLast {
            val md = MessageDigest.getInstance("SHA-256")
            destinationDir.walkTopDown()
                .filter { it.isFile && it.extension in setOf("dylib", "so", "dll") }
                .forEach { lib ->
                    md.reset()
                    lib.inputStream().use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            md.update(buf, 0, n)
                        }
                    }
                    File(lib.path + ".sha256").writeText(md.digest().joinToString("") { "%02x".format(it) })
                }
        }
    }
    val cfg = configurations.create(FfmRuntimePlatforms.nativesConfigName(pa)) {
        isCanBeConsumed = true
        isCanBeResolved = false
    }
    artifacts.add(cfg.name, layout.buildDirectory.dir("ffm-natives/$pa")) {
        builtBy(stage)
    }
}

tasks.named("assemble") {
    dependsOn(ffm.cmakeBuild)
}

// ── Native runtime resolution (published on this module's own metadata) ───────
//
// Default (plain Gradle or Maven, no attributes): the runtime variant depends on every
// :java:runtime-<platform>-<arch> jar — zero-config, all platforms, as before the split.
// Gradle consumers that declare OperatingSystemFamily + MachineArchitecture attributes
// match one of the extra variants below instead and download a single platform's ~13 MB.

// Bucket for the all-platforms default. Kept out of implementation/runtimeOnly so the
// attributed variants (which extend those) don't inherit every platform.
val ffmRuntimeAll = configurations.create("ffmRuntimeAll") {
    isCanBeConsumed = false
    isCanBeResolved = false
}
configurations.named("runtimeElements") { extendsFrom(ffmRuntimeAll) }
dependencies {
    FfmRuntimePlatforms.published.forEach { pa ->
        ffmRuntimeAll(project(":java:runtime-$pa"))
    }
}

// A dev host outside the published set (e.g. macos-x64) has no platform module; bundle its
// locally staged natives into this jar so project-dependency consumers (jvmTest, samples)
// still run. On published platforms the jar stays natives-free. CI publishes from a
// published platform, so this never leaks into a release.
val hostPa = FfmRuntimePlatforms.host()
if (hostPa !in FfmRuntimePlatforms.published) {
    val hostNatives = configurations.create("ffmHostNativesInput") {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
    dependencies {
        hostNatives(project(mapOf("path" to path, "configuration" to FfmRuntimePlatforms.nativesConfigName(hostPa))))
    }
    tasks.named<Jar>("jar") {
        from(hostNatives)
    }
}

// The attributed variants must mirror everything runtimeElements offers (the bindings jar,
// kotlin-stdlib, …) because Gradle selects them INSTEAD of runtimeElements — hence the
// extendsFrom of the declaring buckets, plus the one platform dep that replaces ffmRuntimeAll.
val javaComponent = components["java"] as AdhocComponentWithVariants
FfmRuntimePlatforms.published.forEach { pa ->
    val osFamily = FfmRuntimePlatforms.osFamily(pa)
    val machineArch = FfmRuntimePlatforms.machineArch(pa)
    val variant = configurations.create("ffmRuntime-$pa") {
        isCanBeConsumed = true
        isCanBeResolved = false
        extendsFrom(configurations["implementation"], configurations["runtimeOnly"])
        attributes {
            attribute(OperatingSystemFamily.OPERATING_SYSTEM_ATTRIBUTE, objects.named(osFamily))
            attribute(MachineArchitecture.ARCHITECTURE_ATTRIBUTE, objects.named(machineArch))
        }
    }
    dependencies.add(variant.name, project(":java:runtime-$pa"))
    artifacts.add(variant.name, tasks.named("jar"))
    javaComponent.addVariantsFromConfiguration(variant) { mapToMavenScope("runtime") }
}

// Each variant's attributes must be runtimeElements' full set + os/arch, and nothing less:
// plain consumers then disambiguate to runtimeElements (the strict-subset candidate), while
// attributed consumers match os/arch and get the platform variant. Hand-listing runs behind
// what the kotlin plugin stamps on runtimeElements (e.g. kotlin.platform.type), so copy.
afterEvaluate {
    val source = configurations["runtimeElements"].attributes
    configurations.matching { it.name.startsWith("ffmRuntime-") }.configureEach {
        source.keySet().forEach { key ->
            @Suppress("UNCHECKED_CAST")
            val k = key as Attribute<Any>
            if (attributes.getAttribute(k) == null) {
                attributes.attribute(k, source.getAttribute(k)!!)
            }
        }
    }
}

// Each platform dep reaches the POM twice — once from ffmRuntimeAll via runtimeElements,
// once from its variant's maven-scope mapping. Dedupe by artifactId; GMM is unaffected.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom.withXml {
            val deps = asElement().getElementsByTagName("dependency")
            val seen = mutableSetOf<String>()
            val doomed = (0 until deps.length).map { deps.item(it) }.filter { node ->
                val children = node.childNodes
                val artifactId = (0 until children.length)
                    .map { children.item(it) }
                    .firstOrNull { it.nodeName == "artifactId" }
                    ?.textContent ?: return@filter false
                !seen.add(artifactId)
            }
            doomed.forEach { it.parentNode.removeChild(it) }
        }
    }
}

// ── Loader unit tests (extraction cache + stale-dir cleanup) ──────────────────
dependencies {
    testImplementation(libs.junit)
}
tasks.named<Test>("test") {
    useJUnit()
}
