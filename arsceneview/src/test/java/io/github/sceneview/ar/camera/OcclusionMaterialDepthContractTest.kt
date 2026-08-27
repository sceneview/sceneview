package io.github.sceneview.ar.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract tests for the two occlusion materials' `gl_FragDepth` path (#3340).
 *
 * A GLSL fragment shader cannot run in a pure-JVM test — there is no Filament engine and no
 * GPU. What *can* be pinned is the shape of the depth math, and both bugs this fixes were
 * visible in the source alone:
 *
 *  1. **Invalid depth was fed straight into the projection.** ARCore reports `depth_mm == 0`
 *     for every texel it could not solve — and for every texel of the 1×1 placeholder
 *     texture bound until the first depth image lands. Projecting a point at the camera
 *     origin gives `view.w == 0`, so `view.z / view.w` is `+inf`, which the rasterizer
 *     clamps to `1.0` on write. Filament is reverse-Z, so `1.0` is the NEAR plane: the
 *     camera quad occluded **every** virtual fragment precisely where the depth image had
 *     no data. The model vanished — the reported symptom.
 *
 *  2. **The people-occlusion mask wrote the wrong plane.** `mix(ndc_depth, 0.0, is_person)`
 *     pushes person pixels to the *far* plane under reverse-Z, so virtual objects drew over
 *     people: the inverse of people occlusion. The comment above it even claimed 0.0 was
 *     "the near clip plane", which is where the sign was lost.
 *
 * The end-to-end behaviour needs a device — see the PR's needs-device section and
 * `ARDepthOcclusionDemo` / `ARPeopleOcclusionDemo`.
 */
class OcclusionMaterialDepthContractTest {

    private fun mat(name: String): String {
        val file = File("src/main/materials/$name.mat")
        assertTrue(
            "Expected ${file.absolutePath} — JVM test must run from the arsceneview root.",
            file.exists(),
        )
        return file.readText()
    }

    private val depthMat: String by lazy { mat("camera_stream_depth") }
    private val personMat: String by lazy { mat("camera_stream_person_occlusion") }

    @Test
    fun `depth material never projects a raw depth of zero`() {
        // The exact expression that caused #3340. If it comes back, so does the bug.
        assertFalse(
            "camera_stream_depth.mat must not feed `-depth_mm / 1000` straight into the " +
                "projection: a 0 mm texel divides by `view.w == 0` and clamps to the near " +
                "plane, occluding everything (#3340).",
            depthMat.contains("vec3(0.f, 0.f, (-depth_mm / 1000.f))"),
        )
    }

    @Test
    fun `person material never projects a raw depth of zero either`() {
        assertFalse(
            "camera_stream_person_occlusion.mat carries the same depth path and needs the " +
                "same guard (#3340).",
            personMat.contains("vec3(0.f, 0.f, (-depth_mm / 1000.f))"),
        )
    }

    @Test
    fun `both materials substitute a finite far distance for invalid texels`() {
        listOf("camera_stream_depth" to depthMat, "camera_stream_person_occlusion" to personMat)
            .forEach { (name, source) ->
                assertTrue(
                    "$name.mat must declare kNoDepthDistanceMeters — the finite stand-in " +
                        "distance for texels with no ARCore depth.",
                    source.contains("kNoDepthDistanceMeters"),
                )
                assertTrue(
                    "$name.mat must select it with the existing `depth_valid` gate, so " +
                        "\"no depth data\" degrades to \"occludes nothing\".",
                    source.contains(
                        "mix(kNoDepthDistanceMeters, depth_m, depth_valid)"
                    ),
                )
                assertTrue(
                    "$name.mat must project the guarded distance, not the raw one.",
                    source.contains("vec3(0.f, 0.f, -occlusion_depth_m)"),
                )
            }
    }

    @Test
    fun `the person mask is forced to the reverse-Z near plane, not to zero`() {
        assertFalse(
            "camera_stream_person_occlusion.mat must NOT write 0.0 for person pixels: under " +
                "Filament's reverse-Z that is the FAR plane, so virtual objects draw over " +
                "people — the inverse of people occlusion (#3340).",
            personMat.contains("mix(ndc_depth, 0.f, is_person)"),
        )
        assertTrue(
            "Person pixels must be forced to the near plane (1.0 under reverse-Z).",
            personMat.contains("mix(ndc_depth, kNdcNearPlane, is_person)"),
        )
        assertTrue(
            "kNdcNearPlane must be 1.0 — Filament maps 1.0 to NEAR and 0.0 to far.",
            Regex("""const\s+float\s+kNdcNearPlane\s*=\s*1\.0""").containsMatchIn(personMat),
        )
    }

    @Test
    fun `both materials still write gl_FragDepth`() {
        // The whole occlusion mechanism. A refactor that drops it silently disables
        // occlusion without failing anything else.
        assertTrue(depthMat.contains("gl_FragDepth ="))
        assertTrue(personMat.contains("gl_FragDepth ="))
    }
}
