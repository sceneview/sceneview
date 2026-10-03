package io.github.sceneview.demo.demos

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.android.filament.MaterialInstance
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.createDefaultCameraManipulator
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.LocalDemoChromeBottomInset
import io.github.sceneview.demo.LocalDemoSceneCover
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SETTINGS_FAB_RESERVED_SPACE
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.demos.internal.DemoMath
import io.github.sceneview.demo.demos.internal.GeometryDemoState
import io.github.sceneview.demo.demos.internal.GeometryLayout
import io.github.sceneview.demo.demos.internal.GeometryShape
import io.github.sceneview.demo.demos.internal.rememberStudioEnvironment
import io.github.sceneview.demo.driving
import io.github.sceneview.demo.rememberContinuousCameraManipulator
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.SceneViewDemoTheme
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.demo.ui.overMediaEdge
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.sample.LifecycleAwareLaunchedEffect
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.ui.LabeledSlider

/** The studio the shapes stand in: what a metallic, mirror-smooth primitive has to reflect. */
private const val STUDIO_ENVIRONMENT = "environments/studio_warm_2k.hdr"

/** Where the spin is parked in QA mode, so two captures of the same state are the same picture. */
private const val QA_SPIN_DEGREES = 30f

/**
 * Tessellation of the round shapes, set once at creation: the library defaults (24 sides) show
 * their facets on the silhouette of a mirror finish at this size.
 */
private const val ROUND_SEGMENTS = 48

/** From this width the two sliders sit side by side, so both fit a landscape sheet's peek. */
private val SIDE_BY_SIDE_CONTROLS_MIN_WIDTH = 560.dp

/**
 * The seven built-in geometry primitives of `SceneScope` — `CubeNode`, `SphereNode`,
 * `CylinderNode`, `ConeNode`, `TorusNode`, `CapsuleNode`, `PlaneNode` — side by side in a studio.
 *
 * On screen:
 * - **Shape chips** over the scene show or hide each primitive. A hidden shape leaves its slot
 *   empty; nothing else moves and the camera stays where it is.
 * - **Animate** (dock) starts and stops the spin; **Recenter** (dock) brings the camera home.
 * - **Settings** holds the one material every shape shares: Metallic and Roughness, the whole
 *   PBR range from chalk to mirror. Reset restores the screen as it opens.
 *
 * The scene is full-bleed and fitted to the band the chrome leaves free, in portrait and in
 * landscape: the band goes to `SceneView(contentPadding = …)`, [GeometryLayout] picks the
 * arrangement and frames it with `fitCameraToBounds`, [GeometryDemoState] holds what the user
 * changed, and this file wires them to the screen.
 */
