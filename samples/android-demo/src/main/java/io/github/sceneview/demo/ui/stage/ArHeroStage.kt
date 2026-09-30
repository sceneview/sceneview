package io.github.sceneview.demo.ui.stage

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.Node
import io.github.sceneview.rememberModelInstance
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

private const val AR_HERO_HDR = "environments/studio_warm_2k.hdr"

/**
 * The AR tab's hero: what AR *does*, playing on its own before the camera is ever opened.
 *
 * A dark stage (the Home hero's, in both themes) with a detected floor — a perspective field
 * of dots, projected with the 3D camera's own lens so it is the plane the models stand on —
 * that a ripple crosses every couple of seconds, like plane detection sweeping a room. On it a
 * reticle, and bundled models placed on the reticle one after the other: the fox, the sheen
 * chair, the shiba. Each one drops in with the placement ease, turns slowly, then gives way.
 *
 * Only the stage: the copy and the call to action are the caller's, drawn over it.
 *
 * @param active The hero is on screen. False parks the 3D and stops the ripple.
 */
@Composable
internal fun ArHeroStage(active: Boolean, modifier: Modifier = Modifier) {
    val moving = active && LocalMotionEnabled.current
    val slots = remember { arrayOfNulls<Node>(ArHeroScene.MODELS.size) }
    val reticle = remember { arrayOfNulls<Node>(1) }
    val pivot = remember { PivotTransform() }
    // The ripple's phase, read in the draw phase only: it redraws the dots, never recomposes.
    val ripple: State<Float> = if (moving) {
        rememberInfiniteTransition(label = "arHeroRipple").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(SceneViewTokens.ArHero.rippleMillis, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "arHeroRipplePhase",
        )
    } else {
        remember { mutableFloatStateOf(ArHeroScene.REST_RIPPLE) }
    }

    Box(
        modifier.background(
            Brush.verticalGradient(
                listOf(SceneViewTokens.ArHero.stageTop, SceneViewTokens.ArHero.stageBottom),
            ),
        ),
    ) {
        DetectedPlane(ripple = ripple, modifier = Modifier.fillMaxSize())
        ShellStage(
            active = active,
            hdrPath = AR_HERO_HDR,
            eye = ArHeroScene.eye,
            target = ArHeroScene.target,
            restSeconds = ArHeroScene.REST_SECONDS,
            modifier = Modifier.fillMaxSize(),
            onFrame = { seconds -> driveArHero(seconds, slots, reticle, pivot) },
        ) {
            val reticleMaterial = remember(materialLoader) {
                materialLoader.createUnlitColorInstance(SceneViewTokens.ArHero.planeDot)
            }
            Node(apply = { reticle[0] = this }) {
                TorusNode(
                    majorRadius = ArHeroScene.RETICLE_RADIUS,
                    minorRadius = ArHeroScene.RETICLE_TUBE,
                    majorSegments = ArHeroScene.RETICLE_SEGMENTS,
                    minorSegments = ArHeroScene.RETICLE_TUBE_SEGMENTS,
                    materialInstance = reticleMaterial,
                    apply = { isShadowCaster = false },
                )
                SphereNode(
                    radius = ArHeroScene.RETICLE_DOT,
                    materialInstance = reticleMaterial,
                    apply = { isShadowCaster = false },
                )
            }
            ArHeroScene.MODELS.forEachIndexed { index, model ->
                val instance = rememberModelInstance(modelLoader, model.path)
                if (instance != null) {
                    // Hidden until its slot comes: the frame loop scales it in.
                    Node(
                        scale = Scale(ArHeroScene.HIDDEN_SCALE),
                        apply = { slots[index] = this },
                    ) {
                        ModelNode(
                            modelInstance = instance,
                            autoAnimate = model.animation != null,
                            animationName = model.animation,
                            scaleToUnits = model.units,
                            centerOrigin = Position(0f, -1f, 0f),
                            apply = { isShadowCaster = false },
                        )
                    }
                }
            }
        }
    }
}

