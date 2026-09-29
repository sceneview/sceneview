package io.github.sceneview.demo.demos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import com.google.android.filament.Engine
import com.google.android.filament.IndexBuffer
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.android.filament.VertexBuffer
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneView
import io.github.sceneview.demo.DemoPreviewPlaceholder
import io.github.sceneview.demo.DemoScaffold
import io.github.sceneview.demo.DemoSettings
import io.github.sceneview.demo.DockItem
import io.github.sceneview.demo.R
import io.github.sceneview.demo.demos.internal.CosmosFraming
import io.github.sceneview.demo.demos.internal.CosmosMeshes
import io.github.sceneview.demo.demos.internal.CosmosScene
import io.github.sceneview.demo.demos.internal.GlowMesh
import io.github.sceneview.demo.demos.internal.RIBBON_STRIDE
import io.github.sceneview.demo.rememberFirstFrameState
import io.github.sceneview.demo.theme.LocalMotionEnabled
import io.github.sceneview.demo.theme.SceneViewTokens
import io.github.sceneview.math.Direction
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.node.MeshNode as MeshNodeImpl
import io.github.sceneview.node.Node as NodeImpl
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberRenderInvalidator
import io.github.sceneview.rememberView
import io.github.sceneview.safeDestroyIndexBuffer
import io.github.sceneview.safeDestroyVertexBuffer
import io.github.sceneview.sample.ui.LabeledSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import com.google.android.filament.Box as FilamentBox

/** Seconds each scene holds the screen before the tour moves on. */
private const val TOUR_SECONDS = 14f

/** One detonation of the burst, start to afterglow, in seconds. */
private const val BURST_PERIOD_SECONDS = 5.5f

/** How long a scene takes to fade in after a switch, in seconds. */
private const val REVEAL_SECONDS = 0.9f

private const val DEFAULT_BLOOM = 0.45f