@Composable
fun GeometryDemo(onBack: () -> Unit) {
    // Inspection mode (Android Studio @Preview pane, Roborazzi snapshot tests): leave before any
    // rememberEngine() call — LayoutLib does not ship Filament's native libraries.
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = stringResource(R.string.demo_geometry_title), onBack = onBack)
        return
    }

    val state = rememberSaveable(saver = GeometryDemoState.Saver) { GeometryDemoState() }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    // Material parameters are written straight into Filament, which an on-demand scene cannot
    // see: with the spin paused, nothing else would draw the change.
    val renderInvalidator = rememberRenderInvalidator()

    // A drawn studio, not just its light: metal has to have something to mirror, or Metallic 1
    // turns every flat face black.
    val studio = rememberStudioEnvironment(engine, environmentLoader, STUDIO_ENVIRONMENT)
    val fallbackEnvironment = rememberEnvironment(environmentLoader)
    val firstFrame = rememberFirstFrameState(engine)
    // The studio is the picture, not a refinement of it: "Scene ready" waits for it (#4174).
    firstFrame.holdUntil(landed = studio != null)

    val materials = rememberShapeMaterials(materialLoader, state.metallic, state.roughness)
    LaunchedEffect(state.metallic, state.roughness) { renderInvalidator.requestRender() }

    val spinDegrees = rememberSpinDegrees(spinning = state.spinning)

    // Height of the chip block, measured: one row in landscape, two in portrait, more at 200 %
    // text. The framing below clears whatever it turns out to be.
    var chipsHeightPx by remember { mutableIntStateOf(0) }

    DemoScaffold(
        title = stringResource(R.string.demo_geometry_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        sceneReady = firstFrame.sceneReady,
        onReset = state::reset,
        dock = listOf(
            DockItem(
                icon = if (state.spinning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                label = stringResource(R.string.demo_geometry_animate),
                selected = state.spinning,
                onClick = { state.spinning = !state.spinning },
            ),
            DockItem(
                icon = Icons.Outlined.RestartAlt,
                label = stringResource(R.string.demo_geometry_recenter),
                onClick = state::recenter,
            ),
        ),
        bottomOverlay = {
            GeometryShapeChips(
                visibleShapes = state.visibleShapes,
                onToggle = state::toggle,
                modifier = Modifier
                    .padding(horizontal = SceneViewTokens.Space.md)
                    .onSizeChanged { chipsHeightPx = it.height },
            )
        },
        controls = {
            GeometryDemoControls(
                metallic = state.metallic,
                onMetallicChange = { state.metallic = it },
                roughness = state.roughness,
                onRoughnessChange = { state.roughness = it },
            )
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // The scene fills the window and the chrome floats over it. What the chrome covers
            // goes to the SDK as `contentPadding`: the camera projects into the band left free —
            // below the title row, above the chips, the dock and, once it is open, the settings
            // sheet — without the surface being resized or the camera moved.
            val layoutDirection = LocalLayoutDirection.current
            val safe = WindowInsets.safeDrawing.asPaddingValues()
            val left = safe.calculateLeftPadding(layoutDirection)
            val right = safe.calculateRightPadding(layoutDirection)
            val chrome = LocalDemoSceneCover.current
            val cover = geometryContentPadding(
                chrome = chrome,
                sceneHeight = maxHeight,
                left = left,
                right = right,
            )

            // The block is laid out and framed for the band **at rest** — the sheet closed. The
            // sheet then only narrows the band the camera projects into: the same picture, a
            // little smaller, follows it up, and the orbit the user set survives opening
            // Settings. Fitting to the live band instead would rebuild the orbit on every frame
            // of a drag.
            val restBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                maxOf(SETTINGS_FAB_RESERVED_SPACE, LocalDemoChromeBottomInset.current) +
                with(LocalDensity.current) { chipsHeightPx.toDp() } + SceneViewTokens.Space.sm
            val restHeight = (maxHeight - chrome.calculateTopPadding() - restBottom)
                .coerceAtLeast(maxHeight * GEOMETRY_MIN_VISIBLE_FRACTION)
            val restAspect = (maxWidth - left - right) / restHeight
            val arrangement = GeometryLayout.arrangementFor(restAspect)
            val qaDistance = DemoSettings.cameraDistance
            val home = remember(arrangement, restAspect, qaDistance) {
                GeometryLayout.framing(arrangement, restAspect, qaDistance)
            }

            // A Filament manipulator has no "go home": the orbit is rebuilt at its framing on
            // Recenter and Reset, and the continuity layer eases the camera there from wherever
            // the user left it. Before the scene is shown there is nothing to ease from.
            val homeOrbit = remember(home, state.cameraHomeGeneration) {
                createDefaultCameraManipulator(eyePosition = home.eye, targetPosition = home.target)
            }
            val cameraManipulator = rememberContinuousCameraManipulator(pivot = home.target)
                .driving(homeOrbit, contentShown = firstFrame.sceneReady.value)
            // `SceneView` asks for a frame when it is handed another manipulator; this one stays
            // the same and only changes what it drives. With the spin paused the scene is parked,
            // and Recenter would wait for the next touch to be seen.
            LaunchedEffect(homeOrbit) { renderInvalidator.requestRender() }

            // The lens goes with the band at rest too: 28 mm on a portrait phone, longer on the
            // shallow band a landscape one leaves, where 28 mm would stretch the ends of the row.
            val focalLength = GeometryLayout.focalLengthMm(restAspect).toDouble()
            val cameraNode = rememberCameraNode(engine) { this.focalLength = focalLength }
            LaunchedEffect(cameraNode, focalLength) {
                cameraNode.focalLength = focalLength
                renderInvalidator.requestRender()
            }

            SceneView(
                modifier = Modifier.fillMaxSize(),
                contentPadding = cover,
                cameraNode = cameraNode,
                onFrame = firstFrame.onFrame,
                engine = engine,
                materialLoader = materialLoader,
                environmentLoader = environmentLoader,
                environment = studio?.resource ?: fallbackEnvironment,
                cameraManipulator = cameraManipulator,
                renderInvalidator = renderInvalidator,
                // Every shape owns a slot. Auto-centring would move the survivors each time one
                // is hidden, and the camera with them.
                autoCenterContent = false,
            ) {
                GeometryShapes(
                    visibleShapes = state.visibleShapes,
                    arrangement = arrangement,
                    materials = materials,
                    spinDegrees = spinDegrees.floatValue,
                )
            }
        }
    }
}

/**
 * What the scene hands to `SceneView(contentPadding = …)`, from what the scaffold reports as
 * covered.
 *
 * The bottom is taken as it is: the chips, the dock and the settings sheet are where the shapes
 * must not be. The sides are the window's safe insets — a display cutout on a phone held
 * sideways — so the block is centred where the chips are centred. The top is the title row; under
 * a sheet dragged all the way up it gives way, because what is left is thinner than the tenth of
 * the view the SDK keeps visible and the SDK would take the difference from both edges — part of
 * it under the sheet.
 */
internal fun geometryContentPadding(
    chrome: PaddingValues,
    sceneHeight: Dp,
    left: Dp = 0.dp,
    right: Dp = 0.dp,
): PaddingValues {
    val bottom = chrome.calculateBottomPadding()
    val room = sceneHeight * (1f - GEOMETRY_MIN_VISIBLE_FRACTION) - bottom
    val top = minOf(chrome.calculateTopPadding(), room.coerceAtLeast(0.dp))
    return PaddingValues.Absolute(left = left, top = top, right = right, bottom = bottom)
}

/** The floor the SDK applies to `contentPadding`: a tenth of the view stays visible. */
private const val GEOMETRY_MIN_VISIBLE_FRACTION = 0.1f

/** The shape's colour: the four brand tones in turn, so no two neighbours share one. */
internal val GeometryShape.color: Color
    get() = SceneViewColors.Ramp4[ordinal % SceneViewColors.Ramp4.size]

@get:StringRes
internal val GeometryShape.labelRes: Int
    get() = when (this) {
        GeometryShape.Cube -> R.string.demo_geometry_shape_cube
        GeometryShape.Sphere -> R.string.demo_geometry_shape_sphere
        GeometryShape.Cylinder -> R.string.demo_geometry_shape_cylinder
        GeometryShape.Cone -> R.string.demo_geometry_shape_cone
        GeometryShape.Torus -> R.string.demo_geometry_shape_torus
        GeometryShape.Capsule -> R.string.demo_geometry_shape_capsule
        GeometryShape.Plane -> R.string.demo_geometry_shape_plane
    }

// ── Scene ────────────────────────────────────────────────────────────────────────────────────────

/**
 * One material per shape, sharing [metallic] and [roughness]. The instances live as long as the
 * screen; the sliders only write their parameters.
 */
@Composable
private fun rememberShapeMaterials(
    materialLoader: MaterialLoader,
    metallic: Float,
    roughness: Float,
): Map<GeometryShape, MaterialInstance> {
    val materials = GeometryShape.entries.associateWith { shape ->
        rememberMaterialInstance(materialLoader, shape.color, metallic, roughness)
    }
    // A quad has one face; without this it disappears whenever the orbit goes behind it.
    val plane = materials.getValue(GeometryShape.Plane)
    DisposableEffect(plane) {
        plane.setDoubleSided(true)
        onDispose { }
    }
    return materials
}

/**
 * The angle the shapes have turned by, advancing while [spinning] and the screen is resumed.
 * QA mode parks it, so a capture does not depend on when it was taken.
 */
@Composable
private fun rememberSpinDegrees(spinning: Boolean): MutableFloatState {
    val degrees = remember { mutableFloatStateOf(0f) }
    LifecycleAwareLaunchedEffect(DemoSettings.qaMode, spinning) {
        if (DemoSettings.qaMode) {
            degrees.floatValue = QA_SPIN_DEGREES
            return@LifecycleAwareLaunchedEffect
        }
        if (!spinning) return@LifecycleAwareLaunchedEffect
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                if (lastNanos != 0L) {
                    degrees.floatValue = DemoMath.nextSpinDegrees(degrees.floatValue, nanos - lastNanos)
                }
                lastNanos = nanos
            }
        }
    }
    return degrees
}

