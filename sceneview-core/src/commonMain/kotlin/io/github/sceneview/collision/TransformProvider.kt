// Derived from Google Sceneform (Apache-2.0), modified by the SceneView contributors.
package io.github.sceneview.collision

/**
 * Supplies the world transformation matrix used to transform collision shapes.
 *
 * Typically implemented by a scene node so that its collision shape can be moved
 * into world space for intersection tests.
 */
fun interface TransformProvider {
    /** Returns the current 4×4 world transformation matrix. */
    fun getTransformationMatrix(): Matrix
}
