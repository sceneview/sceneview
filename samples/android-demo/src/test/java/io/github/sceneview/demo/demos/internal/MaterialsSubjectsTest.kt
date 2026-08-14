package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.sketchfab.SampleAssets
import io.github.sceneview.demo.sketchfab.SketchfabSlug
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Determinism contract for the Materials demo's **PBR Materials** section (#2874).
 *
 * The defect these guard against is invisible to a per-frame check: every
 * individual capture of the demo looked fine, they just differed from each other
 * — a streamed subject on a device with a warm network, a bundled fallback on one
 * without. A store slot, a Maestro assertion and a listing diff all need the
 * cold-launch frame to be the same every run, so the invariant is asserted here
 * rather than left to a screenshot.
 *
 * Pure JVM — no Android framework, no network, no Filament.
 */
class MaterialsSubjectsTest {

    @Test
    fun `cold launch opens on a bundled subject, never a streamed one`() {
        val subject = MaterialsSubjects.all()[MaterialsSubjects.DEFAULT_INDEX]
        assertTrue(
            "The default subject must be bundled — a streamed one makes the first " +
                "frame depend on the network / API key / cache (#2874). Got: $subject",
            subject is MaterialsSubject.Bundled,
        )
    }

    @Test
    fun `the default subject is the first chip`() {
        assertEquals(0, MaterialsSubjects.DEFAULT_INDEX)
        assertEquals(MaterialsSubjects.BUNDLED_DEFAULT, MaterialsSubjects.all().first())
    }

    @Test
    fun `the bundled default points at an APK asset path`() {
        val path = MaterialsSubjects.BUNDLED_DEFAULT.assetPath
        assertTrue(
            "Bundled subjects load through the asset-path overload, so the path must " +
                "be `assets/`-relative (no scheme, no leading slash). Got: $path",
            path.startsWith("models/") && path.endsWith(".glb"),
        )
    }

    @Test
    fun `the bundled default is attributed`() {
        assertTrue(MaterialsSubjects.BUNDLED_DEFAULT.author.isNotBlank())
        assertTrue(MaterialsSubjects.BUNDLED_DEFAULT.displayName.isNotBlank())
        assertTrue(
            "The chip's extension tag is what tells the user which KHR_materials_* " +
                "family they are looking at",
            MaterialsSubjects.BUNDLED_DEFAULT.extensionTag.contains("KHR_materials_"),
        )
    }

    @Test
    fun `every streamed subject comes from the materials registry category`() {
        val registry = SampleAssets.byCategory["materials"].orEmpty()
        val streamed = MaterialsSubjects.all()
            .filterIsInstance<MaterialsSubject.Streamed>()
            .map { it.slug }
        assertEquals(registry, streamed)
    }

    @Test
    fun `variety survives — the streamed slugs are still selectable`() {
        val subjects = MaterialsSubjects.all()
        assertTrue(
            "The streamed catalogue must stay reachable as an explicit chip tap; " +
                "pinning the default subject is not a reason to drop it (#2874)",
            subjects.filterIsInstance<MaterialsSubject.Streamed>().isNotEmpty(),
        )
        assertEquals(subjects.size, subjects.map { it.displayName }.distinct().size)
    }

    @Test
    fun `an empty registry still yields a renderable default`() {
        val subjects = MaterialsSubjects.all(slugs = emptyList())
        assertEquals(listOf(MaterialsSubjects.BUNDLED_DEFAULT), subjects)
    }

    @Test
    fun `framing constants keep the camera outside the subject`() {
        assertTrue(MaterialsSubjects.FRAMING_UNITS > 0f)
        assertTrue(
            "An orbit radius inside the subject's own bounding cube puts the camera " +
                "inside the model — the framing failure the store-capture script " +
                "documents for model-viewer at 2.0 m",
            MaterialsSubjects.ORBIT_RADIUS_METERS > MaterialsSubjects.FRAMING_UNITS,
        )
        assertTrue(
            "…and a radius far beyond the subject is the other failure mode: a speck " +
                "in a black frame (#2874)",
            MaterialsSubjects.ORBIT_RADIUS_METERS < 6f * MaterialsSubjects.FRAMING_UNITS,
        )
    }