/** One frame of the placement loop. Allocation-free: pivots are written from one matrix. */
private fun driveArHero(
    seconds: Double,
    slots: Array<Node?>,
    reticle: Array<Node?>,
    pivot: PivotTransform,
) {
    val period = SceneViewTokens.ArHero.placementSeconds
    val cycle = floor(seconds / period).toInt()
    val current = cycle % slots.size
    val local = (seconds - cycle * period).toFloat()
    val t = seconds.toFloat()
    val enter = (local / ArHeroScene.ENTER_SECONDS).coerceIn(0f, 1f)
    val exit = ((local - (period.toFloat() - ArHeroScene.EXIT_SECONDS)) / ArHeroScene.EXIT_SECONDS)
        .coerceIn(0f, 1f)
    for (i in slots.indices) {
        val node = slots[i] ?: continue
        if (i != current) {
            pivot.write(node, scale = ArHeroScene.HIDDEN_SCALE)
            continue
        }
        val drop = 1f - easeOutCubic(enter)
        val grow = easeOutBack(enter) * (1f - easeInCubic(exit))
        pivot.write(
            node,
            y = ArHeroScene.DROP_UNITS * drop,
            yawDegrees = ArHeroScene.YAW_START + t * ArHeroScene.YAW_DEGREES_PER_SECOND,
            scale = (ArHeroScene.ENTER_SCALE + (1f - ArHeroScene.ENTER_SCALE) * grow)
                .coerceAtLeast(ArHeroScene.HIDDEN_SCALE),
        )
    }
    reticle[0]?.let { node ->
        // The reticle breathes, and answers each landing with one pulse.
        val landing = ((local - ArHeroScene.ENTER_SECONDS * LANDING_AT) / ArHeroScene.PULSE_SECONDS)
            .coerceIn(0f, 1f)
        val pulse = if (landing < 1f) ArHeroScene.PULSE_SCALE * sin(landing * PI.toFloat()) else 0f
        val breath = ArHeroScene.BREATH_SCALE * sin(t * 2f * PI.toFloat() / ArHeroScene.BREATH_PERIOD)
        pivot.write(node, y = ArHeroScene.RETICLE_LIFT, scale = 1f + pulse + breath)
    }
}

private const val LANDING_AT = 0.7f

private fun easeOutCubic(x: Float): Float {
    val u = 1f - x
    return 1f - u * u * u
}

private fun easeInCubic(x: Float): Float = x * x * x

private fun easeOutBack(x: Float): Float {
    val c1 = 1.4f
    val c3 = c1 + 1f
    val u = x - 1f
    return 1f + c3 * u * u * u + c1 * u * u
}

/**
 * The floor the camera "detected": dots on the world's y = 0 plane, projected through the
 * stage camera's own lens, so the reticle and the models stand exactly on them. A ripple runs
 * out from the reticle. Positions are computed once per size; the draw pass only reads the
 * ripple phase.
 */
@Composable
private fun DetectedPlane(ripple: State<Float>, modifier: Modifier) {
    val dotColor = SceneViewTokens.ArHero.planeDot
    val shadowColor = SceneViewTokens.ArHero.contactShadow
    Canvas(
        modifier.drawWithCache {
            val plane = projectPlane(size)
            val dotRadius = SceneViewTokens.ArHero.planeDotRadius.toPx()
            val shadowBrush = Brush.radialGradient(
                colors = listOf(shadowColor, Color.Transparent),
                center = Offset(plane.originX, plane.originY),
                radius = plane.shadowRadiusX,
            )
            onDrawBehind {
                drawContactShadow(plane, shadowBrush)
                drawPlaneDots(plane, ripple.value, dotColor, dotRadius)
            }
        },
    ) { }
}