/**
 * **Cosmos** — four procedural, real-time space scenes lit by nothing but their own light and
 * a bloom pass: a barred spiral galaxy, a plasma star, a particle-track burst and a vortex flow
 * field. Nothing is a video or a texture; every point and stroke is geometry built on the CPU
 * once ([CosmosMeshes]) and animated in the shader.
 *
 * ### The recipe it teaches
 *
 * Glow is **additive, unlit, HDR geometry plus bloom**:
 *
 * - Radiance above 1.0 is what the bloom pass bleeds from, so every colour here is written in
 *   linear HDR (a star core at 3–6, a dim dust grain at 0.05) and the tone mapper rolls it off.
 * - `blending: add` in the material makes overlapping light pile up — 26 000 arm stars stack
 *   into a white-hot bulge with no sorting at all.
 * - `PrimitiveType.POINTS` and `LINES` draw at one device pixel on mobile, so points are
 *   camera-facing quads and strokes are camera-facing ribbons, both expanded in the vertex
 *   shader (`cosmos_sprite.mat`, `cosmos_ribbon.mat`) from one static buffer.
 * - The star is a `SphereNode` with a noise material (`cosmos_plasma.mat`): domain-warped fbm,
 *   ridged filaments and a Fresnel limb.
 *
 * Animation is uniforms only — time, the burst's head and fade, the galaxy's spin — so a frame
 * costs a handful of `setParameter` calls and no buffer upload.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun CosmosDemo(onBack: () -> Unit) {
    if (LocalInspectionMode.current) {
        DemoPreviewPlaceholder(title = "Cosmos", onBack = onBack)
        return
    }

    var scene by remember { mutableStateOf(CosmosScene.Galaxy) }
    var touring by remember { mutableStateOf(!DemoSettings.qaMode) }
    var animating by remember { mutableStateOf(true) }
    var bloom by remember { mutableFloatStateOf(DEFAULT_BLOOM) }
    val motionEnabled = LocalMotionEnabled.current

    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val view = rememberView(engine)
    val renderInvalidator = rememberRenderInvalidator()
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0f, 6f)
        lookAt(Position(0f, 0f, 0f))
        // Room for the star field shell (radius ~44) behind a camera up to ~8 units out.
        far = 200f
        near = 0.05f
    }

    val spriteMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_sprite.filamat")
    }
    val ribbonMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_ribbon.filamat")
    }
    val plasmaMaterial by produceState<Material?>(null, materialLoader) {
        value = materialLoader.loadMaterial("materials/cosmos_plasma.filamat")
    }
    val sprites = remember(materialLoader, spriteMaterial) {
        spriteMaterial?.let { material ->
            SpriteInstances(List(SPRITE_SLOTS) { materialLoader.createInstance(material) })
        }
    }
    val ribbons = remember(materialLoader, ribbonMaterial) {
        ribbonMaterial?.let { material ->
            RibbonInstances(List(RIBBON_SLOTS) { materialLoader.createInstance(material) })
        }
    }
    val plasma = remember(materialLoader, plasmaMaterial) {
        plasmaMaterial?.let { material ->
            PlasmaInstances(
                star = materialLoader.createInstance(material).apply { setPlasma(STAR_PLASMA) },
                nucleus = materialLoader.createInstance(material).apply { setPlasma(NUCLEUS_PLASMA) },
            )
        }
    }

    // GPU meshes, built per scene on first use. The arrays are computed off the main thread;
    // the Filament buffers are created on it. Declared before the SceneView so they are
    // destroyed after the nodes that draw them.
    val meshes = remember { mutableStateMapOf<CosmosScene, SceneMeshes>() }
    val stars by produceState<GpuMesh?>(null, engine) {
        val cpu = withContext(Dispatchers.Default) { CosmosMeshes.starField().staged() }
        value = cpu.upload(engine)
    }
    LaunchedEffect(engine, scene) {
        // The selected scene first, then the others in the background so a switch is instant.
        val order = listOf(scene) + CosmosScene.entries.filter { it != scene }
        for (target in order) {
            if (meshes.containsKey(target)) continue
            val staged = withContext(Dispatchers.Default) { buildScene(target) }
            meshes[target] = staged.mapValues { (_, mesh) -> mesh.upload(engine) }.let(::SceneMeshes)
        }
    }
    DisposableEffect(engine) {
        onDispose {
            meshes.values.forEach { scene -> scene.parts.values.forEach { it.destroy(engine) } }
            meshes.clear()
        }
    }
    DisposableEffect(engine, stars) {
        val mesh = stars
        onDispose { mesh?.destroy(engine) }
    }

    val clock = remember { CosmosClock() }
    val galaxyNode = remember { arrayOfNulls<NodeImpl>(1) }
    val starNode = remember { arrayOfNulls<NodeImpl>(1) }
    val firstFrame = rememberFirstFrameState(engine)

    DemoScaffold(
        title = stringResource(R.string.demo_cosmos_title),
        onBack = onBack,
        firstFrameRendered = firstFrame.rendered,
        loadingLabel = stringResource(R.string.demo_cosmos_loading),
        peekHeader = "Four procedural scenes, lit only by bloom",
        onResetSettings = {
            touring = true
            animating = true
            bloom = DEFAULT_BLOOM
        },
        dock = listOf(
            sceneDockItem(CosmosScene.Galaxy, scene) { scene = it; touring = false },
            sceneDockItem(CosmosScene.Star, scene) { scene = it; touring = false },
            sceneDockItem(CosmosScene.Burst, scene) { scene = it; touring = false },
            sceneDockItem(CosmosScene.Flow, scene) { scene = it; touring = false },
        ),
        controls = {
            LabeledSlider(
                label = "Bloom",
                value = bloom,
                onValueChange = { bloom = it },
                valueRange = 0f..1f,
                decimals = 2,
            )
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.md))
            ToggleRow("Tour the scenes", touring) { touring = it }
            Spacer(modifier = Modifier.height(SceneViewTokens.Space.xs))
            ToggleRow("Animate", animating) { animating = it }
        },
    ) {
        SceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            view = view,
            materialLoader = materialLoader,
            cameraNode = cameraNode,
            cameraManipulator = null,
            autoCenterContent = false,
            frameRatePolicy = FrameRatePolicy.Continuous(),
            renderInvalidator = renderInvalidator,
            onFrame = { nanos ->
                firstFrame.onFrame(nanos)
                val frozen = DemoSettings.qaMode || !motionEnabled
                val current = scene
                clock.advance(nanos, current, running = animating && !frozen)
                if (touring && !frozen && clock.sceneTime > TOUR_SECONDS) {
                    scene = CosmosScene.entries[(current.ordinal + 1) % CosmosScene.entries.size]
                }
                val time = if (frozen) QA_TIME[current.ordinal] else clock.sceneTime
                val reveal = if (frozen) 1f else CosmosFraming.reveal(clock.sceneTime, REVEAL_SECONDS)
                val viewport = view.viewport
                val aspect = if (viewport.height > 0) viewport.width.toFloat() / viewport.height else 0.5f
                val pose = CosmosFraming.pose(current, time, aspect)
                cameraNode.lookAt(
                    eye = Position(pose[0], pose[1], pose[2]),
                    center = Position(pose[3], pose[4], pose[5]),
                    up = Direction(0f, 1f, 0f),
                )
                galaxyNode[0]?.rotation = Rotation(y = -time * GALAXY_SPIN_DEGREES_PER_SECOND)
                starNode[0]?.rotation = Rotation(y = time * STAR_SPIN_DEGREES_PER_SECOND, x = 12f)
                starNode[0]?.scale = Scale(1f + 0.012f * sin(time * 2.1f))
                sprites?.update(current, time, reveal)
                ribbons?.update(current, time, reveal)
                plasma?.update(current, time, reveal)
            },
        ) {
            val sceneMeshes = meshes[scene]
            stars?.let { mesh -> sprites?.let { GlowMeshNode(mesh, it.starField) } }
            val materialsReady = sprites != null && ribbons != null && plasma != null
            if (sceneMeshes != null && materialsReady) {
                when (scene) {
                    CosmosScene.Galaxy -> Node(apply = { galaxyNode[0] = this }) {
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.galaxy)
                    }
                    CosmosScene.Star -> {
                        Node(apply = { starNode[0] = this }) {
                            SphereNode(
                                radius = 1f,
                                stacks = 64,
                                slices = 96,
                                materialInstance = plasma.star,
                                apply = {
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                },
                            )
                            GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.prominences)
                        }
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.halo)
                    }
                    CosmosScene.Burst -> {
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.burst)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Dust), sprites.sparks)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.flash)
                    }
                    CosmosScene.Flow -> {
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Main), sprites.backdrop)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Strokes), ribbons.flow)
                        GlowMeshNode(sceneMeshes.parts.getValue(CosmosPart.Dust), sprites.dust)
                        CosmosMeshes.flowVortices.take(NUCLEI).forEachIndexed { index, vortex ->
                            SphereNode(
                                radius = NUCLEUS_RADII[index],
                                stacks = 24,
                                slices = 32,
                                materialInstance = plasma.nucleus,
                                position = Position(
                                    vortex.x,
                                    vortex.y,
                                    CosmosMeshes.flowDepth(vortex.x, vortex.y) + NUCLEUS_RADII[index] * 0.4f,
                                ),
                                apply = {
                                    isShadowCaster = false
                                    isShadowReceiver = false
                                },
                            )
                        }
                    }
                }
            }
        }
        // Raw View writes, declared after the SceneView so they land after its own
        // render-quality effect (#1078). Nothing here casts or receives a shadow and nothing
        // is lit, so SSAO and shadows are pure cost; bloom is the whole look.
        LaunchedEffect(view, bloom) {
            view.bloomOptions = view.bloomOptions.also { options ->
                options.enabled = bloom > 0f
                options.strength = bloom
                options.levels = 7
                options.resolution = 512
                options.threshold = true
                options.lensFlare = false
            }
            view.ambientOcclusionOptions = view.ambientOcclusionOptions.also { it.enabled = false }
            view.setShadowingEnabled(false)
            renderInvalidator.requestRender()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    // Toggleable on the whole row, so tapping the label flips it and UiAutomator finds it.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = value, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = null)
    }
}

private fun sceneDockItem(target: CosmosScene, current: CosmosScene, select: (CosmosScene) -> Unit) = DockItem(
    icon = when (target) {
        CosmosScene.Galaxy -> Icons.Filled.Cyclone
        CosmosScene.Star -> Icons.Filled.WbSunny
        CosmosScene.Burst -> Icons.Filled.Flare
        CosmosScene.Flow -> Icons.Filled.Waves
    },
    label = target.label,
    onClick = { select(target) },
    selected = current == target,
)

@Composable
private fun io.github.sceneview.SceneScope.GlowMeshNode(mesh: GpuMesh, material: MaterialInstance) {
    MeshNode(
        primitiveType = RenderableManager.PrimitiveType.TRIANGLES,
        vertexBuffer = mesh.vertexBuffer,
        indexBuffer = mesh.indexBuffer,
        boundingBox = mesh.box,
        materialInstance = material,
        apply = { configureGlow() },
    )
}

private fun MeshNodeImpl.configureGlow() {
    isShadowCaster = false
    isShadowReceiver = false
}

/** Frozen per-scene times for QA captures and reduced motion: each at its most telling moment. */
private val QA_TIME = floatArrayOf(6f, 3f, 1.9f, 4f)

