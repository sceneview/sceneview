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

/** Real-device stress check: renderer-only toggles retain the live comparison screen. */
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
        val toggleReady = device.wait(Until.hasObject(By.text("Turn occlusion off")), timeout)
        if (!toggleReady) {
            org.junit.Assume.assumeFalse(
                "Requires a device with ARCore Depth API",
                device.hasObject(By.text("This feature isn’t available on this device."))
            )
        }
        assertTrue("Supported comparison never became ready", toggleReady)
        repeat(TOGGLE_COUNT) { index ->
            val before = if (index % 2 == 0) "Turn occlusion off" else "Turn occlusion on"
            val after = if (index % 2 == 0) "Turn occlusion on" else "Turn occlusion off"
            device.findObject(By.text(before)).click()
            assertTrue("Effect state did not update", device.wait(Until.hasObject(By.text(after)), timeout))
            assertTrue("Demo dismissed during toggling", device.hasObject(By.text(expectedTitle)))
        }
    }

    companion object {
        private const val TOGGLE_COUNT = 10
    }
}
