package io.github.erkko68.filament

/**
 * An axis-aligned 3D box represented by its center and half-extent.
 */
class Box(
    /** Center of the 3D box */
    val center: FloatArray = FloatArray(3),
    /** Half extent from the center on all 3 axes */
    val halfExtent: FloatArray = FloatArray(3)
) {
    constructor() : this(FloatArray(3), FloatArray(3))

    constructor(centerX: Float, centerY: Float, centerZ: Float, halfExtentX: Float, halfExtentY: Float, halfExtentZ: Float) : this(
        floatArrayOf(centerX, centerY, centerZ),
        floatArrayOf(halfExtentX, halfExtentY, halfExtentZ)
    )

    /**
     * Sets the center of the box to the given coordinates.
     *
     * @param x X-coordinate of the center
     * @param y Y-coordinate of the center
     * @param z Z-coordinate of the center
     */
    fun setCenter(x: Float, y: Float, z: Float) {
        center[0] = x
        center[1] = y
        center[2] = z
    }

    /**
     * Sets the half-extent of the box on all axes.
     *
     * @param x Half-extent on the X-axis
     * @param y Half-extent on the Y-axis
     * @param z Half-extent on the Z-axis
     */
    fun setHalfExtent(x: Float, y: Float, z: Float) {
        halfExtent[0] = x
        halfExtent[1] = y
        halfExtent[2] = z
    }

    /**
     * Computes the lowest coordinates corner of the box.
     * @return [Float] array representing center - halfExtent
     */
    val min: FloatArray get() = floatArrayOf(center[0] - halfExtent[0], center[1] - halfExtent[1], center[2] - halfExtent[2])

    /**
     * Computes the largest coordinates corner of the box.
     * @return [Float] array representing center + halfExtent
     */
    val max: FloatArray get() = floatArrayOf(center[0] + halfExtent[0], center[1] + halfExtent[1], center[2] + halfExtent[2])
}
