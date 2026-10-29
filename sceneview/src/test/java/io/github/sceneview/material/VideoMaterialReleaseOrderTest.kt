package io.github.sceneview.material

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The teardown order of [VideoMaterial.destroy], run against [releaseVideoMaterial] — the one
 * sequence `destroy()` goes through, with the Filament calls replaced by recorders.
 *
 * Filament's `FEngine::prepare` commits every surface material instance of the engine on every
 * frame, whether or not a renderable uses it. An instance left alive once its external texture is
 * destroyed aborts the process in `FMaterialInstance::commit` ("Invalid texture still bound to
 * MaterialInstance"). The real abort needs an engine and a GPU; this pins what is released and in
 * which order.
 */
class VideoMaterialReleaseOrderTest {

    private val released = mutableListOf<String>()
    private var pendingRelease: (() -> Unit)? = null

    private fun destroy() = releaseVideoMaterial(
        destroyInstance = { released += "instance" },
        whenInstanceReclaimed = { pendingRelease = it },
        releaseVideo = {
            released += "texture"
            released += "stream"
        },
    )

    @Test
    fun `the material instance is destroyed with the material`() {
        destroy()
        pendingRelease?.invoke()

        assertEquals(listOf("instance", "texture", "stream"), released)
    }

    @Test
    fun `the texture outlives the instance until the backend has reclaimed it`() {
        destroy()

        assertEquals(listOf("instance"), released)
        assertNotNull("the texture and the stream are never released", pendingRelease)
        pendingRelease!!.invoke()
        assertEquals(listOf("instance", "texture", "stream"), released)
    }
}