/**
 * The primitives themselves, each in its slot of [arrangement]. This is the part of the demo that
 * is the public API: one `SceneScope` composable per shape, sized by plain parameters.
 */
@Composable
private fun SceneScope.GeometryShapes(
    visibleShapes: Set<GeometryShape>,
    arrangement: GeometryLayout.Arrangement,
    materials: Map<GeometryShape, MaterialInstance>,
    spinDegrees: Float,
) {
    fun slot(shape: GeometryShape) = GeometryLayout.position(shape, arrangement)
    fun material(shape: GeometryShape) = materials.getValue(shape)

    // Tilted, then spun about the vertical: a solid of revolution turning on its own axis would
    // look still, and a cube seen square-on would look like a square.
    val tumble = Rotation(x = GeometryLayout.TILT_DEGREES, y = spinDegrees)

    if (GeometryShape.Cube in visibleShapes) {
        CubeNode(
            size = Size(GeometryLayout.CUBE_EDGE),
            materialInstance = material(GeometryShape.Cube),
            position = slot(GeometryShape.Cube),
            rotation = tumble,
        )
    }
    if (GeometryShape.Sphere in visibleShapes) {
        SphereNode(
            radius = GeometryLayout.SPHERE_RADIUS,
            stacks = ROUND_SEGMENTS,
            slices = ROUND_SEGMENTS,
            materialInstance = material(GeometryShape.Sphere),
            position = slot(GeometryShape.Sphere),
            rotation = tumble,
        )
    }
    if (GeometryShape.Cylinder in visibleShapes) {
        CylinderNode(
            radius = GeometryLayout.CYLINDER_RADIUS,
            height = GeometryLayout.CYLINDER_HEIGHT,
            sideCount = ROUND_SEGMENTS,
            materialInstance = material(GeometryShape.Cylinder),
            position = slot(GeometryShape.Cylinder),
            rotation = tumble,
        )
    }
    if (GeometryShape.Cone in visibleShapes) {
        ConeNode(
            radius = GeometryLayout.CONE_RADIUS,
            height = GeometryLayout.CONE_HEIGHT,
            sideCount = ROUND_SEGMENTS,
            materialInstance = material(GeometryShape.Cone),
            position = slot(GeometryShape.Cone),
            rotation = tumble,
        )
    }
    if (GeometryShape.Torus in visibleShapes) {
        TorusNode(
            majorRadius = GeometryLayout.TORUS_MAJOR_RADIUS,
            minorRadius = GeometryLayout.TORUS_MINOR_RADIUS,
            majorSegments = ROUND_SEGMENTS,
            minorSegments = ROUND_SEGMENTS / 2,
            materialInstance = material(GeometryShape.Torus),
            position = slot(GeometryShape.Torus),
            // The ring lies flat (its axis is Y): stood up so the hole faces the camera once
            // per turn instead of never.
            rotation = Rotation(x = GeometryLayout.TORUS_TILT_DEGREES, y = spinDegrees),
        )
    }
    if (GeometryShape.Capsule in visibleShapes) {
        CapsuleNode(
            radius = GeometryLayout.CAPSULE_RADIUS,
            height = GeometryLayout.CAPSULE_HEIGHT,
            sideSlices = ROUND_SEGMENTS,
            materialInstance = material(GeometryShape.Capsule),
            position = slot(GeometryShape.Capsule),
            rotation = tumble,
        )
    }
    if (GeometryShape.Plane in visibleShapes) {
        PlaneNode(
            // A flat XY quad facing +Z. `size.z` has to be 0: Plane uses all three components,
            // and a depth would shear the quad into a diagonal surface.
            size = Size(GeometryLayout.PLANE_EDGE, GeometryLayout.PLANE_EDGE, 0f),
            normal = Direction(z = 1f),
            materialInstance = material(GeometryShape.Plane),
            position = slot(GeometryShape.Plane),
            // Not the tumble: a zero-thickness quad spun about Y is edge-on twice a turn and
            // vanishes. It turns in its own plane instead, tilted so it reads as a surface in
            // space (#3237).
            rotation = Rotation(x = GeometryLayout.TILT_DEGREES, z = spinDegrees),
        )
    }
}