private fun DrawScope.drawContactShadow(plane: ProjectedPlane, brush: Brush) {
    val rx = plane.shadowRadiusX
    val ry = plane.shadowRadiusY
    drawOval(
        brush = brush,
        topLeft = Offset(plane.originX - rx, plane.originY - ry),
        size = Size(rx * 2f, ry * 2f),
    )
}

private fun DrawScope.drawPlaneDots(plane: ProjectedPlane, phase: Float, color: Color, radius: Float) {
    val front = ArHeroScene.RIPPLE_REACH * phase
    val fadeOut = 1f - phase
    for (i in 0 until plane.count) {
        val d = plane.distance[i]
        val band = (d - front) / ArHeroScene.RIPPLE_WIDTH
        val wave = exp(-band * band) * fadeOut
        val alpha = (SceneViewTokens.ArHero.planeDotAlpha + SceneViewTokens.ArHero.planeRippleAlpha * wave) *
            plane.fade[i]
        if (alpha <= MIN_VISIBLE_ALPHA) continue
        drawCircle(
            color = color,
            radius = radius * plane.depthScale[i] * (1f + wave * RIPPLE_GROWTH),
            center = Offset(plane.x[i], plane.y[i]),
            alpha = alpha.coerceAtMost(1f),
        )
    }
}

private const val MIN_VISIBLE_ALPHA = 0.01f
private const val RIPPLE_GROWTH = 0.6f

/** Screen-space floor, one entry per dot, projected once per canvas size. */
private class ProjectedPlane(capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val depthScale = FloatArray(capacity)
    val fade = FloatArray(capacity)
    /** World distance from the reticle, for the ripple. */
    val distance = FloatArray(capacity)
    var count = 0
    var originX = 0f
    var originY = 0f
    var shadowRadiusX = 0f
    var shadowRadiusY = 0f
}

/**
 * Pinhole projection matching SceneView's default camera: a 28 mm lens on Filament's 24 mm
 * sensor — `tan(fovY / 2) = 12 / 28` — looking from [ArHeroScene.eye] at [ArHeroScene.target].
 */
private fun projectPlane(size: Size): ProjectedPlane {
    val eye = ArHeroScene.eye
    val target = ArHeroScene.target
    var fx = target.x - eye.x
    var fy = target.y - eye.y
    var fz = target.z - eye.z
    val fl = sqrt(fx * fx + fy * fy + fz * fz)
    fx /= fl; fy /= fl; fz /= fl
    // right = f × up(0,1,0)
    var rx = -fz
    val ry = 0f
    var rz = fx
    val rl = sqrt(rx * rx + rz * rz)
    rx /= rl; rz /= rl
    // up = right × f
    val ux = ry * fz - rz * fy
    val uy = rz * fx - rx * fz
    val uz = rx * fy - ry * fx
    val halfH = size.height / 2f
    val halfW = size.width / 2f
    val focal = halfH / LENS_TAN_HALF_FOV
    val out = FloatArray(3)

    fun project(wx: Float, wy: Float, wz: Float): Boolean {
        val dx = wx - eye.x
        val dy = wy - eye.y
        val dz = wz - eye.z
        val zc = dx * fx + dy * fy + dz * fz
        if (zc < NEAR) return false
        out[0] = halfW + (dx * rx + dy * ry + dz * rz) / zc * focal
        out[1] = halfH - (dx * ux + dy * uy + dz * uz) / zc * focal
        out[2] = zc
        return true
    }

    val steps = ArHeroScene.GRID_STEPS
    val plane = ProjectedPlane(steps * steps)
    val span = ArHeroScene.GRID_HALF_SPAN
    val step = span * 2f / (steps - 1)
    val reference = sqrt(
        (eye.x - target.x) * (eye.x - target.x) + eye.y * eye.y + (eye.z - target.z) * (eye.z - target.z),
    )
    for (i in 0 until steps) {
        for (j in 0 until steps) {
            val wx = -span + i * step
            val wz = -span + j * step + ArHeroScene.GRID_OFFSET_Z
            if (!project(wx, 0f, wz)) continue
            if (out[0] < -size.width * EDGE_SLACK || out[0] > size.width * (1f + EDGE_SLACK)) continue
            if (out[1] < 0f || out[1] > size.height) continue
            val n = plane.count
            plane.x[n] = out[0]
            plane.y[n] = out[1]
            plane.depthScale[n] = (reference / out[2]).coerceIn(MIN_DEPTH_SCALE, MAX_DEPTH_SCALE)
            val d = sqrt(wx * wx + wz * wz)
            plane.distance[n] = d
            // Far dots dissolve toward the horizon, and the field's edge is soft.
            plane.fade[n] = (1f - d / (span * 1.1f)).coerceIn(0f, 1f) *
                (reference / out[2]).coerceIn(0f, 1f)
            plane.count = n + 1
        }
    }
    if (project(0f, 0f, 0f)) {
        plane.originX = out[0]
        plane.originY = out[1]
        val originDepth = out[2]
        plane.shadowRadiusX = ArHeroScene.SHADOW_RADIUS / originDepth * focal
        // Foreshortened by the camera's pitch.
        plane.shadowRadiusY = plane.shadowRadiusX * (-fy).coerceIn(MIN_FORESHORTEN, 1f)
    }
    return plane
}

