package io.github.sceneview.demo.demos

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugEvent
import io.github.sceneview.demo.demos.internal.ArDebugLogWriter
import io.github.sceneview.demo.demos.internal.DebugPose
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.RerunCapturePack
import io.github.sceneview.demo.demos.internal.RerunSyntheticBedroom
import io.github.sceneview.demo.demos.internal.RescanGuidance
import io.github.sceneview.demo.demos.internal.RescanKind
import io.github.sceneview.demo.demos.internal.RescanMergeTest
import io.github.sceneview.demo.demos.internal.RescanPose
import io.github.sceneview.demo.demos.internal.RescanRefusal
import io.github.sceneview.demo.demos.internal.RescanRegistrar
import io.github.sceneview.demo.demos.internal.RescanRegistrationTest
import io.github.sceneview.demo.demos.internal.RescanZone
import io.github.sceneview.demo.demos.internal.RescanZonesTest.Companion.filter
import io.github.sceneview.demo.demos.internal.RerunSessionSource
import io.github.sceneview.demo.demos.internal.RerunSessionStore
import io.github.sceneview.demo.demos.internal.ScanCopy
import io.github.sceneview.demo.demos.internal.ScanLens
import io.github.sceneview.demo.demos.internal.ScanSaveFailure
import io.github.sceneview.demo.demos.internal.ScanStageLayout
import io.github.sceneview.demo.demos.internal.ThumbCorner
import io.github.sceneview.demo.demos.internal.SvpcCodec
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.StageChrome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A part scanned again, live (#4736): the ray-cast room scanned with a stretch of its back wall
 * missed, and a second session that starts elsewhere, facing some other way, and looks at it.
 * The guide says it is lining up, then frames the part in amber where it stands in the second
 * session's own world and says how much of it is filled, then that it is covered; saving merges
 * the re-scan into a copy of the scan, or refuses another room. Each render run leaves the guide
 * in `build/rescan-renders/` (not committed).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RescanGuideTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val zone = RescanZone(RescanKind.MissingWall, RescanRegistrationTest.ZONE, 1.4f, "1.4 m of this wall was never seen.")

    private fun target() = RescanTarget(
        RescanPick(zone, listOf(zone), RescanRegistrationTest.scan),
        RescanMergeTest.capture(RescanRegistrationTest.scan),
        "Living room, completed",
    )

    /** The guide over a dark stand-in for the camera, the phone's eye [lens] already tracked. */
    private fun show(target: RescanTarget, lens: ScanLens, live: () -> DenseCloud? = { null }) {
        // The guide's frame loop never settles: frames are stepped by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = true) {
                CompositionLocalProvider(LocalStageChrome provides StageChrome.Media) {
                    Box(Modifier.fillMaxSize().background(StageChrome.Media.ground)) {
                        RescanGuide(target, live = live, lens = lens, drawn = true)
                    }
                }
            }
        }
        step()
    }

    private fun step() {
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    private fun line(text: String) = composeRule.onNodeWithTag(RESCAN_GUIDE_TAG).assertContentDescriptionEquals(text)

    @Test
    fun `the guide lines up, frames the part where it stands in the re-scan's world, and counts it filled`() {
        val offset = RescanRegistrationTest.OFFSETS.first()
        val lens = eye(offset)
        val target = target()
        show(target, lens)
        line(context.getString(R.string.scan_rescan_lining_up))
        composeRule.onNodeWithTag(RESCAN_GUIDE_FRAME_TAG).assertDoesNotExist()

        // Half the missed stretch scanned again: lined up, the frame drawn, the share said.
        val half = RescanRegistrationTest.rescan.filter { x, y, z -> !(RescanRegistrar.inside(ZONE, x, y, z, 0f) && x > HALF_X_M) }
        composeRule.runOnIdle { target.follow(RescanRegistrationTest.noisy(offset.inverse().moved(half), SEED)) }
        step()
        val partly = target.status as RescanStatus.Lined
        assertTrue("fill ${partly.fill}", partly.fill > MIN_PARTLY && !RescanGuidance.covered(partly.fill))
        line(context.getString(R.string.scan_rescan_fill, (partly.fill * PERCENT).roundToInt()))
        val image = composeRule.onNodeWithTag(RESCAN_GUIDE_FRAME_TAG).captureToImage().asAndroidBitmap()
        save(image, "rescan-guide-filling")
        assertFrameAt(image, offset, lens)

        // The whole stretch: covered.
        composeRule.runOnIdle { target.follow(RescanRegistrationTest.session(offset)) }
        step()
        val whole = target.status as RescanStatus.Lined
        assertTrue("fill ${whole.fill}", RescanGuidance.covered(whole.fill))
        line(context.getString(R.string.scan_rescan_covered))
        save(composeRule.onNodeWithTag(RESCAN_GUIDE_FRAME_TAG).captureToImage().asAndroidBitmap(), "rescan-guide-covered")
    }

    @Test
    fun `while the live 3D map is in its thumbnail, the guide's line stays clear of it, dark`() =
        stagedGuide(dark = true, mapFirst = false)

    @Test
    fun `while the live 3D map is in its thumbnail, the guide's line stays clear of it, light`() =
        stagedGuide(dark = false, mapFirst = false)

    @Test
    fun `swapped into the thumbnail, the guide is scaled with the camera and its line stays inside it, dark`() =
        stagedGuide(dark = true, mapFirst = true)

    @Test
    fun `swapped into the thumbnail, the guide is scaled with the camera and its line stays inside it, light`() =
        stagedGuide(dark = false, mapFirst = true)

    /**
     * A rescan's capture on the 3D Scan stage, the live map drawn as a plain surface: camera-first,
     * as a rescan starts, the guide's line must not sit under the map's thumbnail; swapped, the
     * guide is scaled into the camera's thumbnail and its line stays inside it.
     */
    private fun stagedGuide(dark: Boolean, mapFirst: Boolean) {
        val offset = RescanRegistrationTest.OFFSETS.first()
        val lens = eye(offset)
        val target = target()
        var staged: ScanStageLayout? = null
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalStageChrome provides StageChrome.Media) {
                    BoxWithConstraints(Modifier.fillMaxSize().background(StageChrome.Media.ground)) {
                        val width = constraints.maxWidth.toFloat()
                        val height = constraints.maxHeight.toFloat()
                        val layout = ScanStageLayout.of(
                            stageWidth = width,
                            stageHeight = height,
                            hudBottom = height * HUD_SHARE,
                            controlsTop = height * CONTROLS_SHARE,
                            mapFirst = mapFirst,
                            corner = ThumbCorner.BottomEnd,
                            margin = 0f,
                        )
                        staged = layout
                        val map = Modifier.fillMaxSize().stagePane({ layout?.map }, 0f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                        if (mapFirst) Box(map)
                        Box(Modifier.fillMaxSize().stagePane({ layout?.camera }, 0f)) {
                            RescanGuide(target, live = { null }, lens = lens, drawn = true)
                        }
                        if (!mapFirst) Box(map)
                    }
                }
            }
        }
        step()
        composeRule.runOnIdle { target.follow(RescanRegistrationTest.session(offset)) }
        step()
        val layout = staged!!
        val camera = layout.camera
        // The frame is drawn over the camera's own picture, scaled with it: it covers what the
        // camera's pane shows, cropped where the pane crops the picture.
        val frame = composeRule.onNodeWithTag(RESCAN_GUIDE_FRAME_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "frame at $frame, the camera at $camera",
            frame.left <= camera.left + 1f && frame.right >= camera.right - 1f &&
                frame.top <= camera.top + 1f && frame.bottom >= camera.bottom - 1f,
        )
        val line = composeRule.onNodeWithTag(RESCAN_GUIDE_TAG).fetchSemanticsNode().boundsInRoot
        if (mapFirst) {
            assertTrue(
                "line at $line, the camera's thumbnail at $camera",
                line.left >= camera.left - 1f && line.right <= camera.right + 1f &&
                    line.top >= camera.top - 1f && line.bottom <= camera.bottom + 1f,
            )
        } else {
            val map = layout.map
            assertTrue(
                "line at $line, the map's thumbnail at $map",
                line.right <= map.left + 1f || line.left >= map.right - 1f ||
                    line.bottom <= map.top + 1f || line.top >= map.bottom - 1f,
            )
        }
        val name = if (mapFirst) "swapped" else "staged"
        save(composeRule.onRoot().captureToImage().asAndroidBitmap(), "rescan-guide-$name-${if (dark) "dark" else "light"}")
    }

    @Test
    fun `the live view lines up on its own, every few seconds`() {
        val offset = RescanRegistrationTest.OFFSETS[1]
        val target = target()
        val live = RescanRegistrationTest.session(offset)
        show(target, eye(offset), live = { live })
        composeRule.mainClock.advanceTimeBy(FOLLOW_WAIT_MS)
        composeRule.waitUntil(LINING_UP_MS) { target.status is RescanStatus.Lined }
        step()
        line(context.getString(R.string.scan_rescan_covered))
    }

    @Test
    fun `a later view that does not line up keeps the last pose`() {
        val offset = RescanRegistrationTest.OFFSETS[2]
        val target = target()
        target.follow(RescanRegistrationTest.session(offset))
        val pose = (target.status as RescanStatus.Lined).pose
        // The phone turned to another room: refused, the session still lined up as it was.
        target.follow(RescanRegistrationTest.fuse(RerunSyntheticBedroom.scene, RerunSyntheticBedroom.poses()))
        assertEquals(pose, (target.status as RescanStatus.Lined).pose)
    }

    @Test
    fun `saving merges the re-scan into a copy of the scan, the scan untouched`() {
        val target = target()
        val manifest = target.pack.manifest.copyOf()
        val media = target.pack.media.copyOf()
        val saved = target.merge(RescanMergeTest.capture(RescanRegistrationTest.session(RescanRegistrationTest.OFFSETS.first())))
        val merged = (saved as RescanSave.Merged).pack
        assertTrue(merged !== target.pack)
        assertArrayEquals(manifest, target.pack.manifest)
        assertArrayEquals(media, target.pack.media)
        val opened = requireNotNull(merged.open())
        val dense = requireNotNull(opened.manifest.dense)
        val cloud = requireNotNull(opened.bytesOf(dense.path)?.let { SvpcCodec.decode(it) })
        assertTrue("${cloud.count} vs ${RescanRegistrationTest.scan.count}", cloud.count > RescanRegistrationTest.scan.count)
        // The added points fill the part: in the scan's frame, inside it.
        val added = (RescanRegistrationTest.scan.count until cloud.count).count { i ->
            RescanRegistrar.inside(ZONE, cloud.positions[i * 3], cloud.positions[i * 3 + 1], cloud.positions[i * 3 + 2], RescanRegistrar.OPEN_MARGIN_M)
        }
        assertEquals(cloud.count - RescanRegistrationTest.scan.count, added)
        assertTrue(target.status !is RescanStatus.Refused)
    }

    @Test
    fun `another room is refused at saving, and the guide says so`() {
        val target = target()
        show(target, eye(RescanPose.Identity))
        val bedroom = RescanRegistrationTest.fuse(RerunSyntheticBedroom.scene, RerunSyntheticBedroom.poses())
        val saved = composeRule.runOnIdle { target.merge(RescanMergeTest.capture(bedroom)) }
        assertTrue("$saved", saved is RescanSave.Refused && saved.why != null)
        step()
        line(context.getString(R.string.scan_rescan_refused))
        save(composeRule.onNodeWithTag(RESCAN_GUIDE_TAG).captureToImage().asAndroidBitmap(), "rescan-guide-refused")
    }

    @Test
    fun `a refused re-scan is refused again on Try again, and still saves as a separate scan`() {
        // What lost Thomas's scans on 4.62.0 (#4815): lining up is deterministic, so Try again
        // refused the same re-scan the same way, and Discard was the only way out.
        val target = target()
        val fused = RescanMergeTest.capture(RescanRegistrationTest.fuse(RerunSyntheticBedroom.scene, RerunSyntheticBedroom.poses()))
        // A recorded re-scan: its camera path in the log, beside its dense cloud.
        val path = ArDebugLogWriter.writeBytes(listOf(ArDebugEvent.CameraPose(1_000L, DebugPose(0f, 1.4f, 0f, 0f, 0f, 0f, 1f))))
        val bedroom = RerunCapturePack(fused.manifest, path, fused.media)
        val first = target.merge(bedroom) as RescanSave.Refused
        val again = target.merge(bedroom) as RescanSave.Refused
        assertEquals(first.why, again.why)
        // Save as a separate scan: the re-scan's own capture, never lined up, is kept and reopens.
        val store = RerunSessionStore(File(context.cacheDir, "rescan-separate").apply { deleteRecursively() })
        val saved = store.save(bedroom, "Scan · Oct 23", RerunSessionSource.Recorded)
        assertEquals(listOf(saved.id), store.list().map { it.id })
        val kept = requireNotNull(store.capture(saved.id))
        assertArrayEquals(bedroom.log, kept.log)
        assertArrayEquals(bedroom.media, kept.media)
        assertTrue(kept.open() != null)
        assertTrue(ScanCopy.rescanRefusedDetail(first.why).startsWith("This part didn't line up with your scan: "))
    }

    @Test
    fun `a part that did not line up says why on the card and saves as a separate scan, dark`() = refusedCard(dark = true)

    @Test
    fun `a part that did not line up says why on the card and saves as a separate scan, light`() = refusedCard(dark = false)

    /** The stage after Save on a re-scan of another room: the card alone, the guide gone (#4815). */
    private fun refusedCard(dark: Boolean) {
        val target = target()
        val bedroom = RescanMergeTest.capture(RescanRegistrationTest.fuse(RerunSyntheticBedroom.scene, RerunSyntheticBedroom.poses()))
        val why = (target.merge(bedroom) as RescanSave.Refused).why
        var separate = 0
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SceneViewDemoTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalStageChrome provides StageChrome.Media) {
                    Box(Modifier.fillMaxSize().background(StageChrome.Media.ground)) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                            ScanSaveFailedCard(
                                onDiscard = {},
                                onRetry = { separate++ },
                                failure = ScanSaveFailure.Refused,
                                refusal = why,
                            )
                        }
                    }
                }
            }
        }
        step()
        composeRule.onNodeWithText(ScanCopy.RESCAN_REFUSED_TITLE).assertExists()
        composeRule.onNodeWithText(ScanCopy.rescanRefusedDetail(why)).assertExists()
        composeRule.onNodeWithText(ScanCopy.TRY_AGAIN).assertDoesNotExist()
        composeRule.onNodeWithTag(SCAN_SAVE_RETRY_TAG).assertTextEquals(ScanCopy.SAVE_SEPARATE).performClick()
        assertEquals(1, separate)
        save(composeRule.onRoot().captureToImage().asAndroidBitmap(), "rescan-refused-card-${if (dark) "dark" else "light"}")
    }

    @Test
    fun `a re-scan with nothing to line up is refused, never merged`() {
        val target = target()
        val empty = RescanMergeTest.capture(DenseCloud.Empty)
        assertNull((target.merge(empty) as RescanSave.Refused).why)
        val bare = RescanRegistrationTest.session(RescanPose.Identity).let { DenseCloud(it.positions, it.colors) }
        assertEquals(RescanRefusal.NoNormals, (target.merge(RescanMergeTest.capture(bare)) as RescanSave.Refused).why)
        val noNormals = RescanTarget(RescanPick(zone, listOf(zone), DenseCloud(RescanRegistrationTest.scan.positions, RescanRegistrationTest.scan.colors)), target.pack, "")
        assertEquals(RescanRefusal.NoNormals, (noNormals.merge(target.pack) as RescanSave.Refused).why)
    }

    /**
     * The phone 3 m back from the back wall facing it, in the first session's frame, then taken
     * into the re-scan's own world, [offset] away, seen through [PROJECTION].
     */
    private fun eye(offset: RescanPose): ScanLens {
        val back = offset.inverse()
        val lens = ScanLens()
        lens.update { cameraToWorld, projection ->
            // Columns: right (−x), up (+y), back (−z: the eye looks down +z), position.
            val axes = listOf(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, -1f))
            for ((k, axis) in axes.withIndex()) {
                cameraToWorld[k * 4] = back.x(axis[0], axis[2]) - back.tx
                cameraToWorld[k * 4 + 1] = axis[1]
                cameraToWorld[k * 4 + 2] = back.z(axis[0], axis[2]) - back.tz
            }
            cameraToWorld[12] = back.x(EYE[0], EYE[2])
            cameraToWorld[13] = back.y(EYE[1])
            cameraToWorld[14] = back.z(EYE[0], EYE[2])
            cameraToWorld[15] = 1f
            PROJECTION.copyInto(projection)
        }
        return lens
    }

    /**
     * The amber on [image] spans the part's frame where it stands in the re-scan's world, [offset]
     * away from the scan, seen by [lens]: not where it stands in the scan's own frame.
     */
    private fun assertFrameAt(image: Bitmap, offset: RescanPose, lens: ScanLens) {
        val corners = offset.inverse().corners(ZONE)
        val edges = RescanGuidance.outline(corners, lens.cameraToWorld, lens.projection, image.width.toFloat(), image.height.toFloat())
        assertTrue(edges.isNotEmpty())
        val xs = edges.filterIndexed { i, _ -> i % 2 == 0 }
        val ys = edges.filterIndexed { i, _ -> i % 2 == 1 }
        val amber = io.github.sceneview.demo.theme.SceneViewTokens.ArOverlay.accentGuidance.toArgb()
        val pixels = IntArray(image.width * image.height)
        image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
        val found = pixels.indices.filter { close(pixels[it], amber) }
        assertTrue("only ${found.size} amber pixels", found.size > MIN_AMBER)
        val fx = found.map { it % image.width }
        val fy = found.map { it / image.width }
        val slack = SLACK_PX * context.resources.displayMetrics.density
        assertEquals("left", xs.min(), fx.min().toFloat(), slack)
        assertEquals("right", xs.max(), fx.max().toFloat(), slack)
        assertEquals("top", ys.min(), fy.min().toFloat(), slack)
        assertEquals("bottom", ys.max(), fy.max().toFloat(), slack)
    }

    private fun close(a: Int, b: Int) = (0 until 3).all { shift ->
        val bits = shift * BITS_PER_CHANNEL
        abs((a shr bits and CHANNEL) - (b shr bits and CHANNEL)) <= AMBER_SLACK
    }

    private fun save(image: Bitmap, name: String) {
        val out = File("build/rescan-renders").apply { mkdirs() }.resolve("$name.png")
        out.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        // Where the status card ends and the controls start, as shares of the stage.
        const val HUD_SHARE = 0.18f
        const val CONTROLS_SHARE = 0.84f
        val ZONE = RescanRegistrationTest.ZONE
        const val SEED = 4736
        const val HALF_X_M = 0.3f
        const val MIN_PARTLY = 0.2f
        const val PERCENT = 100f
        const val FOLLOW_WAIT_MS = 1_600L
        const val LINING_UP_MS = 30_000L
        const val MIN_AMBER = 50
        const val BITS_PER_CHANNEL = 8
        const val CHANNEL = 0xFF

        /** Antialiasing along the lines: their core lands within a few levels of the token. */
        const val AMBER_SLACK = 6

        /** The stroke's half width and its round caps, dp. */
        const val SLACK_PX = 4f

        /** Where the eye stands in the scan's frame: 3 m back from the back wall, at head height. */
        val EYE = floatArrayOf(0.3f, 1.4f, -1.2f)

        /** A wide perspective on a phone's tall view, 0.05 m to 20 m, column-major. */
        val PROJECTION = run {
            val fx = 1.2f
            val fy = fx * 393f / 851f
            val n = 0.05f
            val f = 20f
            floatArrayOf(fx, 0f, 0f, 0f, 0f, fy, 0f, 0f, 0f, 0f, -(f + n) / (f - n), -1f, 0f, 0f, -2f * f * n / (f - n), 0f)
        }
    }
}
