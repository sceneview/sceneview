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
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.ToneMapper
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
import io.github.sceneview.demo.demos.internal.DenseSurfels
import io.github.sceneview.demo.demos.internal.DollhouseRoom
import io.github.sceneview.demo.demos.internal.PlaneLayering
import io.github.sceneview.demo.demos.internal.RoomDollhouse
import io.github.sceneview.demo.theme.DebugPalette
import io.github.sceneview.demo.theme.LocalStageChrome
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.material.setColor
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.rememberRenderer
import io.github.sceneview.rememberView

/*
 * The dollhouse of #4075, drawn: the room a Rerun recording kept, through the replay's own
 * layers ([ReplayLayers] — the scan's dense coloured cloud when it kept one; else the planes'
 * photos and the points in the colours the camera saw) and its geometry ([ArDebugGeometry] — the
 * planes that have no photo, the path walked on request), plus a plinth, all under one node
 * scaled down to a miniature.
 *
 * Why the replay's layers and not the session's .glb export (#4088): the export writes points as
 * glTF POINTS and the path as LINES, which Filament draws one pixel wide whatever the distance —
 * at 1:12 on a table they vanish. The replay's points are small solids and its path a tube, sized
 * here for the table ([RoomDollhouse.styleFor]), and the room looks exactly as it does in the
 * replay a tap away. Nothing is written to disk and nothing is re-read.
 */

/**
 * A recording made ready to stand, off the main thread: its [room] ([RoomDollhouse.room]), `null`
 * when it holds nothing, and the room's dense cloud cut open ([RoomDollhouse.cropDense]) and
 * built for drawing — the scan's own layer when the cut kept all of it.
 */
