package io.github.sceneview.demo.demos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source contract for #4492 — how `DollhousePreview` and its screen are wired to
 * [io.github.sceneview.demo.demos.internal.DollhouseOpening].
 *
 * `DollhouseOpeningTest` proves the opening itself, played in `SceneView`'s frame order. It cannot
 * see the composable that drives it: `DollhousePreview` builds a Filament `ColorGrading`, a `View`
 * and a `SceneView`, none of which exist on the JVM, so it cannot be composed here. What a phone's
 * recording showed is pinned at the source level instead, the way
 * `ContactShadowResetRenderWiringTest` pins its own: each of these lines, moved or dropped, puts
 * back a first frame from another camera or a veil that never lifts.
 */
class DollhousePreviewWiringTest {

    // JVM tests run with the module directory as CWD.
    private val demos = File("src/main/java/io/github/sceneview/demo/demos")

    /** `DollhousePreview` only, from its signature to the next top-level declaration, on one line. */
    private val preview: String = File(demos, "DollhouseLayers.kt").readText()
        .substringAfter("internal fun DollhousePreview(", missingDelimiterValue = "")
        .substringBefore("\n}\n")
        .oneLine()

    private val screen: String = File(demos, "RoomDollhouseScreen.kt").readText().oneLine()

    private fun String.oneLine() = replace(Regex("""\s+"""), " ")

    @Test
    fun `the preview body is found`() {
        assertTrue(
            "DollhousePreview was renamed or moved — point this contract at it.",
            preview.contains("SceneView("),
        )
    }

    @Test
    fun `the camera is aimed from the composition, ahead of the scene's first frame`() {
        val aim = "SideEffect { opening.aim(orbit, home.value()) }"
        assertEquals(
            "DollhousePreview must aim the camera from one SideEffect.",
            1,
            preview.split(aim).size - 1,
        )
        assertTrue(
            "The SideEffect that aims the camera must stand before SceneView: aimed from onFrame " +
                "alone, the first frame is drawn from the orbit's default pose (#4492).",
            preview.indexOf(aim) < preview.indexOf("SceneView("),
        )
    }

    @Test
    fun `each frame is judged before the camera is aimed for the next one`() {
        assertTrue(
            "onFrame must ask the opening about the frame just drawn, call onShown when it says " +
                "so, then aim for the next frame — in that order.",
            preview.contains(
                "val framed = home.value() " +
                    "if (opening.drawn(orbit, framed)) shown.value() " +
                    "opening.aim(orbit, framed) }",
            ),
        )
        assertEquals(
            "SceneView's onFrame is the only one DollhousePreview may set.",
            1,
            Regex("""\bonFrame\s*=""").findAll(preview).count(),
        )
    }

    @Test
    fun `another room or another camera has an opening and a framing of its own`() {
        assertTrue(
            "The opening must be remembered per room bounds and per orbit: kept across rooms it " +
                "stays shown, and the next room is seen before it is framed.",
            preview.contains("val opening = remember(bounds, orbit) { DollhouseOpening() }"),
        )
        assertTrue(
            "The framing must be remembered per room bounds and per orbit.",
            preview.contains("val framing = remember(bounds, orbit) { DollhouseFraming("),
        )
    }

    @Test
    fun `the chrome's four sides reach the framing on their own sides`() {
        // ArDebugFramingFitTest catches a top and bottom swapped inside the fit; this is where
        // the bands are handed to it.
        assertTrue(
            "FramingInsets must take left and right over the view's width, top and bottom over " +
                "its height, each from the cover's matching side.",
            preview.contains(
                "FramingInsets( " +
                    "left = (cover.calculateLeftPadding(direction) + gutter) / maxWidth, " +
                    "top = (cover.calculateTopPadding() + gutter) / maxHeight, " +
                    "right = (cover.calculateRightPadding(direction) + gutter) / maxWidth, " +
                    "bottom = (cover.calculateBottomPadding() + gutter) / maxHeight, )",
            ),
        )
        assertTrue(
            "The view's shape must come from its constraints: the surface reports its size " +
                "after the composition, too late for the first frame.",
            preview.contains(
                "val aspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)",
            ),
        )
    }

    @Test
    fun `the screen's orbit does not drift, and starts afresh with its veil for each room`() {
        // DollhouseOpening counts frames on a framing that stands still; see the drifting-orbit
        // test in DollhouseOpeningTest for what a drift does to it.
        val keys = "remember(room, stage == DollhouseStage.Preview)"
        assertTrue(
            "RoomDollhouseScreen must make its orbit per room and per opening of the 3D view, " +
                "with drift = false: a drifting orbit never lifts the opening's veil.",
            screen.contains("val orbit = $keys { ArDebugOrbitCamera(drift = false) }"),
        )
        assertTrue(
            "previewShown must share the orbit's keys: another room starts veiled.",
            screen.contains("var previewShown by $keys { mutableStateOf(false) }"),
        )
        assertTrue(
            "The preview must report its opening to previewShown.",
            screen.contains("onShown = { previewShown = true }"),
        )
    }
}
