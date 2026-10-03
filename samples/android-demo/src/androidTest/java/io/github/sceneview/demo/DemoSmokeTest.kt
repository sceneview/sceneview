package io.github.sceneview.demo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File

/**
 * Smoke tests for demos that cannot be interaction-tested on the Apple M3 Metal emulator or
 * without ARCore. Each test verifies the demo composable *launches* and renders a first frame
 * (scaffold title visible, no JNI crash) — a meaningful baseline on emulator even when the
 * underlying feature (ARCore session, Filament manipulator, device camera) is unavailable.
 *
 * Kept in a separate class from [DemoInteractionTest] so a hard JNI crash in one of these
 * (historically: `camera-controls` on emulator trips `UnsatisfiedLinkError
 * Manipulator.nCreateBuilder`) doesn't tear down the full interaction suite.
 *
 * **Pulling screenshots** — same path as [DemoInteractionTest]:
 * ```bash
 * adb pull /sdcard/Download/sceneview-qa/ tools/qa-screenshots/interactions/
 * ```
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class DemoSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = demoPackage
    private val timeout = 8_000L

    @Before
    fun goHome() {
        device.pressHome()
    }

    @After
    fun teardown() {
        device.pressHome()
    }

    /**
     * Opens the demo and takes a screenshot regardless of whether the title actually renders.
     * Unlike [DemoInteractionTest.openDemo], this does NOT error if the title never shows up —
     * an AR demo on an emulator without ARCore may paint an error dialog or permission prompt
     * instead, and that state is itself valuable QA output.
     *
     * Returns `true` iff the expected scaffold title rendered within the timeout (i.e. the
     * demo launched cleanly end-to-end).
     */
    private fun openDemoTolerant(demoId: String, expectedTitle: String): Boolean {
        val intent = Intent().apply {
            setClassName(pkg, DemoHostActivity::class.java.name)
            putExtra(DemoHostActivity.EXTRA_DEMO_ID, demoId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        val found = device.wait(Until.hasObject(By.text(expectedTitle)), timeout) != null
        // See rationale in DemoInteractionTest.openDemo — 10 s covers the Apple M3 Metal slow
        // path including the first PBR-pass on cold-boot (where 6 s left a black SurfaceView
        // in the captured screenshot).
        Thread.sleep(10000)
        return found
    }

    private fun screenshot(name: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val tmpDir = File(targetContext.getExternalFilesDir(null), "sceneview-qa-tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        val tmpPng = File(tmpDir, ".tmp_$name.png")
        device.takeScreenshot(tmpPng)
        val bmp = BitmapFactory.decodeFile(tmpPng.absolutePath)
            ?: error("Failed to decode screenshot PNG for '$name'")

        val resolver = targetContext.contentResolver
        val filename = "$name.jpg"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val selection = "${MediaStore.Downloads.RELATIVE_PATH}=? AND " +
            "${MediaStore.Downloads.DISPLAY_NAME}=?"
        val args = arrayOf("Download/sceneview-qa/", filename)
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID), selection, args, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val oldUri = android.content.ContentUris.withAppendedId(collection, id)
                    resolver.delete(oldUri, null, null)
                }
            }
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/sceneview-qa/")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending)
            ?: error("MediaStore insert returned null for '$name'")
        resolver.openOutputStream(uri)?.use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        } ?: error("MediaStore openOutputStream returned null for '$name'")
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        bmp.recycle()
        tmpPng.delete()
    }

    // ── AR demos — no ARCore installed on the emulator, so we expect either a graceful
    //    "ARCore not available" composable or a permission prompt. Either way, the test
    //    process must not crash. Ordered alphabetically (ar-cloud-anchor, ar-face, …).

    @Test
    fun a01_arCloudAnchor_smokeOpen() {
        openDemoTolerant("ar-cloud-anchor", "Cloud Anchors")
        screenshot("s01_ar_cloud_anchor")
    }

    @Test
    fun a02_arFace_smokeOpen() {
        openDemoTolerant("ar-face", "Augmented Faces")
        screenshot("s02_ar_face")
    }

    @Test
    fun a03_arImage_smokeOpen() {
        openDemoTolerant("ar-image", "Image Tracking")
        screenshot("s03_ar_image")
    }

    @Test
    fun a04_arPlacement_smokeOpen() {
        openDemoTolerant("ar-placement", "Tap to Place")
        screenshot("s04_ar_placement")
    }

    // Samples step 0 — `ar-pose` is the Free pose mode of `ar-placement`; the retired id
    // proves its alias still opens that mode, whose own title stays "Pose Placement".
    @Test
    fun a05_arPose_smokeOpen() {
        openDemoTolerant("ar-pose", "Pose Placement")
        screenshot("s05_ar_pose")
    }

    @Test
    fun a06_arRerun_smokeOpen() {
        openDemoTolerant("ar-rerun", "Room Scan")
        screenshot("s06_ar_rerun")
    }

    // Record mode: the landing's "Record your room" opens the live room scan. The emulator
    // cannot track (#2754), so this only proves the switch into the camera screen survives
    // without a crash; the scan itself is checked on a Pixel from the PR's needs-device list.
    @Test
    fun a06b_arRerun_recordSmokeOpen() {
        openDemoTolerant("ar-rerun", "Room Scan")
        device.wait(Until.findObject(By.text("Record your room")), timeout)?.click()
        Thread.sleep(5000)
        screenshot("s06b_ar_rerun_record")
        check(device.currentPackageName == pkg) { "The demo left the foreground after opening Record" }
    }

    // The system theme changes while the bundled replay's 3D view is on screen (#4330). The
    // activity handles `uiMode` itself, so the scene stays and takes the other theme's colours
    // on the layers it already draws. Rebuilding them freed textures their material instances
    // still held: Filament aborted the process on the next frame, and this run with it.
    @Test
    fun a06c_arRerun_themeSwitchDuringReplay() =
        switchThemeOverReplay(qaState = "replay", settleMillis = 10000, shot = "s06c_ar_rerun_theme_switch")

    // Same switch over the Surface view. Its ground is the scene's skybox, which the theme
    // replaces: the scene kept the old one a dispatch after its owner had destroyed it, and
    // Filament read it while taking the new one — a segfault, on some switches only.
    @Test
    fun a06d_arRerun_themeSwitchOverSurface() =
        switchThemeOverReplay(qaState = "model-synthetic", settleMillis = 20000, shot = "s06d_ar_rerun_theme_surface")

    private fun switchThemeOverReplay(qaState: String, settleMillis: Long, shot: String) {
        val initial = device.executeShellCommand("cmd uimode night").substringAfter(": ").trim()
        try {
            context.startActivity(
                Intent().apply {
                    setClassName(pkg, MainActivity::class.java.name)
                    putExtra("demo", "ar-rerun")
                    putExtra("qa_mode", true)
                    putExtra("qa_state", qaState)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            device.wait(Until.hasObject(By.text("Room Scan")), timeout)
            Thread.sleep(settleMillis)
            repeat(THEME_SWITCH_ROUNDS) {
                device.executeShellCommand("cmd uimode night yes")
                Thread.sleep(3000)
                device.executeShellCommand("cmd uimode night no")
                Thread.sleep(3000)
            }
            screenshot(shot)
            check(device.currentPackageName == pkg) { "The demo left the foreground after a theme switch" }
        } finally {
            device.executeShellCommand("cmd uimode night ${if (initial in NIGHT_MODES) initial else "no"}")
        }
    }

    // Samples step 0 — `ar-streetscape` is now the Streetscape mode of the Geospatial
    // Anchors card. The leg deliberately keeps driving the RETIRED id: that is what proves
    // the alias and its ALIAS_INITIAL_TAB entry still land on the Streetscape mode. The
    // title on screen is the absorbing card's.
    @Test
    fun a07_arStreetscape_smokeOpen() {
        openDemoTolerant("ar-streetscape", "Geospatial Anchors")
        screenshot("s07_ar_streetscape")
    }

    // ── Camera & Gestures — known to trip `Manipulator.nCreateBuilder` UnsatisfiedLinkError on
    //    the Apple M3 Metal translator AVD (symbol present in libfilament-jni.so but unresolved
    //    at runtime). The test runs last (`z_` prefix) so a hard crash here doesn't mask the
    //    preceding 7 AR smoke tests. #3500 rebuilt the screen on its own spherical rig
    //    (`StudioCameraManipulator`), which never calls `Manipulator.nCreateBuilder` — the
    //    tolerant open and the `z_` ordering stay as a belt-and-braces guard.

    @Test
    fun z01_cameraControls_smokeOpen() {
        openDemoTolerant("camera-gestures", "Camera & Gestures")
        screenshot("s08_camera_controls")
    }

    private companion object {
        /** What `cmd uimode night` takes back, to leave the device as it was found. */
        val NIGHT_MODES = setOf("yes", "no", "auto")

        /** Dark then light, this many times: the freed skybox is not read on every switch. */
        const val THEME_SWITCH_ROUNDS = 3
    }
}
