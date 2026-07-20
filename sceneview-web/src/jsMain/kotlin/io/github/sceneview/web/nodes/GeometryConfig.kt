package io.github.sceneview.web.nodes

import io.github.sceneview.collision.Box
import io.github.sceneview.collision.CollisionShape
import io.github.sceneview.collision.Sphere
import io.github.sceneview.collision.Vector3

/**
 * Geometry configuration for SceneView web.
 *
 * Creates procedural geometry primitives (cube, sphere, cylinder, plane)
 * using the KMP core geometry generators, rendered via in-memory GLB
 * through the gltfio pipeline.
 *
 * ```kotlin
 * geometry {
 *     type("cube")
 *     size(1.0, 1.0, 1.0)
 *     color(1.0, 0.0, 0.0, 1.0)
 *     position(0.0, 0.5, -2.0)
 * }
 * ```
 */
class GeometryConfig {
    var geometryType = GeometryType.CUBE; private set
    var sizeX = 1.0; private set
    var sizeY = 1.0; private set
    var sizeZ = 1.0; private set
    var radius = 1.0; private set
    var height = 2.0; private set
    var colorR = 0.8; private set
    var colorG = 0.8; private set
    var colorB = 0.8; private set
    var colorA = 1.0; private set
    /**
     * When `true`, the material ignores all scene lighting (no PBR shading,
     * no IBL, no shadows) and renders the flat colour straight to the framebuffer
     * via the glTF 2.0 `KHR_materials_unlit` extension. Use for HUD overlays,
     * gizmos, axes, lines, sprites — anywhere lighting would fight the use case.
     *
     * Cross-platform parity with `MaterialLoader.createUnlitColorInstance` on
     * Android and `CustomMaterial.unlit(color:)` on Apple. Defaults to `false`.
     */
    var unlit = false; private set
    var positionX = 0.0; private set
    var positionY = 0.0; private set
    var positionZ = 0.0; private set
    var rotationX = 0.0; private set
    var rotationY = 0.0; private set
    var rotationZ = 0.0; private set
    var scaleX = 1.0; private set
    var scaleY = 1.0; private set
    var scaleZ = 1.0; private set

    /** Set the geometry type by name: "cube", "sphere", "cylinder", or "plane". */
    fun type(name: String) {
        geometryType = when (name.lowercase()) {
            "cube", "box" -> GeometryType.CUBE
            "sphere" -> GeometryType.SPHERE
            "cylinder" -> GeometryType.CYLINDER
            "plane", "quad" -> GeometryType.PLANE
            else -> error("Unknown geometry type: $name. Use 'cube', 'sphere', 'cylinder', or 'plane'.")
        }
    }

    fun cube() { geometryType = GeometryType.CUBE }
    fun sphere() { geometryType = GeometryType.SPHERE }
    fun cylinder() { geometryType = GeometryType.CYLINDER }
    fun plane() { geometryType = GeometryType.PLANE }

    /** Set the size for cube geometry (width, height, depth). */
    fun size(x: Double, y: Double, z: Double) {
        sizeX = x; sizeY = y; sizeZ = z
    }

    /** Set uniform size for cube geometry. */
    fun size(value: Double) {
        sizeX = value; sizeY = value; sizeZ = value
    }

    /** Set the radius for sphere or cylinder geometry. */
    fun radius(value: Double) { radius = value }

    /** Set the height for cylinder geometry. */
    fun height(value: Double) { height = value }

    /** Set the color (RGBA, 0.0-1.0). */
    fun color(r: Double, g: Double, b: Double, a: Double = 1.0) {
        colorR = r; colorG = g; colorB = b; colorA = a
    }

    /** Toggle unlit shading on this geometry (ignores all lighting; flat colour only). */
    fun unlit(value: Boolean = true) { unlit = value }

    /** Set the position in world space. */
    fun position(x: Double, y: Double, z: Double) {
        positionX = x; positionY = y; positionZ = z
    }

    /** Set the rotation in degrees (Euler angles). */
    fun rotation(x: Double, y: Double, z: Double) {
        rotationX = x; rotationY = y; rotationZ = z
    }

    /** Set non-uniform scale. */
    fun scale(x: Double, y: Double, z: Double) {
        scaleX = x; scaleY = y; scaleZ = z
    }

    /** Set uniform scale. */
    fun scale(value: Double) {
        scaleX = value; scaleY = value; scaleZ = value
    }
}

enum class GeometryType {
    CUBE,
    SPHERE,
    CYLINDER,
    PLANE
}

/** Picking thickness of a flat plane's collision box (a zero-height box cannot be hit edge-on). */
private const val PLANE_PICK_THICKNESS = 0.01

/**
 * The **local-space** collision shape matching this config's generated
 * geometry (#2024 P5c) — analytic (dimensions are known at build time), so
 * picking works immediately, with no post-load AABB gate.
 *
 * A baked `rotation(...)` in the config is NOT folded into the box (the box
 * stays axis-aligned in node space) — for the rare rotated-bake config the
 * bounds are approximate; assign `Node.collisionShape` manually for a tight
 * fit.
 */
internal fun GeometryConfig.localCollisionShape(): CollisionShape {
    if (geometryType == GeometryType.SPHERE) {
        return Sphere(
            (radius * maxOf(scaleX, scaleY, scaleZ)).toFloat(),
            Vector3(positionX.toFloat(), positionY.toFloat(), positionZ.toFloat()),
        )
    }
    val (sx, sy, sz) = when (geometryType) {
        GeometryType.CUBE -> Triple(sizeX, sizeY, sizeZ)
        GeometryType.CYLINDER -> Triple(radius * 2, height, radius * 2)
        GeometryType.PLANE -> Triple(sizeX, PLANE_PICK_THICKNESS, sizeZ)
        GeometryType.SPHERE -> error("handled above")
    }
    return Box(
        Vector3((sx * scaleX).toFloat(), (sy * scaleY).toFloat(), (sz * scaleZ).toFloat()),
        Vector3(positionX.toFloat(), positionY.toFloat(), positionZ.toFloat()),
    )
}
