package io.github.sceneview.ar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Vertex-attribute contract between each plane visualizer's mesh and the materials its renderer
 * applies to that mesh (#3186 / #3188 log signature).
 *
 * ## What broke
 *
 * [PlaneVisualizer] (V1 — the default plane renderer since v4.16.1) declared a POSITION-only
 * `VertexBuffer`, while [io.github.sceneview.ar.scene.PlaneRenderer] applies
 * `materials/plane_renderer_shadow.filamat` to it. That blob is a `shadowMultiplier : true`
 * material, and Filament unconditionally adds `TANGENTS` to a shadow-multiplier material's
 * required attributes — so every detected plane's shadow submesh logged
 *
 * ```
 * missing required attributes (0x3), declared=0x1
 * ```
 *
 * on every renderable rebuild AND on every per-frame `setMaterialInstanceAt`, and shaded against
 * OpenGL's generic-attribute fallback instead of the plane's real surface normal.
 *
 * ## Why a source/blob contract rather than a render test
 *
 * The mismatch is a *build-time* fact — the declared set and the required set are both fixed
 * before a frame is ever drawn — and Filament does not throw on it, it warns and renders wrong.
 * A pure-JVM test can therefore catch the whole class, while a render test cannot run at all
 * here (the native engine is not loaded in unit tests; same rationale as
 * `ShadowReceiverPlaneContractTest`).
 *
 * So this reads the two facts from their sources of truth and compares them:
 *  * **required** — the `MAT_REQA` chunk of each committed `.filamat` blob,
 *  * **declared** — the `VertexBuffer.VertexAttribute.*` set in each visualizer's Kotlin source.
 *
 * It generalises: any future material added to a plane renderer, or any attribute dropped from a
 * visualizer, fails here rather than on a user's device log.
 */
class PlaneVisualizerAttributeContractTest {

    /**
     * Pairs a plane renderer (which owns the materials) with the visualizer whose mesh those
     * materials are applied to. JVM tests run with the module directory as CWD.
     */
    private data class PlanePipeline(
        val label: String,
        val visualizerClass: String,
        val renderer: File,
        val visualizer: File
    )

    private val pipelines = listOf(
        PlanePipeline(
            label = "V1",
            visualizerClass = "PlaneVisualizer",
            renderer = File("src/main/java/io/github/sceneview/ar/scene/PlaneRenderer.kt"),
            visualizer = File("src/main/java/io/github/sceneview/ar/PlaneVisualizer.kt")
        ),
        PlanePipeline(
            label = "V2",
            visualizerClass = "PlaneVisualizerV2",
            renderer = File("src/main/java/io/github/sceneview/ar/scene/PlaneRendererV2.kt"),
            visualizer = File("src/main/java/io/github/sceneview/ar/PlaneVisualizerV2.kt")
        )
    )

    // ── 1. The rule that bit us: shadowMultiplier ⇒ TANGENTS required ─────────────────────────

    @Test
    fun `plane_renderer_shadow requires TANGENTS because it is a shadowMultiplier material`() {
        val matSource = File("src/main/materials/plane_renderer_shadow.mat")
        assertTrue("Expected ${matSource.absolutePath}", matSource.exists())
        assertTrue(
            "plane_renderer_shadow.mat is expected to be a shadow catcher " +
                "(`shadowMultiplier : true`) — that flag is WHY its compiled blob requires " +
                "TANGENTS. If it is gone, this whole contract needs rewriting, not relaxing.",
            matSource.readText().contains(Regex("""shadowMultiplier\s*:\s*true"""))
        )

        val required = requiredAttributes("plane_renderer_shadow")
        assertEquals(
            "plane_renderer_shadow.filamat MAT_REQA must be POSITION|TANGENTS (0x3): Filament " +
                "adds TANGENTS to any shadowMultiplier material even though the shader is " +
                "`unlit` and never reads a normal explicitly. Read: " +
                "0x${required.toString(16)}.",
            POSITION_BIT or TANGENTS_BIT,
            required
        )
    }

    @Test
    fun `the REQA parser distinguishes a POSITION-only material from a TANGENTS one`() {
        // Vacuity guard for the assertion above: a parser that returned a constant, or that
        // silently read the wrong chunk, would "pass" the shadow test for the wrong reason.
        // plane_renderer.filamat is the neighbouring blob, compiled by the same GenerateFilamat
        // run, applied to the same mesh — and it requires POSITION alone.
        assertEquals(
            "plane_renderer.filamat must require POSITION only (0x1) — it is `unlit` WITHOUT " +
                "shadowMultiplier. If this reads 0x3 too, the parser is not reading MAT_REQA.",
            POSITION_BIT,
            requiredAttributes("plane_renderer")
        )
    }

    // ── 2. The regression guard ───────────────────────────────────────────────────────────────

    @Test
    fun `every plane visualizer declares every attribute its materials require`() {
        pipelines.forEach { pipeline ->
            val declared = declaredAttributes(pipeline.visualizer)
            val materials = materialAssets(pipeline.renderer)

            // Vacuity guards: an empty parse must never read as "nothing to check".
            assertTrue(
                "${pipeline.label}: parsed no VertexBuffer.VertexAttribute.* out of " +
                    "${pipeline.visualizer.absolutePath} — the parser is looking at the wrong " +
                    "file or the declaration moved.",
                declared != 0
            )
            assertTrue(
                "${pipeline.label}: parsed ${materials.size} material asset(s) out of " +
                    "${pipeline.renderer.absolutePath}; a plane renderer applies at least a " +
                    "plane material and a shadow material.",
                materials.size >= 2
            )
            assertTrue(
                "${pipeline.label}: ${pipeline.renderer.name} must actually drive " +
                    "${pipeline.visualizerClass} — otherwise this pairing compares a mesh with " +
                    "materials that are never applied to it.",
                pipeline.renderer.readText().contains(pipeline.visualizerClass)
            )

            materials.forEach { material ->
                val required = requiredAttributes(material)
                val missing = required and declared.inv()
                assertEquals(
                    "${pipeline.label}: $material.filamat requires ${format(required)} but " +
                        "${pipeline.visualizer.name} declares only ${format(declared)} — " +
                        "missing ${format(missing)}. Filament does NOT fail the build for this: " +
                        "it logs `missing required attributes (0x${required.toString(16)}), " +
                        "declared=0x${declared.toString(16)}` and shades against the generic " +
                        "vertex-attribute fallback, so the geometry renders WRONG rather than " +
                        "not at all (#3186 / #3188). Add the attribute to the vertex buffer.",
                    0,
                    missing
                )
            }
        }
    }

    @Test
    fun `every plane visualizer allocates one backing buffer per declared attribute`() {
        // A declared attribute whose buffer index is out of `bufferCount` range, or a buffer that
        // is allocated but never bound, is the other half of the same failure: Filament reads an
        // unset buffer. Keeping the two numbers equal keeps the "one attribute, one buffer"
        // layout both visualizers use (and that `geometries/Geometry.kt` uses too).
        pipelines.forEach { pipeline ->
            val source = stripComments(pipeline.visualizer.readText())
            val attributeCount = Regex("""\.attribute\(""").findAll(source).count()
            assertTrue(
                "${pipeline.label}: found no `.attribute(` call in ${pipeline.visualizer.name}.",
                attributeCount > 0
            )
            assertEquals(
                "${pipeline.label}: ${pipeline.visualizer.name} declares $attributeCount vertex " +
                    "attribute(s) but allocates ${bufferCount(pipeline.visualizer)} backing " +
                    "buffer(s).",
                attributeCount,
                bufferCount(pipeline.visualizer)
            )
        }
    }

    @Test
    fun `V1 uploads its constant tangent frame once, not per frame`() {
        // Declaring TANGENTS without ever filling the buffer trades the warning for an unset
        // buffer. V1's frame is constant (its mesh is flat in the plane's own frame and the pose
        // rides on the entity transform), so the upload belongs at construction — the per-frame
        // path must stay one POSITION upload + one index upload, as it was before the fix.
        val source = stripComments(File("src/main/java/io/github/sceneview/ar/PlaneVisualizer.kt").readText())
        val uploads = Regex("""setBufferAt\(\s*engine,\s*BUFFER_INDEX_TANGENT""").findAll(source).count()
        assertEquals(
            "PlaneVisualizer must upload its TANGENTS buffer exactly once (at construction). " +
                "Found $uploads upload site(s).",
            1,
            uploads
        )
        assertTrue(
            "The TANGENTS upload must sit in the `init { }` block, before updateGeometry() — " +
                "the per-frame path must not grow a second upload.",
            source.indexOf("BUFFER_INDEX_TANGENT,") < source.indexOf("private fun updateGeometry")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Bit set by each `VertexBuffer.VertexAttribute` — the enum's ordinal, as Filament packs it. */
    private val attributeBits = mapOf(
        "POSITION" to (1 shl 0),
        "TANGENTS" to (1 shl 1),
        "COLOR" to (1 shl 2),
        "UV0" to (1 shl 3),
        "UV1" to (1 shl 4),
        "BONE_INDICES" to (1 shl 5),
        "BONE_WEIGHTS" to (1 shl 6),
        "UNUSED" to (1 shl 7),
        "CUSTOM0" to (1 shl 8),
        "CUSTOM1" to (1 shl 9),
        "CUSTOM2" to (1 shl 10),
        "CUSTOM3" to (1 shl 11),
        "CUSTOM4" to (1 shl 12),
        "CUSTOM5" to (1 shl 13),
        "CUSTOM6" to (1 shl 14),
        "CUSTOM7" to (1 shl 15)
    )

    /** Required-attribute bitmask of a committed blob, read from its `MAT_REQA` chunk. */
    private fun requiredAttributes(material: String): Int {
        val blob = File("src/main/assets/materials/$material.filamat")
        assertTrue("Expected ${blob.absolutePath}", blob.exists())
        val chunk = filamatChunk(blob.readBytes(), "MAT_REQA")
        assertNotNull(
            "$material.filamat has no MAT_REQA chunk — corrupt blob, or a matc version that " +
                "renamed the chunk (then this test must follow, not be deleted).",
            chunk
        )
        return readUInt32LE(chunk!!, 0)
    }

    /** Declared-attribute bitmask of a visualizer, read from its `VertexBuffer.Builder` chain. */
    private fun declaredAttributes(source: File): Int {
        assertTrue("Expected ${source.absolutePath}", source.exists())
        return Regex("""VertexBuffer\.VertexAttribute\.([A-Z0-9_]+)""")
            .findAll(stripComments(source.readText()))
            .fold(0) { mask, match ->
                val name = match.groupValues[1]
                val bit = attributeBits[name]
                assertNotNull(
                    "Unknown VertexAttribute `$name` in ${source.name} — add it to this test's " +
                        "ordinal table (silently ignoring it would make the contract vacuous).",
                    bit
                )
                mask or bit!!
            }
    }

    /** `bufferCount(n)` of a visualizer, resolving a `const val` argument to its literal. */
    private fun bufferCount(source: File): Int {
        val text = stripComments(source.readText())
        val argument = Regex("""\.bufferCount\(\s*([A-Za-z0-9_]+)\s*\)""").find(text)?.groupValues?.get(1)
        assertNotNull("No `.bufferCount(...)` call found in ${source.name}", argument)
        return argument!!.toIntOrNull()
            ?: Regex("""const\s+val\s+$argument\s*=\s*(\d+)""").find(text)?.groupValues?.get(1)?.toInt()
            ?: error("Could not resolve `bufferCount($argument)` to a literal in ${source.name}")
    }

    /** The `materials/<name>.filamat` assets a renderer loads. */
    private fun materialAssets(renderer: File): List<String> {
        assertTrue("Expected ${renderer.absolutePath}", renderer.exists())
        return Regex(""""materials/([A-Za-z0-9_]+)\.filamat"""")
            .findAll(renderer.readText())
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    private fun format(mask: Int): String {
        val names = attributeBits.filterValues { it and mask != 0 }.keys
        return "0x${mask.toString(16)}" + if (names.isEmpty()) "" else " (${names.joinToString("|")})"
    }

    /** Drops block then line comments, so prose mentioning an attribute never counts as code. */
    private fun stripComments(source: String): String = source
        .replace(Regex("""/\*[\s\S]*?\*/"""), "")
        .replace(Regex("""//[^\n]*"""), "")

    /**
     * Returns the payload of the first `.filamat` chunk with the given [tag], or null.
     *
     * The filamat container is a sequence of `[8-byte tag][uint32 LE size][payload]` chunks.
     * Tags are 8-char ASCII identifiers stored as little-endian uint64s, so they appear
     * byte-reversed on disk ("MAT_REQA" → "AQER_TAM").
     */
    private fun filamatChunk(bytes: ByteArray, tag: String): ByteArray? {
        val tagBytes = tag.reversed().toByteArray(Charsets.US_ASCII)
        var i = 0
        while (i + 12 <= bytes.size) {
            val size = readUInt32LE(bytes, i + 8)
            if (size < 0 || i + 12 + size > bytes.size) return null
            if (bytes.copyOfRange(i, i + 8).contentEquals(tagBytes)) {
                return bytes.copyOfRange(i + 12, i + 12 + size)
            }
            i += 12 + size
        }
        return null
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val POSITION_BIT = 1 shl 0
        const val TANGENTS_BIT = 1 shl 1
    }
}
