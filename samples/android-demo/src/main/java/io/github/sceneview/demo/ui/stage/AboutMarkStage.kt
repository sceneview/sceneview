package io.github.sceneview.demo.ui.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import io.github.sceneview.demo.R
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Size
import io.github.sceneview.math.Position
import io.github.sceneview.node.Node
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val ABOUT_HDR = "environments/studio_2k.hdr"

/**
 * The SceneView mark as a real object: the launcher icon's cube, glossy, lit by the studio
 * HDR, floating over the About page with two tilted orbit rings — the Cosmos ringed world,
 * told with the brand's own shape. It turns a sixteenth of a turn every second, bobs, and two
 * satellites ride the rings. No card behind it: the identity block is not a card (#3565).
 *
 * Until the stage has presented its first frames the launcher icon stands where the cube
 * will be, and the two crossfade.
 *
 * @param active The block is on screen. False parks the stage on its last frame.
 */
@Composable
internal fun AboutMarkStage(active: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val shadow = if (dark) SceneViewTokens.MarkColor.shadowDark else SceneViewTokens.MarkColor.shadowLight
    val body = remember { arrayOfNulls<Node>(1) }
    val ringA = remember { arrayOfNulls<Node>(1) }
    val ringB = remember { arrayOfNulls<Node>(1) }
    val satelliteA = remember { arrayOfNulls<Node>(1) }
    val satelliteB = remember { arrayOfNulls<Node>(1) }
    val pivot = remember { PivotTransform() }

    Box(modifier.fillMaxWidth().height(SceneViewTokens.About.stageHeight)) {
        // The contact shadow is Compose, under the transparent stage: a flat ellipse the
        // mark hovers over. It does not follow the bob — a still ground reads as calm.
        Canvas(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = SceneViewTokens.Space.sm)
                .size(
                    width = SceneViewTokens.About.stageShadowWidth,
                    height = SceneViewTokens.About.stageShadowHeight,
                ),
        ) {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(shadow, Color.Transparent),
                    center = center,
                    radius = size.width / 2f,
                ),
                size = size,
            )
        }
        ShellStage(
            active = active,
            hdrPath = ABOUT_HDR,
            eye = MarkScene.eye,
            target = MarkScene.target,
            restSeconds = MarkScene.REST_SECONDS,
            keyLight = MarkScene.keyLight,
            keyLightLux = MarkScene.KEY_LIGHT_LUX,
            modifier = Modifier.fillMaxSize(),
            placeholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.drawable.ic_sceneview_hero),
                        contentDescription = null,
                        modifier = Modifier
                            .size(SceneViewTokens.About.markSize)
                            .clip(RoundedCornerShape(SceneViewTokens.Radius.xl)),
                    )
                }
            },
            onFrame = { seconds ->
                val t = seconds.toFloat()
                val bob = MarkScene.BOB_UNITS * wave(t, MarkScene.BOB_PERIOD)
                body[0]?.let {
                    pivot.write(
                        it,
                        y = bob,
                        yawDegrees = MarkScene.YAW_START + t * MarkScene.YAW_DEGREES_PER_SECOND,
                        pitchDegrees = MarkScene.WOBBLE_DEGREES * wave(t, MarkScene.WOBBLE_PERIOD_X),
                        rollDegrees = MarkScene.WOBBLE_DEGREES * wave(t, MarkScene.WOBBLE_PERIOD_Z),
                    )
                }
                ringA[0]?.let {
                    pivot.write(
                        it,
                        y = bob * MarkScene.RING_BOB_SHARE,
                        yawDegrees = MarkScene.RING_A_YAW + t * MarkScene.RING_A_PRECESSION,
                        pitchDegrees = MarkScene.RING_A_PITCH,
                        rollDegrees = MarkScene.RING_A_ROLL,
                    )
                }
                ringB[0]?.let {
                    pivot.write(
                        it,
                        y = bob * MarkScene.RING_BOB_SHARE,
                        yawDegrees = MarkScene.RING_B_YAW + t * MarkScene.RING_B_PRECESSION,
                        pitchDegrees = MarkScene.RING_B_PITCH,
                        rollDegrees = MarkScene.RING_B_ROLL,
                    )
                }
                satelliteA[0]?.let {
                    val angle = MarkScene.SAT_A_START + t * MarkScene.SAT_A_DEGREES_PER_SECOND
                    val radians = angle * DEG
                    pivot.write(
                        it,
                        x = MarkScene.RING_A_RADIUS * cos(radians),
                        z = MarkScene.RING_A_RADIUS * sin(radians),
                        yawDegrees = angle * 2f,
                        pitchDegrees = MarkScene.SAT_A_TILT,
                    )
                }
                satelliteB[0]?.let {
                    val radians = (MarkScene.SAT_B_START + t * MarkScene.SAT_B_DEGREES_PER_SECOND) * DEG
                    pivot.write(
                        it,
                        x = MarkScene.RING_B_RADIUS * cos(radians),
                        z = MarkScene.RING_B_RADIUS * sin(radians),
                    )
                }
            },
        ) {
            val bodyMaterial = remember(materialLoader) {
                materialLoader.createColorInstance(
                    color = SceneViewTokens.MarkColor.body,
                    metallic = 0f,
                    roughness = MarkScene.BODY_ROUGHNESS,
                    reflectance = MarkScene.BODY_REFLECTANCE,
                )
            }
            val lidMaterial = remember(materialLoader) {
                materialLoader.createColorInstance(
                    color = SceneViewTokens.MarkColor.lid,
                    metallic = 0f,
                    roughness = MarkScene.LID_ROUGHNESS,
                    reflectance = MarkScene.BODY_REFLECTANCE,
                )
            }
            val orbitMaterial = remember(materialLoader) {
                materialLoader.createUnlitColorInstance(SceneViewTokens.MarkColor.orbit)
            }
            Node(apply = { body[0] = this }) {
                CubeNode(
                    size = Size(MarkScene.CUBE_UNITS),
                    materialInstance = bodyMaterial,
                    apply = { isShadowCaster = false },
                )
                CubeNode(
                    size = Size(MarkScene.LID_UNITS, MarkScene.LID_THICKNESS, MarkScene.LID_UNITS),
                    center = Position(y = MarkScene.CUBE_UNITS / 2f + MarkScene.LID_THICKNESS / 2f),
                    materialInstance = lidMaterial,
                    apply = { isShadowCaster = false },
                )
            }
            Node(apply = { ringA[0] = this }) {
                TorusNode(
                    majorRadius = MarkScene.RING_A_RADIUS,
                    minorRadius = MarkScene.RING_TUBE,
                    majorSegments = MarkScene.RING_SEGMENTS,
                    minorSegments = MarkScene.RING_TUBE_SEGMENTS,
                    materialInstance = orbitMaterial,
                    apply = { isShadowCaster = false },
                )
                Node(apply = { satelliteA[0] = this }) {
                    CubeNode(
                        size = Size(MarkScene.SAT_A_UNITS),
                        materialInstance = bodyMaterial,
                        apply = { isShadowCaster = false },
                    )
                }
            }
            Node(apply = { ringB[0] = this }) {
                TorusNode(
                    majorRadius = MarkScene.RING_B_RADIUS,
                    minorRadius = MarkScene.RING_TUBE,
                    majorSegments = MarkScene.RING_SEGMENTS,
                    minorSegments = MarkScene.RING_TUBE_SEGMENTS,
                    materialInstance = orbitMaterial,
                    apply = { isShadowCaster = false },
                )
                Node(apply = { satelliteB[0] = this }) {
                    SphereNode(
                        radius = MarkScene.SAT_B_RADIUS,
                        materialInstance = orbitMaterial,
                        apply = { isShadowCaster = false },
                    )
                }
            }
        }
    }
}

