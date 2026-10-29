package io.github.sceneview.demo.demos

import io.github.sceneview.demo.demos.internal.ArDebugTrace
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.ReplayLens
import io.github.sceneview.demo.demos.internal.ReplayManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cap on a surfel's size on screen the dense layer's material is given
 * ([RerunReplayMedia.denseMaxSide]): a tenth of the view's height while a recording is drawn over
 * the camera, the replay's everywhere else — a saved scan, and a recording's save flight, which
 * leaves the phone's eye and keeps half a metre from the walls, where a capped coarse surfel would
 * leave gaps. The material itself is Filament's, built only on a device: this pins the value it
 * is handed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DenseMaxSideTest {

    private fun scan() = RerunReplayMedia(
        trace = ArDebugTrace(),
        manifest = ReplayManifest(
            ReplayLens.Default, frameRate = 1f, frameCount = 0, floorY = null, textures = emptyList(),
        ),
        planeBitmaps = emptyMap(),
        thumbnails = emptyMap(),
        archive = ByteArray(0),
    )

    @Test
    fun `a saved scan draws its surfels under the replay's cap`() {
        assertEquals(DenseSurfels.MAX_SCREEN_SIDE, scan().denseMaxSide)
    }

    @Test
    fun `a recording over the camera draws its surfels under the live cap`() {
        assertEquals(DenseSurfels.LIVE_MAX_SCREEN_SIDE, scan().withLiveDense { null }.denseMaxSide)
    }

    @Test
    fun `a recording's save flight draws its surfels under the replay's cap`() {
        var saving = false
        val recording = scan().withLiveDense(overCamera = { !saving }) { null }
        assertEquals(DenseSurfels.LIVE_MAX_SCREEN_SIDE, recording.denseMaxSide)
        saving = true
        assertEquals("a wall's coarse surfels near the flight", DenseSurfels.MAX_SCREEN_SIDE, recording.denseMaxSide)
    }
}
