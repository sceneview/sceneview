package io.github.sceneview.demo.demos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Skybox
import com.google.android.filament.ToneMapper
import com.google.android.filament.utils.KTX1Loader
import io.github.sceneview.DEFAULT_IBL_INTENSITY
import io.github.sceneview.FrameRatePolicy
import io.github.sceneview.SceneScope
import io.github.sceneview.SceneView
import io.github.sceneview.SurfaceType
import io.github.sceneview.collision.Box as CollisionBox
import io.github.sceneview.collision.Vector3
import io.github.sceneview.demo.demos.internal.ArDebugFraming
import io.github.sceneview.demo.demos.internal.ArDebugGeometry
import io.github.sceneview.demo.demos.internal.ArDebugOrbitCamera
import io.github.sceneview.demo.demos.internal.DebugLayer
import io.github.sceneview.demo.demos.internal.DebugMesh
import io.github.sceneview.demo.demos.internal.DollhouseRoom
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.environment.Environment
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.math.colorOf
import io.github.sceneview.math.toLinearSpace
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView
import io.github.sceneview.utils.readBuffer

/*
 * The dollhouse of #4075, drawn: the room a Rerun recording kept, through the replay's own
 * layers ([ReplayLayers] — the planes' photos) and its geometry ([ArDebugGeometry] — the planes
 * that have no photo, the points, the path walked), plus a plinth, all under one node scaled
 * down to a miniature.
 *
 * Why the replay's layers and not the session's .glb export (#4088): the export writes points as
 * glTF POINTS and the path as LINES, which Filament draws one pixel wide whatever the distance —
 * at 1:12 on a table they vanish. The replay's points are small solids and its path a tube, sized
 * here for the table ([RoomDollhouse.styleFor]), and the room looks exactly as it does in the
 * replay a tap away. Nothing is written to disk and nothing is re-read.
 */

/**
 * The dollhouse's Filament side: every layer node, and the materials and textures they draw
 * with. The nodes are handed to one parent ([DollhouseModel]) and are destroyed with it; the
 * materials and textures go in [destroy], after. Main thread.
 */
