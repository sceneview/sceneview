package io.github.erkko68.filament

/**
 * Camera represents the eye(s) through which the scene is viewed.
 *
 * A Camera has a position and orientation and controls projection and exposure.
 * For stereoscopic rendering, a Camera maintains two separate "eyes" (Eye 0 and Eye 1).
 *
 * **Coordinate system:** Camera points towards its -Z axis; +Y is up, +X is right.
 * Near/far planes are at -distance(near) and -distance(far) in view space.
 *
 * **Depth-buffer precision:** Near plane distance greatly affects depth precision.
 * Use the largest possible near distance; typical ratios: 1:100 to 1:100000 (near:far).
 *
 * **Exposure:** Camera exposure (aperture, shutter speed, sensitivity) controls overall
 * scene brightness, interacting with light intensities just like a real camera.
 *
 * **Stereoscopic rendering:** The Camera's transform defines "head" space. Each eye's
 * transform relative to head space is set via [setEyeModelMatrix], and each eye can have
 * its own projection matrix via [setCustomEyeProjection].
 */
expect class Camera {
    /**
     * Projection type for the camera frustum.
     * - PERSPECTIVE: Objects get smaller as they are farther (realistic 3D)
     * - ORTHO: Parallel projection; preserves distances (isometric/2D)
     */
    enum class Projection { PERSPECTIVE, ORTHO }

    /**
     * Field-of-view axis direction.
     * - VERTICAL: FOV is measured on the vertical axis
     * - HORIZONTAL: FOV is measured on the horizontal axis
     */
    enum class Fov { VERTICAL, HORIZONTAL }
 
    /**
     * Set the projection matrix from six frustum planes.
     *
     * The near and far planes' positions in view space are z = -near and z = -far.
     *
     * @param projection PERSPECTIVE or ORTHO
     * @param left Distance from camera to left plane at near plane
     * @param right Distance from camera to right plane at near plane
     * @param bottom Distance from camera to bottom plane at near plane
     * @param top Distance from camera to top plane at near plane
     * @param near Distance from camera to near plane (> 0 for PERSPECTIVE, != far for ORTHO)
     * @param far Distance from camera to far plane (> near for PERSPECTIVE, != near for ORTHO)
     */
    fun setProjection(projection: Projection, left: Double, right: Double, bottom: Double, top: Double, near: Double, far: Double)

    /**
     * Set the projection matrix from field-of-view and aspect ratio.
     *
     * Helper method to set a standard perspective or orthographic projection.
     *
     * @param fovInDegrees Full field-of-view in degrees (0 < fov < 180)
     * @param aspect Aspect ratio (width / height)
     * @param near Distance to near plane (> 0)
     * @param far Distance to far plane (> near)
     * @param direction Axis on which fovInDegrees is measured (VERTICAL or HORIZONTAL)
     */
    fun setProjection(fovInDegrees: Double, aspect: Double, near: Double, far: Double, direction: Fov)

    /**
     * Set the projection matrix from focal length (lens-based approach).
     *
     * Useful for matching real camera parameters. Assumes a 35mm reference sensor.
     *
     * @param focalLength Focal length in millimeters (> 0)
     * @param aspect Aspect ratio (width / height)
     * @param near Distance to near plane (> 0)
     * @param far Distance to far plane (> near)
     */
    fun setLensProjection(focalLength: Double, aspect: Double, near: Double, far: Double)

    /**
     * Set a custom projection matrix used for both rendering and culling.
     *
     * The matrix must define NDC as [-1, 1] on all axes (OpenGL convention).
     * For rendering, the far plane is internally set to infinity.
     * For culling, the specified far distance is used.
     *
     * @param matrix Custom projection matrix (4×4, row-major or column-major per platform)
     * @param near Distance to near plane in world units
     * @param far Distance to far plane in world units (!= near)
     */
    fun setCustomProjection(matrix: DoubleArray, near: Double, far: Double)

    /**
     * Set custom projection matrices for rendering and culling separately.
     *
     * Both matrices must define NDC as [-1, 1] on all axes (OpenGL convention).
     * The rendering matrix has its far plane set to infinity internally.
     *
     * @param matrix Custom projection matrix for rendering
     * @param matrixForCulling Custom projection matrix for culling (must be finite)
     * @param near Distance to near plane in world units
     * @param far Distance to far plane in world units (!= near)
     */
    fun setCustomProjection(matrix: DoubleArray, matrixForCulling: DoubleArray, near: Double, far: Double)

    /**
     * Set custom projection matrices for stereoscopic rendering (each eye can have different projection).
     *
     * All projection matrices must define NDC as [-1, 1] on all axes (OpenGL convention).
     * The projectionForCulling matrix must encompass the frustums of all eyes.
     * Call with all eye projections simultaneously.
     *
     * @param projection Array of projection matrices (one per eye)
     * @param count Size of projection array (must be >= stereoscopicEyeCount)
     * @param projectionForCulling Unified culling frustum encompassing all eyes
     * @param near Distance to near plane in world units
     * @param far Distance to far plane in world units (!= near)
     */
    fun setCustomEyeProjection(projection: DoubleArray, count: Int, projectionForCulling: DoubleArray, near: Double, far: Double)

    /**
     * Set the position of an individual eye relative to the camera (head) space.
     *
     * By default, both eyes' transforms are identity.
     *
     * Example: position Eye 0 3cm left, Eye 1 3cm right:
     * ```
     * camera.setEyeModelMatrix(0, floatArrayOf(-0.03f, 0f, 0f, ...)) // left eye
     * camera.setEyeModelMatrix(1, floatArrayOf(0.03f, 0f, 0f, ...))  // right eye
     * ```
     *
     * Call [setModelMatrix] to update the head position, not this method per-frame.
     *
     * @param eyeId Eye index (must be < stereoscopicEyeCount)
     * @param modelMatrix 4×4 model matrix for this eye relative to camera space
     */
    fun setEyeModelMatrix(eyeId: Int, modelMatrix: DoubleArray)

    /**
     * Apply a 2D scaling to the projection matrix after projection.
     *
     * Useful for adjusting aspect ratio independent of projection.
     * Example: for Fov.HORIZONTAL with aspect w/h:
     * ```
     * setProjection(fov, 1.0, near, far, Fov.HORIZONTAL)
     * setScaling(1.0, aspect)
     * ```
     *
     * Default: identity (1.0, 1.0).
     *
     * @param x Horizontal scale factor
     * @param y Vertical scale factor
     */
    fun setScaling(x: Double, y: Double)

    /**
     * Get the 2D scaling factors applied to the projection matrix.
     * @param out Optional DoubleArray of at least 2 elements [x, y]; created if null
     * @return The out array with scaling factors
     */
    fun getScaling(out: DoubleArray? = null): DoubleArray

    /**
     * Apply a 2D translation shift to the projection matrix after projection.
     *
     * Shift is specified in NDC coordinates ([-1, 1] range).
     * If shifting by pixels: scale by 1.0 / viewport dimensions.
     *
     * Default: identity (0.0, 0.0).
     *
     * @param x Horizontal shift in NDC
     * @param y Vertical shift in NDC
     */
    fun setShift(x: Double, y: Double)

    /**
     * Get the 2D translation shift applied to the projection matrix.
     * @param out Optional DoubleArray of at least 2 elements [x, y]; created if null
     * @return The out array with shift offsets
     */
    fun getShift(out: DoubleArray? = null): DoubleArray

    /**
     * Set the camera's model matrix (position and orientation) using lookAt semantics.
     *
     * Helper method to position the camera by specifying eye position, target, and up vector.
     *
     * @param eyeX Camera position X (world space)
     * @param eyeY Camera position Y (world space)
     * @param eyeZ Camera position Z (world space)
     * @param centerX Look-at target X (world space)
     * @param centerY Look-at target Y (world space)
     * @param centerZ Look-at target Z (world space)
     * @param upX Camera up vector X (should be normalized)
     * @param upY Camera up vector Y (should be normalized)
     * @param upZ Camera up vector Z (should be normalized)
     */
    fun lookAt(eyeX: Double, eyeY: Double, eyeZ: Double, centerX: Double, centerY: Double, centerZ: Double, upX: Double, upY: Double, upZ: Double)

    /**
     * Set the camera's model matrix (position and orientation).
     *
     * Equivalent to updating the camera entity's TransformManager component.
     * The camera points towards its -Z axis. The matrix must be a rigid transform.
     *
     * @param modelMatrix 4×4 model matrix (rigid transform) in world space
     */
    fun setModelMatrix(modelMatrix: FloatArray)

    /**
     * Set the camera's model matrix (position and orientation).
     * @param modelMatrix 4×4 model matrix (rigid transform) in world space
     */
    fun setModelMatrix(modelMatrix: DoubleArray)
    
    /**
     * Get the projection matrix used for rendering (far plane set to infinity).
     *
     * This may differ from the matrix set via setProjection/setLensProjection
     * because the rendering far plane is always infinity for depth precision.
     *
     * @param out Optional DoubleArray for result; created if null
     * @return The projection matrix (4×4)
     */
    fun getProjectionMatrix(out: DoubleArray? = null): DoubleArray

    /**
     * Get the projection matrix used for culling (far plane is finite).
     *
     * This is the matrix set by setProjection, setLensProjection, or setCustomProjection.
     *
     * @param out Optional DoubleArray for result; created if null
     * @return The culling projection matrix (4×4)
     */
    fun getCullingProjectionMatrix(out: DoubleArray? = null): DoubleArray

    /**
     * Get the camera's model matrix (position and orientation in world space).
     *
     * Includes parent transforms if the camera entity is nested.
     *
     * @param out Optional FloatArray for result; created if null
     * @return The model matrix (4×4)
     */
    fun getModelMatrix(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's model matrix (position and orientation in world space).
     * @param out Optional DoubleArray for result; created if null
     * @return The model matrix (4×4)
     */
    fun getModelMatrix(out: DoubleArray? = null): DoubleArray

    /**
     * Get the camera's view matrix (inverse of the model matrix).
     *
     * Transforms from world space to camera/view space.
     *
     * @param out Optional FloatArray for result; created if null
     * @return The view matrix (4×4)
     */
    fun getViewMatrix(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's view matrix (inverse of the model matrix).
     * @param out Optional DoubleArray for result; created if null
     * @return The view matrix (4×4)
     */
    fun getViewMatrix(out: DoubleArray? = null): DoubleArray

    /**
     * Get the camera's position in world space.
     * @param out Optional FloatArray of at least 3 elements [x, y, z]; created if null
     * @return The position array
     */
    fun getPosition(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's normalized left vector (basis vector for +X in world space).
     * @param out Optional FloatArray of at least 3 elements; created if null
     * @return The left vector (normalized)
     */
    fun getLeftVector(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's normalized up vector (basis vector for +Y in world space).
     * @param out Optional FloatArray of at least 3 elements; created if null
     * @return The up vector (normalized)
     */
    fun getUpVector(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's forward vector (direction opposite to -Z, i.e., camera looking direction).
     * @param out Optional FloatArray of at least 3 elements; created if null
     * @return The forward vector (normalized)
     */
    fun getForwardVector(out: FloatArray? = null): FloatArray

    /**
     * Get the camera's near plane distance.
     * Affects depth-buffer precision significantly; use the largest value possible.
     */
    val near: Float

    /**
     * Get the camera's far plane distance used for culling.
     * Note: for rendering, the far plane is set to infinity internally for depth precision.
     */
    val cullingFar: Float

    /**
     * Set the camera's exposure using physical camera parameters.
     *
     * Default: aperture = f/16, shutter speed = 1/125s, ISO = 100.
     * This provides adequate exposure for a sunny outdoor scene with the sun at zenith.
     *
     * Exposure ultimately controls overall scene brightness. For a scene with sun-like lighting
     * (≈100,000 lux), these defaults work well.
     *
     * @param aperture F-stops (clamped 0.5-64); lower values increase exposure (brighter)
     * @param shutterSpeed Seconds (clamped 1/25,000 to 60); lower increases exposure
     * @param sensitivity ISO (clamped 10-204,800); higher increases exposure
     */
    fun setExposure(aperture: Float, shutterSpeed: Float, sensitivity: Float)

    /**
     * Set the camera's exposure directly (unit-less approach).
     *
     * Useful for matching lighting from other engines/tools. Sets aperture = 1.0,
     * shutter = 1.2, and computes sensitivity to match the desired exposure.
     * For exposure = 1.0, sensitivity is set to 100 ISO.
     *
     * @param exposure Unit-less exposure value
     */
    fun setExposure(exposure: Float)

    /**
     * Get the camera's aperture in f-stops.
     */
    val aperture: Float

    /**
     * Get the camera's shutter speed in seconds.
     */
    val shutterSpeed: Float

    /**
     * Get the camera's sensitivity in ISO.
     */
    val sensitivity: Float

    /**
     * Get the camera's focal length in meters for a 35mm reference sensor.
     * Computed from Eye 0's projection matrix.
     */
    val focalLength: Double

    /**
     * Set the focus distance for depth-of-field post-processing.
     * @param distance Distance from camera to plane of focus in world units (> near plane)
     */
    var focusDistance: Float
    

    /**
     * Get the camera's field of view in degrees.
     * @param direction Axis (VERTICAL or HORIZONTAL) for which to return FOV
     * @return Full field-of-view in degrees
     */
    @PlatformGap(platforms = [FilamentPlatform.WEB], behavior = "always returns 0.0 — Camera::getFieldOfViewInDegrees is not bound in filament.js.")
    fun getFieldOfViewInDegrees(direction: Fov): Double

    /**
     * Get the entity representing this camera.
     *
     * The camera is a component attached to this entity. Use this to query or modify
     * the entity's TransformManager for position/rotation, or other components.
     */
    val entity: Entity
}
