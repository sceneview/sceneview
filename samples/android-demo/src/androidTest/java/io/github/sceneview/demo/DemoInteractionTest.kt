package io.github.sceneview.demo

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.provider.MediaStore
import io.github.sceneview.demo.R
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Interaction tests for the SceneView Android demos.
 *
 * **Approach**: launch each demo composable directly via [DemoHostActivity] (a debug-only
 * harness that accepts `demo_id` as intent extra), then drive the real controls with
 * UiAutomator as a user would. Screenshots are captured between each interaction with
 * `UiDevice.takeScreenshot()` (full framebuffer including Filament SurfaceView).
 *
 * **Why DemoHostActivity?** The earlier scroll-then-click approach was fragile for demos in
 * the Advanced section (`physics`, `custom-geometry`, …) — `UiScrollable.scrollTextIntoView()`
 * gave up before the Compose LazyColumn recomposed the far-down row into view. Launching
 * the demo composable directly with an Intent bypasses the home list entirely.
 *
 * **Why not ComposeTestRule?** Compose's test runner dispatches coroutines on a thread
 * Filament has not "adopted", which trips `getState:347 — This thread has not been adopted`.
 * Going through the real app process means the app's own Dispatchers.Main owns Filament,
 * exactly as in production.
 *
 * **Pulling screenshots**:
 * ```bash
 * adb pull /sdcard/Download/sceneview-qa/ tools/qa-screenshots/interactions/
 * ```
 *
 * JPEGs are written directly to the public `Download/sceneview-qa/` folder via the
 * MediaStore API (not `java.io.File` — scoped storage blocks that for third-party apps
 * on API 30+, and the app-private `getExternalFilesDir()` gets wiped when
 * `connectedAndroidTest` uninstalls the demo APK).
 */