// ── Controls ─────────────────────────────────────────────────────────────────────────────────────

/**
 * One switch per shape, floating over the scene: solid white while the shape is shown, glass
 * once it is hidden — the white-on-media language of the dock, readable in both themes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GeometryShapeChips(
    visibleShapes: Set<GeometryShape>,
    onToggle: (GeometryShape) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(
            SceneViewTokens.Space.sm,
            Alignment.CenterHorizontally,
        ),
        verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.sm),
    ) {
        GeometryShape.entries.forEach { shape ->
            GeometryShapeChip(
                label = stringResource(shape.labelRes),
                shown = shape in visibleShapes,
                onToggle = { onToggle(shape) },
            )
        }
    }
}

@Composable
private fun GeometryShapeChip(label: String, shown: Boolean, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(SceneViewTokens.Radius.full)
    val container by animateColorAsState(
        targetValue = if (shown) SceneViewTokens.Glass.onGlass else SceneViewTokens.Glass.surface,
        animationSpec = SceneViewTokens.Motion.fade(),
        label = "geometry-chip-container",
    )
    val content by animateColorAsState(
        targetValue = if (shown) SceneViewTokens.Stage.background else SceneViewTokens.Glass.onGlass,
        animationSpec = SceneViewTokens.Motion.fade(),
        label = "geometry-chip-content",
    )
    Box(
        modifier = Modifier
            .heightIn(min = SceneViewTokens.Layout.touchTarget)
            .overMediaEdge(shape)
            .clip(shape)
            .background(container)
            .toggleable(value = shown, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = SceneViewTokens.Glass.pillPaddingHorizontal),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * The settings sheet: the material every shape shares. Two sliders and nothing else, so the
 * sheet stays at its content height and the scene stays visible while a value is dragged.
 */
