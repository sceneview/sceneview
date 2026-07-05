package io.github.sceneview.demo.ar

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sceneview.demo.ALL_DEMOS
import io.github.sceneview.demo.DemoHostActivity
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stress test for the depth-mode toggle in `ARDepthOcclusionDemo` ([#1777]).
 *
 * Flipping the "Depth occlusion" switch re-keys the whole `ARSceneView` (`key(depthOn)`),
 * which tears down and rebuilds the Filament engine + ARCore camera stream. Doing this
 * rapidly is the worst case for the depth ↔ flat material swap: a queued depth frame
 * arriving mid-rebuild, or an engine that hasn't finished tearing down, can crash the
 * process or strand the renderer in an inconsistent state.
 *
 * This test launches the demo via [DemoHostActivity], opens the settings sheet, then
 * toggles the depth switch **10 times in quick succession** and asserts:
 * 1. The process never dies — the demo title stays on screen for the whole run.
 * 2. The transition spinner (test-tag `depth-transition-spinner`, added in #1777) is
 *    wired — it appears during at least one swap, confirming the user gets feedback.
 *
 * **Environment note:** real depth occlusion needs ARCore Depth-API hardware, which the
 * software-GPU CI emulator does not provide. The toggle, the `key()` remount and the
 * spinner are all independent of depth-support, so this test exercises the *rebuild
 * stability* contract regardless of whether the device actually supports depth — which
 * is exactly the regression class #1777 cares about. Related: pre-existing #1617.
 */
@RunWith(AndroidJUnit4::class)
class ARDepthOcclusionToggleTest {

    private lateinit var context: Context
    private lateinit var device: UiDevice

    private val pkg = "io.github.sceneview.demo"
    private val demoId = "ar-depth-occlusion"
    private val timeout = 15_000L

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // AGP reinstalls the demo APK before each test class, so pre-grant the AR
        // permissions — otherwise the demo blocks on the system camera prompt.
        device.executeShellCommand("pm grant $pkg android.permission.CAMERA")
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    @Test
    fun depthToggle_rapidly_flipped_ten_times_stays_stable() {
        val titleRes = ALL_DEMOS.first { it.id == demoId }.titleRes
        val expectedTitle = context.getString(titleRes)

        context.startActivity(
            Intent().apply {
                setClassName(pkg, "$pkg.DemoHostActivity")
                putExtra(DemoHostActivity.EXTRA_DEMO_ID, demoId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        // Wait for the demo scaffold to render — confirms Compose + Filament are wired.
        assertTrue(
            "Demo '$demoId' never rendered its title bar",
            device.wait(Until.hasObject(By.text(expectedTitle)), timeout)
        )
        // Let the first AR session + camera stream settle before stressing the toggle.
        Thread.sleep(6_000)

        // Open the per-demo settings sheet so the depth Switch is in the view tree.
        val fab = device.findObject(By.res("demo-settings-fab"))
            ?: device.findObject(By.desc("Demo settings"))
        assertTrue("Demo settings FAB not found", fab != null)
        fab!!.click()
        device.waitForIdle()
        Thread.sleep(500)

        var spinnerObserved = false
        repeat(TOGGLE_COUNT) {
            // The depth Switch carries the "Depth occlusion" label in the sheet.
            val toggle = device.findObject(By.text("Depth occlusion"))
                ?: device.findObject(By.clazz("android.widget.Switch"))
            if (toggle != null) {
                toggle.click()
            }
            // A short window so the `key(depthOn)` remount actually starts before the
            // next flip — this is the "rapid" stress, not an instant double-tap.
            if (device.wait(Until.hasObject(By.res("depth-transition-spinner")), 1_200)) {
                spinnerObserved = true
            }
            device.waitForIdle()
        }

        // Process survived the 10-flip stress — the title bar is still on screen.
        assertTrue(
            "Demo crashed or was dismissed during rapid depth toggling",
            device.hasObject(By.text(expectedTitle))
        )
        // The transition spinner fired at least once — the user got feedback.
        assertTrue(
            "Depth-transition spinner (#1777) never appeared during 10 toggles",
            spinnerObserved
        )
    }

    companion object {
        private const val TOGGLE_COUNT = 10
    }
}
