package io.github.sceneview.demo.demos

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sceneview.demo.mainActivityComponent
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * The scan result's Photo layer on a real Filament scene ([ResultPhotoLayer]): the QA synthetic
 * room's result, Model → Photo → Model. Photo hides the model (its renderables' layer mask) under
 * the splats, so the scene changes; going back to Model restores the mask, so the scene is the
 * one it was before. A mask left at 0 — the risk the layer-mask swap carries — fails the second
 * comparison. Filament exposes no layer-mask getter, so the screen is the witness.
 */
@RunWith(AndroidJUnit4::class)
class ResultPhotoLayerTest {
    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.executeShellCommand("cmd uimode night no")
    }

    @After
    fun tearDown() {
        device.executeShellCommand("cmd uimode night auto")
    }

    @Test
    fun photoHidesTheModelAndModelBringsItBack() {
        device.executeShellCommand(
            "am start -n $mainActivityComponent -f 0x14000000 " +
                "--es demo ar-rerun --ez qa_mode true --es qa_state model-synthetic " +
                "--es telemetry_consent denied",
        )
        assertNotNull(
            "the result's layer switch never appeared",
            device.wait(Until.findObject(By.text(MODEL)), RESULT_TIMEOUT_MS),
        )
        // Compared against the finished model, not the raw one it replaces.
        device.wait(Until.gone(By.textStartsWith(CREATING)), RESULT_TIMEOUT_MS)
        assertTrue(
            "the model never finished",
            device.wait(Until.gone(By.textStartsWith(FINISHING)), RESULT_TIMEOUT_MS) == true,
        )
        Thread.sleep(SETTLE_MS)
        val before = capture("model-before")

        assertTrue("Photo never took", pick(PHOTO) { device.hasObject(By.text(PHOTO_NOTE)) })
        Thread.sleep(SETTLE_MS)
        val photo = capture("photo")

        assertTrue("Model never took", pick(MODEL) { !device.hasObject(By.text(PHOTO_NOTE)) })
        Thread.sleep(SETTLE_MS)
        val after = capture("model-after")

        val swapped = changedShare(before, photo)
        val restored = changedShare(before, after)
        assertTrue("Photo did not change the scene ($swapped of its pixels)", swapped > MIN_SWAP_SHARE)
        assertTrue("Model did not come back as it was ($restored of its pixels differ)", restored < MAX_RESTORE_SHARE)
    }

    /** Taps [label] until [took], the switch ignoring taps until the model is seen. */
    private fun pick(label: String, took: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + RESULT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            device.findObject(By.text(label))?.click()
            val tapDeadline = System.currentTimeMillis() + TAP_WAIT_MS
            while (System.currentTimeMillis() < tapDeadline) {
                if (took()) return true
                Thread.sleep(POLL_MS)
            }
        }
        return false
    }

    private fun capture(name: String): Bitmap {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val file = File(dir, "result-photo-layer-$name.png")
        assertTrue("screenshot $name", device.takeScreenshot(file))
        return checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "decode $name" }
    }

    /** Share of the scene band's pixels (between the top bar and the bottom band) that differ. */
    private fun changedShare(a: Bitmap, b: Bitmap): Float {
        val y0 = a.height * SCENE_TOP_PERCENT / 100
        val y1 = a.height * SCENE_BOTTOM_PERCENT / 100
        var changed = 0
        var total = 0
        for (y in y0 until y1 step STEP) for (x in 0 until a.width step STEP) {
            val p = a.getPixel(x, y)
            val q = b.getPixel(x, y)
            val d = maxOf(
                abs(Color.red(p) - Color.red(q)),
                abs(Color.green(p) - Color.green(q)),
                abs(Color.blue(p) - Color.blue(q)),
            )
            if (d > CHANNEL_DIFF) changed++
            total++
        }
        return changed.toFloat() / total
    }

    private companion object {
        const val PHOTO = "Photo"
        const val MODEL = "Model"
        const val CREATING = "Creating your model"
        const val FINISHING = "Finishing your model"
        const val PHOTO_NOTE ="Photo preview · measures stay on the model"
        const val RESULT_TIMEOUT_MS = 120_000L
        const val TAP_WAIT_MS = 5_000L
        const val POLL_MS = 200L
        const val SETTLE_MS = 2_500L
        const val SCENE_TOP_PERCENT = 15
        const val SCENE_BOTTOM_PERCENT = 70
        const val STEP = 6
        const val CHANNEL_DIFF = 24
        const val MIN_SWAP_SHARE = 0.05f
        const val MAX_RESTORE_SHARE = 0.03f
    }
}