private const val LENS_TAN_HALF_FOV = 12f / 28f
private const val NEAR = 0.05f
private const val EDGE_SLACK = 0.02f
private const val MIN_DEPTH_SCALE = 0.35f
private const val MAX_DEPTH_SCALE = 1.8f
private const val MIN_FORESHORTEN = 0.2f

/** One model the hero places, from the bundled assets (no download, no APK growth). */
private class HeroModel(val path: String, val units: Float, val animation: String? = null)

/** Art direction of the AR hero — world units, degrees and seconds, not UI tokens. */
private object ArHeroScene {
    /** Raised and to the left of the reticle, so the models stand on the right of the copy. */
    val eye = Position(-0.4f, 0.74f, 1.9f)
    val target = Position(-0.46f, 0.1f, 0f)

    val MODELS = listOf(
        HeroModel("models/khronos_fox.glb", units = 0.64f, animation = "Survey"),
        HeroModel("models/khronos_sheen_chair.glb", units = 0.5f),
        HeroModel("models/shiba.glb", units = 0.5f),
    )

    /** Pose drawn under reduced motion: the fox, landed, three-quarter view. */
    const val REST_SECONDS = 1.6
    const val REST_RIPPLE = 0.4f

    const val HIDDEN_SCALE = 0.0001f
    const val ENTER_SECONDS = 0.6f
    const val EXIT_SECONDS = 0.3f
    const val ENTER_SCALE = 0.55f
    const val DROP_UNITS = 0.32f
    const val YAW_START = -30f
    const val YAW_DEGREES_PER_SECOND = 14f

    const val RETICLE_RADIUS = 0.3f
    const val RETICLE_TUBE = 0.005f
    const val RETICLE_DOT = 0.014f
    const val RETICLE_SEGMENTS = 72
    const val RETICLE_TUBE_SEGMENTS = 6
    /** Just above the floor, so the ring never z-fights the model's feet. */
    const val RETICLE_LIFT = 0.003f
    const val PULSE_SECONDS = 0.45f
    const val PULSE_SCALE = 0.22f
    const val BREATH_SCALE = 0.03f
    const val BREATH_PERIOD = 2.4f

    const val GRID_STEPS = 21
    const val GRID_HALF_SPAN = 2.4f
    /** The field runs further behind the reticle than in front of it. */
    const val GRID_OFFSET_Z = -0.9f
    const val RIPPLE_REACH = 2.8f
    const val RIPPLE_WIDTH = 0.22f
    const val SHADOW_RADIUS = 0.34f
}
