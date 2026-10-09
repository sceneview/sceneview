package io.github.sceneview.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlRenderer.isLegacySwiftShader] must name one implementation and nothing next to it: the
 * strings below are what each stack reports, and every emulator-based test of this SDK runs
 * on one of the "false" ones (#4411).
 */
class GlRendererTest {

    @Test
    fun `the guest SwiftShader GLES library of Android 11 and older is legacy SwiftShader`() {
        // external/swiftshader/src/OpenGL/libGLESv2/libGLESv2.cpp, glGetString.
        assertTrue(
            GlRenderer(
                vendor = "Google Inc.",
                renderer = "Google SwiftShader",
                version = "OpenGL ES 3.0 SwiftShader 4.1.0.7",
            ).isLegacySwiftShader,
        )
    }

    @Test
    fun `either string alone is enough, whatever the case or the padding`() {
        assertTrue(GlRenderer("", " google swiftshader ", "OpenGL ES 3.0").isLegacySwiftShader)
        assertTrue(
            GlRenderer("Acme", "Acme Virtual GPU", "OpenGL ES 3.0 SwiftShader 4.0.0.1").isLegacySwiftShader,
        )
        assertTrue(GlRenderer("", "", "OpenGL ES 2.0 SwiftShader 3.3.0.2").isLegacySwiftShader)
    }

    @Test
    fun `the emulator translating to a host SwiftShader is not`() {
        // `-gpu swiftshader_indirect`: the CI golden leg and the device-QA emulator.
        assertFalse(
            GlRenderer(
                vendor = "Google (Google Inc.)",
                renderer = "Android Emulator OpenGL ES Translator (Google SwiftShader)",
                version = "OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)",
            ).isLegacySwiftShader,
        )
        assertFalse(
            GlRenderer(
                vendor = "Google (Google Inc. (Google))",
                renderer = "Android Emulator OpenGL ES Translator (ANGLE (Google, Vulkan 1.3.0 " +
                    "(SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver-5.0.0))",
                version = "OpenGL ES 3.1 (OpenGL ES 3.1 ANGLE git hash: 1234abcd)",
            ).isLegacySwiftShader,
        )
    }

    @Test
    fun `ANGLE over SwiftShader Vulkan is not`() {
        assertFalse(
            GlRenderer(
                vendor = "Google Inc. (Google)",
                renderer = "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (LLVM 10.0.0) " +
                    "(0x0000C0DE)), SwiftShader driver-5.0.0)",
                version = "OpenGL ES 3.1 ANGLE git hash: 1234abcd",
            ).isLegacySwiftShader,
        )
    }

    @Test
    fun `hardware GPUs are not`() {
        assertFalse(GlRenderer("Qualcomm", "Adreno (TM) 740", "OpenGL ES 3.2 V@0676.42").isLegacySwiftShader)
        assertFalse(GlRenderer("ARM", "Mali-G78", "OpenGL ES 3.2 v1.r44p0-01eac0").isLegacySwiftShader)
        assertFalse(
            GlRenderer("Imagination Technologies", "PowerVR Rogue GE8320", "OpenGL ES 3.2 build 1.11@5425693")
                .isLegacySwiftShader,
        )
        assertFalse(GlRenderer("", "", "").isLegacySwiftShader)
    }
}
