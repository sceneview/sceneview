package io.github.sceneview.demo.auto

/**
 * Stage geometry, in metres. The podium's top face is `y = 0`: a model bottom-aligned on the
 * origin is parked on it. The floor the car drives on is [FLOOR_Y], one podium height lower.
 */
internal object GarageStage {
    /** Length every car is normalised to, so one camera framing fits all of them. */
    const val CAR_LENGTH = 4.4f
    const val PODIUM_RADIUS = 3.1f
    const val PODIUM_HEIGHT = 0.14f
    /** The light ring shows as a thin line around the podium's foot. */
    const val RING_RADIUS = PODIUM_RADIUS + 0.05f
    const val RING_HEIGHT = 0.03f
    /** Enough sides for a circle's edge to read as a circle at 1920 px. */
    const val ROUND_SIDES = 96

    // ── Floor ────────────────────────────────────────────────────────────────────────────────
    // Three stacked discs: the floor out to the dark, a disc of paint, and the driving floor on
    // top of it — what shows of the paint is the line that marks the edge of the road.
    const val FLOOR_RADIUS = 60f
    const val FLOOR_Y = -PODIUM_HEIGHT
    /** One layer of the stack: thick enough for the depth buffer to tell two layers apart. */
    const val FLOOR_LAYER = 0.02f
    const val EDGE_LINE_RADIUS = 27.2f
    const val EDGE_LINE_WIDTH = 0.2f

    // ── Road ─────────────────────────────────────────────────────────────────────────────────
    /** How far from the centre a car can drive: short of the edge line and of the pillars. */
    const val DRIVE_RADIUS = 25.5f
    /** Room kept between the podium and the centre of a car driving round it. */
    const val PODIUM_CLEARANCE = 1.7f
    /** The dashed line of the lane that circles the podium. */
    const val LANE_RADIUS = 15f
    const val LANE_DASHES = 28
    const val LANE_DASH_LENGTH = 1.5f
    const val LANE_DASH_WIDTH = 0.16f

    // ── Pillars ──────────────────────────────────────────────────────────────────────────────
    const val PILLAR_RING_RADIUS = 30f
    const val PILLARS = 14
    const val PILLAR_SIDE = 1.1f
    const val PILLAR_HEIGHT = 6f
    /** Height of the light band round each pillar. */
    const val PILLAR_BAND_Y = 2.4f
    const val PILLAR_BAND_HEIGHT = 0.1f
    /** How far the band stands out of the concrete, both sides together. */
    const val PILLAR_BAND_PROUD = 0.04f

    // ── Shadow and headlights, as fractions of a car's body length ───────────────────────────
    const val SHADOW_LENGTH_RATIO = 1.15f
    const val SHADOW_WIDTH_RATIO = 0.55f
    /** Dense: a faint shadow leaves the car hovering. */
    const val SHADOW_INTENSITY = 0.95f
    const val HEADLIGHT_FORWARD_RATIO = 0.42f
    const val HEADLIGHT_SIDE_RATIO = 0.15f
    const val HEADLIGHT_HEIGHT_RATIO = 0.15f
    /** Luminous power of one headlight, in lumens, and how far it carries. */
    const val HEADLIGHT_LUMENS = 36_000_000f
    const val HEADLIGHT_REACH = 45f
    /** Full-brightness and cut-off half-angles of the beam, in radians. */
    const val HEADLIGHT_CONE_INNER = 0.22f
    const val HEADLIGHT_CONE_OUTER = 0.55f
    const val HEADLIGHT_KELVIN = 5_200f
    /** Vertical part of the beam's direction, for one unit forward: dipped onto the road. */
    const val HEADLIGHT_DIP = -0.08f
    /** Share of a mood's key light kept once the headlights are on: they have to be the light. */
    const val HEADLIGHT_KEY_SHARE = 0.12f
}