private const val DEG = (PI / 180.0).toFloat()
private const val TWO_PI = (2.0 * PI).toFloat()

/** A sine of the given period in seconds, in `[-1, 1]`. */
private fun wave(seconds: Float, period: Float): Float = sin(seconds * TWO_PI / period)

/** Art direction of the floating mark — world units, degrees and seconds, not UI tokens. */
private object MarkScene {
    /** Raised ~24°, so the top face reads as the mark's lit rhombus. */
    val eye = Position(0f, 1.32f, 3.0f)
    val target = Position(0f, 0.02f, 0f)

    /** Pose drawn under reduced motion: both satellites in front of the cube. */
    const val REST_SECONDS = 1.4

    /** From the right, a little above: one side face lit, the other in shade — the icon's two blues. */
    val keyLight = Direction(-0.86f, -0.48f, -0.18f)

    /** Softer than the shared key: on a white page a brighter cube washes to one pale blue. */
    const val KEY_LIGHT_LUX = 42_000f

    const val CUBE_UNITS = 1f
    const val LID_UNITS = 0.64f
    const val LID_THICKNESS = 0.03f
    const val BODY_ROUGHNESS = 0.28f
    const val LID_ROUGHNESS = 0.35f
    const val BODY_REFLECTANCE = 0.4f

    const val BOB_UNITS = 0.06f
    const val BOB_PERIOD = 4.8f
    /** The launcher icon's view: a corner toward the camera. */
    const val YAW_START = 45f
    const val YAW_DEGREES_PER_SECOND = 16f
    const val WOBBLE_DEGREES = 3f
    const val WOBBLE_PERIOD_X = 7.3f
    const val WOBBLE_PERIOD_Z = 6.1f

    /** The rings bob with the cube, a little less — they read as held by it, not glued. */
    const val RING_BOB_SHARE = 0.6f
    const val RING_TUBE = 0.011f
    const val RING_SEGMENTS = 96
    const val RING_TUBE_SEGMENTS = 8

    const val RING_A_RADIUS = 1.08f
    const val RING_A_YAW = 20f
    const val RING_A_PRECESSION = 6f
    const val RING_A_PITCH = 16f
    const val RING_A_ROLL = -12f

    const val RING_B_RADIUS = 1.34f
    const val RING_B_YAW = -35f
    const val RING_B_PRECESSION = -4f
    const val RING_B_PITCH = -9f
    const val RING_B_ROLL = 20f

    const val SAT_A_UNITS = 0.13f
    const val SAT_A_START = 60f
    const val SAT_A_DEGREES_PER_SECOND = 34f
    const val SAT_A_TILT = 25f

    const val SAT_B_RADIUS = 0.045f
    const val SAT_B_START = 150f
    const val SAT_B_DEGREES_PER_SECOND = -22f
}
