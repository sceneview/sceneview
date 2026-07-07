package io.github.sceneview.ar.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Headless contracts for [ShadowReceiverPlaneNode] + its `shadow_receiver` material
 * (#2241 Sprint-1, PR 3).
 *
 * The Filament-bound runtime behaviour (shadow rendering, material-instance upload) cannot run
 * in a pure-JVM test — the native engine is not loaded (same rationale as
 * `ARCameraStreamPersonOcclusionContractTest`). What IS verifiable headlessly, and pinned here:
 *
 *  1. the committed `shadow_receiver.filamat` blob exists, was compiled with the `matc`
 *     matching the Filament runtime version in `gradle/libs.versions.toml` (the runtime ↔
 *     blob ABI invariant that broke v4.1.0 — see CONTRIBUTING.md), with the ARCore profile-C
 *     flags, and actually exposes the `shadowIntensity` parameter,
 *  2. the `.mat` source declares the shadow-catcher construction (`unlit` + `transparent` +
 *     `shadowMultiplier`),
 *  3. the material is enrolled in `tools/GenerateFilamat.sh`'s `MATS` inventory — otherwise
 *     the pre-push drift gate (`GenerateFilamat.sh --check`) would silently never check it,
 *  4. the node's source-level contract: receives shadows / never casts, loads the right asset,
 *     destroys the material after the renderable teardown, and keeps the Depth Lab default
 *     intensity.
 *
 * The visual behaviour (a real shadow darkening a real floor) is device territory — exercised
 * by Sprint-1 PR 5's demo integration and the #2241 "never claim wow without a frame" gate.
 */
class ShadowReceiverPlaneContractTest {

    // JVM tests run with the module directory as CWD.
    private val matFile = File("src/main/materials/shadow_receiver.mat")
    private val filamatFile = File("src/main/assets/materials/shadow_receiver.filamat")
    private val nodeFile = File("src/main/java/io/github/sceneview/ar/node/ShadowReceiverPlaneNode.kt")
    private val scopeFile = File("src/main/java/io/github/sceneview/ar/ARSceneScope.kt")
    private val generateScript = File("../tools/GenerateFilamat.sh")
    private val versionsToml = File("../gradle/libs.versions.toml")

    private val matSource: String by lazy {
        assertTrue("Expected ${matFile.absolutePath}", matFile.exists())
        matFile.readText()
    }

    private val nodeSource: String by lazy {
        assertTrue("Expected ${nodeFile.absolutePath}", nodeFile.exists())
        nodeFile.readText()
    }

    // ── 1. Committed .filamat blob ────────────────────────────────────────────────────────────

    @Test
    fun `shadow_receiver filamat blob is committed`() {
        assertTrue(
            "materials/shadow_receiver.filamat must ship in arsceneview assets — " +
                "ShadowReceiverPlaneNode loads it at runtime.",
            filamatFile.exists() && filamatFile.length() > 0
        )
    }

    @Test
    fun `filamat blob material version matches the Filament runtime in libs versions toml`() {
        // The ABI invariant (CONTRIBUTING.md "Filament runtime ↔ .filamat ABI invariant"):
        // Filament 1.<minor>.x runtimes only accept blobs whose MAT_VERS chunk == <minor>.
        // v4.1.0 shipped a mismatched pair and 10 demos crashed at first frame.
        val expectedVersion = filamentMinorVersion()
        val blob = filamatFile.readBytes()
        val versionChunk = filamatChunk(blob, "MAT_VERS")
        assertNotNull("shadow_receiver.filamat has no MAT_VERS chunk — corrupt blob?", versionChunk)
        assertEquals(
            "shadow_receiver.filamat was compiled with matc MATERIAL_VERSION " +
                "${readUInt32LE(versionChunk!!, 0)} but the Filament runtime pinned in " +
                "gradle/libs.versions.toml expects $expectedVersion. Re-run " +
                "`bash tools/GenerateFilamat.sh` and commit the regenerated blob.",
            expectedVersion,
            readUInt32LE(versionChunk, 0)
        )
    }

    @Test
    fun `filamat blob was compiled with the ARCore profile C flags`() {
        // matc embeds its verbatim flag string in the MAT_CPRM chunk. arsceneview materials
        // use profile C (see GenerateFilamat.sh / CONTRIBUTING.md).
        val blobText = String(filamatFile.readBytes(), Charsets.ISO_8859_1)
        assertTrue(
            "shadow_receiver.filamat must embed the profile-C matc flags " +
                "(--optimize-size -p mobile -a opengl -a vulkan).",
            blobText.contains("--optimize-size -p mobile -a opengl -a vulkan")
        )
    }

    @Test
    fun `filamat blob exposes the shadowIntensity parameter`() {
        val blobText = String(filamatFile.readBytes(), Charsets.ISO_8859_1)
        assertTrue(
            "shadow_receiver.filamat must expose the `shadowIntensity` material parameter — " +
                "ShadowReceiverPlaneNode sets it at construction and on every intensity change.",
            blobText.contains("shadowIntensity")
        )
    }

    // ── 2. .mat source ────────────────────────────────────────────────────────────────────────

    @Test
    fun `mat source declares the Filament shadow-catcher construction`() {
        // unlit + transparent + shadowMultiplier is Filament's dedicated AR shadow-catcher
        // feature — the port of Depth Lab's `Blend Zero SrcColor`. Removing any of the three
        // turns the "invisible, shadows-only" surface into a visible or shadow-less quad.
        assertTrue("shadingModel must be unlit", matSource.contains(Regex("""shadingModel\s*:\s*unlit""")))
        assertTrue("blending must be transparent", matSource.contains(Regex("""blending\s*:\s*transparent""")))
        assertTrue("shadowMultiplier must be true", matSource.contains(Regex("""shadowMultiplier\s*:\s*true""")))
        assertTrue(
            "the shadowIntensity float parameter must be declared",
            matSource.contains("\"shadowIntensity\"")
        )
    }

    // ── 3. Build-pipeline enrolment ───────────────────────────────────────────────────────────

    @Test
    fun `shadow_receiver is enrolled in the GenerateFilamat MATS inventory with profile C`() {
        assertTrue("Expected ${generateScript.absolutePath}", generateScript.exists())
        val script = generateScript.readText()
        assertTrue(
            "tools/GenerateFilamat.sh must list shadow_receiver in its MATS inventory with the " +
                "profile-C flags — otherwise the pre-push drift gate never checks this blob and " +
                "a stale .filamat can ship (the v4.1.0 failure class).",
            script.contains(
                "arsceneview:shadow_receiver:arsceneview/src/main/materials/shadow_receiver.mat:" +
                    "arsceneview/src/main/assets/materials/shadow_receiver.filamat:" +
                    "--optimize-size -p mobile -a opengl -a vulkan"
            )
        )
    }

    // ── 4. Node source contract ───────────────────────────────────────────────────────────────

    @Test
    fun `node loads the shadow_receiver material asset`() {
        assertTrue(
            "ShadowReceiverPlaneNode must load materials/shadow_receiver.filamat.",
            nodeSource.contains("materials/shadow_receiver.filamat")
        )
    }

    @Test
    fun `node receives shadows and never casts them`() {
        assertTrue(
            "The shadow-catcher mesh must be built with castShadows(false).",
            nodeSource.contains(Regex("""castShadows\(\s*false\s*\)"""))
        )
        assertTrue(
            "The shadow-catcher mesh must be built with receiveShadows(true).",
            nodeSource.contains(Regex("""receiveShadows\(\s*true\s*\)"""))
        )
    }

    @Test
    fun `update guards against the base-constructor call before subclass init (NPE #2620)`() {
        // The ACTUAL 4.21.0 crash: base PlaneNode's `init { trackable = plane }` calls update()
        // — dispatched to this override — before meshNode is initialized, so the override
        // dereferenced a null meshNode and NPE'd the instant a plane was detected on-device.
        // update() MUST bail until construction completes.
        assertTrue(
            "ShadowReceiverPlaneNode.update() must early-return until construction finishes " +
                "(the base PlaneNode calls update() from its init before meshNode exists, #2620).",
            nodeSource.contains(Regex("""if\s*\(\s*!constructed\s*\)\s*return"""))
        )
        assertTrue(
            "The `constructed` guard must be flipped true at the end of the init block.",
            nodeSource.contains("constructed = true")
        )
    }

    @Test
    fun `node mirrors PlaneVisualizer's device-proven flat shadow-receiver recipe`() {
        // #2620 REGRESSION GUARD. PlaneVisualizer (shipped + device-QA'd for years) builds its
        // flat shadow receiver with castShadows(false) + receiveShadows(true) + culling(false) +
        // a non-degenerate boundingBox. The original ShadowReceiverPlaneNode had only the first
        // two; the missing culling(false) + a flat (zero-Y) geometry-derived AABB crashed the
        // FL2+ cascaded-shadow path on real devices at plane detection (the FL1 emulator never
        // hit it, so it shipped in 4.21.0). Keep all four levers in sync with PlaneVisualizer.
        assertTrue(
            "The shadow-catcher mesh must be built with culling(false) — mirroring PlaneVisualizer; " +
                "a flat quad's zero-thickness culling volume feeds the on-device shadow-focus math (#2620).",
            nodeSource.contains(Regex("""culling\(\s*false\s*\)"""))
        )
        assertTrue(
            "ShadowReceiverPlaneNode must override meshNode.axisAlignedBoundingBox (a flat plane's " +
                "auto AABB is degenerate and crashes Filament's shadow map on-device, #2620).",
            nodeSource.contains(Regex("""meshNode\.axisAlignedBoundingBox\s*="""))
        )
        assertTrue(
            "The overridden shadow-receiver AABB must use a NON-zero Y half-extent " +
                "(SHADOW_AABB_HALF_HEIGHT) — a zero Y extent is the degenerate box that crashes.",
            nodeSource.contains(Regex("""SHADOW_AABB_HALF_HEIGHT\s*=\s*(0\.\d*[1-9]|[1-9])"""))
        )
        assertTrue(
            "The valid AABB must be re-applied after updateGeometry() (which re-derives the flat " +
                "degenerate box), or the crash returns once ARCore refines the plane extents.",
            nodeSource.substringAfter("updateGeometry(").contains("applyShadowReceiverBounds()")
        )
    }

    @Test
    fun `node destroys its material after the renderable teardown`() {
        // Destroy order matters: destroying the Material (and thus its default instance) while
        // the mesh renderable is still registered is the SIGABRT class fixed in #851/#852.
        val destroyIdx = nodeSource.indexOf("override fun destroy()")
        assertTrue("ShadowReceiverPlaneNode must override destroy()", destroyIdx >= 0)
        val body = nodeSource.substring(destroyIdx, minOf(destroyIdx + 400, nodeSource.length))
        val superIdx = body.indexOf("super.destroy()")
        val materialIdx = body.indexOf("destroyMaterial(")
        assertTrue("destroy() must call super.destroy()", superIdx >= 0)
        assertTrue("destroy() must destroy the loaded material", materialIdx >= 0)
        assertTrue(
            "destroy() must tear down the renderable (super.destroy()) BEFORE destroying the material.",
            superIdx < materialIdx
        )
    }

    @Test
    fun `node keeps the Depth Lab default shadow intensity`() {
        assertTrue(
            "DEFAULT_SHADOW_INTENSITY must stay 0.6f (Depth Lab's _GlobalShadowIntensity " +
                "default, also plane_renderer_shadow.mat's baked alpha) — change deliberately, " +
                "with a device frame proving the new value.",
            nodeSource.contains(Regex("""DEFAULT_SHADOW_INTENSITY\s*=\s*0\.6f"""))
        )
    }

    @Test
    fun `node extends the AR PlaneNode pose follower`() {
        assertTrue(
            "ShadowReceiverPlaneNode must extend PlaneNode so it follows Plane.centerPose and " +
                "inherits visibleTrackingStates / subsumed-plane semantics.",
            nodeSource.contains(Regex("""ShadowReceiverPlaneNode[\s\S]{0,700}?\)\s*:\s*PlaneNode\("""))
        )
    }

    // ── 5. Composable surface ─────────────────────────────────────────────────────────────────

    @Test
    fun `ARSceneScope declares the ShadowReceiverPlane composable`() {
        assertTrue("Expected ${scopeFile.absolutePath}", scopeFile.exists())
        val scope = scopeFile.readText()
        assertTrue(
            "ARSceneScope must expose `@Composable fun ShadowReceiverPlane(...)` — the " +
                "declarative surface for the shadow catcher (#2241).",
            scope.contains(Regex("""fun\s+ShadowReceiverPlane\("""))
        )
        assertTrue(
            "The composable must keep `shadowIntensity` in sync on recomposition (SideEffect).",
            scope.contains(Regex("""node\.shadowIntensity\s*=\s*shadowIntensity"""))
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────

    /** Parses `filament = "1.<minor>.<patch>"` from gradle/libs.versions.toml → `<minor>`. */
    private fun filamentMinorVersion(): Int {
        assertTrue("Expected ${versionsToml.absolutePath}", versionsToml.exists())
        val version = versionsToml.readLines()
            .firstNotNullOfOrNull { Regex("""^filament\s*=\s*"([^"]+)"""").find(it)?.groupValues?.get(1) }
        assertNotNull("Could not parse `filament = \"X.Y.Z\"` from libs.versions.toml", version)
        return version!!.split(".")[1].toInt()
    }

    /**
     * Returns the payload of the first `.filamat` chunk with the given [tag], or null.
     *
     * The filamat container is a sequence of `[8-byte tag][uint32 LE size][payload]` chunks.
     * Tags are 8-char ASCII identifiers stored as little-endian uint64s, so they appear
     * byte-reversed on disk ("MAT_VERS" → "SREV_TAM").
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
}
