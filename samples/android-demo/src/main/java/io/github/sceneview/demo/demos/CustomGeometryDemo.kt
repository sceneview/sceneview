package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.R
import io.github.sceneview.demo.SceneViewColors
import io.github.sceneview.demo.initialDemoMode
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.rememberPausableHeroYaw
import io.github.sceneview.math.Position
import io.github.sceneview.math.Position2
import io.github.sceneview.math.Rotation
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.sample.rememberMaterialInstance
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Unified "Custom Geometry" demo — consolidates the retired `custom-mesh` and
 * `shape` demos behind a single segmented-button toggle. The two sub-modes
 * showcase the two distinct entry points to custom geometry in SceneView:
 *
 * - **Custom Mesh** — combine primitive nodes ([SphereNode], [CylinderNode])
 *   into a composite "molecule" structure with raw 3D transforms.
 * - **Shape Extrude** — drive a [ShapeNode] from a 2D polygon path and let
 *   the library triangulate it into a 3D mesh.
 *
 * Each sub-mode keeps its own internal controls + camera framing — the two
 * have different scales / target points and the unified scaffold lets them
 * coexist without compromising either. Old deep links continue to work via
 * [io.github.sceneview.demo.DeepLinkRouter.DEMO_ID_ALIASES] (#2239).
 */
@Composable
fun CustomGeometryDemo(onBack: () -> Unit) {
    var mode by remember {
        mutableStateOf(initialDemoMode(CustomGeometryMode.entries, CustomGeometryMode.CustomMesh))
    }
    when (mode) {
        CustomGeometryMode.CustomMesh -> CustomMeshSection(onBack, mode) { mode = it }
        CustomGeometryMode.Shape -> ShapeSection(onBack, mode) { mode = it }
    }
}

private enum class CustomGeometryMode(val label: String) {
    CustomMesh("Custom Mesh"),
    Shape("Shape Extrude"),
}

@Composable
private fun ModeSelector(
    current: CustomGeometryMode,
    onModeChange: (CustomGeometryMode) -> Unit,
) {
    val modes = CustomGeometryMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, m ->
            SegmentedButton(
                selected = m == current,
                onClick = { onModeChange(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                label = { Text(m.label) },
            )
        }
    }
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun CustomMeshSection(
    onBack: () -> Unit,
    mode: CustomGeometryMode,
    onModeChange: (CustomGeometryMode) -> Unit,
) {
    var rotating by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(0.5f) }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val sphereMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Primary)
    val bondMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Accent)

    val (rotationY, _) = rememberPausableHeroYaw(
        trigger = rotating, durationMillis = 8_000, staticYaw = 0f,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_custom_geometry_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = rotating,
                        onValueChange = { rotating = it },
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto-Rotate", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = rotating, onCheckedChange = null)
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("Scale: ${"%.1f".format(Locale.US, scale)}x", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = scale,
                onValueChange = { scale = it },
                valueRange = 0.5f..2.5f
            )
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 0.1f, 2f),
                targetPosition = Position(0f, 0f, 0f),
            ),
        ) {
            Node(
                rotation = Rotation(y = rotationY),
                scale = Float3(scale)
            ) {
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = 0.2f,
                    position = Position(0f, 0f, 0f)
                )
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = 0.12f,
                    position = Position(0f, 0.6f, 0f)
                )
                CylinderNode(
                    materialInstance = bondMaterial,
                    radius = 0.03f,
                    height = 0.4f,
                    position = Position(0f, 0.3f, 0f)
                )
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = 0.12f,
                    position = Position(0.6f, 0f, 0f)
                )
                CylinderNode(
                    materialInstance = bondMaterial,
                    radius = 0.03f,
                    height = 0.4f,
                    position = Position(0.3f, 0f, 0f),
                    rotation = Rotation(z = 90f)
                )
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = 0.12f,
                    position = Position(-0.6f, 0f, 0f)
                )
                CylinderNode(
                    materialInstance = bondMaterial,
                    radius = 0.03f,
                    height = 0.4f,
                    position = Position(-0.3f, 0f, 0f),
                    rotation = Rotation(z = 90f)
                )
                SphereNode(
                    materialInstance = sphereMaterial,
                    radius = 0.12f,
                    position = Position(0f, 0f, 0.6f)
                )
                CylinderNode(
                    materialInstance = bondMaterial,
                    radius = 0.03f,
                    height = 0.4f,
                    position = Position(0f, 0f, 0.3f),
                    rotation = Rotation(x = 90f)
                )
            }
        }
    }
}

@Composable
private fun ShapeSection(
    onBack: () -> Unit,
    mode: CustomGeometryMode,
    onModeChange: (CustomGeometryMode) -> Unit,
) {
    var selectedShape by remember { mutableStateOf("Triangle") }

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)

    val trianglePath = remember {
        listOf(
            Position2(0f, 0.5f),
            Position2(-0.5f, -0.3f),
            Position2(0.5f, -0.3f)
        )
    }

    val starPath = remember {
        buildList {
            val outerR = 0.5f
            val innerR = 0.2f
            for (i in 0 until 10) {
                val angle = (i * 36f - 90f) * (PI.toFloat() / 180f)
                val r = if (i % 2 == 0) outerR else innerR
                add(Position2(cos(angle) * r, sin(angle) * r))
            }
        }
    }

    val hexagonPath = remember {
        buildList {
            val r = 0.4f
            for (i in 0 until 6) {
                val angle = (i * 60f) * (PI.toFloat() / 180f)
                add(Position2(cos(angle) * r, sin(angle) * r))
            }
        }
    }

    val triangleMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Primary)
    val starMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.Accent)
    val hexagonMaterial = rememberMaterialInstance(materialLoader, SceneViewColors.TintLight)
    val shapeMaterials = mapOf(
        "Triangle" to triangleMaterial,
        "Star" to starMaterial,
        "Hexagon" to hexagonMaterial,
    )
    val shapePaths = mapOf(
        "Triangle" to trianglePath,
        "Star" to starPath,
        "Hexagon" to hexagonPath,
    )

    val firstFrame = rememberFirstFrameState()

    DemoScaffold(
        title = stringResource(R.string.demo_custom_geometry_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        controls = {
            ModeSelector(mode, onModeChange)
            Text("Shape", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Triangle", "Star", "Hexagon").forEach { shape ->
                    FilterChip(
                        selected = selectedShape == shape,
                        onClick = { selectedShape = shape },
                        label = { Text(shape) }
                    )
                }
            }
        }
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            onFrame = firstFrame.onFrame,
            engine = engine,
            materialLoader = materialLoader,
            cameraManipulator = rememberCameraManipulator(
                orbitHomePosition = Position(0f, 0f, 1.5f),
                targetPosition = Position(0f, 0f, -1f),
            ),
        ) {
            key(selectedShape) {
                ShapeNode(
                    polygonPath = shapePaths.getValue(selectedShape),
                    materialInstance = shapeMaterials.getValue(selectedShape),
                    position = Position(y = 0f, z = -1f)
                )
            }
        }
    }
}
