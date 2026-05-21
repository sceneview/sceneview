package io.github.sceneview.ar.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract tests for the people-occlusion path added to [ARCameraStream] (#1761).
 *
 * The Filament-bound runtime behaviour (material swap, semantic-image upload, depth write)
 * cannot run in a pure-JVM test — the native engine is not loaded. We instead pin the
 * source-level contract so the most likely regressions are caught:
 *
 *  - the public [ARCameraStream.isPersonOcclusionEnabled] toggle being removed or renamed,
 *  - the people-occlusion material no longer being selected ahead of the depth material,
 *  - the semantic-image acquire losing its `image.close()` lifecycle call,
 *  - people occlusion silently dropping the depth-texture upload (its material still needs
 *    depth so static geometry keeps occluding).
 *
 * The behavioural contract is exercised on-device through `ARPeopleOcclusionDemo`.
 */
class ARCameraStreamPersonOcclusionContractTest {

    private val streamFile =
        File("src/main/java/io/github/sceneview/ar/camera/ARCameraStream.kt")

    private val source: String by lazy {
        assertTrue(
            "Expected ${streamFile.absolutePath} — JVM test must run from arsceneview root.",
            streamFile.exists()
        )
        streamFile.readText()
    }

    @Test
    fun `ARCameraStream exposes the isPersonOcclusionEnabled toggle`() {
        assertTrue(
            "ARCameraStream must declare `var isPersonOcclusionEnabled` — the public " +
                "people-occlusion toggle (#1761).",
            Regex("""var\s+isPersonOcclusionEnabled""").containsMatchIn(source)
        )
    }

    @Test
    fun `ARCameraStream takes a person-occlusion material file in its constructor`() {
        assertTrue(
            "ARCameraStream's constructor must accept `personOcclusionMaterialFile` defaulting " +
                "to the camera_stream_person_occlusion.filamat asset.",
            source.contains("personOcclusionMaterialFile") &&
                source.contains("camera_stream_person_occlusion.filamat")
        )
    }

    @Test
    fun `people occlusion is selected ahead of depth occlusion`() {
        // The people-occlusion material subsumes the depth path, so when both flags are on
        // it must win. Pin the `when` branch order in `applyOcclusionMaterial`.
        val applyIdx = source.indexOf("private fun applyOcclusionMaterial()")
        assertTrue("applyOcclusionMaterial() must exist", applyIdx >= 0)
        val body = source.substring(applyIdx, minOf(applyIdx + 600, source.length))
        val personIdx = body.indexOf("isPersonOcclusionEnabled ->")
        val depthIdx = body.indexOf("isDepthOcclusionEnabled ->")
        assertTrue("both occlusion branches must be present", personIdx >= 0 && depthIdx >= 0)
        assertTrue(
            "isPersonOcclusionEnabled must be checked before isDepthOcclusionEnabled so " +
                "people occlusion wins when both are enabled",
            personIdx < depthIdx
        )
    }

    @Test
    fun `people occlusion still uploads the depth texture`() {
        // The person-occlusion material keeps the depth-occlusion path so static real-world
        // geometry still occludes. The depth-acquire block must therefore also run when
        // person occlusion is enabled.
        assertTrue(
            "the depth-image block must be gated on `isDepthOcclusionEnabled || " +
                "isPersonOcclusionEnabled` so person occlusion keeps depth occlusion alive.",
            Regex("""if\s*\(\s*isDepthOcclusionEnabled\s*\|\|\s*isPersonOcclusionEnabled\s*\)""")
                .containsMatchIn(source)
        )
    }

    @Test
    fun `the semantic image is closed after the mask is built`() {
        // The ARCore semantic-image pool is only 2-3 slots deep — failing to close leaks a
        // native handle and the next acquire throws ResourceExhausted.
        val updateIdx = source.indexOf("private fun updatePersonMask(")
        assertTrue("updatePersonMask() must exist", updateIdx >= 0)
        val body = source.substring(updateIdx, minOf(updateIdx + 1600, source.length))
        assertTrue(
            "updatePersonMask must call image.close() (in a finally block) so the shallow " +
                "ARCore semantic-image pool is not exhausted.",
            body.contains("image.close()")
        )
        assertTrue(
            "image.close() must run in a finally block so it fires even if PersonMask.build " +
                "throws.",
            body.contains("finally")
        )
    }
}