internal class DollhouseBuild(val media: RerunReplayMedia, val room: DollhouseRoom?, val dense: ReplayDenseLayer?) {
    companion object {
        /** Reads [media]'s last frame and cuts its room open. Seconds for a dense scan: off the main thread. */
        fun of(media: RerunReplayMedia): DollhouseBuild {
            val source = media.dense
            val room = RoomDollhouse.room(media.trace.frameAt(media.trace.duration), source?.cloud)
            val cut = room?.dense
            val dense = when {
                source == null || cut == null -> null
                cut.count == source.cloud.count -> source
                else -> ReplayDenseLayer(
                    cloud = cut,
                    voxelM = source.voxelM,
                    mesh = DenseSurfels.mesh(cut, source.voxelM),
                    atlas = DenseSurfels.atlas(cut, fallback = ReplayDenseLayer.DENSE_FALLBACK_COLOR),
                )
            }
            return DollhouseBuild(media, room, dense)
        }
    }
}

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
    dense: ReplayDenseLayer?,
    private var palette: DebugPalette,
    private var base: Color,
) {
    /** The scan's dense cloud, cut open, is the room itself, in the colours the camera saw. */
    private val hasDense = dense != null
    private val replay = ReplayLayers(engine, materialLoader, media, dense = dense)
    private val tints = ArrayList<Tint>()

    /**
     * A flat layer and the colour it takes [of] a palette and a plinth base. [twoSided] `null`
     * follows the colour: a solid one is seen from both sides — walls, the path — a see-through
     * one from its front only.
     */
    private class Tint(
        val node: DebugLayerNode,
        var material: MaterialInstance,
        var color: Color,
        val twoSided: Boolean?,
        val of: (DebugPalette, Color) -> Color,
    )

    private fun material(color: Color, twoSided: Boolean?): MaterialInstance =
        materialLoader.createUnlitColorInstance(color).also {
            if (twoSided ?: color.isSolid) it.setCullingMode(Material.CullingMode.NONE)
        }

    private fun node(priority: Int, twoSided: Boolean? = null, color: (DebugPalette, Color) -> Color): DebugLayerNode {
        val first = color(palette, base)
        val material = material(first, twoSided)
        return DebugLayerNode(engine, material, priority).also { tints += Tint(it, material, first, twoSided, color) }
    }

    /**
     * The flat layers in [palette]'s colours over a plinth of [base]: what a theme switch changes,
     * on the nodes the scene already draws. The replay's layers — the room's photos and points,
     * and every texture — are the camera's own colours and are left alone: building the layers
     * again for a palette freed textures a frame still drew with (#4330).
     */
    fun paint(palette: DebugPalette, base: Color) {
        if (palette == this.palette && base == this.base) return
        this.palette = palette
        this.base = base
        for (tint in tints) {
            val color = tint.of(palette, base)
            if (color == tint.color) continue
            if (color.isSolid == tint.color.isSolid) {
                tint.material.setColor(color)
            } else {
                // Solid on one stage, see-through on the other (the plinth is): the unlit colour
                // is another material then. The node takes the new instance before the old one
                // goes, so no frame draws with a destroyed one.
                val old = tint.material
                tint.material = material(color, tint.twoSided)
                tint.node.materialInstance = tint.material
                materialLoader.destroyMaterialInstance(old)
            }
            tint.color = color
        }
    }

    /**
     * The planes ARCore found without a photo, in the replay's tints made solid over the plinth:
     * the replay's see-through fills read as a ghost of a room on a table, a floor and walls of
     * one flat colour each read as a model's.
     */
    private val flat: Map<DebugLayer, DebugLayerNode> = mapOf(
        DebugLayer.PlaneFloor to node(FILL_PRIORITY) { palette, base -> solid(base, palette.floorFill) },
        DebugLayer.PlaneWall to node(FILL_PRIORITY) { palette, base -> solid(base, palette.wallFill) },
        DebugLayer.PlaneOther to node(FILL_PRIORITY) { palette, base -> solid(base, palette.otherFill) },
        DebugLayer.OutlineFloor to node(OUTLINE_PRIORITY) { palette, _ -> palette.floorOutline },
        DebugLayer.OutlineWall to node(OUTLINE_PRIORITY) { palette, _ -> palette.wallOutline },
        DebugLayer.OutlineOther to node(OUTLINE_PRIORITY) { palette, _ -> palette.otherOutline },
    )
    private val trail = node(TRAIL_PRIORITY) { palette, _ -> palette.trailNew }
    private val plinth = node(BASE_PRIORITY, twoSided = true) { _, base -> base }

    /** The plinth's edge, a shade darker than its top, so it reads as a solid base. */
    private val plinthEdge =
        node(BASE_PRIORITY, twoSided = true) { _, base -> lerp(base, Color.Black, EDGE_SHADE) }

    /**
     * The contact shadow on the table: rings of faint black around the plinth's foot, overlapping
     * towards it, so the miniature sits on the table rather than floating over the camera feed.
     */
    private val shadow =
        node(SHADOW_PRIORITY, twoSided = true) { _, _ -> Color.Black.copy(alpha = SHADOW_ALPHA) }

    /** Every node, none of them pickable: a touch lands on [DollhouseModel]'s box instead. */
    val nodes: List<DebugLayerNode> =
        (replay.nodes + flat.values + trail + plinth + plinthEdge + shadow).onEach {
            // Each layer is bounded by a 500 m box for cheap culling: as a collider it would take
            // every touch in the room.
            it.isHittable = false
        }

    private var synced: Triple<Float, Boolean, Boolean>? = null

    /**
     * Builds the meshes for points and paths sized as if drawn at [scale]: the miniature's own
     * scale in AR and in the 3D view alike, so both look the same; 1 at real size. The path the
     * phone walked shows only on request ([showPath]): the room first, not the route through it.
     * Without [plinth] — at real size, on the real floor — the room stands bare.
     */
    fun sync(scale: Float, showPath: Boolean, plinth: Boolean) {
        val key = Triple(scale, showPath, plinth)
        if (key == synced) return
        synced = key
        val style = RoomDollhouse.styleFor(scale)
        val frame = room.frame
        // The dense cloud is the room, surfaces and all: the planes and their photos would only
        // cut through it. Without one, the planes' photos and the points in the colours the
        // camera saw them in — the replay's, not the live view's one-colour markers.
        replay.sync(
            frame, style, room.fit.floorY,
            ReplayVisibility(planes = !hasDense, points = true, anchors = false, trail = false),
        )
        val meshes = flat.keys.associateWith { DebugMesh() }
        if (!hasDense) {
            // The flat fills are opaque, like the photos: each at its own depth, or they z-fight.
            ArDebugGeometry.buildPlanes(
                frame.planes, style, { meshes.getValue(it) },
                layering = PlaneLayering.of(frame, room.fit.floorY),
            ) { replay.isTextured(it) }
        }
        flat.forEach { (layer, node) -> node.upload(meshes.getValue(layer)) }
        // Every step of the replay's gradient into one mesh, in one colour: at this size a
        // gradient reads as noise.
        val path = DebugMesh()
        if (showPath) ArDebugGeometry.buildTrail(frame.trail, style) { path }
        trail.upload(path)
        this.plinth.isVisible = plinth
        plinthEdge.isVisible = plinth
        shadow.isVisible = plinth
        // A dense room's plinth covers the whole cloud, not only the planes ARCore found.
        val top = if (hasDense) RoomDollhouse.basePolygon(room.fit) else RoomDollhouse.basePolygon(frame, room.fit)
        val topY = room.fit.floorY - RoomDollhouse.BASE_DROP_M
        val bottomY = topY - RoomDollhouse.plinthThickness(scale)
        this.plinth.upload(DebugMesh().also { ArDebugGeometry.addFan(it, top) })
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
        tints.forEach { materialLoader.destroyMaterialInstance(it.material) }
        tints.clear()
    }

    private companion object {
        const val SHADOW_PRIORITY = 0
        const val BASE_PRIORITY = 1
        const val FILL_PRIORITY = 2
        const val OUTLINE_PRIORITY = 3
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

        /** Drawn with the opaque unlit material, as [MaterialLoader.createUnlitColorInstance] picks it. */
        val Color.isSolid: Boolean get() = alpha >= 1f

        /** [fill] as an opaque colour: its tint laid over [base] at its own alpha, or more. */
        fun solid(base: Color, fill: Color): Color =
            lerp(base, fill.copy(alpha = 1f), maxOf(fill.alpha, SOLID_MIX))
    }
}

