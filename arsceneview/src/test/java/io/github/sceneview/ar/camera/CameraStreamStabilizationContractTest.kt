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
            assertTrue("$name must receive all three camera coordinates", source.contains("getCustom0()"))
        }
    }
}
