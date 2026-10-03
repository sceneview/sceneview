package io.github.sceneview.ar

import android.content.Context
import android.content.ContextWrapper
import android.view.ContextThemeWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * `ARSceneView` binds its session to the host activity by default, not to
 * `LocalLifecycleOwner`: a navigation destination leaves `RESUMED` when its exit transition
 * starts, which stopped the camera while the screen was still on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class HostLifecycleTest {

    private val application: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `a lifecycle-owning context resolves to its own lifecycle`() {
        val activity = HostContext(application)

        assertSame(activity.lifecycle, activity.findHostLifecycle())
    }

    @Test
    fun `a wrapped host context resolves to the host lifecycle`() {
        // What LocalContext is inside a Dialog, a themed subtree or a ViewNode window.
        val activity = HostContext(application)
        val wrapped = ContextThemeWrapper(ContextThemeWrapper(activity, 0), 0)

        assertSame(activity.lifecycle, wrapped.findHostLifecycle())
    }

    @Test
    fun `the nearest lifecycle owner wins`() {
        val activity = HostContext(application)
        val nested = HostContext(ContextThemeWrapper(activity, 0))

        assertSame(nested.lifecycle, ContextThemeWrapper(nested, 0).findHostLifecycle())
    }

    @Test
    fun `a context without a lifecycle owner resolves to nothing`() {
        // The caller then falls back to LocalLifecycleOwner.
        assertNull(application.findHostLifecycle())
        assertNull(ContextThemeWrapper(application, 0).findHostLifecycle())
    }

    /** Stands in for a `ComponentActivity`: a context that owns a lifecycle. */
    private class HostContext(base: Context) : ContextWrapper(base), LifecycleOwner {
        override val lifecycle: Lifecycle = LifecycleRegistry.createUnsafe(this)
    }
}
