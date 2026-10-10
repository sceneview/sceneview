package io.github.sceneview.demo.auto

/**
 * Stage geometry, in metres. The floor the cars stand on is `y = 0`; everything the garage
 * builds sits at or under it, so a model bottom-aligned on the origin is on the podium.
 */
internal object GarageStage {
    /** Length every car is normalised to, so one camera framing fits all of them. */
    const val CAR_LENGTH = 4.4f
    const val PODIUM_RADIUS = 3.1f
    const val PODIUM_HEIGHT = 0.14f
    /** The light ring shows as a thin line around the podium's foot. */
    const val RING_RADIUS = PODIUM_RADIUS + 0.05f
    const val RING_HEIGHT = 0.03f
    const val FLOOR_RADIUS = 30f
    const val FLOOR_HEIGHT = 0.02f
    /** Enough sides for the podium's edge to read as a circle at 1920 px. */
    const val ROUND_SIDES = 96
    /** The contact shadow is a soft ellipse, a little larger than the car's footprint. */
    const val SHADOW_LENGTH = CAR_LENGTH * 1.15f
    const val SHADOW_WIDTH = CAR_LENGTH * 0.55f
}
