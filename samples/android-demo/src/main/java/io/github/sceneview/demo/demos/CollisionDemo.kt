package io.github.sceneview.demo.demos

import android.view.MotionEvent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.SceneAction
import io.github.sceneview.demo.common.SceneActionBar
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCollisionSystem
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import io.github.sceneview.rememberView
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * Demonstrates collision-based hit testing.
 *
 * Several geometry nodes (cubes and spheres) are placed in the scene. Tapping a node changes its
 * color to highlight it. A "Reset Colors" button reverts all nodes to their default appearance.
 */
@Composable
fun CollisionDemo(onBack: () -> Unit) {
    // Track which node indices have been "hit" (highlighted).
    var highlightedIndices by remember { mutableStateOf(setOf<Int>()) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val view = rememberView(engine)
    val collisionSystem = rememberCollisionSystem(view)

    // Pre-create materials for default and highlighted states.
    // On-brand: Primary blue by default, Accent purple when a shape is hit — same hero
    // gradient as the product identity. Unlit so the hit/no-hit colour flip stays
    // legible regardless of scene lighting (the colour itself IS the signal here).
    val defaultMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Primary)
    val highlightedMaterial = rememberUnlitMaterialInstance(materialLoader, SceneViewColors.Accent)

    // Node layout: 3 cubes and 2 spheres in a row.
    data class ShapeSpec(
        val index: Int,
        val isSphere: Boolean,
        val position: Position
    )

    val shapes = remember {
        listOf(
            ShapeSpec(0, isSphere = false, position = Position(x = -0.6f, y = 0f, z = -2f)),
            ShapeSpec(1, isSphere = true, position = Position(x = -0.3f, y = 0.3f, z = -2f)),
            ShapeSpec(2, isSphere = false, position = Position(x = 0f, y = 0f, z = -2f)),
            ShapeSpec(3, isSphere = true, position = Position(x = 0.3f, y = 0.3f, z = -2f)),
            ShapeSpec(4, isSphere = false, position = Position(x = 0.6f, y = 0f, z = -2f))
        )
    }

    val gestureListener = rememberOnGestureListener(
        // `onSingleTapUp` fires on release, no 300 ms double-tap disambiguation window.
        // Matches the ViewNodeDemo fix — users (and tests) firing several single taps in
        // quick succession should see each one register instead of being dropped as a
        // possible double-tap.
        onSingleTapUp = { _: MotionEvent, node: Node? ->
            if (node != null) {
                val idx = node.name?.removePrefix("shape_")?.toIntOrNull()
                if (idx != null) {
                    highlightedIndices = if (idx in highlightedIndices) {
                        highlightedIndices - idx
                    } else {
                        highlightedIndices + idx
                    }
                }
            }
        }
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_collision_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        // "Reset Colors" is the demo's reset action, so it lives on-screen via
        // SceneActionBar (#1964). The sheet keeps only the one-line how-to text.
        controls = {
            Text(
                "Tap a shape to highlight it. Use the on-screen \"Reset Colors\" " +
                    "button to clear all highlights.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    ) {
      Box(modifier = Modifier.fillMaxSize()) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            environmentLoader = environmentLoader,
            view = view,
            collisionSystem = collisionSystem,
            // This demo hand-authors five shapes in a row at z = -2 and a camera
            // manipulator whose orbit pivot is fixed on that row. With the default
            // autoCenterContent the union bounding box of the five shapes is recentred
            // to the scene origin — which shifts them off the camera's (0, 0.15, -2)
            // pivot. The camera then orbits empty space (feels unresponsive) and the
            // visible shapes no longer line up with where taps are expected to land,
            // so hit-tests appear to fire outside the objects (#1430, same root cause
            // as the LightingDemo #1421 fix). Disable it so each shape keeps its
            // authored world position and collision matches what is rendered.
            autoCenterContent = false,
            // Dolly closer — shapes span 1.2 m at z = -2, default camera z = 4 put them
            // at 6 m which made the tap targets tiny on phone screens.
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 0.2f, 1f),
                targetPosition = Position(0f, 0.15f, -2f),
            ),
            onGestureListener = gestureListener
        ) {
            for (shape in shapes) {
                val mat = if (shape.index in highlightedIndices) {
                    highlightedMaterial
                } else {
                    defaultMaterial
                }
                if (shape.isSphere) {
                    SphereNode(
                        radius = 0.15f,
                        materialInstance = mat,
                        position = shape.position,
                        apply = {
                            name = "shape_${shape.index}"
                            isHittable = true
                        }
                    )
                } else {
                    CubeNode(
                        size = Size(x = 0.25f, y = 0.25f, z = 0.25f),
                        materialInstance = mat,
                        position = shape.position,
                        apply = {
                            name = "shape_${shape.index}"
                            isHittable = true
                        }
                    )
                }
            }
        }

        // Primary action on-screen (#1964) — "Reset Colors" is the demo's reset
        // action, so it lives bottom-start instead of in the Settings sheet.
        SceneActionBar(
            SceneAction("Reset Colors", onClick = { highlightedIndices = emptySet() }),
        )
      }
    }
}
