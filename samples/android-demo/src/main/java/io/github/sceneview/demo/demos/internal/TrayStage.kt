package io.github.sceneview.demo.demos.internal

import androidx.compose.ui.graphics.Color

/**
 * The look of the `rolling-balls` board: a pale maple playing field inside a rounded walnut frame,
 * both lacquered — the wooden tilt-maze board everyone has held, which is what a tray of balls to
 * tip reads as at a glance. Chrome, glass and bright rubber balls roll on it.
 *
 * The values are `DESIGN.md`'s `stage-tray-*` rows. Like `stage-lighting-floor` they are fixed in
 * both themes: the board is an object on the stage, not a themed surface — only the stage sky and
 * the floor under it follow light and dark.
 *
 * The wood is drawn by `tray_wood.filamat`, a procedural grain between two tones per species, so
 * each species is an early (pale, between the rings) and a late (dark, the rings) colour.
 */
object TrayStage {

    /** `stage-tray-maple-early` / `-late` — the playing field. Pale enough for every ball to read on. */
    val MAPLE_EARLY: Color = Color(0xFFE6CDA3)
    val MAPLE_LATE: Color = Color(0xFFC39A63)

    /** `stage-tray-walnut-early` / `-late` — the frame, dark so the field is the lit stage. */
    val WALNUT_EARLY: Color = Color(0xFF6E452B)
    val WALNUT_LATE: Color = Color(0xFF2E1B12)

    /** Growth rings per metre across the grain: maple is fine and tight, walnut broader. */
    const val MAPLE_RING_DENSITY: Float = 34f
    const val WALNUT_RING_DENSITY: Float = 22f

    /** Satin wood under a gloss lacquer: the studio's softboxes streak across the board. */
    const val WOOD_ROUGHNESS: Float = 0.55f
    const val LACQUER: Float = 0.8f
    const val LACQUER_ROUGHNESS: Float = 0.12f

    /** `stage-tray-steel` — the chrome ball: near-neutral, so it mirrors the studio. */
    val STEEL_COLOR: Color = Color(0xFFD7DCE3)
    const val STEEL_ROUGHNESS: Float = 0.06f

    /** `stage-tray-glass` — the glass marble: a pale aqua tint on the light passing through. */
    val GLASS_COLOR: Color = Color(0xFFBFE6EA)
    const val GLASS_ROUGHNESS: Float = 0.02f
    const val GLASS_IOR: Float = 1.5f

    /**
     * `stage-tray-rubber-*` — the rubber balls, one colour after the other, so a tray full of them
     * is a handful of sweets rather than one flat mass. All five hold on the maple field and on the
     * dark walnut.
     */
    val RUBBER_COLORS: List<Color> = listOf(
        Color(0xFFF2654B), // coral
        Color(0xFF2E86F0), // azure
        Color(0xFFF5B029), // amber
        Color(0xFF2FBF8F), // mint
        Color(0xFF9B5DE5), // orchid
    )
    const val RUBBER_ROUGHNESS: Float = 0.42f
    const val RUBBER_COAT: Float = 0.6f
    const val RUBBER_COAT_ROUGHNESS: Float = 0.18f

    /** Thickness of the playing field; its top face is the simulation floor. */
    const val FIELD_THICKNESS: Float = 0.02f

    /** Width of the frame, from the field's edge outward. */
    const val RIM_WIDTH: Float = 0.1f

    /** Height of the frame above the field. The collision rails hold at any height; this is the look. */
    const val RIM_HEIGHT: Float = 0.1f

    /** Radius the frame's top edges and outer corners are rounded to. */
    const val RIM_EDGE_RADIUS: Float = 0.022f

    /** Height of the wooden body under the field. */
    const val BODY_HEIGHT: Float = 0.07f
}
