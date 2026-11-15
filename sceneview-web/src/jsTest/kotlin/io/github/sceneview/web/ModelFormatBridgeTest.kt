package io.github.sceneview.web

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class ModelFormatBridgeTest {
    @Test fun convertsAnInMemoryStlWithAnExplicitUnit() {
        val bytes = ("solid mesh\nfacet normal 0 0 1\nouter loop\n" +
            "vertex 0 0 0\nvertex 10 0 0\nvertex 0 10 0\nendloop\nendfacet\nendsolid mesh\n")
            .encodeToByteArray().toArrayBuffer()
        assertEquals("STL", jsModelFormat(bytes))
        val glb = jsModelToGlb(bytes, "part.stl", "meter")
        assertEquals("glTF", (0..3).map { glb[it].toInt().toChar() }.joinToString(""))
    }

    @Test fun unsupportedInputHasAStableJavascriptErrorType() {
        val error = assertFails { jsModelFormat(Uint8Array(4).buffer, "part.fbx") }
        assertEquals("UnsupportedModelFormatError", error.asDynamic().name)
        assertEquals("unsupported-format", error.asDynamic().code)
    }

    @Test fun rejectsMisspelledUnitsInsteadOfSilentlyUsingMillimeters() {
        assertFailsWith<IllegalArgumentException> { modelUnit("metre") }
        assertEquals("millimeter", modelUnit(null).id)
    }
}
