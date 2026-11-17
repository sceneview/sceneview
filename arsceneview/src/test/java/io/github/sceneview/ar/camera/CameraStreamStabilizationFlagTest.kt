package io.github.sceneview.ar.camera

import com.google.android.filament.MaterialInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The EIS material flag is resolved at bind time and never read from the bound material per
 * frame (#5091).
 *
 * On a Pixel 4a with Filament 1.77.3, `update()` called
 * `materialInstance.material.hasParameter(...)` every frame. `materialInstance` is read back
 * from the renderable, and Filament 1.77 builds that wrapper without its `Material`: the call threw
 * a NullPointerException on every frame, so the camera quad was never revealed and every AR demo
 * showed "Camera couldn't start". The JVM tests had no Filament to see it.
 */
class CameraStreamStabilizationFlagTest {

    private class FakeInstance(val name: String, val declaresFlag: Boolean)

    private val writes = mutableListOf<Pair<String, Boolean>>()
    private val flag = CameraStreamStabilizationFlag<FakeInstance>(
        accepts = { it.declaresFlag },
        write = { instance, enabled -> writes += instance.name to enabled },
    )

    @Test
    fun `a wrapper read back from a renderable has no material and is not a flag target`() {
        // What `RenderableManager.getMaterialInstanceAt` returns since Filament 1.77: no natives
        // are needed to build it, and its `material` is null.
        val readBack = MaterialInstance.wrap(42L)
        assertNull(readBack.material)
        val threw = runCatching { readBack.material.hasParameter(kImageStabilizationParameter) }
            .exceptionOrNull()
        assertTrue("the pre-#5091 per-frame call throws here", threw is NullPointerException)

        assertFalse(acceptsImageStabilizationFlag(readBack))
    }

    @Test
    fun `binding a material that declares the flag writes the current value once`() {
        flag.enabled = true
        assertEquals(emptyList<Pair<String, Boolean>>(), writes)

        flag.bind(FakeInstance("depth", declaresFlag = true))
        assertEquals(listOf("depth" to true), writes)
    }

    @Test
    fun `an unchanged value writes nothing, however many frames pass`() {
        flag.bind(FakeInstance("flat", declaresFlag = true))
        writes.clear()

        repeat(300) { flag.enabled = false }
        assertEquals(emptyList<Pair<String, Boolean>>(), writes)

        flag.enabled = true
        repeat(300) { flag.enabled = true }
        assertEquals(listOf("flat" to true), writes)
    }

    @Test
    fun `a legacy material without the flag is never written`() {
        flag.bind(FakeInstance("legacy", declaresFlag = false))
        flag.enabled = true
        flag.enabled = false
        assertEquals(emptyList<Pair<String, Boolean>>(), writes)
    }

    @Test
    fun `switching material carries the current value to the new one`() {
        flag.bind(FakeInstance("flat", declaresFlag = true))
        flag.enabled = true
        flag.bind(FakeInstance("legacy", declaresFlag = false))
        flag.bind(FakeInstance("person", declaresFlag = true))
        assertEquals(listOf("flat" to false, "flat" to true, "person" to true), writes)
    }

    @Test
    fun `the per-frame update never reads the bound material`() {
        val source = File("src/main/java/io/github/sceneview/ar/camera/ARCameraStream.kt").readText()
        val update = source.substringAfter("fun update(session: Session, frame: Frame) {")
            .substringBefore("\n    fun ")
            .substringBefore("\n    private fun ")
        assertTrue("update() body not found", update.contains("coordinateUploads.acquire()"))
        for (forbidden in listOf(".material.", ".material)", "materialOrNull", "hasParameter")) {
            assertFalse("update() must not call `$forbidden` per frame (#5091)", update.contains(forbidden))
        }
    }
}