@RunWith(AndroidJUnit4::class)
class DemoInteractionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val pkg = "io.github.sceneview.demo"
    private val timeout = 10_000L

    @Before
    fun goHome() {
        device.pressHome()
    }

    @After
    fun teardown() {
        device.pressHome()
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Saves a full-device screenshot as JPEG to `Download/sceneview-qa/<name>.jpg` via
     * the MediaStore API (the only post-uninstall-persistent location a third-party app
     * can write to on scoped-storage Android without special permissions).
     *
     * `UiDevice.takeScreenshot` always writes PNG regardless of extension / `quality`
     * parameter (the int is the PNG deflate level, not a JPEG quality). A 1080×2400 PNG
     * at ~700 kB × 86 captures = 60 MB per run. Going through `Bitmap.compress(JPEG, 75)`
     * cuts that to ~200 kB per shot at indistinguishable visual quality on Filament +
     * UI chrome content.
     *
     * The tmp PNG is staged in the app-private external dir (free-scoped-storage, wiped
     * on uninstall), decoded, recompressed as JPEG, then inserted into MediaStore.Downloads.
     */
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

    /**
     * Launches [DemoHostActivity] for the given demo id (see [ALL_DEMOS]),
     * bypassing the home list scroll entirely. Waits until the demo's title bar appears.
     *
     * The expected title is resolved from [ALL_DEMOS] via `context.getString(titleRes)`
     * rather than passed as a literal — so the `By.text(...)` match stays in sync with
     * the string resources and never drifts when a demo title is renamed.
     */
    private fun openDemo(demoId: String) {
        val titleRes = ALL_DEMOS.firstOrNull { it.id == demoId }?.titleRes
            ?: error("openDemo: demo id '$demoId' is not registered in ALL_DEMOS")
        val expectedTitle = context.getString(titleRes)
        val intent = Intent().apply {
            setClassName(pkg, "$pkg.DemoHostActivity")
            putExtra(DemoHostActivity.EXTRA_DEMO_ID, demoId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        // Wait for the demo's scaffold title to render (confirms Compose + Filament wired up)
        device.wait(Until.hasObject(By.text(expectedTitle)), timeout)
        // First-frame Filament setup on Apple M3 Metal translator: Engine resolve ~200 ms +
        // material link ~300 ms + async GLB decode ~4-5 s + GPU upload ~1 s + first PBR pass
        // a few hundred ms. 6 s caught most cases but on cold-boot the first PBR frame can
        // arrive at 8-9 s, leaving a black SurfaceView in the screenshot. 10 s covers the
        // worst case and adds ~96 s across the 24-test suite (~25 % overhead, acceptable
        // for guaranteed visual capture).
        Thread.sleep(10000)
    }

    /**
     * Since DemoScaffold v2 (#1154), per-demo controls live inside a ModalBottomSheet
     * launched by a "demo-settings-fab" FloatingActionButton anchored bottom-end of the
     * screen. Tests that drive these controls must open the sheet first so the chips /
     * sliders / toggles are in the visible UI tree.
     *
     * Targeted by the stable test-tag (TestTag("demo-settings-fab")) which avoids relying
     * on the icon's contentDescription string.
     *
     * Returns silently if the FAB is not present (demos with `controls = null`).
     */
    private fun openSettingsSheet() {
        val fab = device.findObject(By.res("demo-settings-fab"))
            ?: device.findObject(By.desc("Demo settings"))
            ?: return
        fab.click()
        // Sheet slide-in animation ~300 ms + first composition of controls ~200 ms.
        Thread.sleep(700)
    }

    private fun tap(text: String) {
        // DemoScaffold v2 (#1154): controls live inside a ModalBottomSheet that
        // we must open first. Try opening it if the target isn't already visible.
        if (!device.wait(Until.hasObject(By.text(text)), 1500)) {
            openSettingsSheet()
        }
        // First try without scrolling (most controls are above the fold). If the target
        // isn't visible, scroll the controls panel up — the controls Column wraps demos
        // with many controls and the bottom rows (e.g. AnimationDemo's Loop/Once chips)
        // sit below the fold on the Pixel_7a AVD's 1080x2400 viewport.
        // 3 s wait covers Compose's recomposition tail when run as part of the larger
        // suite, where state from a previous test (Filament Engine, NavHost, etc.) may
        // still be settling.
        if (!device.wait(Until.hasObject(By.text(text)), 3000)) {
            scrollControlsToFind(text)
        }
        // After scrolling, give Compose another beat to draw + lay out the newly-visible
        // chips before findObject — without this, on slow emulator runs the
        // AccessibilityNodeInfo tree can lag the visible state by 100-300 ms.
        device.wait(Until.hasObject(By.text(text)), 2000)
        val node = device.findObject(By.text(text))
            ?: error("Clickable '$text' not found on screen")
        // Text inside a FilterChip / Button / Card is not clickable — walk up to the
        // nearest clickable ancestor so the onClick handler actually fires.
        val clickable = generateSequence(node) { it.parent }
            .firstOrNull { it.isClickable } ?: node
        clickable.click()
        Thread.sleep(800)
    }

    /**
     * Swipes the controls panel up to bring [text] into view. Some demos have very
     * tall controls panels (camera chips + model chips + playback + speed slider +
     * loop/once chips for AnimationDemo, or sliders + intensity for environment) that
     * push the lower rows below the AVD's 1080×2400 viewport.
     *
     * Uses `UiScrollable.scrollIntoView` — the purpose-built API that walks the
     * accessibility tree to find a scroll container, drives the gesture, and
     * confirms the target is visible. We scope it to the bottom-half of the screen
     * (the controls panel) by anchoring on the `Camera` / `Mode` / `Playback` label
     * that every demo's controls section starts with — but if no such anchor exists
     * we fall back to a generic shell `input swipe` repeated up to 5 times.
     */
    private fun scrollControlsToFind(text: String, maxSwipes: Int = 5) {
        // Try UiScrollable first — drives a real synthesized gesture against the
        // first scrollable container under the visible window.
        val scrollable = UiScrollable(UiSelector().scrollable(true))
        if (scrollable.exists()) {
            try {
                scrollable.flingForward()
                if (device.hasObject(By.text(text))) return
                scrollable.scrollTextIntoView(text)
                if (device.hasObject(By.text(text))) return
            } catch (_: Throwable) {
                // Fall through to shell-input swipe.
            }
        }
        // Shell-input swipe fallback: direct adb-style gesture, runs as shell user
        // and bypasses any Compose-level event filtering. This is the path that
        // actually works against `Column { verticalScroll(...) }` on the AVD.
        val w = device.displayWidth
        val h = device.displayHeight
        val midX = w / 2
        val startY = (h * 0.96).toInt()
        val endY = (h * 0.71).toInt()
        repeat(maxSwipes) {
            if (device.hasObject(By.text(text))) return
            device.executeShellCommand("input swipe $midX $startY $midX $endY 250")
            // Allow the input events to be processed AND the verticalScroll to settle
            // before re-checking visibility.
            Thread.sleep(700)
            device.waitForIdle(1000)
        }
    }

    /**
     * Drags a Compose [Slider] whose label starts with [labelPrefix]. Prefix (not full text)
     * because slider labels typically include the current value (`"Density: 0.15"`) which
     * changes each frame during a drag, so looking up the exact label after a drag is flaky.
     *
     * [fraction] is in `[0, 1]` — 0 drags the thumb to the minimum end, 1 to the maximum.
     */
    /**
     * Taps a view whose accessibility content-description matches [desc]. Used for icon-only
     * buttons (Play / Pause, etc.) that have no visible text label — `tap(text)` can't find
     * them. The underlying `IconButton` is already clickable so no ancestor walk is needed.
     */
    private fun tapByDesc(desc: String) {
        // DemoScaffold v2 (#1154): if the icon button lives inside the controls
        // sheet, the sheet must be open. The settings FAB is also targeted via
        // contentDescription "Demo settings" — never confuse it for a demo icon.
        if (desc != "Demo settings" &&
            !device.wait(Until.hasObject(By.desc(desc)), 1500)
        ) {
            openSettingsSheet()
        }
        device.wait(Until.hasObject(By.desc(desc)), timeout)
        val node = device.findObject(By.desc(desc))
            ?: error("Element with contentDescription '$desc' not found on screen")
        node.click()
        Thread.sleep(800)
    }

    /**
     * Types [text] into a Compose `OutlinedTextField`, replacing anything already there.
     *
     * [currentValue] is the *current text* of the field (not its label). UiObject2.text
     * = … drives the accessibility setText action which handles focus + IME + commit
     * atomically — the older `click + input keyevent` recipe did not move focus on a
     * Compose `OutlinedTextField` (the synthetic click landed on the BasicText composable
     * rather than the underlying AndroidComposeView's focus target) and every keyevent
     * that followed was dispatched to an unfocused surface.
     */
    private fun typeInto(currentValue: String, text: String) {
        // DemoScaffold v2 (#1154): text fields live inside the controls sheet.
        if (!device.hasObject(By.text(currentValue))) {
            openSettingsSheet()
        }
        val field = device.wait(Until.findObject(By.text(currentValue)), timeout)
            ?: error("Text field with current value '$currentValue' not found")
        field.text = text
        Thread.sleep(600)  // let onValueChange propagate to state + recomposition
    }

    // ── Camera-gesture helpers ────────────────────────────────────────────────────
    //
    // Every helper here targets the 3D viewport region (the top ~55 % of the screen —
    // below the top-app-bar, above the scaffold controls column). None of them touch UI
    // chrome, so they exercise the CameraManipulator / gesture layer of SceneView without
    // accidentally clicking a chip or slider.

    private val viewportCenterX get() = device.displayWidth / 2
    private val viewportCenterY get() = (device.displayHeight * 0.30).toInt()

    /** Horizontal orbit via one-finger drag. [pixels] is signed — positive = right. */
    private fun orbit(pixels: Int = 300) {
        device.swipe(
            viewportCenterX, viewportCenterY,
            viewportCenterX + pixels, viewportCenterY,
            30,
        )
        Thread.sleep(600)
    }

    /** Vertical tilt via one-finger drag. Positive = down (camera pitches up). */
    private fun tilt(pixels: Int = 200) {
        device.swipe(
            viewportCenterX, viewportCenterY,
            viewportCenterX, viewportCenterY + pixels,
            30,
        )
        Thread.sleep(600)
    }

    /**
     * Two-finger pinch centred on the viewport. [open] = true for zoom-in (fingers move
     * apart), false for zoom-out. [percent] is the fraction of the viewport the pinch
     * spans (0..1). 0.25 is enough to drive the CameraManipulator visibly while staying
     * below the dolly distance at which the camera clips into the model.
     *
     * We bypass the Compose surface-view hit target (UiObject2 on Compose's SurfaceView
     * is finicky) and drive the pinch via the root-level [UiObject2] obtained from the
     * package's top window. The gesture is accessibility-dispatched, so it reaches the
     * underlying GestureDetector + CameraManipulator in SceneView exactly the same way
     * a real user's pinch would.
     */
    private fun pinch(open: Boolean, percent: Float = 0.4f) {
        val root = device.findObject(By.pkg(pkg).depth(0))
            ?: error("Could not find root UiObject2 for package '$pkg'")
        if (open) root.pinchOpen(percent) else root.pinchClose(percent)
        Thread.sleep(600)
    }

    /** Quick double-tap at viewport centre — the CameraManipulator reset / focus shortcut. */
    private fun doubleTapViewport() {
        device.click(viewportCenterX, viewportCenterY)
        Thread.sleep(60)
        device.click(viewportCenterX, viewportCenterY)
        Thread.sleep(400)
    }

    private fun dragSlider(labelPrefix: String, fraction: Float) {
        // DemoScaffold v2 (#1154): slider labels live inside the controls sheet.
        if (!device.hasObject(By.textStartsWith(labelPrefix))) {
            openSettingsSheet()
        }
        val labelNode = device.findObject(By.textStartsWith(labelPrefix))
            ?: error("Slider label starting with '$labelPrefix' not found on screen")
        val b = labelNode.visibleBounds
        // Slider track: ~48 dp below the label baseline on Material 3, spanning the scaffold
        // controls column (~92% of screen width in most demos).
        val density = device.displayWidth / 411f  // Pixel 7a is 411 dp wide
        val trackY = b.bottom + (20 * density).toInt()  // ~20 dp below the label
        val trackLeft = (device.displayWidth * 0.04f).toInt()
        val trackRight = (device.displayWidth * 0.96f).toInt()
        val targetX = trackLeft + ((trackRight - trackLeft) * fraction.coerceIn(0f, 1f)).toInt()
        device.swipe((trackLeft + trackRight) / 2, trackY, targetX, trackY, 30)
        Thread.sleep(800)
    }

    // ── 1. Lighting — 3 light-type chips ──────────────────────────────────────

    @Test
    fun lighting_allThreeLightTypes() {
        openDemo("lighting")
        screenshot("01_lighting_directional_default")

        tap("Point")
        screenshot("02_lighting_point")

        tap("Spot")
        screenshot("03_lighting_spot")

        tap("Directional")
        screenshot("04_lighting_directional_back")

        // Intensity slider sweep — min / mid / max
        dragSlider("Intensity:", fraction = 0.0f); screenshot("04a_lighting_intensity_min")
        dragSlider("Intensity:", fraction = 0.5f); screenshot("04b_lighting_intensity_mid")
        dragSlider("Intensity:", fraction = 1.0f); screenshot("04c_lighting_intensity_max")

        // Color swatches — targeted via `semantics { contentDescription = "<Name> light color" }`
        // added to the demo for a11y + UiAutomator reachability.
        tapByDesc("Warm light color"); screenshot("04d_lighting_color_warm")
        tapByDesc("Blue light color"); screenshot("04e_lighting_color_blue")
        tapByDesc("Red light color"); screenshot("04f_lighting_color_red")
        tapByDesc("White light color"); screenshot("04g_lighting_color_white")
    }

    // ── 2. Fog — toggle + density slider + colour presets (single screen open) ─

    @Test
    fun fog_fullScreen() {
        openDemo("fog")
        screenshot("05_fog_enabled_mist")

        // Toggle off / on
        tap("Fog Enabled"); screenshot("06_fog_disabled")
        tap("Fog Enabled"); screenshot("07_fog_re_enabled")

        // Colour presets
        tap("Eerie Green"); screenshot("08_fog_eerie_green")
        tap("Warm Haze"); screenshot("09_fog_warm_haze")
        tap("Deep Smoke"); screenshot("10_fog_deep_smoke")

        // Density slider (back to default preset Mist first)
        tap("Mist")
        dragSlider("Density:", fraction = 1.0f); screenshot("10a_fog_density_max")
        dragSlider("Density:", fraction = 0.0f); screenshot("10b_fog_density_min")
        dragSlider("Density:", fraction = 0.5f); screenshot("10c_fog_density_mid")
    }

    // ── 3. Physics ─────────────────────────────────────────────────────────────
    // #2239 Batch 3 — `physics` consolidated into `animation-physics` (Physics tab).
    // Covered by `animationPhysics_allTabs` below, which taps the Physics tab and
    // exercises Drop / Drop 10 / Reset.

    // ── 4. Geometry Primitives — 4 shape chips ────────────────────────────────

    @Test
    fun geometryPrimitives_allShapes() {
        openDemo("geometry")
        screenshot("15_geometry_cube_default")

        tap("Sphere")
        screenshot("16_geometry_sphere_on")

        tap("Cylinder")
        screenshot("17_geometry_cylinder_on")

        tap("Plane")
        screenshot("18_geometry_plane_on")

        tap("Cube")
        screenshot("19_geometry_cube_off")
    }

    // ── 5. Custom Geometry — Custom Mesh mode (auto-rotate + orbit + scale) ───
    //
    // Both former demos (`custom-mesh` and `shape`) are now sub-modes of the
    // unified `custom-geometry` demo, toggled by a segmented button at the top
    // of the controls panel (#2239 Batch 1). The Custom Mesh mode is the
    // default landing tab, so the deep-link still arrives ready to exercise
    // auto-rotate / orbit / scale. The retired `custom-mesh` and `shape`
    // deep-link ids continue to resolve through `DEMO_ID_ALIASES`.

    @Test
    fun customMesh_autoRotateAndOrbit() {
        openDemo("custom-geometry")
        screenshot("20_customMesh_autoRotate_on")

        tap("Auto-Rotate")
        screenshot("21_customMesh_autoRotate_off")

        // Orbit the camera by swiping horizontally on the SurfaceView area
        device.swipe(
            device.displayWidth / 2, device.displayHeight / 3,
            device.displayWidth / 2 + 250, device.displayHeight / 3,
            20
        )
        Thread.sleep(600)
        screenshot("22_customMesh_after_orbit_drag")

        // Scale slider — min / max / default-ish (0.5)
        dragSlider("Scale:", fraction = 0.0f); screenshot("22a_customMesh_scale_min")
        dragSlider("Scale:", fraction = 1.0f); screenshot("22b_customMesh_scale_max")
        dragSlider("Scale:", fraction = 0.5f); screenshot("22c_customMesh_scale_mid")
    }

    // ── 6. Custom Geometry — Shape Extrude mode (Triangle/Star/Hexagon chips) ─

    @Test
    fun shape_allPolygons() {
        openDemo("custom-geometry")
        // Switch from the default Custom Mesh mode to the Shape Extrude mode.
        tap("Shape Extrude")
        screenshot("23_shape_triangle_default")

        tap("Star")
        screenshot("24_shape_star")

        tap("Hexagon")
        screenshot("25_shape_hexagon")

        tap("Triangle")
        screenshot("26_shape_triangle_back")
    }

    // ── 7. Models — all 3 segmented tabs ──────────────────────────────────────

    @Test
    fun modelViewer_allTabs() {
        // #2239 Batch 5 — `multi-model` and `scene-gallery` consolidated into the
        // existing `model-viewer` entry (the flagship umbrella, kept live) with a
        // 3-way segmented toggle. One test taps through every tab so each merged
        // half is exercised (the unified demo opens on its default Single Model tab).
        openDemo("model-viewer")

        // ── Single Model tab (default landing tab) — bundled hero helmet ──────
        screenshot("27_models_single_default")

        // ── Multi-Model tab — themed "Park" scene + per-model visibility chips ─
        tap("Models")
        tap("Park scene")
        screenshot("28_models_multi_default")
        // The visibility chips are labelled from the resolved `park` slug's
        // `displayName` (#2933) — "Oak Trees" is slot 1's registry entry, not a
        // hardcoded noun. If SampleAssets renames or replaces that slug this tap
        // has to follow it; the label is the catalogue's, not the demo's.
        tap("Oak Trees")   // toggle a node off / on so the chips are exercised
        screenshot("28a_models_multi_no_hero")
        tap("Oak Trees")
        screenshot("28b_models_multi_hero_back")

        // ── Gallery tab — chip-picked themed Sketchfab model ──────────────────
        tap("Models")
        tap("Browse online models…")
        screenshot("29_models_gallery_default")
    }

    // ── 8. Post Processing — 4 toggle rows ────────────────────────────────────

    // ── 8. Lighting Lab — all 4 segmented tabs ────────────────────────────────

    @Test
    fun lightingLab_allTabs() {
        // #2239 Batch 2 — `dynamic-sky`, `environment`, `reflection-probes`, and
        // `post-processing` consolidated into `lighting-lab` with a 4-way segmented
        // toggle. One test taps through every tab so each merged half is exercised
        // (the unified demo opens on its default Sky tab).
        openDemo("lighting-lab")

        // ── Sky tab (default landing tab) — time + turbidity sliders ──────────
        screenshot("31_lab_sky_default")
        dragSlider("Time of Day:", fraction = 0.1f)   // dawn
        screenshot("31a_lab_sky_dawn")
        dragSlider("Time of Day:", fraction = 0.9f)   // dusk
        screenshot("31b_lab_sky_dusk")
        dragSlider("Turbidity:", fraction = 1.0f)
        screenshot("31c_lab_sky_high_turbidity")

        // ── Environment tab — HDR chips ───────────────────────────────────────
        tap("Environment")
        screenshot("32_lab_env_studio_default")
        tap("Sunset")
        screenshot("32a_lab_env_sunset")
        tap("Studio")
        screenshot("32b_lab_env_studio_back")

        // ── Reflections tab — probe radius + Y sliders ────────────────────────
        tap("Reflections")
        screenshot("33_lab_probes_default")
        dragSlider("Probe Radius:", fraction = 1.0f)
        screenshot("33a_lab_probes_max_radius")
        dragSlider("Probe Y Position:", fraction = 1.0f)
        screenshot("33b_lab_probes_y_max")

        // ── Post-FX tab — SSAO / MSAA / FXAA / dithering switches ──────────────
        // Defaults: SSAO=on, MSAA=off, FXAA=on, Dithering=on (the SDK's library
        // defaults — FXAA & temporal dithering are cheap and noticeably improve
        // quality on mobile GPUs).
        tap("Post-FX")
        screenshot("34_lab_postFx_defaults")
        tap("SSAO (Ambient Occlusion)")      // SSAO → off
        screenshot("34a_lab_postFx_ssao_off")
        tap("MSAA (4x Multi-Sample)")        // MSAA → on
        screenshot("34b_lab_postFx_msaa_on")
        // Revert the two toggled switches back to their defaults.
        tap("SSAO (Ambient Occlusion)")
        tap("MSAA (4x Multi-Sample)")
        screenshot("35_lab_postFx_back_to_defaults")
    }

    // ── 8b. Materials — all 3 segmented tabs ──────────────────────────────────

    @Test
    fun materials_allTabs() {
        // #2239 Batch 4 — `texture-streaming` and `occlusion-material` consolidated
        // into the existing `materials` entry with a 3-way segmented toggle. One test
        // taps through every tab so each merged half is exercised (the unified demo
        // opens on its default PBR Materials tab).
        openDemo("materials")

        // ── PBR Materials tab (default landing tab) — KHR_materials_* chips ────
        screenshot("38_materials_pbr_default")

        // ── Streaming tab — runtime material-set swap on a sphere ─────────────
        tap("Streaming")
        screenshot("38a_materials_streaming_default")
        tap("Copper")
        screenshot("38b_materials_streaming_copper")
        tap("Matte Plastic")
        screenshot("38c_materials_streaming_plastic")

        // ── Occlusion tab — invisible depth-writing occluder plane ────────────
        // The "Occluder visible" Switch is the only interactive control; it sits in a
        // plain Row (not a `toggleable` row like the Post-FX switches), so `tap(text)`
        // can't reach the Switch from its sibling label. Capturing the default-state
        // frame is enough to exercise the merged Occlusion section here; the toggle
        // behaviour is verified visually during device-QA.
        tap("Occlusion")
        screenshot("38d_materials_occlusion_default")
    }

    // ── 9. Debug Overlay — preset reset ───────────────────────────────────────

    @Test
    fun debugOverlay_resetPreset() {
        // The "Show Overlay" toggle was removed when the demo became a stress-test
        // dashboard (the FPS/Frame/Nodes/Tris stats overlay is now always-on). The Reset
        // button is the only stable interactive control — we tap it to confirm the spawn
        // count drops back to the baseline preset without crashing the scene.
        openDemo("debug-overlay")
        screenshot("36_debugOverlay_initial")

        tap("Reset")
        screenshot("37_debugOverlay_after_reset")
    }

    // ── 10. Animation & Physics — both segmented tabs ─────────────────────────

    @Test
    fun animationPhysics_allTabs() {
        // #2239 Batch 3 — `animation` and `physics` consolidated into
        // `animation-physics` with a 2-way segmented toggle. One test taps through
        // both tabs so each merged half is exercised (the unified demo opens on its
        // default Animation tab).
        openDemo("animation-physics")

        // ── Animation tab (default landing tab) — loop / once / speed / playback ──
        screenshot("39_animation_loop_default")
        tap("Once")
        screenshot("40_animation_once")
        tap("Loop")
        screenshot("41_animation_loop_back")
        // Speed slider sweep — slow / fast
        dragSlider("Speed:", fraction = 0.0f); screenshot("41a_animation_speed_min")
        dragSlider("Speed:", fraction = 1.0f); screenshot("41b_animation_speed_max")
        // Play / Pause icon-only button — reached via contentDescription.
        tapByDesc("Pause"); screenshot("41c_animation_paused")
        tapByDesc("Play"); screenshot("41d_animation_playing")

        // ── Physics tab — drop + reset ────────────────────────────────────────
        tap("Physics")
        screenshot("41e_physics_initial")
        tap("Drop")
        Thread.sleep(1500)  // let physics settle
        screenshot("41f_physics_dropped_1")
        tap("Drop")
        Thread.sleep(400)
        tap("Drop")
        Thread.sleep(2000)
        screenshot("41g_physics_dropped_3")
        tap("Reset")
        Thread.sleep(1500)
        screenshot("41h_physics_reset")
    }

    // ── 11. Environment Gallery ───────────────────────────────────────────────
    // #2239 Batch 2 — `environment` consolidated into `lighting-lab` (Environment
    // tab). Covered by `lightingLab_allTabs` above, which taps the Environment tab
    // and cycles the HDR chips.

    // ── 12. Billboard — skipped until lib bug #XXX fixed ──────────────────────

    /**
     * **Library bug discovered by this test suite on 2026-04-23** — DO NOT UN-IGNORE until
     * fixed in `sceneview/` (BillboardNode / ImageNode teardown):
     *
     * ```
     * E Filament: Precondition in commit:240
     *   reason: Invalid texture still bound to MaterialInstance: 'Transparent Textured'
     * F libc: SIGABRT in io.github.sceneview.demo
     * ```
     *
     * Reproducer: just open BillboardDemo and close it (or toggle the visibility chip).
     * Root cause: when Compose drops the `BillboardNode` / `ImageNode` from the scene, its
     * `MaterialInstance` is destroyed while a texture is still bound to it. The unbind must
     * happen before destroy in the Node lifecycle.
     *
     * Visual validation of my framing fix (commit 34187a81) is confirmed elsewhere by the
     * Pixel 9 screenshot `tools/qa-screenshots/pixel9/final/12_billboard.png` — no need to
     * re-capture here.
     */
    @Test
    @org.junit.Ignore(
        "Filament UAF on visibility toggle — `Invalid texture still bound to " +
            "MaterialInstance` SIGABRT, see comment block above. Re-enable once the " +
            "BillboardNode / ImageNode teardown order is fixed in sceneview/. (#887)"
    )
    fun billboard_visibilityChips() {
        // #2239 Batch 1 — `billboard` consolidated into `two-d-in-three-d`.
        openDemo("two-d-in-three-d")
        tap("Billboard")
        screenshot("49_billboard_both_visible")
        tap("Billboard Panel"); screenshot("50_billboard_only_fixed")
        tap("Fixed Image"); screenshot("51_billboard_none")
    }

    // ── 13. Secondary Camera — 4 PiP angle chips ──────────────────────────────

    @Test
    fun secondaryCamera_pipAngles() {
        openDemo("secondary-camera")
        screenshot("53_secondaryCam_top_default")

        // Chip labels resolved from string resources (R.string.demo_secondary_camera_chip_*,
        // added in PR #1270) so the tap matcher stays in sync with the string resources.
        tap(context.getString(R.string.demo_secondary_camera_chip_side))
        screenshot("54_secondaryCam_side")

        tap(context.getString(R.string.demo_secondary_camera_chip_front))
        screenshot("55_secondaryCam_front")

        tap(context.getString(R.string.demo_secondary_camera_chip_corner))
        screenshot("56_secondaryCam_corner")

        tap(context.getString(R.string.demo_secondary_camera_chip_top))
        screenshot("57_secondaryCam_top_back")
    }

    // ── 14. Gesture Editing — editable switch + reset button ──────────────────

    @Test
    fun gestureEditing_editableAndReset() {
        // #2239 Batch 1 — `gesture-editing` and `camera-controls` consolidated
        // into `camera-gestures`. Open the unified demo and switch to the
        // "Node Gestures" tab before driving the editable / reset flow.
        openDemo("camera-gestures")
        // #2239 — exercise the Camera Modes tab (the absorbed `camera-controls` half)
        // before switching tabs: it is the default landing tab, so orbit the camera here
        // to confirm the manipulator works, then move to Node Gestures.
        orbit(pixels = 200); screenshot("57b_camera_modes_orbit")
        tap("Node Gestures")
        screenshot("58_gesture_editable_default")

        tap("Editable")
        screenshot("59_gesture_disabled")

        tap("Editable")
        screenshot("60_gesture_re_enabled")

        // Single-finger drag on the editable model translates it in screen space.
        orbit(pixels = 200); screenshot("60a_gesture_dragged_right")
        tilt(pixels = 150); screenshot("60b_gesture_dragged_down")

        // Two-finger pinch on the editable model scales it.
        pinch(open = true); screenshot("60c_gesture_scaled_up")
        pinch(open = false); screenshot("60d_gesture_scaled_down")

        tap("Reset Position")
        screenshot("61_gesture_after_reset")
    }

    // ── 15. Lines & Paths — chips + line-width slider (single screen open) ────

    @Test
    fun linesPaths_fullScreen() {
        openDemo("lines-paths")
        screenshot("62_linesPaths_both_default")

        tap("Line"); screenshot("63_linesPaths_no_line")
        tap("Path"); screenshot("64_linesPaths_none")
        tap("Line"); tap("Path"); screenshot("65_linesPaths_both_back")

        dragSlider("Line Width:", fraction = 1.0f); screenshot("70_linesPaths_width_max")
        dragSlider("Line Width:", fraction = 0.0f); screenshot("71_linesPaths_width_zero")
        dragSlider("Line Width:", fraction = 0.5f); screenshot("71c_linesPaths_width_mid")

        // Path Points slider sweep
        dragSlider("Path Points:", fraction = 0.0f); screenshot("71a_linesPaths_points_min")
        dragSlider("Path Points:", fraction = 1.0f); screenshot("71b_linesPaths_points_max")
    }

    // ── 18. Dynamic Sky ───────────────────────────────────────────────────────
    // #2239 Batch 2 — `dynamic-sky` consolidated into `lighting-lab` (Sky tab, the
    // default landing tab). Covered by `lightingLab_allTabs` above.

    // ── 19. Reflection Probes ─────────────────────────────────────────────────
    // #2239 Batch 2 — `reflection-probes` consolidated into `lighting-lab`
    // (Reflections tab). Covered by `lightingLab_allTabs` above.

    // ── 20. Image — scale slider ──────────────────────────────────────────────

    @Test
    fun image_scaleSlider() {
        // #2239 Batch 1 — `image` consolidated into `two-d-in-three-d`.
        openDemo("two-d-in-three-d")
        tap("Image")
        screenshot("79_image_default_scale")

        dragSlider("Scale:", fraction = 1.0f)
        screenshot("80_image_max_scale")

        dragSlider("Scale:", fraction = 0.0f)
        screenshot("81_image_min_scale")

        dragSlider("Scale:", fraction = 0.5f)
        screenshot("81a_image_mid_scale")
    }

    // ── 21. Text Labels — font-size slider ────────────────────────────────────

    @Test
    fun textLabels_fontSizeSlider() {
        // #2239 Batch 1 — `text` consolidated into `two-d-in-three-d` (Text is the
        // default landing tab, so no extra tap is needed before the slider drives).
        openDemo("two-d-in-three-d")
        screenshot("82_text_default")

        dragSlider("Font Size:", fraction = 1.0f)
        screenshot("83_text_max_size")

        dragSlider("Font Size:", fraction = 0.0f)
        screenshot("84_text_min_size")

        dragSlider("Font Size:", fraction = 0.5f)
        screenshot("84b_text_mid_size")

        // "Display Text" OutlinedTextField — the demo seeds it with "Hello SceneView",
        // so we look up the field by that current value (not the label) to get the input
        // itself rather than the floating label element.
        typeInto("Hello SceneView", "SceneView Works")
        Thread.sleep(600)
        screenshot("84a_text_custom_input")
    }

    // ── 22a. ViewNode — visible toggle + coord-tap on the in-scene card ──────

    @Test
    fun viewNode_visibleAndTapCounter() {
        // #3329 — `picking-collision`'s two tabs are now one scene: the shapes and the
        // Compose card share it, so there is no tab to switch to any more.
        openDemo("picking-collision")
        screenshot("88_viewNode_visible_default")

        // ViewNode's card is rendered inside a Compose hierarchy attached to the 3D-textured
        // quad, so UiAutomator still cannot *see* it — it has no node in the accessibility
        // tree of the host window. It can be driven by raw coordinates though: since #2845
        // SceneView converts the picking hit into a view pixel and dispatches the stream into
        // that Compose tree, so a click on the quad is a real click on the card (which is
        // `Card(onClick = …)`, with the "Tap me" Button inside it). We click three distinct
        // positions so three genuine up-events fire (tapping the same pixel back-to-back can
        // coalesce into a double-tap sequence on some gesture stacks); the counter must read 3.
        // The card now floats at world y = 0.52 above the shape row, with the eye pulled
        // back to 4.2 m (PickingLayout) — that projects to ~0.42 × h in the portrait frame.
        val cx = device.displayWidth / 2
        val cy = (device.displayHeight * 0.42).toInt()
        device.click(cx - 40, cy); Thread.sleep(500)
        device.click(cx, cy + 40); Thread.sleep(500)
        device.click(cx + 40, cy); Thread.sleep(700)
        screenshot("89_viewNode_tapped_3")

        tap("Compose card")
        screenshot("90_viewNode_hidden")

        tap("Compose card")
        screenshot("91_viewNode_visible_back")
    }

    // ── 22b. Video — just verify the scaffold + initial render ────────────────

    @Test
    fun video_initialRender() {
        // #2239 Batch 1 — `video` consolidated into `two-d-in-three-d`.
        openDemo("two-d-in-three-d")
        tap("Video")
        Thread.sleep(1500)  // let the video texture warm up
        screenshot("92_video_initial")

        // Play / Pause icon-only button (contentDescription toggles with state).
        // After openDemo the player is auto-playing so the button is in "Pause" state.
        tapByDesc("Pause"); screenshot("92a_video_paused")
        tapByDesc("Play"); screenshot("92b_video_resumed")
    }

    // ── 22c. Model Viewer — just verify the scaffold + initial render ────────

    @Test
    fun modelViewer_initialRender() {
        openDemo("model-viewer")
        screenshot("93_modelViewer_initial")
    }

    /**
     * Camera-gesture coverage for the Model Viewer demo — kept in its own test so the
     * cumulative drag + tilt state doesn't bleed into `modelViewer_initialRender`'s
     * pristine baseline screenshot.
     */
    @Test
    fun modelViewer_cameraGestures() {
        openDemo("model-viewer")

        // ── One-finger orbit (left/right) ────────────────────────────────────────────
        orbit(pixels = 400); screenshot("93a_modelViewer_orbit_right")
        orbit(pixels = -500); screenshot("93b_modelViewer_orbit_left")

        // Back to an initial-ish framing before pitching, so the tilt screenshots show
        // the model from above/below center (not a random place mid-orbit).
        orbit(pixels = 100)

        // ── One-finger tilt (up/down) ────────────────────────────────────────────────
        tilt(pixels = 250); screenshot("93c_modelViewer_tilt_down")
        tilt(pixels = -300); screenshot("93d_modelViewer_tilt_up")

        // ── Two-finger pinch (zoom-out only) ────────────────────────────────────────
        // pinchOpen on ModelViewer dollies the CameraManipulator straight into the
        // model — the pinch percent is relative to the *root window diagonal*, which is
        // larger than the viewport, so any >10 % spread tips the camera past the
        // helmet and the viewport clips to black even from a fresh scene. The
        // zoom-out direction works fine (camera dollies away), so we exercise just
        // that + a bounded orbit+pinchClose+reopen cycle for full coverage. The
        // zoom-in case is covered by the `gestureEditing_*` test which pinches a
        // model node (not a manipulator) — different code path.
        openDemo("model-viewer")
        pinch(open = false, percent = 0.25f); screenshot("93e_modelViewer_zoom_out")
    }

    // ── 23. Collision — reset-colors button + shape taps ──────────────────────

    @Test
    fun collision_shapeTapAndReset() {
        // #3329 — one scene now: the shape row and the Compose card share it, and the
        // row was pulled in to x = ±0.5 with the eye at 4.2 m so it stops being clipped
        // by the portrait viewport edges (see PickingLayout).
        openDemo("picking-collision")
        screenshot("85_collision_default")

        val w = device.displayWidth
        val h = device.displayHeight
        // Projected from PickingLayout for a phone-portrait frame (eye at z = 4.2 aimed at
        // y = 0.1, viewport ≈ 0.92 × h centred on 0.53 × h):
        //   spheres (world y = -0.05) → ≈ 0.57 × h
        //   cubes   (world y = -0.30) → ≈ 0.64 × h
        //   x = -0.5 → 0.22 | -0.25 → 0.36 | 0 → 0.50 | 0.25 → 0.64 | 0.5 → 0.78
        val sphereY = (h * 0.57).toInt()
        val cubeY   = (h * 0.64).toInt()
        device.click((w * 0.22).toInt(), cubeY);   Thread.sleep(300)  // cube   0
        device.click((w * 0.36).toInt(), sphereY); Thread.sleep(300)  // sphere 1
        device.click((w * 0.50).toInt(), cubeY);   Thread.sleep(300)  // cube   2
        device.click((w * 0.64).toInt(), sphereY); Thread.sleep(300)  // sphere 3
        device.click((w * 0.78).toInt(), cubeY);   Thread.sleep(400)  // cube   4
        screenshot("86_collision_after_taps")

        tap("Reset Colors")
        screenshot("87_collision_after_reset")
    }
}