internal class DollhouseLayers(
    private val engine: Engine,
    private val materialLoader: MaterialLoader,
    media: RerunReplayMedia,
    private val room: DollhouseRoom,
    palette: DebugPalette,
    base: Color,
) {
    private val replay = ReplayLayers(engine, materialLoader, media)
    private val materials = ArrayList<MaterialInstance>()

    private fun node(color: Color, priority: Int, twoSided: Boolean = color.alpha >= 1f): DebugLayerNode {
        val material = materialLoader.createUnlitColorInstance(color).also {
            // Walls and the path are seen from both sides.
            if (twoSided) it.setCullingMode(Material.CullingMode.NONE)
            materials += it
        }
        return DebugLayerNode(engine, material, priority)
    }

    /**
     * The planes ARCore found without a photo, in the replay's tints made solid over the plinth:
     * the replay's see-through fills read as a ghost of a room on a table, a floor and walls of
     * one flat colour each read as a model's.
     */
    private val flat: Map<DebugLayer, DebugLayerNode> = mapOf(
        DebugLayer.PlaneFloor to node(solid(base, palette.floorFill), FILL_PRIORITY),
        DebugLayer.PlaneWall to node(solid(base, palette.wallFill), FILL_PRIORITY),
        DebugLayer.PlaneOther to node(solid(base, palette.otherFill), FILL_PRIORITY),
        DebugLayer.OutlineFloor to node(palette.floorOutline, OUTLINE_PRIORITY),
        DebugLayer.OutlineWall to node(palette.wallOutline, OUTLINE_PRIORITY),
        DebugLayer.OutlineOther to node(palette.otherOutline, OUTLINE_PRIORITY),
    )
    private val trail = node(palette.trailNew, TRAIL_PRIORITY)
    private val plinth = node(base, BASE_PRIORITY, twoSided = true)

    /** The plinth's edge, a shade darker than its top, so it reads as a solid base. */
    private val plinthEdge = node(lerp(base, Color.Black, EDGE_SHADE), BASE_PRIORITY, twoSided = true)

    /**
     * The contact shadow on the table: rings of faint black around the plinth's foot, overlapping
     * towards it, so the miniature sits on the table rather than floating over the camera feed.
     */
    private val shadow = node(Color.Black.copy(alpha = SHADOW_ALPHA), SHADOW_PRIORITY, twoSided = true)

    /**
     * The points, in the palette's point colour, like the live 3D view draws them. Not the
     * replay's photo-coloured layer: its colour atlas drew nothing here on the emulator, even
     * with every point in the fallback colour, where these solids always draw.
     */
    private val points = node(palette.mapPoint, POINTS_PRIORITY)

    /** Every node, none of them pickable: a touch lands on [DollhouseModel]'s box instead. */
    val nodes: List<DebugLayerNode> =
        (replay.nodes + flat.values + points + trail + plinth + plinthEdge + shadow).onEach {
            // Each layer is bounded by a 500 m box for cheap culling: as a collider it would take
            // every touch in the room.
            it.isHittable = false
        }

    private var styleScale = Float.NaN

    /**
     * Builds the meshes for points and paths sized as if drawn at [scale]: the miniature's own
     * scale in AR and in the 3D view alike, so both look the same; 1 at real size.
     */
    fun sync(scale: Float) {
        if (scale == styleScale) return
        styleScale = scale
        val style = RoomDollhouse.styleFor(scale)
        val frame = room.frame
        replay.sync(
            frame, style, room.fit.floorY,
            ReplayVisibility(planes = true, points = false, anchors = false, trail = false),
        )
        val meshes = flat.keys.associateWith { DebugMesh() }
        ArDebugGeometry.buildPlanes(frame.planes, style, { meshes.getValue(it) }) { replay.isTextured(it) }
        flat.forEach { (layer, node) -> node.upload(meshes.getValue(layer)) }
        points.upload(DebugMesh().also { ArDebugGeometry.buildMapPoints(frame.mapPoints, style, it) })
        // Every step of the replay's gradient into one mesh, in one colour: at this size a
        // gradient reads as noise.
        val path = DebugMesh()
        ArDebugGeometry.buildTrail(frame.trail, style) { path }
        trail.upload(path)
        val top = RoomDollhouse.basePolygon(frame, room.fit)
        val topY = room.fit.floorY - RoomDollhouse.BASE_DROP_M
        val bottomY = topY - RoomDollhouse.plinthThickness(scale)
        plinth.upload(DebugMesh().also { ArDebugGeometry.addFan(it, top) })
        plinthEdge.upload(DebugMesh().also { addSkirt(it, top, bottomY) })
        // The shadow's rings are sized on the table, like the points: the same few millimetres
        // round a bedroom at 1:12 and a hall at 1:50.
        val reach = RoomDollhouse.SHADOW_ON_TABLE_M / scale.coerceAtLeast(MIN_STYLE_SCALE)
        val rings = DebugMesh()
        for (ring in 1..SHADOW_RINGS) {
            ArDebugGeometry.addFan(
                rings,
                RoomDollhouse.expand(top, reach * ring / SHADOW_RINGS, bottomY + SHADOW_LIFT_M * ring),
            )
        }
        shadow.upload(rings)
    }

    /** The plinth's sides: one quad per edge of its [top], down to [bottomY]. */
    private fun addSkirt(mesh: DebugMesh, top: FloatArray, bottomY: Float) {
        val n = top.size / 3
        for (i in 0 until n) {
            val j = (i + 1) % n
            val a = mesh.vertex(top[i * 3], top[i * 3 + 1], top[i * 3 + 2])
            val b = mesh.vertex(top[j * 3], top[j * 3 + 1], top[j * 3 + 2])
            val c = mesh.vertex(top[j * 3], bottomY, top[j * 3 + 2])
            val d = mesh.vertex(top[i * 3], bottomY, top[i * 3 + 2])
            mesh.triangle(a, b, c)
            mesh.triangle(a, c, d)
        }
    }

    /** Materials and textures, once the nodes are gone. */
    fun destroy() {
        replay.destroy()
        materials.forEach(materialLoader::destroyMaterialInstance)
        materials.clear()
    }

    private companion object {
        const val SHADOW_PRIORITY = 0
        const val BASE_PRIORITY = 1
        const val FILL_PRIORITY = 2
        const val OUTLINE_PRIORITY = 3
        const val POINTS_PRIORITY = 4
        const val TRAIL_PRIORITY = 5

        /** How much darker the plinth's edge is than its top. */
        const val EDGE_SHADE = 0.28f

        /** Each ring's black: three overlapping rings darken to about 0.3 at the plinth's foot. */
        const val SHADOW_ALPHA = 0.11f
        const val SHADOW_RINGS = 3

        /** Each ring a hair above the last, in the room's metres, so none of them fight. */
        const val SHADOW_LIFT_M = 0.0005f
        const val MIN_STYLE_SCALE = 0.001f

        /** The least a fill without a photo shows of its own tint over the plinth. */
        const val SOLID_MIX = 0.72f

        /** [fill] as an opaque colour: its tint laid over [base] at its own alpha, or more. */
        fun solid(base: Color, fill: Color): Color =
            lerp(base, fill.copy(alpha = 1f), maxOf(fill.alpha, SOLID_MIX))
    }
}

/**
 * The room as a miniature, standing with the middle of its floor at this node's origin: drawn at
 * [scale] (the fit's, or 1 at real size), its points and path sized for [styleScale].
 *
 * With [pickable], a box around the room takes the touches — the layers are unbounded meshes —
 * and the node is editable with every edit off, as `AutoPlacementNode` asks, so a drag, a twist
 * or a pinch reaches the placement's pivot.
 */
