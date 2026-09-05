package io.github.sceneview.core.threemf

/**
 * Thrown when a `.3mf` file cannot be read: not a ZIP/OPC package, no `3D/3dmodel.model` part,
 * malformed XML, or geometry that does not describe a mesh. Every low-level failure (a truncated
 * archive, an out-of-range vertex index) is translated into this one exception, so callers never
 * have to catch anything else.
 */
class ThreeMfParseException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Raise a [ThreeMfParseException] — a single-throw helper in the spirit of the stdlib `error()`, so
 * the many guard clauses in the ZIP/XML/3MF readers keep each function within detekt's
 * `ThrowsCount` budget and error construction stays in one place.
 */
internal fun threeMfError(message: String, cause: Throwable? = null): Nothing =
    throw ThreeMfParseException(message, cause)

/**
 * The length unit a 3MF declares on its root `<model>` element. 3MF is a manufacturing format, so
 * it carries real-world size — a ChatGPT-generated print is almost always in [MILLIMETER], which is
 * why a raw 3MF dropped into a metres-based renderer would otherwise appear 1000× too large.
 *
 * @property id     The token as it appears in the file's `unit` attribute.
 * @property meters How many metres one unit is; the scale applied when converting to glTF.
 */
enum class ThreeMfUnit(val id: String, val meters: Float) {
    MICRON("micron", 0.000001f),
    MILLIMETER("millimeter", 0.001f),
    CENTIMETER("centimeter", 0.01f),
    INCH("inch", 0.0254f),
    FOOT("foot", 0.3048f),
    METER("meter", 1.0f);

    companion object {
        /** The 3MF default when a file declares no `unit` attribute. */
        val Default = MILLIMETER

        /** Parse a `unit` attribute value; unknown or absent tokens fall back to [Default]. */
        fun fromId(id: String?): ThreeMfUnit =
            entries.firstOrNull { it.id == id?.lowercase() } ?: Default
    }
}

/**
 * A triangle mesh exactly as the file stores it: indexed positions in model units, plus an optional
 * per-triangle colour.
 *
 * @property positions      Vertex positions, three floats (x, y, z) per vertex, in [ThreeMfModel]'s
 *                          unit and the file's own Z-up axis convention.
 * @property indices        Three vertex indices per triangle.
 * @property triangleColors One packed `0xRRGGBBAA` colour per triangle, or `null` when the whole
 *                          mesh takes its owning object's colour. [NoColor] marks an untinted
 *                          triangle.
 */
class ThreeMfMesh(
    val positions: FloatArray,
    val indices: IntArray,
    val triangleColors: IntArray? = null
) {
    /** Number of vertices (`positions.size / 3`). */
    val vertexCount: Int get() = positions.size / 3

    /** Number of triangles (`indices.size / 3`). */
    val triangleCount: Int get() = indices.size / 3
}

/**
 * A child object placed inside another one, with its own placement matrix.
 *
 * @property objectId The referenced `<object id>`.
 * @property transform A 16-float **column-major** 4×4 matrix, the same layout glTF node matrices
 *                     use.
 */
class ThreeMfComponent(val objectId: Int, val transform: FloatArray)

/**
 * One `<object>` from the file's `<resources>`: either a mesh, or an assembly of [components], or
 * both.
 *
 * @property color Packed `0xRRGGBBAA` colour resolved from the object's `pid`/`pindex` material
 *                 reference, or [NoColor] when the file assigns none.
 */
class ThreeMfObject(
    val id: Int,
    val name: String?,
    val mesh: ThreeMfMesh?,
    val components: List<ThreeMfComponent> = emptyList(),
    val color: Int = NoColor
)

/**
 * One `<build><item>`: the placement of an object on the print plate.
 *
 * @property transform A 16-float **column-major** 4×4 matrix.
 */
class ThreeMfItem(val objectId: Int, val transform: FloatArray)

/**
 * A parsed 3MF document — the printable objects it defines and where the build plate places them.
 *
 * The data is kept in the file's own frame: **Z-up**, in [unit]. The conversion to a renderer's
 * Y-up metres happens once, in [ThreeMfLoader.toGlb].
 */
class ThreeMfModel(
    val unit: ThreeMfUnit,
    val objects: List<ThreeMfObject>,
    val items: List<ThreeMfItem>
) {
    /** Objects by `id`, for resolving [ThreeMfItem.objectId] and [ThreeMfComponent.objectId]. */
    val objectsById: Map<Int, ThreeMfObject> = objects.associateBy { it.id }

    /** Total triangles across every object defined in the file (before build-item instancing). */
    val triangleCount: Int get() = objects.sumOf { it.mesh?.triangleCount ?: 0 }
}

/** The "no colour assigned" sentinel: fully transparent black, which no 3MF ever means literally. */
const val NoColor: Int = 0

/** The identity matrix, column-major — the placement of an item or component with no `transform`. */
internal fun identityMatrix() = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
    0f, 0f, 0f, 1f
)
