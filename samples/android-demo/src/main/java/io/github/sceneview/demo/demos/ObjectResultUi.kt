package io.github.sceneview.demo.demos

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import com.google.android.filament.Engine
import com.google.android.filament.Material
import io.github.sceneview.SceneScope
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.ArDebugStyle
import io.github.sceneview.demo.demos.internal.DenseCloud
import io.github.sceneview.demo.demos.internal.DenseFusion
import io.github.sceneview.demo.demos.internal.MeasureUnitSystem
import io.github.sceneview.demo.demos.internal.ScanBox
import io.github.sceneview.demo.demos.internal.ScanObjectMeshes
import io.github.sceneview.demo.demos.internal.ScanScope
import io.github.sceneview.demo.demos.internal.ScanSubject
import io.github.sceneview.demo.demos.internal.SurveyFormat
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.demo.theme.SceneViewTokens.Type
import io.github.sceneview.demo.theme.StageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.material.setColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * What the result of an object's scan is for (ScanScope.Object): the object itself, boxed on its
 * model, and its three sides on one card under it. As a cupboard's result does for its inside
 * (SpaceResultUi), with the box a tap draws in a replay (ScanObjects).
 */

/** What an object's result knows of its object. */
internal sealed interface SubjectMeasure {
    /** Not read yet: the card waits, it does not say "none". */
    data object Measuring : SubjectMeasure

    /** The scan went round no one object: a room walked through, or too little of a thing to box. */
    data object None : SubjectMeasure

    class Found(val box: ScanBox) : SubjectMeasure
}

/**
 * The object [surfels] were scanned round, `null` for any other [scope] and for a scan with no
 * path kept, which cannot tell what the phone aimed at: read once, off the main thread. [aims]
 * is where the phone looked as it went ([ScanSubject.aims]); [voxelM] the grid [surfels] were
 * kept on, the scan's own when it says one.
 */
@Composable
internal fun rememberScanSubject(scope: ScanScope, surfels: DenseCloud?, aims: List<ScanSubject.Aim>?, voxelM: Float? = null): SubjectMeasure? {
    if (scope != ScanScope.Object || aims.isNullOrEmpty()) return null
    val measure by produceState<SubjectMeasure>(SubjectMeasure.Measuring, surfels, aims, voxelM) {
        // No points yet is not "no object": a kept scan's are read after its model is shown.
        val cloud = surfels ?: return@produceState
        value = withContext(Dispatchers.Default) {
            runCatching { ScanSubject.of(cloud, voxelM ?: DenseFusion.VOXEL_M, aims) }.getOrNull()
                ?.let { SubjectMeasure.Found(it) } ?: SubjectMeasure.None
        }
    }
    return measure
}

/**
 * [measure] on its card: nothing while it is read, then the object's three sides beside the
 * colour its box is drawn in, or what to film again. [stage] is the chrome of the ground its
 * model stands on, which the key is cut from. Figures in the language's [units], as a
 * cupboard's ([SpaceMeasureCard]).
 */
@Composable
internal fun ScanSubjectCard(
    measure: SubjectMeasure,
    modifier: Modifier = Modifier,
    units: MeasureUnitSystem = SurveyFormat.units(LocalLocale.current.platformLocale),
    stage: StageChrome = StageChrome.Media,
) {
    val chrome = LocalStageChrome.current
    when (measure) {
        SubjectMeasure.Measuring -> Unit
        SubjectMeasure.None -> OverlayCard(testTag = SUBJECT_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
            Text(stringResource(R.string.scan_object_none), style = Type.card, color = chrome.onCard)
            Text(stringResource(R.string.scan_object_none_hint), style = Type.body, color = chrome.onCardMuted)
        }
        is SubjectMeasure.Found -> OverlayCard(testTag = SUBJECT_CARD_TAG, modifier = modifier.semantics(mergeDescendants = true) { }) {
            val box = measure.box
            val unit = stringResource(
                if (units == MeasureUnitSystem.Imperial) R.string.scan_space_unit_in else R.string.scan_space_unit_cm,
            )
            SpaceLine(
                stringResource(R.string.scan_object_size, side(box.length, units), side(box.depth, units), side(box.height, units), unit),
                style = Type.card,
                color = chrome.onCard,
                key = SpaceKey(stage.ground, stage.accent),
            )
            SpaceLine(stringResource(R.string.scan_object_size_hint), style = Type.caption, color = chrome.onCardMuted)
        }
    }
}

/**
 * An object's box on its model, in the stage's accent: the edges a tap draws in a replay
 * ([ScanObjectMeshes.edges]), sized for [style]'s camera. Drawn through the model, so the side
 * of the box behind the object shows too, and with no wash on its faces: the object is what is
 * looked at.
 */
@Composable
internal fun SceneScope.ScanSubjectLayer(box: ScanBox, style: ArDebugStyle, engine: Engine, materialLoader: MaterialLoader) {
    val color = LocalStageChrome.current.accent
    // Remembered before the node, so Compose releases the material after the node destroyed the layer.
    val material = remember(engine, materialLoader) {
        materialLoader.createUnlitColorInstance(color).also {
            it.setCullingMode(Material.CullingMode.NONE)
            // Over the model and through it, never cut by the object it boxes.
            it.setDepthCulling(false)
            // Its depth written: the stage's backdrop is drawn last, wherever nothing was, and
            // took back every stretch of an edge with no model behind it (Pixel 4a, 2026-11-15).
            it.setDepthWrite(true)
        }
    }
    DisposableEffect(material) { onDispose { materialLoader.destroyMaterialInstance(material) } }
    SideEffect { material.setColor(color) }
    val node = remember(material) { DebugLayerNode(engine, material, SUBJECT_EDGE_PRIORITY) }
    val radius = style.outlineHalfWidth
    LaunchedEffect(node, box, radius) {
        node.upload(withContext(Dispatchers.Default) { ScanObjectMeshes.edges(box, radius) })
    }
    key(node) {
        Node(apply = { addChildNode(node) })
    }
}

/** After the model (Filament's default, 4), which the box is drawn over. */
private const val SUBJECT_EDGE_PRIORITY = 7

internal const val SUBJECT_CARD_TAG = "rerun_subject_card"
