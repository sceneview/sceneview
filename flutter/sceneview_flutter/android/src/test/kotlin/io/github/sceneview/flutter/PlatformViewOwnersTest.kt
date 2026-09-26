package io.github.sceneview.flutter

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Pins the platform view's view-tree owners (#3928).
 *
 * A plain `FlutterActivity` is an `android.app.Activity`: it installs no
 * `ViewTreeLifecycleOwner`, so the plugin's `ComposeView` threw
 * `ViewTreeLifecycleOwner not found from FlutterView` and the host app crashed at
 * launch. The `FrameLayout` content view below stands in for the `FlutterView`.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [34])
class PlatformViewOwnersTest {

    private class Probe {
        var lifecycleOwner: LifecycleOwner? = null
        var composed = false
        var disposed = false
    }

    private fun probeView(activity: Activity, probe: Probe) = ComposeView(activity).apply {
        setContent {
            probe.lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(Unit) {
                probe.composed = true
                onDispose { probe.disposed = true }
            }
        }
    }

    /** Adds [view] under a stand-in `FlutterView`, like Flutter's platform-view wrapper. */
    private fun attach(activity: Activity, view: ComposeView) {
        val flutterView = FrameLayout(activity)
        activity.setContentView(flutterView)
        flutterView.addView(view)
        flutterView.measure(0, 0)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `composes in a plain Activity and tears down on destroy`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val probe = Probe()
        val view = probeView(activity, probe)

        val owners = PlatformViewOwners.installIfHostHasNone(view, activity)
        assertNotNull("a plain Activity has no owners: the platform view must bring its own", owners)
        assertEquals(Lifecycle.State.RESUMED, owners!!.lifecycle.currentState)

        attach(activity, view) // threw IllegalStateException before #3928

        assertTrue("content never composed", probe.composed)
        assertSame(owners, probe.lifecycleOwner)

        // PlatformView.dispose(): composition first (Filament teardown), then the owners.
        view.disposeComposition()
        owners.destroy()
        assertTrue("composition was not disposed", probe.disposed)
        assertEquals(Lifecycle.State.DESTROYED, owners.lifecycle.currentState)
        owners.destroy() // idempotent
    }

    @Test
    fun `uses the host owners when the host provides them`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // ComponentActivity installs its owners on the decor view in setContentView,
        // as FlutterFragmentActivity does before any platform view is created.
        activity.setContentView(FrameLayout(activity))
        val probe = Probe()
        val view = probeView(activity, probe)

        assertNull(PlatformViewOwners.installIfHostHasNone(view, activity))

        attach(activity, view)

        assertTrue("content never composed", probe.composed)
        assertSame(activity, probe.lifecycleOwner)
    }
}