private const val GALAXY_SPIN_DEGREES_PER_SECOND = 3.5f
private const val STAR_SPIN_DEGREES_PER_SECOND = 4f
private const val NUCLEI = 4
private val NUCLEUS_RADII = floatArrayOf(0.1f, 0.075f, 0.06f, 0.08f)

/** Which mesh of a scene a buffer pair is. */
private enum class CosmosPart { Main, Strokes, Dust }

private class SceneMeshes(val parts: Map<CosmosPart, GpuMesh>)

private fun buildScene(scene: CosmosScene): Map<CosmosPart, StagedMesh> = when (scene) {
    CosmosScene.Galaxy -> mapOf(CosmosPart.Main to CosmosMeshes.galaxy().staged())
    CosmosScene.Star -> mapOf(
        CosmosPart.Main to CosmosMeshes.starHalo().staged(),
        CosmosPart.Strokes to CosmosMeshes.prominences().staged(),
    )
    CosmosScene.Burst -> mapOf(
        CosmosPart.Main to CosmosMeshes.burstCore().staged(),
        CosmosPart.Strokes to CosmosMeshes.burst().staged(),
        CosmosPart.Dust to CosmosMeshes.burstSparks().staged(),
    )
    CosmosScene.Flow -> mapOf(
        CosmosPart.Main to CosmosMeshes.flowBackdrop().staged(),
        CosmosPart.Strokes to CosmosMeshes.flowField().staged(),
        CosmosPart.Dust to CosmosMeshes.flowDust().staged(),
    )
}

