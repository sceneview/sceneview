package io.github.sceneview.ar.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The perspective divide must happen after interpolation, in every camera material (#2728). */
class CameraStreamStabilizationContractTest {
    @Test
    fun `camera materials sample homogeneous EIS coordinates in the fragment shader`() {
        for (name in listOf("flat", "depth", "person_occlusion")) {
            val source = File("src/main/materials/camera_stream_$name.mat").readText()
            val fragment = source.substringAfter("fragment {").substringBefore("vertex {")
            assertTrue(
                "$name must divide interpolated camera coordinates by their homogeneous Z",
                fragment.contains("variable_cameraUv.xy / variable_cameraUv.z"),
            )
            assertTrue(
                "$name must use the compensated coordinates to sample the camera",
                fragment.contains("texture(materialParams_cameraTexture, cameraUv)"),
            )
            assertTrue(
                "$name must preserve main's UV0 sampling while EIS is OFF",
                fragment.contains("materialParams.imageStabilizationEnabled\n") &&
                    fragment.contains("? variable_cameraUv.xy / variable_cameraUv.z : getUV0()"),
            )
            assertTrue("$name must receive all three camera coordinates", source.contains("getCustom0()"))
        }
    }

    @Test
    fun `depth fog and person masks use the same coordinates as the camera`() {
        for (name in listOf("depth", "person_occlusion")) {
            val source = File("src/main/materials/camera_stream_$name.mat").readText()
            val fragment = source.substringAfter("fragment {").substringBefore("vertex {")
            assertTrue(fragment.contains("texture(materialParams_depthTexture, cameraUv)"))
            assertTrue(fragment.contains("ivec2(cameraUv * vec2(textureSize(materialParams_depthTexture, 0)))"))
            assertTrue(fragment.contains("occlusionDepthMeters(cameraUv)"))
            // UV0 may appear only in the OFF fallback, never at a mask lookup.
            assertTrue(fragment.split("getUV0()").size == 2)
            if (name == "person_occlusion") {
                assertTrue(fragment.contains("texture(materialParams_personTexture, cameraUv)"))
            }
        }
    }
}
