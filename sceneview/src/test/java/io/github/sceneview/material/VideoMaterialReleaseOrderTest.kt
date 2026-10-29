package io.github.sceneview.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The teardown order of [VideoMaterial.destroy], run against [VideoMaterialTeardown] — the class
 * `destroy()` delegates to, given one recorder per Filament or Android release call.
 *
 * Filament's `FEngine::prepare` commits every surface material instance of the engine on every
 * frame, whether or not a renderable uses it. An instance left alive once its external texture is
 * destroyed aborts the process in `FMaterialInstance::commit` ("Invalid texture still bound to
 * MaterialInstance"). The real abort needs an engine and a GPU; this pins what is released, how
 * many times and in which order.
 */
class VideoMaterialReleaseOrderTest {

    private val released = mutableListOf<String>()
    private var onReclaimed: (() -> Unit)? = null
    private var onEngineGone: (() -> Unit)? = null

    private val teardown = VideoMaterialTeardown(
        releaseSurface = { released += "surface" },
        destroyInstance = { released += "instance" },
        whenInstanceReclaimed = { reclaimed, engineGone ->
            released += "wait"
            onReclaimed = reclaimed
            onEngineGone = engineGone
        },
        destroyTexture = { released += "texture" },
        destroyStream = { released += "stream" },
        releaseSurfaceTexture = { released += "surfaceTexture" },
    )

    @Test
    fun `the instance is destroyed, then the video once the backend has reclaimed it`() {
        teardown.destroy()

        assertEquals(listOf("surface", "instance", "wait"), released)
        assertNotNull("the texture and the stream are never released", onReclaimed)
        onReclaimed!!.invoke()
        assertEquals(
            listOf("surface", "instance", "wait", "texture", "stream", "surfaceTexture"),
            released,
        )
    }

    @Test
    fun `with the engine already gone only the SurfaceTexture is left to release`() {
        teardown.destroy()

        assertNotNull("the engine-gone path is never handed over", onEngineGone)
        onEngineGone!!.invoke()
        assertEquals(listOf("surface", "instance", "wait", "surfaceTexture"), released)
    }

    @Test
    fun `a second destroy releases nothing`() {
        teardown.destroy()
        onReclaimed?.invoke()
        val first = released.toList()

        teardown.destroy()

        assertEquals(first, released)
    }
}