    @Test
    fun `subject metadata is delegated, not copied, for streamed slugs`() {
        val slug = SketchfabSlug(
            uid = "0".repeat(32),
            displayName = "Test Model",
            author = "tester",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.3f,
            hasBakedAnimation = false,
            category = "materials",
            tags = listOf("KHR_materials_sheen"),
        )
        val subject = MaterialsSubject.Streamed(slug)
        assertEquals("Test Model", subject.displayName)
        assertEquals("tester", subject.author)
        assertEquals("KHR_materials_sheen", subject.extensionTag)
    }

    /**
     * Every subject node in the section must derive `autoAnimate` from
     * `DemoSettings.qaMode` (#2958).
     *
     * `qaMode` freezes the orbit yaw (`DemoHelpers`) but not model animation, and
     * `SceneScope.ModelNode` defaults `autoAnimate` to `true` — so a subject with a
     * baked animation would keep moving under `qaMode` and the section's golden
     * screenshots would drift frame to frame.
     *
     * This is asserted on the SOURCE rather than on a frame on purpose: every subject
     * the section can show today is static, so no capture — on any device, in either
     * mode — can tell the fixed version from the broken one. The trigger is a CATALOG
     * edit (one animated slug added to `SampleAssets`' `materials` category), which is
     * a data change no rendering test would attribute to this parameter.
     */
    @Test
    fun `every subject node derives autoAnimate from qaMode`() {
        val subjectCalls = modelNodeCallArguments(demoSource("MaterialsDemo.kt"))
            .filter { it.contains("MaterialsSubjects.FRAMING_UNITS") }
        assertTrue(
            "Expected the bundled + streamed subject nodes to be mounted with " +
                "`scaleToUnits = MaterialsSubjects.FRAMING_UNITS`; found " +
                "${subjectCalls.size}. If the section was restructured, update this " +
                "test rather than dropping the contract it guards.",
            subjectCalls.size >= 2,
        )
        subjectCalls.forEach { call ->
            assertTrue(
                "A Materials subject node is mounted without " +
                    "`autoAnimate = !DemoSettings.qaMode` (#2958). `ModelNode` defaults " +
                    "it to true, so an animated subject would break the section's qaMode " +
                    "determinism contract. Offending call args: ${call.trim()}",
                call.contains("autoAnimate = !DemoSettings.qaMode"),
            )
        }
    }

    /**
     * The argument list of every `ModelNode(…)` call in [source], balanced on parentheses
     * so a nested `Position(…)` / `Scale(…)` argument does not truncate the match.
     */
    private fun modelNodeCallArguments(source: String): List<String> = buildList {
        var from = source.indexOf(CALL)
        while (from >= 0) {
            var depth = 1
            var i = from + CALL.length
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            if (depth == 0) add(source.substring(from + CALL.length, i - 1))
            from = source.indexOf(CALL, from + CALL.length)
        }
    }

    /**
     * Reads a demo source file. The Gradle test task's working directory is the module
     * directory, but the search walks up so the test also passes when a runner starts it
     * from the repository root.
     */
    private fun demoSource(fileName: String): String {
        val relative = "samples/android-demo/src/main/java/io/github/sceneview/demo/demos/$fileName"
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $relative from ${File("").absolutePath}")
    }

    private companion object {
        const val CALL = "ModelNode("
    }

    @Test
    fun `a tagless slug degrades to an empty extension tag, not a crash`() {
        val slug = SketchfabSlug(
            uid = "1".repeat(32),
            displayName = "Untagged",
            author = "tester",
            licenseUrl = "https://creativecommons.org/licenses/by/4.0/",
            fallbackBundledPath = "models/khronos_damaged_helmet.glb",
            scaleToUnits = 0.3f,
            hasBakedAnimation = false,
            category = "materials",
        )
        assertEquals("", MaterialsSubject.Streamed(slug).extensionTag)
    }
}
