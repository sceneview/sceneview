@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.sceneview.demo.common.placement

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Engine
import io.github.sceneview.ExperimentalSceneViewApi
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DEFAULT_ORBIT_ELEVATION_DEGREES
import io.github.sceneview.demo.R
import io.github.sceneview.demo.common.DemoModalBottomSheet
import io.github.sceneview.demo.common.MODEL_DEMO_HDR
import io.github.sceneview.demo.fitOrbitRadius
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.environment.rememberHDREnvironment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.model
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEnvironmentLoader

/** The room every "View in 3D" preview shows its subject in: a bright, mostly white living room. */
internal const val PLACEMENT_PREVIEW_HDR: String = MODEL_DEMO_HDR

/** Camera distance that frames a subject [extent] metres across in the square preview stage. */
internal fun placementPreviewOrbitRadius(extent: Size): Float = fitOrbitRadius(
    extent.x, extent.y, extent.z,
    aspect = 1f,
    elevationDegrees = DEFAULT_ORBIT_ELEVATION_DEGREES,
)

/**
 * Width, height and depth, in metres, of a model whose unscaled bounding box has [halfExtent]
 * once `ModelNode(scaleToUnits = units)` has fitted its longest side to [units].
 */
internal fun scaledToUnitsExtent(halfExtent: FloatArray, units: Float): Size {
    val longest = 2f * maxOf(halfExtent[0], halfExtent[1], halfExtent[2])
    // ModelNode leaves an empty box unscaled; so does this.
    val scale = if (longest > 0f) units / longest else 1f
    return Size(2f * halfExtent[0] * scale, 2f * halfExtent[1] * scale, 2f * halfExtent[2] * scale)
}

/** [scaledToUnitsExtent] for a loaded model. */
internal fun ModelInstance.extentScaledTo(units: Float): Size =
    scaledToUnitsExtent(model.boundingBox.halfExtent, units)

/**
 * "View in 3D": the object a placement demo places, shown without AR in a studio room and
 * framed to fill a square stage you orbit by dragging (#3864, #3884).
 *
 * - **Lit, with a backdrop.** The bundled studio HDRI, skybox drawn: the room is the backdrop a
 *   dark subject reads against and the light a glossy one reflects. SceneView's default black
 *   stage made the near-black TV invisible and left the helmet and the lantern dim. The room is
 *   media, not a themed surface, so it looks the same in light and dark; only the sheet around
 *   it follows the theme.
 * - **Framed.** The camera starts at [placementPreviewOrbitRadius] for [subjectExtent], not at
 *   the stock 2.78 m where a 0.3 m object covers a tenth of the square.
 * - **Fully open, and the stage owns its drags.** The sheet skips its half-height detent, which
 *   hid the bottom of the stage and the close button. Its own drag is off: SceneView's surface is
 *   an Android view, and Compose hands every touch to the sheet before the view, so the sheet
 *   took any vertical drag that started on the stage. The handle goes with the drag; the scrim,
 *   Back and "Close preview" dismiss the sheet.
 *
 * A spinner holds the stage until both the room and [subjectExtent] are known, rather than the
 * default black stage this replaces.
 *
 * The subject must not be editable (`isEditable`): SceneView gives a touch that lands on an
 * editable node to that node and never to the camera, so an editable subject filling the stage
 * cannot be orbited.
 */
@Composable
internal fun PlacementPreviewSheet(
    title: String,
    subjectExtent: Size?,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    onDismiss: () -> Unit,
    content: @Composable SceneScope.() -> Unit,
) {
    DemoModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
    ) {
        // #3716: the container reaches the true bottom edge. Clear the navigation bar
        // explicitly, or "Close preview" lands under it.
        Column(Modifier.navigationBarsPadding()) {
            Text(title, Modifier.padding(SceneViewTokens.Space.md))
            PlacementPreviewStage(
                subjectExtent, engine, modelLoader, materialLoader,
                Modifier.fillMaxWidth().aspectRatio(1f), content,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ar_place_close_preview)) }
        }
    }
}

@OptIn(ExperimentalSceneViewApi::class)
@Composable
private fun PlacementPreviewStage(
    subjectExtent: Size?,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier,
    content: @Composable SceneScope.() -> Unit,
) {
    val environmentLoader = rememberEnvironmentLoader(engine)
    val room = rememberHDREnvironment(environmentLoader, PLACEMENT_PREVIEW_HDR, createSkybox = true)
    Box(modifier, contentAlignment = Alignment.Center) {
        if (room == null || subjectExtent == null) {
            CircularProgressIndicator()
        } else {
            val orbitRadius = remember(subjectExtent) { placementPreviewOrbitRadius(subjectExtent) }
            SceneView(
                modifier = Modifier.fillMaxSize(),
                engine = engine,
                modelLoader = modelLoader,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = room,
                cameraManipulator = rememberCameraManipulator(orbitRadius = orbitRadius),
                content = content,
            )
        }
    }
}