@Composable
internal fun GeometryDemoControls(
    metallic: Float,
    onMetallicChange: (Float) -> Unit,
    roughness: Float,
    onRoughnessChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metallicLabel = stringResource(R.string.demo_geometry_metallic)
    val roughnessLabel = stringResource(R.string.demo_geometry_roughness)
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        if (maxWidth >= SIDE_BY_SIDE_CONTROLS_MIN_WIDTH) {
            Row(horizontalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.lg)) {
                LabeledSlider(
                    label = metallicLabel,
                    value = metallic,
                    onValueChange = onMetallicChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                LabeledSlider(
                    label = roughnessLabel,
                    value = roughness,
                    onValueChange = onRoughnessChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(SceneViewTokens.Space.md)) {
                LabeledSlider(
                    label = metallicLabel,
                    value = metallic,
                    onValueChange = onMetallicChange,
                    valueRange = 0f..1f,
                )
                LabeledSlider(
                    label = roughnessLabel,
                    value = roughness,
                    onValueChange = onRoughnessChange,
                    valueRange = 0f..1f,
                )
            }
        }
    }
}

// ── Android Studio @Preview support ──────────────────────────────────────────────────────────────
//
// In the preview pane the demo body short-circuits to DemoPreviewPlaceholder (see the top of
// GeometryDemo); the chips and the sheet content are plain Compose and preview as they are.

@Preview(name = "Demo (light)", showBackground = true)
@Composable
private fun GeometryDemoPreview_Light() {
    SceneViewDemoTheme(darkTheme = false) {
        GeometryDemo(onBack = {})
    }
}

@Preview(name = "Demo (dark)", showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun GeometryDemoPreview_Dark() {
    SceneViewDemoTheme(darkTheme = true) {
        GeometryDemo(onBack = {})
    }
}

@Preview(name = "Controls", showBackground = true)
@Composable
private fun GeometryDemoControlsPreview() {
    SceneViewDemoTheme(darkTheme = false) {
        Box(modifier = Modifier.padding(SceneViewTokens.Space.md)) {
            GeometryDemoControls(
                metallic = GeometryDemoState.DEFAULT_METALLIC,
                onMetallicChange = {},
                roughness = GeometryDemoState.DEFAULT_ROUGHNESS,
                onRoughnessChange = {},
            )
        }
    }
}

@Preview(name = "Shape chips", showBackground = true, backgroundColor = 0xFF0B0F16)
@Composable
private fun GeometryShapeChipsPreview() {
    SceneViewDemoTheme(darkTheme = true) {
        GeometryShapeChips(
            visibleShapes = GeometryDemoState.ALL_SHAPES - GeometryShape.Cone,
            onToggle = {},
            modifier = Modifier.padding(SceneViewTokens.Space.md),
        )
    }
}