@Composable
internal fun SceneScope.DollhouseModel(
    media: RerunReplayMedia,
    room: DollhouseRoom,
    engine: Engine,
    materialLoader: MaterialLoader,
    palette: DebugPalette,
    base: Color,
    scale: Float,
    styleScale: Float,
    pickable: Boolean,
) {
    // Remembered before the node, so Compose releases it after the node destroyed the layers.
    val layers = remember(engine, materialLoader, media, room, palette, base) {
        DollhouseLayers(engine, materialLoader, media, room, palette, base)
    }
    DisposableEffect(layers) { onDispose { layers.destroy() } }
    SideEffect { layers.sync(styleScale) }
    val fit = room.fit
    // The plinth's foot, not the room's floor, stands on the table.
    val floor = fit.floorY - RoomDollhouse.BASE_DROP_M - RoomDollhouse.plinthThickness(styleScale)
    key(layers) {
        Node(
            position = Position(-fit.centerX * scale, -floor * scale, -fit.centerZ * scale),
            scale = Scale(scale),
            isEditable = pickable,
            apply = {
                layers.nodes.forEach { addChildNode(it) }
                if (pickable) {
                    isPositionEditable = false
                    isRotationEditable = false
                    isScaleEditable = false
                    collisionShape = CollisionBox(
                        Vector3(fit.width, fit.height, fit.depth),
                        Vector3(fit.centerX, floor + fit.height / 2f, fit.centerZ),
                    )
                }
            },
        )
    }
}

/**
 * The dollhouse in a plain 3D view, on the demo's themed stage: for a device without AR (the
 * emulator, #2754), and a tap away on one with it. Drawn at room scale — the orbit camera frames
 * rooms — with the miniature's points and path, so it looks as it does on the table.
 */
@Composable
internal fun DollhousePreview(
    media: RerunReplayMedia,
    room: DollhouseRoom,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    onShown: () -> Unit = {},
) {
    val context = LocalContext.current
    val chrome = LocalStageChrome.current
    val shown = rememberUpdatedState(onShown)
    val colorGrading = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    }
    DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
    val environment = rememberEnvironment(engine, key = chrome.ground) {
        val stage = colorOf(chrome.ground).toLinearSpace()
        Environment(
            indirectLight = KTX1Loader.createIndirectLight(
                engine,
                context.assets.readBuffer("environments/neutral/neutral_ibl.ktx"),
            ).indirectLight?.also { it.intensity = DEFAULT_IBL_INTENSITY },
            skybox = Skybox.Builder().color(stage.x, stage.y, stage.z, 1f).build(engine),
        )
    }
    val view = rememberView(engine)
    val renderer = rememberRenderer(engine)
    val fit = room.fit
    // The room as it stands here: centred on the origin, its floor at 0.
    val bounds = remember(fit) {
        floatArrayOf(-fit.width / 2f, 0f, -fit.depth / 2f, fit.width / 2f, fit.height, fit.depth / 2f)
    }
    val frames = remember { IntArray(2) }
    Box(modifier.background(chrome.ground)) {
        SceneView(
            modifier = Modifier.matchParentSize(),
            surfaceType = SurfaceType.TextureSurface,
            engine = engine,
            modelLoader = modelLoader,
            materialLoader = materialLoader,
            view = view,
            renderer = renderer,
            isOpaque = true,
            frameRatePolicy = FrameRatePolicy.Continuous(),
            autoCenterContent = false,
            environment = environment,
            cameraManipulator = orbit,
            onFrame = {
                if (frames[0] == 0) view.configureForDebug(colorGrading)
                val home = ArDebugFraming.home(
                    bounds, orbit.home.azimuthDegrees, orbit.verticalFovDegrees, orbit.aspect,
                    elevationDegrees = PREVIEW_ELEVATION,
                    margin = ArDebugFraming.REPLAY_MARGIN,
                )
                if (orbit.following) orbit.home = home
                if (!orbit.hasFramedContent) {
                    orbit.hasFramedContent = true
                    orbit.snapTo(home)
                }
                frames[0]++
                // A few frames on screen: the photos are uploaded, nothing is half drawn.
                if (frames[0] == SHOWN_AFTER_FRAMES) shown.value()
            },
        ) {
            DollhouseModel(
                media = media,
                room = room,
                engine = engine,
                materialLoader = materialLoader,
                palette = chrome.debug,
                base = chrome.onCardMuted,
                scale = 1f,
                styleScale = fit.scale,
                pickable = false,
            )
        }
    }
}

/** A little higher than the replay's three-quarter view: a dollhouse is looked into from above. */
private const val PREVIEW_ELEVATION = 40f
private const val SHOWN_AFTER_FRAMES = 3
