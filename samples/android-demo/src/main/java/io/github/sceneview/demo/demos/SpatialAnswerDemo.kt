package io.github.sceneview.demo.demos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import com.google.ar.core.Config
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.ar.PlacementScene
import io.github.sceneview.ar.PlacementController
import io.github.sceneview.ar.arcore.position
import io.github.sceneview.ar.node.AnchorNode as ARAnchorNode
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.spatial.*
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.model.restPoseBoundingBox
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpatialAnswerDemo(onBack: () -> Unit) {
    val example = stringResource(R.string.spatial_sentence)
    var sentence by rememberSaveable { mutableStateOf(example) }
    var result by remember { mutableStateOf(SpatialSentenceParser.parse(example)) }
    var expanded by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.demo_spatial_answer)) }, navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.spatial_back))
            }
        })
    }) { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(SceneViewTokens.Space.md),
            verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
        ) {
            OutlinedTextField(
                value = sentence, onValueChange = { sentence = it },
                label = { Text(stringResource(R.string.spatial_prompt)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { result = SpatialSentenceParser.parse(sentence) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.spatial_show))
            }
            when (val current = result) {
                is SpatialResult.Error -> Text(spatialErrorMessage(current.reason), color = MaterialTheme.colorScheme.error)
                is SpatialResult.Success -> {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.spatial_hide_json else R.string.spatial_show_json))
                    }
                    if (expanded) {
                        Text(SpatialPayload.encode(current.spec), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    key(current.spec) { SpatialAnswer(current.spec, Modifier.fillMaxWidth()) }
                }
            }
            Text(stringResource(R.string.spatial_footnote), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

sealed interface SpatialAnswerEvent {
    data object OpenedAR : SpatialAnswerEvent
    data object ReturnedToCard : SpatialAnswerEvent
    data object Placed : SpatialAnswerEvent
    data class InvalidSpec(val reason: SpatialError) : SpatialAnswerEvent
}

/** Reusable inline answer. Only the AR host switches to a full-screen dialog. No service integration. */
@Composable
fun SpatialAnswer(
    spec: SpatialAnswerSpec,
    modifier: Modifier = Modifier,
    onEvent: (SpatialAnswerEvent) -> Unit = {},
) {
    val invalid = spec.validate()
    if (invalid != null) {
        LaunchedEffect(spec) { onEvent(SpatialAnswerEvent.InvalidSpec(invalid)) }
        Text(spatialErrorMessage(invalid), modifier, color = MaterialTheme.colorScheme.error)
        return
    }
    var inAR by rememberSaveable(spec) { mutableStateOf(false) }
    fun returnToCard() { inAR = false; onEvent(SpatialAnswerEvent.ReturnedToCard) }
    // The viewport stays outside a scrolling SurfaceView: Texture supports card clipping and scrolling.
    Card(modifier) {
        Column(Modifier.padding(SceneViewTokens.Space.md), verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm)) {
            Text(spec.label, style = MaterialTheme.typography.titleLarge)
            SpatialViewport(spec, Modifier.fillMaxWidth().aspectRatio(1.25f), ar = false, onEvent = onEvent)
            Text(stringResource(when (spec.dimensionSource) {
                DimensionSource.USER -> R.string.spatial_source_user
                DimensionSource.ESTIMATED -> R.string.spatial_source_estimated
                DimensionSource.MEASURED -> R.string.spatial_source_measured
            }), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { inAR = true; onEvent(SpatialAnswerEvent.OpenedAR) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.spatial_view_ar))
            }
        }
    }
    if (inAR) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = ::returnToCard,
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false),
        ) {
            BackHandler { returnToCard() }
            // Full-bleed camera; the instructions float over it on a surface, like the other AR demos.
            Box(Modifier.fillMaxSize()) {
                SpatialViewport(spec, Modifier.fillMaxSize(), ar = true, onEvent = onEvent)
                Column(
                    Modifier.align(Alignment.TopStart).fillMaxWidth().safeDrawingPadding()
                        .padding(SceneViewTokens.Space.md),
                    verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
                ) {
                    FilledTonalButton(onClick = ::returnToCard) { Text(stringResource(R.string.spatial_back_to_card)) }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(SceneViewTokens.Space.md)) {
                            Text(stringResource(if (spec.placement == SpatialPlacement.FLOOR) R.string.spatial_place_floor else R.string.spatial_place_table),
                                style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.spatial_ar_note), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpatialViewport(spec: SpatialAnswerSpec, modifier: Modifier, ar: Boolean, onEvent: (SpatialAnswerEvent) -> Unit) {
    val engine = rememberEngine()
    val loader = rememberModelLoader(engine)
    val materials = rememberMaterialLoader(engine)
    val asset = spec.subject as? SpatialObject.Asset
    val instance = asset?.let { rememberModelInstance(loader, SpatialAssets.paths.getValue(it.assetId)) }
    val size = when (val subject = spec.subject) {
        is SpatialObject.Box -> Size(subject.sizeM[0], subject.sizeM[1], subject.sizeM[2])
        is SpatialObject.Asset -> instance?.asset?.restPoseBoundingBox?.halfExtent?.let { Size(it[0] * 2, it[1] * 2, it[2] * 2) }
    }
    val colors = MaterialTheme.colorScheme
    // MaterialLoader owns these instances; all creation occurs in composition on the main thread.
    val fill = remember(materials, colors.primary, ar) { materials.createColorInstance(colors.primary.copy(alpha = if (ar) 0.16f else 0.65f)) }
    val edge = remember(materials, colors.primary) { materials.createUnlitColorInstance(colors.primary) }
    val ground = remember(materials, colors.surfaceContainerHighest) { materials.createColorInstance(colors.surfaceContainerHighest) }
    if (ar) {
        var placement by remember { mutableStateOf<PlacementController?>(null) }
        Box(modifier) {
            PlacementScene(
                modifier = Modifier.fillMaxSize(), engine = engine, modelLoader = loader, materialLoader = materials,
                planeFindingMode = Config.PlaneFindingMode.HORIZONTAL, instantPlacement = false,
                groundShadows = false, reticleColor = colors.primary,
                onPlaced = { anchor ->
                    LaunchedEffect(anchor) { onEvent(SpatialAnswerEvent.Placed) }
                    // One answer at a time, including for assets whose instance has one scene parent.
                    if (anchor == placement?.anchors?.lastOrNull()) {
                        var node by remember(anchor) { mutableStateOf<ARAnchorNode?>(null) }
                        AnchorNode(anchor, apply = { node = this }) {
                            if (size != null) DimensionedObject(size, instance, fill, edge,
                                { node?.frame?.camera?.pose?.position ?: Position(z = 1f) }, ar = true)
                        }
                    }
                },
                content = { controller -> SideEffect { placement = controller } },
            )
            FilledTonalButton(
                onClick = { placement?.clear() }, enabled = (placement?.count ?: 0) > 0,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(SceneViewTokens.Space.md),
            ) { Text(stringResource(R.string.spatial_clear)) }
        }
    } else {
        val dimensions = size ?: Size(1f)
        val radius = maxOf(dimensions.x, dimensions.y, dimensions.z) * 1.7f
        val camera = rememberCameraNode(engine)
        val manipulator = remember(radius) {
            createDefaultCameraManipulator(eyePosition = Position(radius, radius * 0.8f, radius),
                targetPosition = Position(y = dimensions.y / 2))
        }
        SceneView(modifier = modifier, surfaceType = SurfaceType.TextureSurface, engine = engine,
            modelLoader = loader, materialLoader = materials, cameraNode = camera,
            cameraManipulator = manipulator, autoCenterContent = false, isOpaque = false) {
            PlaneNode(size = Size(maxOf(dimensions.x, dimensions.z) * 1.4f),
                normal = Position(y = 1f), materialInstance = ground, position = Position(y = -0.002f))
            if (size != null) DimensionedObject(size, instance, fill, edge, { camera.worldPosition }, ar = false)
        }
        if (asset != null && instance == null) Text(stringResource(R.string.spatial_asset_loading))
    }
}

@Composable
private fun SceneScope.DimensionedObject(
    size: Size, instance: ModelInstance?, fill: com.google.android.filament.MaterialInstance,
    edge: com.google.android.filament.MaterialInstance, camera: (() -> Position)?, ar: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val w = size.x; val h = size.y; val d = size.z
    val annotationScale = maxOf(w, h, d)
    if (instance != null) {
        ModelNode(instance, autoAnimate = false, centerOrigin = Position(0f, -1f, 0f),
            apply = { if (ar) materialInstances = materialInstances.map { slots -> slots.map { fill } } })
    } else {
        CubeNode(size = size, center = Position(y = h / 2), materialInstance = fill)
    }
    val corners = listOf(
        Position(-w/2, 0f, -d/2), Position(w/2, 0f, -d/2), Position(w/2, 0f, d/2), Position(-w/2, 0f, d/2),
        Position(-w/2, h, -d/2), Position(w/2, h, -d/2), Position(w/2, h, d/2), Position(-w/2, h, d/2),
    )
    val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0, 4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)
    edges.forEach { (a, b) -> TubeNode(points = listOf(corners[a], corners[b]), radius = annotationScale * 0.003f, materialInstance = edge) }
    val labels = listOf(
        w to Position(0f, h + annotationScale * 0.08f, d/2),
        h to Position(-w/2 - annotationScale * 0.22f, h/2, d/2),
        d to Position(w/2 + annotationScale * 0.22f, annotationScale * 0.07f, 0f),
    )
    labels.forEach { (value, position) ->
        TextNode(text = dimensionLabel(value), position = position, widthMeters = annotationScale * 0.40f, heightMeters = annotationScale * 0.13f,
            textColor = colors.onSurface.toArgb(), backgroundColor = colors.surface.toArgb(), cameraPositionProvider = camera)
    }
}

private fun dimensionLabel(metres: Float): String {
    val cm = metres * 100
    return if (cm < 100) String.format(Locale.US, "%.1f", cm).removeSuffix(".0") + " cm"
    else String.format(Locale.US, "%.2f", metres).trimEnd('0').trimEnd('.') + " m"
}

@Composable
private fun spatialErrorMessage(error: SpatialError): String = stringResource(when (error) {
    SpatialError.InvalidSize -> R.string.spatial_error_size
    SpatialError.UnknownSentence -> R.string.spatial_error_sentence
    SpatialError.UnknownAsset -> R.string.spatial_error_asset
    SpatialError.InvalidLabel -> R.string.spatial_error_label
    SpatialError.UnsupportedVersion -> R.string.spatial_error_version
    SpatialError.MalformedPayload -> R.string.spatial_error_payload
})