/** A mesh copied into direct buffers, ready for Filament — built off the main thread. */
private class StagedMesh(val mesh: GlowMesh, val vertices: ByteBuffer, val indices: ByteBuffer)

private fun GlowMesh.staged(): StagedMesh {
    val v = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    v.asFloatBuffer().put(vertices)
    val i = ByteBuffer.allocateDirect(indices.size * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
    i.asIntBuffer().put(indices)
    return StagedMesh(this, v, i)
}

private class GpuMesh(val vertexBuffer: VertexBuffer, val indexBuffer: IndexBuffer, val box: FilamentBox) {
    fun destroy(engine: Engine) {
        engine.safeDestroyVertexBuffer(vertexBuffer)
        engine.safeDestroyIndexBuffer(indexBuffer)
    }
}

/** Main thread only: Filament buffer creation is a JNI call into the engine. */
private fun StagedMesh.upload(engine: Engine): GpuMesh {
    val strideBytes = mesh.stride * Float.SIZE_BYTES
    val builder = VertexBuffer.Builder()
        .bufferCount(1)
        .vertexCount(mesh.vertexCount)
        .attribute(VertexBuffer.VertexAttribute.POSITION, 0, VertexBuffer.AttributeType.FLOAT3, 0, strideBytes)
        .attribute(VertexBuffer.VertexAttribute.COLOR, 0, VertexBuffer.AttributeType.FLOAT4, 12, strideBytes)
        .attribute(VertexBuffer.VertexAttribute.CUSTOM0, 0, VertexBuffer.AttributeType.FLOAT4, 28, strideBytes)
    if (mesh.stride == RIBBON_STRIDE) {
        builder.attribute(VertexBuffer.VertexAttribute.CUSTOM1, 0, VertexBuffer.AttributeType.FLOAT4, 44, strideBytes)
    }
    val vertexBuffer = builder.build(engine)
    vertexBuffer.setBufferAt(engine, 0, vertices)
    val indexBuffer = IndexBuffer.Builder()
        .indexCount(mesh.indices.size)
        .bufferType(IndexBuffer.Builder.IndexType.UINT)
        .build(engine)
    indexBuffer.setBuffer(engine, indices)
    val min = mesh.boundsMin
    val max = mesh.boundsMax
    val box = FilamentBox(
        (min[0] + max[0]) / 2f, (min[1] + max[1]) / 2f, (min[2] + max[2]) / 2f,
        (max[0] - min[0]) / 2f, (max[1] - min[1]) / 2f, (max[2] - min[2]) / 2f,
    )
    return GpuMesh(vertexBuffer, indexBuffer, box)
}

/** Seconds since the current scene came on screen, advanced only while animating. */
private class CosmosClock {
    var sceneTime = 0f
        private set
    private var lastNanos = 0L
    private var scene: CosmosScene? = null

    fun advance(nanos: Long, current: CosmosScene, running: Boolean) {
        if (scene != current) {
            scene = current
            sceneTime = 0f
        } else if (running && lastNanos != 0L) {
            // Clamp a long hitch (backgrounding, a GC) so the scene does not jump ahead.
            sceneTime += ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        }
        lastNanos = nanos
    }
}

private const val SPRITE_SLOTS = 8
private const val RIBBON_SLOTS = 3

private class SpriteInstances(all: List<MaterialInstance>) {
    val starField = all[0]
    val galaxy = all[1]
    val halo = all[2]
    val sparks = all[3]
    val flash = all[4]
    val dust = all[5]
    val backdrop = all[6]

    init {
        all.forEach { instance ->
            instance.setParameter("time", 0f)
            instance.setParameter("intensity", 1f)
            instance.setParameter("sizeScale", 1f)
            instance.setParameter("twinkle", 0f)
            instance.setParameter("minPixels", 1.1f)
        }
        starField.setParameter("twinkle", 0.3f)
        starField.setParameter("minPixels", 1.4f)
        galaxy.setParameter("twinkle", 0.12f)
        galaxy.setParameter("minPixels", 1.0f)
        sparks.setParameter("twinkle", 0.6f)
        sparks.setParameter("minPixels", 1.6f)
        dust.setParameter("twinkle", 0.7f)
        dust.setParameter("minPixels", 1.3f)
    }

    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        starField.setParameter("time", time)
        starField.setParameter("intensity", reveal * if (scene == CosmosScene.Flow) 0.35f else 1f)
        when (scene) {
            CosmosScene.Galaxy -> {
                galaxy.setParameter("time", time)
                galaxy.setParameter("intensity", reveal)
            }
            CosmosScene.Star -> {
                halo.setParameter("intensity", reveal * (1f + 0.12f * sin(time * 2.1f)))
            }
            CosmosScene.Burst -> {
                val envelope = CosmosMeshes.burstEnvelope((time / BURST_PERIOD_SECONDS) % 1f)
                sparks.setParameter("time", time)
                sparks.setParameter("intensity", reveal * envelope[1] * envelope[0].coerceAtMost(1f))
                sparks.setParameter("sizeScale", 0.4f + 0.6f * envelope[0].coerceAtMost(1f))
                flash.setParameter("intensity", reveal * envelope[2])
            }
            CosmosScene.Flow -> {
                dust.setParameter("time", time)
                dust.setParameter("intensity", reveal)
                backdrop.setParameter("intensity", reveal)
            }
        }
    }
}

