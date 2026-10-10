package io.github.sceneview.demo.auto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.google.android.filament.Colors
import com.google.android.filament.LightManager
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.math.toColor
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.node.ModelNode
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance
import kotlin.math.cos
import kotlin.math.sin

/**
 * The static garage: a floor with a horizon. A plain dark disc reads as a flat grey band from a
 * low camera, so the floor carries what gives a floor its depth — a painted edge, the dashes of
 * a lane, and a ring of pillars standing where the light gives out.
 */
@Composable
internal fun SceneScope.GarageFloor() {
    val apron = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.background,
        metallic = 0f,
        roughness = 0.9f,
    )
    val road = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.floor,
        metallic = 0f,
        roughness = 0.42f,
    )
    val paint = rememberUnlitMaterialInstance(materialLoader, AutoTokens.Stage.marking)
    val layer = GarageStage.FLOOR_LAYER
    // Bottom to top; each disc's top face is one layer above the one before.
    listOf(
        GarageStage.FLOOR_RADIUS to apron,
        GarageStage.EDGE_LINE_RADIUS + GarageStage.EDGE_LINE_WIDTH to paint,
        GarageStage.EDGE_LINE_RADIUS to road,
    ).forEachIndexed { index, (radius, material) ->
        CylinderNode(
            radius = radius,
            height = layer,
            sideCount = GarageStage.ROUND_SIDES,
            materialInstance = material,
            position = Position(y = GarageStage.FLOOR_Y - layer * (2.5f - index)),
        )
    }
    repeat(GarageStage.LANE_DASHES) { dash ->
        val degrees = FULL_TURN * dash / GarageStage.LANE_DASHES
        val radians = Math.toRadians(degrees.toDouble())
        CubeNode(
            size = Size(GarageStage.LANE_DASH_WIDTH, layer, GarageStage.LANE_DASH_LENGTH),
            materialInstance = paint,
            position = Position(
                x = GarageStage.LANE_RADIUS * sin(radians).toFloat(),
                y = GarageStage.FLOOR_Y,
                z = GarageStage.LANE_RADIUS * cos(radians).toFloat(),
            ),
            // Along the circle, not across it.
            rotation = Rotation(y = degrees + QUARTER_TURN),
        )
    }
    GaragePillars()
}

@Composable
private fun SceneScope.GaragePillars() {
    val concrete = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.pillar,
        metallic = 0f,
        roughness = 0.8f,
    )
    val band = rememberUnlitMaterialInstance(materialLoader, AutoTokens.Accent.primary)
    repeat(GarageStage.PILLARS) { pillar ->
        val degrees = FULL_TURN * (pillar + 0.5f) / GarageStage.PILLARS
        val radians = Math.toRadians(degrees.toDouble())
        Node(
            position = Position(
                x = GarageStage.PILLAR_RING_RADIUS * sin(radians).toFloat(),
                y = GarageStage.FLOOR_Y,
                z = GarageStage.PILLAR_RING_RADIUS * cos(radians).toFloat(),
            ),
            // One face to the podium.
            rotation = Rotation(y = degrees),
        ) {
            CubeNode(
                size = Size(GarageStage.PILLAR_SIDE, GarageStage.PILLAR_HEIGHT, GarageStage.PILLAR_SIDE),
                materialInstance = concrete,
                position = Position(y = GarageStage.PILLAR_HEIGHT / 2f),
            )
            CubeNode(
                size = Size(
                    GarageStage.PILLAR_SIDE + GarageStage.PILLAR_BAND_PROUD,
                    GarageStage.PILLAR_BAND_HEIGHT,
                    GarageStage.PILLAR_SIDE + GarageStage.PILLAR_BAND_PROUD,
                ),
                materialInstance = band,
                position = Position(y = GarageStage.PILLAR_BAND_Y),
            )
        }
    }
}

/** The podium and its light ring. It does not turn: what turns is the [CarMount] standing on it. */
@Composable
internal fun SceneScope.Podium() {
    val podium = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.podium,
        metallic = 0f,
        roughness = 0.3f,
    )
    val ring = rememberUnlitMaterialInstance(materialLoader, AutoTokens.Accent.primary)
    CylinderNode(
        radius = GarageStage.PODIUM_RADIUS,
        height = GarageStage.PODIUM_HEIGHT,
        sideCount = GarageStage.ROUND_SIDES,
        materialInstance = podium,
        position = Position(y = -GarageStage.PODIUM_HEIGHT / 2f),
    )
    CylinderNode(
        radius = GarageStage.RING_RADIUS,
        height = GarageStage.RING_HEIGHT,
        sideCount = GarageStage.ROUND_SIDES,
        materialInstance = ring,
        position = Position(y = GarageStage.FLOOR_Y + GarageStage.RING_HEIGHT / 2f),
    )
}

