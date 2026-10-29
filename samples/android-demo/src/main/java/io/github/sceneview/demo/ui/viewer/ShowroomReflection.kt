package io.github.sceneview.demo.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.google.android.filament.Camera
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderTarget
import com.google.android.filament.Renderer
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.Viewport
import io.github.sceneview.math.Position
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The subject's mirror image in the showroom floor — the polished floor of a car showroom, as on
 * the web viewer's `/view`.
 *
 * A second Filament view renders the subject alone (it sees layer [MIRROR_LAYER] only, which the
 * subject's renderables carry and the floor, the shadows and the backdrop do not) from the eye
 * mirrored under the floor, into a small RGBA16F texture: linear, pre-exposed, alpha = coverage.
 * `showroom_floor.mat` projects every floor point into that texture and lays the image over the
 * floor after lighting, fading it out a little up the subject and inside its footprint
 * ([ShowroomMath.reflectionFadeHeight], [ShowroomMath.reflectionInset]).
 *
 * The mirror is rendered from [onFrame], right after a presented frame, so it is one frame behind
 * the eye: [onFrame] says when the eye or the subject moved, and the caller asks for the frame
 * that shows the new image. A parked scene renders nothing.
 */
internal class ShowroomReflection(private val engine: Engine, private val mainView: View) {

    /** Where the subject stands, set from the composition. `null` = no reflection. */
    data class Pose(
        /** The subject's bounding-box centre, at rest — the floor quad's centre. */
        val center: Position,
        /** The floor's surface. */
        val floorY: Float,
        /** The subject's bounding radius — the floor quad is [ShowroomMath.FLOOR_SIZE_RADII] of them. */
        val radius: Float,
        /** The subject's unturned footprint, half width and half depth, in metres. */
        val halfWidth: Float,
        val halfDepth: Float,
        /** The subject's height, in metres. */
        val height: Float,
        /** The subject's turn about Y, in degrees. */
        val yawDegrees: Float,
        /** How far the settle spring still holds the subject above its resting pose, in metres. */
        val lift: Float,
        val isDark: Boolean,
    )

    private val renderer: Renderer = engine.createRenderer().apply {
        clearOptions = clearOptions.also {
            it.clear = true
            it.discard = true
            it.clearColor = doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        }
    }
    private val cameraEntity = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val view: View = engine.createView().apply {
        camera = this@ShowroomReflection.camera
        // The mirror image is linear HDR, tone-mapped once, with the floor, by the main view.
        isPostProcessingEnabled = false
        setShadowingEnabled(false)
        ambientOcclusionOptions = ambientOcclusionOptions.also { it.enabled = false }
        // A mirror flips the handedness: the subject's front faces would be culled.
        isFrontFaceWindingInverted = true
        setVisibleLayers(ALL_LAYERS, MIRROR_LAYER)
    }
    private var color: Texture? = null
    private var depth: Texture? = null
    private var target: RenderTarget? = null
    private var width = 0
    private var height = 0

    private var material: MaterialInstance? = null
    private var pose: Pose? = null
    private val lastEye = DoubleArray(MAT4)
    private var lastPose: Pose? = null
    private var lit = false
    private var skipped = false

    private val eyeModel = DoubleArray(MAT4)
    private val mirrorModel = DoubleArray(MAT4)
    private val mirrorView = DoubleArray(MAT4)
    private val mirrorProjection = DoubleArray(MAT4)
    private val culling = DoubleArray(MAT4)
    private val scaling = DoubleArray(4)
    private val shift = DoubleArray(2)
    private val reflectionMatrix = FloatArray(MAT4)

    /**
     * Points the reflection at [floorMaterial] for [pose]; `null` turns it off. Main thread.
     * Returns whether the floor changed — the caller asks for a frame.
     */
    fun set(floorMaterial: MaterialInstance, pose: Pose?): Boolean {
        val changed = pose != this.pose || floorMaterial !== material
        if (material != null && material !== floorMaterial) switchOff()
        material = floorMaterial
        this.pose = pose
        if (pose == null) return switchOff() || changed
        return changed
    }

    /**
     * Renders the mirror image for the eye on screen, after a presented frame; `true` when the eye
     * or the subject moved since the last one, so the frame on screen shows a stale image.
     * Main thread, outside `beginFrame` / `endFrame`.
     */
    fun onFrame(): Boolean {
        val pose = pose ?: return false
        val material = material ?: return false
        val eye = mainView.camera ?: return false
        if (!prepareTarget(material)) return false
        return renderMirror(pose, material, eye)
    }

    /** Points the mirror view at the main view's scene and sizes its target; `false` before layout. */
    private fun prepareTarget(material: MaterialInstance): Boolean {
        val viewport = mainView.viewport
        if (viewport.width <= 0 || viewport.height <= 0) return false
        val scene = mainView.scene ?: return false
        // The same scene as the main view: the layer mask keeps only the subject.
        if (view.scene !== scene) view.scene = scene
        ensureTarget(viewport.width, viewport.height, material)
        return true
    }

    private fun renderMirror(pose: Pose, material: MaterialInstance, eye: Camera): Boolean {
        eye.getModelMatrix(eyeModel)
        val moved = !lit || pose != lastPose || !eyeModel.contentEquals(lastEye)
        if (!moved) return false
        // While the eye moves, every other frame: the image and its matrix stay paired, one
        // frame later than the subject, and the frame after the eye stops is always drawn.
        if (lit && skipped.not() && pose == lastPose) {
            skipped = true
            return true
        }
        skipped = false
        camera.setModelMatrix(ShowroomMath.mirrorModelMatrix(eyeModel, pose.floorY, mirrorModel))
        // The main camera's lens, padding shift and scaling, unchanged: the mirror image lines
        // up with the floor it is projected on.
        eye.getCullingProjectionMatrix(culling)
        camera.setCustomProjection(culling, culling, eye.near.toDouble(), eye.cullingFar.toDouble())
        eye.getScaling(scaling)
        camera.setScaling(scaling)
        eye.getShift(shift)
        camera.setShift(shift[0], shift[1])
        camera.setExposure(eye.aperture, eye.shutterSpeed, eye.sensitivity)
        renderer.renderStandaloneView(view)

        camera.getProjectionMatrix(mirrorProjection)
        camera.getViewMatrix(mirrorView)
        ShowroomMath.reflectionMatrix(
            projection = mirrorProjection,
            view = mirrorView,
            center = pose.center,
            floorY = pose.floorY,
            radius = pose.radius,
            out = reflectionMatrix,
        )
        writeParameters(material, pose, eyeModel)

        eyeModel.copyInto(lastEye)
        lastPose = pose
        lit = true
        return true
    }

    private fun writeParameters(material: MaterialInstance, pose: Pose, eye: DoubleArray) {
        val side = ShowroomMath.FLOOR_SIZE_RADII * pose.radius
        val yaw = Math.toRadians(pose.yawDegrees.toDouble())
        material.setParameter(PARAM_MATRIX, MaterialInstance.FloatElement.MAT4, reflectionMatrix, 0, 1)
        material.setParameter(
            PARAM_FOOTPRINT,
            pose.halfWidth / side,
            pose.halfDepth / side,
            cos(yaw).toFloat(),
            sin(yaw).toFloat(),
        )
        // In the order the compiled shader reads it — `eye.xz` is the ground position, `eye.y` the
        // height, `eye.w` the inset — not the x, z, height order the .mat header lists.
        material.setParameter(
            PARAM_EYE,
            ((eye[TX] - pose.center.x) / side).toFloat(),
            max((eye[TY] - pose.floorY).toFloat(), MIN_EYE_HEIGHT),
            ((eye[TZ] - pose.center.z) / side).toFloat(),
            ShowroomMath.reflectionInset(pose.halfWidth, pose.halfDepth) / side,
        )
        material.setParameter(
            PARAM_SHAPE,
            ShowroomMath.reflectionStrength(pose.isDark),
            ShowroomMath.reflectionFadeHeight(pose.height),
            1f / width,
            1f / height,
        )
    }

    private fun ensureTarget(viewportWidth: Int, viewportHeight: Int, material: MaterialInstance) {
        val w = max(1, (viewportWidth * ShowroomMath.REFLECTION_SCALE).roundToInt())
        val h = max(1, (viewportHeight * ShowroomMath.REFLECTION_SCALE).roundToInt())
        if (w == width && h == height && target != null) return
        destroyTarget()
        width = w
        height = h
        val colorTexture = Texture.Builder()
            .width(w).height(h).levels(1)
            .format(Texture.InternalFormat.RGBA16F)
            .usage(Texture.Usage.COLOR_ATTACHMENT or Texture.Usage.SAMPLEABLE)
            .build(engine)
        val depthTexture = Texture.Builder()
            .width(w).height(h).levels(1)
            .format(Texture.InternalFormat.DEPTH24)
            .usage(Texture.Usage.DEPTH_ATTACHMENT)
            .build(engine)
        target = RenderTarget.Builder()
            .texture(RenderTarget.AttachmentPoint.COLOR, colorTexture)
            .texture(RenderTarget.AttachmentPoint.DEPTH, depthTexture)
            .build(engine)
        color = colorTexture
        depth = depthTexture
        view.renderTarget = target
        view.viewport = Viewport(0, 0, w, h)
        material.setParameter(
            PARAM_TEXTURE,
            colorTexture,
            TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.CLAMP_TO_EDGE,
            ),
        )
    }

    /** Turns the floor's reflection off; `true` when it was on. */
    private fun switchOff(): Boolean {
        val wasLit = lit
        if (lit) material?.setParameter(PARAM_SHAPE, 0f, 1f, 0f, 0f)
        lit = false
        lastPose = null
        return wasLit
    }

    private fun destroyTarget() {
        view.renderTarget = null
        target?.let { engine.destroyRenderTarget(it) }
        color?.let { engine.destroyTexture(it) }
        depth?.let { engine.destroyTexture(it) }
        target = null
        color = null
        depth = null
        width = 0
        height = 0
    }

    /** Frees every Filament object; the floor material is left with its reflection off. */
    fun destroy() {
        switchOff()
        material = null
        pose = null
        destroyTarget()
        runCatching { engine.destroyView(view) }
        runCatching { engine.destroyCameraComponent(cameraEntity) }
        EntityManager.get().destroy(cameraEntity)
        runCatching { engine.destroyRenderer(renderer) }
    }

    companion object {
        /** The layer bit only the subject carries: its renderables are on `0x1 | MIRROR_LAYER`. */
        const val MIRROR_LAYER = 0x2
        private const val ALL_LAYERS = 0xff
        private const val MAT4 = 16
        private const val TX = 12
        private const val TY = 13
        private const val TZ = 14
        private const val MIN_EYE_HEIGHT = 1e-4f
        private const val PARAM_TEXTURE = "reflection"
        private const val PARAM_MATRIX = "reflectionMatrix"
        private const val PARAM_FOOTPRINT = "reflectionFootprint"
        private const val PARAM_EYE = "reflectionEye"
        private const val PARAM_SHAPE = "reflectionShape"
    }
}

/** The [ShowroomReflection] of [view], freed with the composition. */
@Composable
internal fun rememberShowroomReflection(engine: Engine, view: View): ShowroomReflection {
    val reflection = remember(engine, view) { ShowroomReflection(engine, view) }
    DisposableEffect(reflection) {
        onDispose { reflection.destroy() }
    }
    return reflection
}