private class RibbonInstances(all: List<MaterialInstance>) {
    val burst = all[0]
    val flow = all[1]
    val prominences = all[2]

    init {
        all.forEach { instance ->
            instance.setParameter("time", 0f)
            instance.setParameter("intensity", 1f)
            instance.setParameter("widthScale", 1f)
            instance.setParameter("minPixels", 1.0f)
            instance.setParameter("head", 10f)
            instance.setParameter("headGlow", 0f)
            instance.setParameter("base", 1f)
            instance.setParameter("dashAmp", 0f)
            instance.setParameter("dashFreq", 0f)
            instance.setParameter("dashSpeed", 0f)
            instance.setParameter("fade", 1f)
            instance.setParameter("tailTaper", 0f)
        }
        burst.setParameter("headGlow", 3f)
        burst.setParameter("base", 0.75f)
        burst.setParameter("dashAmp", 0.5f)
        burst.setParameter("dashFreq", TWO_PI * 3f)
        burst.setParameter("dashSpeed", 7f)
        burst.setParameter("tailTaper", 0.35f)
        flow.setParameter("base", 0.5f)
        flow.setParameter("dashAmp", 1.3f)
        flow.setParameter("dashFreq", TWO_PI * 5f)
        flow.setParameter("dashSpeed", 2.5f)
        prominences.setParameter("base", 0.6f)
        prominences.setParameter("dashAmp", 1.6f)
        prominences.setParameter("dashFreq", TWO_PI * 3.5f)
        prominences.setParameter("dashSpeed", 1.6f)
        prominences.setParameter("tailTaper", 0.2f)
    }

    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        when (scene) {
            CosmosScene.Burst -> {
                val envelope = CosmosMeshes.burstEnvelope((time / BURST_PERIOD_SECONDS) % 1f)
                burst.setParameter("time", time)
                burst.setParameter("head", envelope[0])
                burst.setParameter("fade", envelope[1])
                burst.setParameter("intensity", reveal)
            }
            CosmosScene.Flow -> {
                flow.setParameter("time", time)
                flow.setParameter("intensity", reveal)
            }
            CosmosScene.Star -> {
                prominences.setParameter("time", time)
                prominences.setParameter("intensity", reveal)
            }
            CosmosScene.Galaxy -> Unit
        }
    }
}

