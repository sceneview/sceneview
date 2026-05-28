package io.github.sceneview.collision

/** Mathematical representation of a ray. Used to perform intersection and collision tests. */
class Ray {
    private var origin = Vector3()
    private var direction = Vector3.forward()

    /** Create a ray with an origin of (0,0,0) and a direction of Vector3.forward(). */
    constructor()

    /**
     * Create a ray with a specified origin and direction. The direction will automatically be
     * normalized.
     *
     * @param origin the ray's origin
     * @param direction the ray's direction
     */
    constructor(origin: Vector3, direction: Vector3) {
        Preconditions.checkNotNull(origin, "Parameter \"origin\" was null.")
        Preconditions.checkNotNull(direction, "Parameter \"direction\" was null.")

        setOrigin(origin)
        setDirection(direction)
    }

    /** Sets the ray origin. The vector is copied, not retained. */
    fun setOrigin(origin: Vector3) {
        Preconditions.checkNotNull(origin, "Parameter \"origin\" was null.")
        this.origin.set(origin)
    }

    /** Returns a copy of the ray origin. Mutating it does not affect the ray. */
    fun getOrigin(): Vector3 = Vector3(origin)

    /**
     * Returns the ray's backing origin vector **without copying**.
     *
     * Package-internal hot-path accessor for the collision math, which only reads the
     * components and never mutates the result. Eliminates the defensive [Vector3]
     * allocation [getOrigin] makes on every call — a per-triangle saving in
     * [MeshCollider.rayTriangleIntersection]. **Callers must treat the result as
     * read-only**; mutating it corrupts the ray. Public callers must use [getOrigin].
     */
    internal fun originRef(): Vector3 = origin

    /** Sets the ray direction. The value is normalized before being stored. */
    fun setDirection(direction: Vector3) {
        Preconditions.checkNotNull(direction, "Parameter \"direction\" was null.")
        this.direction.set(direction.normalized())
    }

    /** Returns a copy of the (normalized) ray direction. */
    fun getDirection(): Vector3 = Vector3(direction)

    /**
     * Returns the ray's backing (normalized) direction vector **without copying**.
     *
     * Package-internal hot-path accessor for the collision math, which only reads the
     * components and never mutates the result. Eliminates the defensive [Vector3]
     * allocation [getDirection] makes on every call — a per-triangle saving in
     * [MeshCollider.rayTriangleIntersection]. **Callers must treat the result as
     * read-only**; mutating it corrupts the ray. Public callers must use [getDirection].
     */
    internal fun directionRef(): Vector3 = direction

    /**
     * Returns the point along the ray at the given [distance] from the origin.
     *
     * Computed as `origin + direction * distance`. Since the direction is normalized,
     * [distance] is in the same units as the origin.
     */
    fun getPoint(distance: Float): Vector3 = Vector3.add(origin, direction.scaled(distance))

    override fun toString(): String = "[Origin:$origin, Direction:$direction]"
}
