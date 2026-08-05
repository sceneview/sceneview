package io.github.erkko68.filament.utils

/**
 * Helper that enables camera interaction similar to sketchfab or Google Maps.
 *
 * Clients notify the manipulator of various mouse or touch events, then periodically call
 * [getLookAt] so that they can adjust their camera. Three modes are supported: [Mode.ORBIT],
 * [Mode.MAP], and [Mode.FLIGHT]. To construct a manipulator, use [Builder] and pass the desired
 * mode to [Builder.build].
 *
 * Usage example:
 * ```kotlin
 * val manip = Manipulator.Builder()
 *     .viewport(1024, 768)
 *     .build(Manipulator.Mode.ORBIT)
 *
 * // In mouse-down handler:
 * manip.grabBegin(x, y, false)
 *
 * // In mouse-move handler:
 * manip.grabUpdate(x, y)
 *
 * // In mouse-up handler:
 * manip.grabEnd()
 *
 * // Each frame:
 * val eye = FloatArray(3); val target = FloatArray(3); val up = FloatArray(3)
 * manip.getLookAt(eye, target, up)
 * camera.lookAt(eye[0], eye[1], eye[2], target[0], target[1], target[2], up[0], up[1], up[2])
 * ```
 *
 * @see Bookmark
 */
expect class Manipulator {
    /** Camera manipulation mode. */
    enum class Mode {
        /** Rotates and dollies around a target point. */
        ORBIT,
        /** Pans and zooms over a flat ground plane. */
        MAP,
        /** First-person free-flight camera. */
        FLIGHT
    }

    /** The axis held constant when the viewport changes in MAP mode. */
    enum class Fov {
        VERTICAL,
        HORIZONTAL
    }

    /**
     * Keys used to translate the camera in [Mode.FLIGHT] mode.
     *
     * FORWARD and BACKWARD dolly the camera forwards and backwards.
     * LEFT and RIGHT strafe the camera left and right.
     * UP and DOWN boom the camera upwards and downwards.
     */
    enum class Key { FORWARD, LEFT, BACKWARD, RIGHT, UP, DOWN }

    /**
     * Builder for [Manipulator] instances.
     */
    class Builder() {
        /** Width and height of the viewing area. */
        fun viewport(width: Int, height: Int): Builder

        /** World-space position of interest; defaults to (0, 0, 0). */
        fun targetPosition(x: Float, y: Float, z: Float): Builder

        /** Orientation for the home position; defaults to (0, 1, 0). */
        fun upVector(x: Float, y: Float, z: Float): Builder

        /** Multiplied with scroll delta; defaults to 0.01. */
        fun zoomSpeed(speed: Float): Builder

        /** Initial eye position in world space for [Mode.ORBIT]; defaults to (0, 0, 1). */
        fun orbitHomePosition(x: Float, y: Float, z: Float): Builder

        /** Multiplied with viewport delta for [Mode.ORBIT]; defaults to 0.01. */
        fun orbitSpeed(x: Float, y: Float): Builder

        /** The axis held constant when viewport changes in [Mode.MAP]. */
        fun fovDirection(fov: Fov): Builder

        /** The full field of view in degrees (not half-angle) for [Mode.MAP]. */
        fun fovDegrees(degrees: Float): Builder

        /** The distance to the far plane for [Mode.MAP]. */
        fun farPlane(distance: Float): Builder

        /** The ground size for computing home position in [Mode.MAP]. */
        fun mapExtent(width: Float, height: Float): Builder

        /** Constrains the zoom-in level in [Mode.MAP]. */
        fun mapMinDistance(distance: Float): Builder

        /** Initial eye position in world space for [Mode.FLIGHT]; defaults to (0, 0, 0). */
        fun flightStartPosition(x: Float, y: Float, z: Float): Builder

        /** Initial pitch and yaw orientation in radians for [Mode.FLIGHT]; defaults to (0, 0). */
        fun flightStartOrientation(pitch: Float, yaw: Float): Builder

        /** Maximum camera speed in world units per second for [Mode.FLIGHT]; defaults to 10. */
        fun flightMaxMoveSpeed(maxSpeed: Float): Builder

        /** Number of speed steps adjustable with the scroll wheel in [Mode.FLIGHT]; defaults to 80. */
        fun flightSpeedSteps(steps: Int): Builder

        /** Multiplied with viewport delta for panning in [Mode.FLIGHT]; defaults to (0.01, 0.01). */
        fun flightPanSpeed(x: Float, y: Float): Builder

        /**
         * Applies a deceleration to camera movement in [Mode.FLIGHT]; defaults to 0 (no damping).
         *
         * Lower values give slower damping times. A good default is 15. Too high a value may
         * lead to instability.
         */
        fun flightMoveDamping(damping: Float): Builder

        /** Plane equation (ax + by + cz + d = 0) used as a raycast fallback for grab-and-pan. */
        fun groundPlane(a: Float, b: Float, c: Float, d: Float): Builder

        /** Sets whether panning is enabled. */
        fun panning(enabled: Boolean): Builder

        /**
         * Creates a new camera manipulator in the specified mode.
         *
         * @param mode the interaction mode: [Mode.ORBIT], [Mode.MAP], or [Mode.FLIGHT]
         * @return a new [Manipulator] instance
         */
        fun build(mode: Mode): Manipulator
    }

    /** Destroys the manipulator and releases all resources. */
    fun destroy()

    /**
     * Gets the immutable mode of the manipulator.
     *
     * @return the [Mode] this manipulator was created with
     */
    fun getMode(): Mode

    /**
     * Sets the viewport dimensions. The manipulator uses this to process grab events and raycasts.
     *
     * @param width the viewport width in pixels
     * @param height the viewport height in pixels
     */
    fun setViewport(width: Int, height: Int)

    /**
     * Gets the current orthonormal basis; this is usually called once per frame.
     *
     * @param outEye float array of size ≥ 3 filled with the eye position in world space
     * @param outTarget float array of size ≥ 3 filled with the target position in world space
     * @param outUp float array of size ≥ 3 filled with the up vector in world space
     */
    fun getLookAt(outEye: FloatArray, outTarget: FloatArray, outUp: FloatArray)

    /**
     * Given a viewport coordinate, picks a point in the ground plane, or in the actual scene if
     * a raycast callback was configured.
     *
     * @param x X-coordinate in viewport space
     * @param y Y-coordinate in viewport space
     * @param outResult float array of size ≥ 3 filled with the world-space intersection point
     */
    fun raycast(x: Int, y: Int, outResult: FloatArray)

    /**
     * Starts a grabbing session (i.e. the user begins dragging in the viewport).
     *
     * In [Mode.MAP] mode, this starts a panning session.
     * In [Mode.ORBIT] mode, this starts either rotating or strafing.
     * In [Mode.FLIGHT] mode, this starts a nodal panning session.
     *
     * @param x X-coordinate for the point of interest in viewport space
     * @param y Y-coordinate for the point of interest in viewport space
     * @param strafe [Mode.ORBIT] only: if true, starts a translation rather than a rotation
     */
    fun grabBegin(x: Int, y: Int, strafe: Boolean)

    /**
     * Updates a grabbing session.
     *
     * Must be called at least once between [grabBegin] and [grabEnd] to dirty the camera.
     *
     * @param x current X-coordinate in viewport space
     * @param y current Y-coordinate in viewport space
     */
    fun grabUpdate(x: Int, y: Int)

    /**
     * Ends a grabbing session.
     */
    fun grabEnd()

    /**
     * Signals that a key is now in the down state.
     *
     * In [Mode.FLIGHT] mode, the camera is translated forward/backward and strafed left/right
     * depending on the depressed keys, enabling WASD-style movement.
     *
     * @param key the key that was pressed
     */
    fun keyDown(key: Key)

    /**
     * Signals that a key is now in the up state.
     *
     * @param key the key that was released
     * @see keyDown
     */
    fun keyUp(key: Key)

    /**
     * In [Mode.MAP] and [Mode.ORBIT] modes, dollys the camera along the viewing direction.
     * In [Mode.FLIGHT] mode, adjusts the move speed of the camera.
     *
     * @param x X-coordinate for the point of interest in viewport space; ignored in [Mode.FLIGHT]
     * @param y Y-coordinate for the point of interest in viewport space; ignored in [Mode.FLIGHT]
     * @param delta in [Mode.MAP] and [Mode.ORBIT]: negative means "zoom in", positive means "zoom out";
     *              in [Mode.FLIGHT]: negative means "slower", positive means "faster"
     */
    fun scroll(x: Int, y: Int, delta: Float)

    /**
     * Processes input and updates internal state.
     *
     * Must be called once every frame before [getLookAt] is valid.
     *
     * @param deltaTime the amount of time in seconds passed since the previous call to update
     */
    fun update(deltaTime: Float)

    /**
     * Gets a handle that can be used to reset the manipulator back to its current position.
     *
     * @return a [Bookmark] representing the current camera state
     * @see jumpToBookmark
     */
    fun getCurrentBookmark(): Bookmark

    /**
     * Gets a handle that can be used to reset the manipulator back to its home position.
     *
     * @return a [Bookmark] representing the home camera state
     * @see jumpToBookmark
     */
    fun getHomeBookmark(): Bookmark

    /**
     * Sets the manipulator position and orientation back to a previously stashed state.
     *
     * @param bookmark a [Bookmark] obtained from [getCurrentBookmark] or [getHomeBookmark]
     * @see getCurrentBookmark
     * @see getHomeBookmark
     */
    fun jumpToBookmark(bookmark: Bookmark)

    /** Opaque handle to a viewing position and orientation, used to animate the camera. */
    class Bookmark
}