/** Surface colours of a plasma instance: deep, hot, rim (linear rgb), rim power, scale, flow. */
private class PlasmaLook(
    val deep: FloatArray,
    val hot: FloatArray,
    val rim: FloatArray,
    val rimPower: Float,
    val noiseScale: Float,
    val flow: Float,
)

private val STAR_PLASMA = PlasmaLook(
    deep = floatArrayOf(0.09f, 0.42f, 0.95f),
    hot = floatArrayOf(0.26f, 0.8f, 1.7f),
    rim = floatArrayOf(0.3f, 0.9f, 2.2f),
    rimPower = 3f,
    noiseScale = 9f,
    flow = 0.22f,
)

private val NUCLEUS_PLASMA = PlasmaLook(
    deep = floatArrayOf(0.004f, 0.008f, 0.02f),
    hot = floatArrayOf(0.03f, 0.07f, 0.16f),
    rim = floatArrayOf(0.25f, 0.6f, 1.6f),
    rimPower = 3f,
    noiseScale = 4f,
    flow = 0.2f,
)

private fun MaterialInstance.setPlasma(look: PlasmaLook, gain: Float = 1f) {
    setParameter("time", 0f)
    setParameter("deepColor", look.deep[0] * gain, look.deep[1] * gain, look.deep[2] * gain)
    setParameter("hotColor", look.hot[0] * gain, look.hot[1] * gain, look.hot[2] * gain)
    setParameter("rimColor", look.rim[0] * gain, look.rim[1] * gain, look.rim[2] * gain)
    setParameter("rimPower", look.rimPower)
    setParameter("noiseScale", look.noiseScale)
    setParameter("flow", look.flow)
}

private class PlasmaInstances(val star: MaterialInstance, val nucleus: MaterialInstance) {
    fun update(scene: CosmosScene, time: Float, reveal: Float) {
        when (scene) {
            CosmosScene.Star -> {
                star.setPlasma(STAR_PLASMA, gain = reveal * (1f + 0.08f * sin(time * 2.1f)))
                star.setParameter("time", time)
            }
            CosmosScene.Flow -> nucleus.setParameter("time", time)
            else -> Unit
        }
    }
}

private const val TWO_PI = (2.0 * PI).toFloat()