/**
 * The room as a miniature, standing with the middle of its floor at this node's origin: drawn at
 * [scale] (the fit's, or 1 at real size), its points and path sized for [styleScale]. On its
 * [plinth] the plinth's foot stands at the origin; without, at real size, the room's own floor
 * does — exactly, so the room lies on the real floor rather than a plinth's height above it.
 *
 * With [pickable], a box around the room takes the touches — the layers are unbounded meshes —
 * and the node is editable with every edit off, as `AutoPlacementNode` asks, so a drag, a twist
 * or a pinch reaches the placement's pivot.
 */
@Composable
internal fun SceneScope.DollhouseModel(
    build: DollhouseBuild,
    room: DollhouseRoom,
    engine: Engine,
    materialLoader: MaterialLoader,
    palette: DebugPalette,
    base: Color,
    scale: Float,
    styleScale: Float,
    pickable: Boolean,
    showPath: Boolean,
    plinth: Boolean = true,
) {
    // Remembered before the node, so Compose releases it after the node destroyed the layers.
    // Not keyed on the palette or the base: a theme switch repaints the layers in place (#4330).
    val layers = remember(engine, materialLoader, build, room) {
        DollhouseLayers(engine, materialLoader, build.media, room, build.dense, palette, base)
    }
    DisposableEffect(layers) { onDispose { layers.destroy() } }
    SideEffect {
        layers.paint(palette, base)
        layers.sync(styleScale, showPath, plinth)
    }
    val fit = room.fit
    // On the table the plinth's foot stands on it; at real size the room's floor is the floor.
    val floor = if (plinth) {
        fit.floorY - RoomDollhouse.BASE_DROP_M - RoomDollhouse.plinthThickness(styleScale)
    } else {
        fit.floorY
    }
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
    build: DollhouseBuild,
    room: DollhouseRoom,
    showPath: Boolean,
    orbit: ArDebugOrbitCamera,
    engine: Engine,
    modelLoader: ModelLoader,
    materialLoader: MaterialLoader,
    modifier: Modifier = Modifier,
    onShown: () -> Unit = {},
) {
    val chrome = LocalStageChrome.current
    val shown = rememberUpdatedState(onShown)
    val colorGrading = remember(engine) {
        ColorGrading.Builder().toneMapper(ToneMapper.Linear()).build(engine)
    }
    DisposableEffect(colorGrading) { onDispose { engine.destroyColorGrading(colorGrading) } }
    val environment = rememberStageEnvironment(engine, chrome.ground)
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
                    band = orbit.band,
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
                build = build,
                room = room,
                engine = engine,
                materialLoader = materialLoader,
                palette = chrome.debug,
                base = chrome.onCardMuted,
                scale = 1f,
                styleScale = fit.scale,
                pickable = false,
                showPath = showPath,
            )
        }
    }
}

/** A little higher than the replay's three-quarter view: a dollhouse is looked into from above. */
private const val PREVIEW_ELEVATION = 40f
private const val SHOWN_AFTER_FRAMES = 3
