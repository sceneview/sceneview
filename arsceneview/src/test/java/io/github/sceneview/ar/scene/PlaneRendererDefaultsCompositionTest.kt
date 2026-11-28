package io.github.sceneview.ar.scene

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import io.github.sceneview.ar.LocalPlaneShadowReceiver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** ARSceneView's SideEffect must not turn the default shadow polygon back on. */
@RunWith(RobolectricTestRunner::class)
class PlaneRendererDefaultsCompositionTest {
    @Test fun `plain scene composition disables the plane shadow mesh`() = runTest {
        val recomposer = Recomposer(backgroundScope.coroutineContext + BroadcastFrameClock())
        val composition = Composition(NoNodes(), recomposer)
        var receivesShadows: Boolean? = null
        try {
            composition.setContent {
                receivesShadows = LocalPlaneShadowReceiver.current
            }
            assertEquals(false, receivesShadows)
        } finally {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoNodes : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
