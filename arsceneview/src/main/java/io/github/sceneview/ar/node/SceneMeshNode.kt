package io.github.sceneview.ar.node

import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.RenderableManager
import com.google.ar.core.StreetscapeGeometry
import com.google.ar.core.TrackingState

/**
 * ARCore / ARKit unified vocabulary for real-world surface classification.
 *
 * **ARCore** (Geospatial Streetscape Geometry, v1.37+): classification is at the geometry level.
 * Every face in a `TERRAIN` geometry receives [TERRAIN]; every face in a `BUILDING` geometry
 * receives [BUILDING]. Per-face labels are not available on Android as of ARCore 1.54.
 *
 * **ARKit** (Scene Reconstruction, iOS 13.4+): classification is per face. Labels include the
 * indoor labels ([FLOOR], [WALL], [CEILING], [TABLE], [SEAT], [WINDOW], [DOOR]) in addition to
 * the outdoor labels. SceneView's iOS layer (`SceneViewSwift`) uses the same enum so
 * classification-driven rendering code (colour maps, physics layer masks, audio-zone filters)
 * compiles on both platforms.
 *
 * Unknown or unsupported faces fall back to [UNLABELED].
 */
enum class MeshClassification {
    /** Horizontal floor-level surface (ARKit per-face; approximated by [TERRAIN] on ARCore). */
    FLOOR,
    /** Vertical wall surface (ARKit per-face). */
    WALL,
    /** Overhead ceiling surface (ARKit per-face). */
    CEILING,
    /** Elevated horizontal surface such as a table top (ARKit per-face). */
    TABLE,
    /** Seat or chair surface (ARKit per-face). */
    SEAT,
    /** Window opening or glazed pane (ARKit per-face). */
    WINDOW,
    /** Door opening or panel (ARKit per-face). */
    DOOR,
    /** Outdoor ground, terrain, or sidewalk mesh (ARCore Streetscape: `TERRAIN`). */
    TERRAIN,
    /** Building exterior mesh (ARCore Streetscape: `BUILDING`). */
    BUILDING,
    /** No classification available — used for unsupported or unknown surfaces. */
    UNLABELED,
}

/**
 * Maps an ARCore [StreetscapeGeometry.Type] to the unified [MeshClassification] vocabulary so
 * code that drives colour or physics off classification does not need to import ARCore types.
 */
fun StreetscapeGeometry.Type.toMeshClassification(): MeshClassification = when (this) {
    StreetscapeGeometry.Type.TERRAIN -> MeshClassification.TERRAIN
    StreetscapeGeometry.Type.BUILDING -> MeshClassification.BUILDING
    else -> MeshClassification.UNLABELED
}

/**
 * A [StreetscapeGeometryNode] with unified [MeshClassification] semantics, providing ARKit
 * `ARMeshAnchor` parity on Android.
 *
 * Wraps an ARCore [StreetscapeGeometry] trackable (introduced in ARCore 1.37) and exposes a
 * [MeshClassification] label for every face in the underlying mesh. On ARCore the label is
 * coarse (one [MeshClassification] per geometry — [MeshClassification.TERRAIN] or
 * [MeshClassification.BUILDING]); on ARKit's Scene Reconstruction the label is fine-grained and
 * per-face. The [onClassifiedFace] callback uses the per-face signature for forward-compatibility
 * so the same consumer code works on both platforms without changes.
 *
 * ### Typical usage
 *
 * ```kotlin
 * ARSceneView(
 *     sessionConfiguration = { _, config ->
 *         config.geospatialMode = Config.GeospatialMode.ENABLED
 *         config.streetscapeGeometryMode = Config.StreetscapeGeometryMode.ENABLED
 *     },
 *     onSessionUpdated = { _, frame ->
 *         geometries = frame.getUpdatedTrackables(StreetscapeGeometry::class.java).toList()
 *     }
 * ) {
 *     geometries.forEach { geo ->
 *         SceneMeshNode(
 *             streetscapeGeometry = geo,
 *             meshMaterialInstance = materialFor(geo),
 *             onClassifiedFace = { faceIndex, classification ->
 *                 // Build per-face metadata for physics, audio, etc.
 *             }
 *         )
 *     }
 * }
 * ```
 *
 * ### Rendering
 *
 * The mesh is built once in [StreetscapeGeometryNode]'s constructor; subsequent [update] calls
 * only refresh the node's world [pose]. For classification-driven colour coding, pass a different
 * [meshMaterialInstance] per geometry type or use the [classification] property after construction.
 *
 * ### Threading
 *
 * Must be constructed and used on the main / AR-frame thread — [StreetscapeGeometry.getMesh] reads
 * Filament-backed native buffers and the JNI calls are not thread-safe.
 *
 * @param engine                  Filament engine.
 * @param streetscapeGeometry     The [StreetscapeGeometry] trackable to render.
 * @param meshMaterialInstance    Optional [MaterialInstance] painted on the mesh faces. When
 *                                `null`, Filament's default material is used. For colour-coded
 *                                overlays, pass a semi-transparent PBR or unlit instance.
 * @param onTrackingStateChanged  Called when [streetscapeGeometry]'s [TrackingState] changes.
 * @param onUpdated               Called each ARCore frame while the geometry is tracked.
 * @param onClassifiedFace        Called once per triangle face when the node is first built
 *                                (not on every frame). Receives the zero-based face index and
 *                                the face's [MeshClassification]. On ARCore all faces of the
 *                                same geometry share the same classification; on ARKit each
 *                                face may have a distinct label.
 * @param builder                 Optional hook into the underlying [RenderableManager.Builder]
 *                                for shadow flags, layer masks, etc.
 */
open class SceneMeshNode(
    engine: Engine,
    streetscapeGeometry: StreetscapeGeometry,
    meshMaterialInstance: MaterialInstance? = null,
    onTrackingStateChanged: ((TrackingState) -> Unit)? = null,
    onUpdated: ((StreetscapeGeometry) -> Unit)? = null,
    onClassifiedFace: ((faceIndex: Int, classification: MeshClassification) -> Unit)? = null,
    builder: RenderableManager.Builder.() -> Unit = {},
) : StreetscapeGeometryNode(
    engine = engine,
    streetscapeGeometry = streetscapeGeometry,
    meshMaterialInstance = meshMaterialInstance,
    builder = builder,
    onTrackingStateChanged = onTrackingStateChanged,
    onUpdated = onUpdated,
) {
    /**
     * Surface classification derived from [StreetscapeGeometry.getType]. On ARCore this is
     * [MeshClassification.TERRAIN] or [MeshClassification.BUILDING]. On ARKit (future) this
     * field reflects the dominant per-face label of the anchor.
     */
    val classification: MeshClassification = streetscapeGeometry.type.toMeshClassification()

    init {
        // Invoke the per-face callback synchronously during construction — the mesh is built once
        // in StreetscapeGeometryNode's constructor so all face data is already available here.
        // On ARCore every face carries the same geometry-level classification; the per-face
        // signature matches ARKit's ARMeshGeometry.classificationOf(faceWithIndex:) for parity.
        if (onClassifiedFace != null) {
            val faceCount = streetscapeGeometry.mesh.indexListSize / 3
            repeat(faceCount) { faceIndex ->
                onClassifiedFace(faceIndex, classification)
            }
        }
    }
}