/**
 * Where the car stands, written by the frame loop: the centre of the podium turning with the
 * turntable, or the pose of [DriveModel] on the floor. Compose state, so a write recomposes the
 * [CarMount] transform and nothing else.
 */
@Stable
internal class MountPose {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
    var z by mutableFloatStateOf(0f)
    var yaw by mutableFloatStateOf(0f)

    fun onPodium(turntableYaw: Float) {
        x = 0f
        y = 0f
        z = 0f
        yaw = turntableYaw
    }

    fun onRoad(drive: DriveModel) {
        x = drive.x
        y = GarageStage.FLOOR_Y
        z = drive.z
        yaw = drive.heading
    }
}

/**
 * The one node every car hangs from. Everything inside [content] is in **car space** — origin
 * under the middle of the car, `y` up, nose along `+Z` — so the model, its shadow and its
 * headlights are declared once and follow the car from the turntable to the road.
 */
@Composable
internal fun SceneScope.CarMount(pose: MountPose, content: @Composable NodeScope.() -> Unit) {
    Node(
        position = Position(pose.x, pose.y, pose.z),
        rotation = Rotation(y = pose.yaw),
        content = content,
    )
}

/**
 * One car on the mount, with the soft shadow that grounds it.
 *
 * Every car of the catalog stays in the scene and only [visible] changes: a switch is then a
 * visibility flip on a model that is already uploaded, not a load — no pop, no empty podium.
 *
 * @param car        Catalog entry: its scale and how it rests on the ground.
 * @param shadow     Whether the car's contact shadow is drawn — only under the car on show.
 * @param onRoad     `true` in Drive mode: the model's showroom props are put away.
 * @param headlights Whether the car lights the road in front of it.
 */
@Composable
internal fun NodeScope.GarageCar(
    car: Car,
    instance: ModelInstance,
    visible: Boolean,
    shadow: Boolean,
    onRoad: Boolean,
    headlights: Boolean,
) {
    val model = remember(instance) { arrayOfNulls<ModelNode>(1) }
    Node(
        position = Position(y = -car.sink - if (onRoad) car.roadSink else 0f),
        rotation = Rotation(y = car.forwardYaw),
    ) {
        ModelNode(
            modelInstance = instance,
            scaleToUnits = car.length,
            // Bottom-aligned: the tyres rest on the ground whatever the model's own origin is.
            centerOrigin = Position(0f, -1f, 0f),
            isVisible = visible,
            apply = { model[0] = this },
        )
    }
    if (car.showroomOnly.isNotEmpty()) {
        SideEffect {
            model[0]?.renderableNodes
                ?.filter { it.name in car.showroomOnly }
                ?.forEach {
                    it.isVisible = !onRoad
                    // A hidden renderable still casts: without this the cloth's shadow drives along.
                    it.isShadowCaster = !onRoad
                }
        }
    }
    if (shadow) {
        ContactShadow(
            size = Size(
                car.bodyLength * GarageStage.SHADOW_WIDTH_RATIO,
                0f,
                car.bodyLength * GarageStage.SHADOW_LENGTH_RATIO,
            ),
            context = ContactShadowContext.Floor,
            intensity = GarageStage.SHADOW_INTENSITY,
            normal = Direction(y = 1f),
        )
    }
    if (headlights) Headlights(car.bodyLength)
}

/** Two spots at the car's nose, dipped onto the road. Children of the mount: they turn with it. */
@Composable
private fun NodeScope.Headlights(bodyLength: Float) {
    val color = remember { Colors.cct(GarageStage.HEADLIGHT_KELVIN).toColor() }
    listOf(-1f, 1f).forEach { side ->
        LightNode(
            type = LightManager.Type.FOCUSED_SPOT,
            intensity = GarageStage.HEADLIGHT_LUMENS,
            direction = Direction(0f, GarageStage.HEADLIGHT_DIP, 1f),
            position = Position(
                x = side * bodyLength * GarageStage.HEADLIGHT_SIDE_RATIO,
                y = bodyLength * GarageStage.HEADLIGHT_HEIGHT_RATIO,
                z = bodyLength * GarageStage.HEADLIGHT_FORWARD_RATIO,
            ),
            color = color,
            apply = {
                falloff(GarageStage.HEADLIGHT_REACH)
                spotLightCone(GarageStage.HEADLIGHT_CONE_INNER, GarageStage.HEADLIGHT_CONE_OUTER)
            },
        )
    }
}

private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
