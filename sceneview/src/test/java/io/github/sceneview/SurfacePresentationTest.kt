package io.github.sceneview

import android.app.Application
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** JVM regression coverage for the platform-view half of SceneView's lifecycle contract. */
@RunWith(RobolectricTestRunner::class)
class SurfacePresentationTest {

    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    /** Stands in for the host activity's own lifecycle. */
    private var hostResumed = true

    private fun presentation() = SurfacePresentation(isHostResumed = { hostResumed })

    @Test
    fun `navigation pause hides the surface and resume reveals it after a frame`() {
        val presentation = presentation()
        val surfaceView = SurfaceView(context)

        presentation.attach(surfaceView)
        assertHidden(surfaceView)

        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        assertAwaitingFrame(surfaceView)

        presentation.onFramePresented()
        assertEquals(1f, surfaceView.alpha, 0f)

        // LifecycleRegistry can synchronise an observer with an already-resumed owner; a repeated
        // resume must not hide a surface whose fresh frame is already on screen.
        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        assertEquals(1f, surfaceView.alpha, 0f)

        // The screen is paused while its activity stays resumed: a navigation exit.
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        assertHidden(surfaceView)

        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        assertAwaitingFrame(surfaceView)

        presentation.onFramePresented()
        assertEquals(1f, surfaceView.alpha, 0f)
    }

    @Test
    fun `activity pause keeps the surface and resume does not blink it`() {
        val presentation = presentation()
        val surfaceView = SurfaceView(context)
        presentation.attach(surfaceView)
        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        presentation.onFramePresented()

        // A permission prompt or a share sheet: the activity is paused with its window visible.
        hostResumed = false
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        assertEquals(View.VISIBLE, surfaceView.visibility)
        assertEquals(1f, surfaceView.alpha, 0f)

        hostResumed = true
        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        assertEquals(View.VISIBLE, surfaceView.visibility)
        assertEquals(1f, surfaceView.alpha, 0f)
    }

    @Test
    fun `activity stop hides the surface until the next resumed frame`() {
        val presentation = presentation()
        val surfaceView = SurfaceView(context)
        presentation.attach(surfaceView)
        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        presentation.onFramePresented()

        hostResumed = false
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        presentation.onLifecycleState(Lifecycle.State.CREATED)
        assertHidden(surfaceView)

        // Coming back: started is not enough, the surface waits for RESUMED and a real frame.
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        assertHidden(surfaceView)

        hostResumed = true
        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        assertAwaitingFrame(surfaceView)

        presentation.onFramePresented()
        assertEquals(1f, surfaceView.alpha, 0f)
    }

    @Test
    fun `a screen entering below resumed stays hidden`() {
        val presentation = presentation()
        val surfaceView = SurfaceView(context)
        presentation.attach(surfaceView)

        // Observer replay during an enter transition: CREATED then STARTED, activity resumed.
        presentation.onLifecycleState(Lifecycle.State.CREATED)
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        assertHidden(surfaceView)

        // Same replay while the activity itself is paused: nothing to keep, still hidden.
        hostResumed = false
        presentation.onLifecycleState(Lifecycle.State.STARTED)
        assertHidden(surfaceView)
    }

    @Test
    fun `surface attached while paused never exposes its retained buffer`() {
        val presentation = presentation()
        val oldSurface = SurfaceView(context)
        val replacement = TextureView(context)

        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        presentation.attach(oldSurface)
        assertAwaitingFrame(oldSurface)

        presentation.onFramePresented()
        assertEquals(1f, oldSurface.alpha, 0f)

        presentation.onLifecycleState(Lifecycle.State.STARTED)
        presentation.attach(replacement)

        assertHidden(oldSurface)
        assertHidden(replacement)
    }

    @Test
    fun `dispose hides both surface types and resets resumed state`() {
        val presentation = presentation()
        val textureView = TextureView(context)

        presentation.onLifecycleState(Lifecycle.State.RESUMED)
        presentation.attach(textureView)
        presentation.onFramePresented()
        assertEquals(1f, textureView.alpha, 0f)

        presentation.detach()
        assertHidden(textureView)

        val laterSurface = SurfaceView(context)
        presentation.attach(laterSurface)
        assertHidden(laterSurface)
    }

    private fun assertHidden(view: View) {
        assertEquals(View.INVISIBLE, view.visibility)
        assertEquals(0f, view.alpha, 0f)
    }

    private fun assertAwaitingFrame(view: View) {
        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(0f, view.alpha, 0f)
    }
}
