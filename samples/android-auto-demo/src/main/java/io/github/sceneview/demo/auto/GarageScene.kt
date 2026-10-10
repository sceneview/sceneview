package io.github.sceneview.demo.auto

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.github.sceneview.NodeScope
import io.github.sceneview.SceneScope
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Size
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.node.ContactShadowContext
import io.github.sceneview.sample.rememberMaterialInstance
import io.github.sceneview.sample.rememberUnlitMaterialInstance

/**
 * The static part of the garage: a dark floor that catches the environment, so the stage has a
 * horizon instead of floating in black.
 */
@Composable
internal fun SceneScope.GarageFloor() {
    val floor = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.background,
        metallic = 0f,
        roughness = 0.45f,
        reflectance = 0.5f,
    )
    CylinderNode(
        radius = GarageStage.FLOOR_RADIUS,
        height = GarageStage.FLOOR_HEIGHT,
        sideCount = GarageStage.ROUND_SIDES,
        materialInstance = floor,
        position = Position(y = -GarageStage.PODIUM_HEIGHT - GarageStage.FLOOR_HEIGHT / 2f),
    )
}

/**
 * The turntable: a glossy podium, its light ring, and whatever stands on it.
 *
 * Everything inside [content] is in **turntable space** — origin at the centre of the podium's
 * top face, `y` up, and it turns with the podium. This is the seam for what comes next: a
 * driving mini-game replaces the turntable's rotation with a vehicle node driven by input, and
 * its children (the car model, its shadow) stay exactly as they are declared here.
 *
 * @param yaw Turntable angle in degrees. Read here and nowhere above, so a spinning turntable
 *            recomposes this node's transform only.
 */
@Composable
internal fun SceneScope.Turntable(
    yaw: () -> Float,
    content: @Composable NodeScope.() -> Unit,
) {
    val podium = rememberMaterialInstance(
        materialLoader = materialLoader,
        color = AutoTokens.Stage.background,
        metallic = 1f,
        roughness = 0.22f,
        reflectance = 0.5f,
    )
    val ring = rememberUnlitMaterialInstance(materialLoader, AutoTokens.Accent.primary)
    Node(rotation = Rotation(y = yaw())) {
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
            position = Position(y = -GarageStage.PODIUM_HEIGHT + GarageStage.RING_HEIGHT / 2f),
        )
        content()
    }
}

/**
 * One car parked on the turntable, with the soft shadow that grounds it.
 *
 * Every car of the catalog stays in the scene and only [visible] changes: a switch is then a
 * visibility flip on a model that is already uploaded, not a load — no pop, no empty podium.
 *
 * @param shadow Whether the car's contact shadow is drawn — only under the car on show.
 */
@Composable
internal fun NodeScope.ParkedCar(
    instance: ModelInstance,
    visible: Boolean,
    shadow: Boolean,
) {
    ModelNode(
        modelInstance = instance,
        scaleToUnits = GarageStage.CAR_LENGTH,
        // Bottom-aligned: the tyres rest on the podium whatever the model's own origin is.
        centerOrigin = Position(0f, -1f, 0f),
        isVisible = visible,
    )
    if (shadow) {
        // glTF does not say which way a car points: the shadow follows the body's long axis.
        val lengthAlongX = remember(instance) {
            val halfExtent = instance.asset.boundingBox.halfExtent
            halfExtent[0] > halfExtent[2]
        }
        ContactShadow(
            size = if (lengthAlongX) {
                Size(GarageStage.SHADOW_LENGTH, 0f, GarageStage.SHADOW_WIDTH)
            } else {
                Size(GarageStage.SHADOW_WIDTH, 0f, GarageStage.SHADOW_LENGTH)
            },
            context = ContactShadowContext.Floor,
            normal = Direction(y = 1f),
        )
    }
}
