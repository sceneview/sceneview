package io.github.sceneview.demo.demos.internal

import androidx.compose.ui.graphics.Color

/**
 * The look of the `rolling-balls` table (#4180): a felt bed inside a lacquered wooden rim with a
 * brass inlay, on a wooden body — a games table, which is what a tray of balls to knock around
 * reads as at a glance.
 *
 * The values are `DESIGN.md`'s `stage-tray-*` rows. Like `stage-lighting-floor` they are fixed in
 * both themes: the table is an object on the stage, not a themed surface — only the stage sky
 * behind it follows light and dark.
 *
 * The felt is a deep green because it is the one ground the three balls all read on: the blue
 * rubber, the chrome steel (which mirrors the studio, not the felt) and the lilac foam. A navy
 * bed — the stage's own hue — would swallow the blue rubber ball.
 */
object TrayStage {

    /** `stage-tray-felt` — the playing surface. */
    val FELT_COLOR: Color = Color(0xFF1E6B52)
    const val FELT_ROUGHNESS: Float = 0.95f

    /** Felt scatters, it does not mirror: a low reflectance keeps the studio's highlights off it. */
    const val FELT_REFLECTANCE: Float = 0.2f

    /** `stage-tray-rim` — a dark walnut under a glossy lacquer: rim and body. */
    val RIM_COLOR: Color = Color(0xFF2E1B12)
    const val RIM_ROUGHNESS: Float = 0.24f
    const val RIM_REFLECTANCE: Float = 0.6f

    /** `stage-tray-inlay` — the brass line along the top of the rim, the table's edge in the dark theme. */
    val INLAY_COLOR: Color = Color(0xFFC9A45C)
    const val INLAY_ROUGHNESS: Float = 0.3f

    /** `stage-tray-steel` — the steel ball: near-neutral chrome, so it mirrors the studio. */
    val STEEL_COLOR: Color = Color(0xFFD7DCE3)

    /** Thickness of the felt bed; its top face is the simulation floor. */
    const val FELT_THICKNESS: Float = 0.02f

    /** Width of the rim, from the felt's edge outward. */
    const val RIM_WIDTH: Float = 0.1f

    /** Height of the rim above the felt. The collision rails hold at any height; this is the look. */
    const val RIM_HEIGHT: Float = 0.1f

    /** Height of the wooden body under the felt. */
    const val BODY_HEIGHT: Float = 0.07f

    /** Cross-section of the brass inlay line. */
    const val INLAY_WIDTH: Float = 0.014f
    const val INLAY_HEIGHT: Float = 0.004f
}
