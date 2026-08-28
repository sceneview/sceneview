package io.github.sceneview.collision

import android.view.MotionEvent
import com.google.android.filament.View
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.math.toCollisionRay
import io.github.sceneview.utils.motionEventToRay
import io.github.sceneview.utils.screenToRay
import io.github.sceneview.utils.viewToRay

/**
 * Manages hit-testing and collision queries against all registered [Collider]s in a scene.
 *
 * Each [Node][io.github.sceneview.node.Node] with a collision shape registers a [Collider] here.
 * The system supports ray-based hit testing from screen coordinates, normalized view coordinates,
 * or arbitrary rays, as well as shape-vs-shape intersection queries.
 *
 * @param view The Filament [View] whose camera projection is used for screen-to-ray conversion.
 */
class CollisionSystem(var view: View) {

    private val colliders = mutableListOf<Collider>()

    fun addCollider(collider: Collider) {
        colliders.add(collider)
    }

    fun removeCollider(collider: Collider) {
        colliders.remove(collider)
    }

    /**
     * Tests to see if a ray is hitting any nodes within the scene.
     *
     * @param motionEvent motionEvent The motion event where you want the hit ray to happen from.
     *
     * @return [HitResult] list containing nodes that were hit sorted by distance.
     * Empty if no nodes were hit.
     */
    fun hitTest(motionEvent: MotionEvent) = view.motionEventToRay(motionEvent)?.let { hitTest(it) } ?: emptyList()

    /**
     * Tests to see if a ray starting from the screen/camera position is hitting any nodes within
     * the scene and returns a list of HitResult containing all the nodes that were hit, sorted by
     * distance.
     *
     * Specify the camera/screen/view position where the hit ray will start from in screen
     * coordinates.
     *
     * @param xPx x screen coordinate in pixels
     * @param yPx y screen coordinate in pixels
     *
     * @return [HitResult] list containing nodes that were hit sorted by distance.
     * Empty if no nodes were hit.
     */
    fun hitTest(xPx: Float, yPx: Float) = view.screenToRay(xPx, yPx)?.let { hitTest(it) } ?: emptyList()

    /**
     * Tests to see if a ray starting from the view position is hitting any nodes within the scene
     * and returns a list of HitResult containing all the nodes that were hit, sorted by distance.
     *
     * @param viewPosition normalized view coordinate
     * x = (0 = left, 0.5 = center, 1 = right)
     * y = (0 = bottom, 0.5 = center, 1 = top)
     *
     * @return [HitResult] list containing the nodes that were hit sorted by distance.
     * Empty if no nodes were hit.
     */
    fun hitTest(viewPosition: Float2) = view.camera?.let { hitTest(it.viewToRay(viewPosition)) } ?: emptyList()

    /**
     * Tests to see if a ray starting from the view position is hitting any nodes within the scene
     * and returns a list of HitResult containing all the nodes that were hit, sorted by distance.
     *
     * @param ray The ray to use for the test.
     *
     * @return [HitResult] list containing the nodes that were hit sorted by distance.
     * Empty if no nodes were hit.
     */
    fun hitTest(ray: dev.romainguy.kotlin.math.Ray): List<HitResult> =
        colliders.mapNotNull { collider ->
            HitResult().takeIf {
                collider.getTransformedShape()?.rayIntersection(ray.toCollisionRay(), it) == true
            }?.apply {
                node = collider.node
                // The shape only knows the point it was hit at; which *side* of a surface that is
                // takes the ray. Stamp it here — the one place that still holds the ray once the
                // per-shape intersection has run — so a ViewNode can map a touch onto the right
                // pixel when its quad is picked from behind (#3329).
                setWorldDirection(ray.direction)
            }?.takeIf {
                it.node.isHittable
            }
        }.sortedBy { it.getDistance() }

    fun intersects(collider: Collider): Collider? {
        val collisionShape = collider.getTransformedShape() ?: return null
        for (otherCollider in colliders) {
            if (otherCollider == collider) {
                continue
            }
            val otherCollisionShape = otherCollider.getTransformedShape() ?: continue
            if (collisionShape.shapeIntersection(otherCollisionShape)) {
                return otherCollider
            }
        }
        return null
    }

    /**
     * Finds all colliders that intersect with [collider] and invokes [onIntersection] for each.
     */
    fun intersectsAll(collider: Collider, onIntersection: (Collider) -> Unit) {
        val collisionShape = collider.getTransformedShape() ?: return
        for (otherCollider in colliders) {
            if (otherCollider == collider) continue
            val otherCollisionShape = otherCollider.getTransformedShape() ?: continue
            if (collisionShape.shapeIntersection(otherCollisionShape)) {
                onIntersection(otherCollider)
            }
        }
    }

    // ── Deprecated compatibility overloads ─────────────────────────────────────────────────────────

    /**
     * @deprecated Use the Kotlin lambda version [intersectsAll] instead.
     */
    @Deprecated("Use the Kotlin lambda version", ReplaceWith("intersectsAll(collider) { processResult.accept(it) }"))
    fun intersectsAll(collider: Collider, processResult: java.util.function.Consumer<Collider>) {
        intersectsAll(collider) { processResult.accept(it) }
    }

    /**
     * @deprecated Use [hitTest] instead.
     */
    @Deprecated("Use hitTest(ray) instead", ReplaceWith("hitTest(ray).firstOrNull()"))
    fun raycast(ray: dev.romainguy.kotlin.math.Ray): HitResult? = hitTest(ray).firstOrNull()

    /**
     * @deprecated Use [hitTest] instead.
     */
    @Deprecated("Use hitTest(ray) instead", ReplaceWith("hitTest(ray)"))
    fun raycastAll(ray: dev.romainguy.kotlin.math.Ray): List<HitResult> = hitTest(ray)

    fun destroy() {
        colliders.clear()
    }
}