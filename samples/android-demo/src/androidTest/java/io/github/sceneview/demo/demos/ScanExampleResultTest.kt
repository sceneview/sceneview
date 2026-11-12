package io.github.sceneview.demo.demos

import android.content.ComponentName
import android.content.Intent
import androidx.core.content.FileProvider
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunExampleAssets
import io.github.sceneview.demo.demos.internal.RerunScanFile
import io.github.sceneview.demo.demos.internal.ScanScope
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.sceneview.demo.R
import io.github.sceneview.demo.mainActivityComponent
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** The landing example replays the room; result layers and Video are checked on an imported scan. */
@RunWith(AndroidJUnit4::class)
class ScanExampleResultTest {
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
    fun theExampleOpensTheBundledRoomReplay() {
        device.executeShellCommand(
            "am start -n $mainActivityComponent -f 0x14008000 --es demo ar-rerun --es telemetry_consent denied",
        )
        val example = device.wait(Until.findObject(By.text(SEE_AN_EXAMPLE)), OPEN_TIMEOUT_MS)
        assertNotNull("3D Scan never offered its example", example)
        example.click()
        assertNotNull(
            "the room replay has no video strip",
            device.wait(Until.findObject(By.desc("Session timeline")), OPEN_TIMEOUT_MS),
        )
        val coverageLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.scan_coverage_label, "{area}").substringBefore("{area}")
        assertNotNull(
            "the room replay has no mini-map",
            device.wait(Until.findObject(By.descStartsWith(coverageLabel)), OPEN_TIMEOUT_MS),
        )
        assertFalse("the example opened a model result", device.hasObject(By.text(VIDEO)))
        assertFalse("the example opened a layer picker", device.hasObject(By.text(MODEL)))
        capture("room-replay")
        device.pressBack()
        assertNotNull(
            "Back from the room replay did not return to the landing",
            device.wait(Until.findObject(By.text(SEE_AN_EXAMPLE)), OPEN_TIMEOUT_MS),
        )
    }

    @Test
    fun aRealScanOpenedFromAFileOffersPhotoModelAndVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Exercise the public file-open path, using the retained dense garden as a scan fixture.
        val pack = RerunCapturePack(
            context.assets.open(RerunExampleAssets.MANIFEST).use { it.readBytes() },
            context.assets.open(RerunExampleAssets.LOG).use { it.readBytes() },
            context.assets.open(RerunExampleAssets.MEDIA).use { it.readBytes() },
        )
        val file = File(context.cacheDir, "rerun-share/scan-result-test.svscan")
        file.parentFile!!.mkdirs()
        file.writeBytes(RerunScanFile.write(RerunScanFile.stamped(pack, ScanScope.Area, "Imported garden test")))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            component = ComponentName.unflattenFromString(mainActivityComponent)
            setDataAndType(uri, "application/octet-stream")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra("telemetry_consent", "denied")
        })
        assertNotNull(
            "the imported scan did not open its result with Video",
            device.wait(Until.findObject(By.text(VIDEO)), OPEN_TIMEOUT_MS),
        )
        Thread.sleep(FIRST_PICTURE_MS)
        val first = capture("imported-first")
        assertTrue("the scene never drew the scan", drawnShare(first) > MIN_DRAWN_SHARE)

        // Photo and Model, once the model is there: two pictures of the same place.
        assertNotNull(
            "the result's layer switch never appeared",
            device.wait(Until.findObject(By.text(MODEL)), MODEL_TIMEOUT_MS),
        )
        device.wait(Until.gone(By.textStartsWith(CREATING)), MODEL_TIMEOUT_MS)
        assertTrue(
            "the model never finished",
            device.wait(Until.gone(By.textStartsWith(FINISHING)), MODEL_TIMEOUT_MS) == true,
        )
        // The finished model has taken the preview's place by itself; a touch stops the slow turn,
        // which would make any two captures differ.
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        Thread.sleep(REVEAL_MS)
        val model = capture("result-model")
        assertTrue("the model draws next to nothing", drawnShare(model) > MIN_DRAWN_SHARE)
        assertTrue("Photo never took", pick(PHOTO) { device.hasObject(By.text(PHOTO_NOTE)) })
        Thread.sleep(SETTLE_MS)
        val photo = capture("result-photo")
        assertTrue("the Photo layer draws next to nothing", drawnShare(photo) > MIN_DRAWN_SHARE)
        val swapped = changedShare(model, photo)
        assertTrue("Photo and Model are the same picture ($swapped of its pixels differ)", swapped > MIN_SWAP_SHARE)

        // The result films itself: a take is recorded, and a video file is what gets shared.
        val film = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, FILM_PATH)
        film.delete()
        device.findObject(By.text(VIDEO)).click()
        assertNotNull("Video started no take", device.wait(Until.findObject(By.text(FILMING)), TAP_WAIT_MS))
        assertTrue("the take never ended", device.wait(Until.gone(By.text(FILMING)), FILM_TIMEOUT_MS) == true)
        assertTrue("the take left no video (${film.length()} bytes)", film.length() > MIN_FILM_BYTES)
        device.pressBack()
    }

    /** Taps [label] until [took], the switch ignoring taps until the model is seen. */
    private fun pick(label: String, took: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + MODEL_TIMEOUT_MS
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
        val file = File(dir, "scan-example-$name.png")
        assertTrue("screenshot $name", device.takeScreenshot(file))
        return checkNotNull(BitmapFactory.decodeFile(file.absolutePath)) { "decode $name" }
    }

    /**
     * Share of the scene band (between the top bar and the bottom band) that is not the stage:
     * pixels away from the band's own most common colour, which a bare stage is all of.
     */
    private fun drawnShare(shot: Bitmap): Float {
        val pixels = bandPixels(shot)
        val ground = pixels.groupingBy { it and GROUND_MASK }.eachCount().maxByOrNull { it.value }?.key ?: return 0f
        return pixels.count { distance(it, ground) > CHANNEL_DIFF }.toFloat() / pixels.size
    }

    /** Share of the scene band's pixels that differ between [a] and [b]. */
    private fun changedShare(a: Bitmap, b: Bitmap): Float {
        val p = bandPixels(a)
        val q = bandPixels(b)
        return p.indices.count { distance(p[it], q[it]) > CHANNEL_DIFF }.toFloat() / p.size
    }

    private fun bandPixels(shot: Bitmap): List<Int> {
        val y0 = shot.height * SCENE_TOP_PERCENT / 100
        val y1 = shot.height * SCENE_BOTTOM_PERCENT / 100
        return buildList {
            for (y in y0 until y1 step STEP) for (x in 0 until shot.width step STEP) add(shot.getPixel(x, y))
        }
    }

    private fun distance(p: Int, q: Int): Int = maxOf(
        abs(Color.red(p) - Color.red(q)),
        abs(Color.green(p) - Color.green(q)),
        abs(Color.blue(p) - Color.blue(q)),
    )

    private companion object {
        const val SEE_AN_EXAMPLE = "See an example"
        const val VIDEO = "Video"
        const val PHOTO = "Photo"
        const val MODEL = "Model"
        const val CREATING = "Creating your model"
        const val FINISHING = "Finishing your model"
        const val PHOTO_NOTE = "Photo preview · measures stay on the model"
        const val FILMING = "Filming…"

        /** Where the take is written, under the app's cache: the share sheet's own folder. */
        const val FILM_PATH = "rerun-share/film/scan_film.mp4"
        const val OPEN_TIMEOUT_MS = 30_000L

        /** A ten-second take, its encoder's start and stop, on a slow device. */
        const val FILM_TIMEOUT_MS = 60_000L
        const val MIN_FILM_BYTES = 100_000L

        /** The model of a 235 000-surfel garden: 45 s on a Pixel 4a, longer on an emulator. */
        const val MODEL_TIMEOUT_MS = 240_000L

        /** Allow the imported scan's Photo layer to grow before checking it. */
        const val FIRST_PICTURE_MS = 2_500L

        /** The model's reveal, once it takes the preview's place (`ModelReveal.TOTAL_MS`), and its veil. */
        const val REVEAL_MS = 5_000L
        const val TAP_WAIT_MS = 5_000L
        const val POLL_MS = 200L
        const val SETTLE_MS = 2_500L
        const val SCENE_TOP_PERCENT = 20
        const val SCENE_BOTTOM_PERCENT = 65
        const val STEP = 6
        const val CHANNEL_DIFF = 24

        /** Colours within 8 levels of each other count as one when the stage's own is looked for. */
        const val GROUND_MASK = 0xF8F8F8
        const val MIN_DRAWN_SHARE = 0.08f
        const val MIN_SWAP_SHARE = 0.02f
    }
}
