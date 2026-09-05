package io.github.sceneview.core.threemf

/**
 * Streaming reader for the 3MF core specification (plus the colours of the materials extension).
 *
 * It walks the `3D/3dmodel.model` XML once with [XmlPullParser] and appends straight into growable
 * primitive arrays, so a million-triangle print costs one float array, not a million objects.
 *
 * Supported: `<model unit>`, `<basematerials>`/`<base displaycolor>`, `<colorgroup>`/`<color>`,
 * `<object>` with `<mesh>` (`<vertices>`/`<vertex>`, `<triangles>`/`<triangle>` including per
 * triangle `pid`/`p1`) and `<components>`/`<component transform>`, and `<build>`/`<item transform>`.
 * Anything else in the document — metadata, slice/beamlattice/production extensions, thumbnails —
 * is skipped rather than rejected: an unrecognised extension must not stop a print from being
 * previewed.
 */
internal object ThreeMfParser {

    fun parse(xml: String): ThreeMfModel {
        val parser = XmlPullParser(xml)
        val state = ParseState()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> onStart(parser, state)
                XmlPullParser.END_TAG -> onEnd(parser, state)
            }
        }
        if (state.objects.isEmpty()) {
            threeMfError("3MF has no <object> in <resources>")
        }
        return ThreeMfModel(
            unit = state.unit,
            objects = state.objects,
            // A file with no <build> still previews: show every top-level object at the origin.
            items = state.items.ifEmpty { state.objects.map { ThreeMfItem(it.id, identityMatrix()) } }
        )
    }

    private class ParseState {
        var unit = ThreeMfUnit.Default
        val objects = ArrayList<ThreeMfObject>()
        val items = ArrayList<ThreeMfItem>()

        /** `id` → palette of packed colours, for both `<basematerials>` and `<colorgroup>`. */
        val palettes = HashMap<Int, IntArray>()
        var paletteId: Int? = null
        var palette = ArrayList<Int>()

        var objectId: Int? = null
        var objectName: String? = null
        var objectColor = NoColor
        var objectPaletteId: Int? = null
        var positions = FloatArrayBuilder()
        var indices = IntArrayBuilder()
        var triangleColors = IntArrayBuilder()
        var anyTriangleColor = false
        var components = ArrayList<ThreeMfComponent>()
    }

    private fun onStart(parser: XmlPullParser, state: ParseState) {
        when (parser.name) {
            "model" -> state.unit = ThreeMfUnit.fromId(parser.attribute("unit"))
            "basematerials", "colorgroup" -> startPalette(parser, state)
            "base" -> state.palette += parseColor(parser.attribute("displaycolor"))
            "color" -> state.palette += parseColor(parser.attribute("color"))
            "object" -> startObject(parser, state)
            "vertex" -> readVertex(parser, state)
            "triangle" -> readTriangle(parser, state)
            "component" -> state.components += ThreeMfComponent(
                objectId = parser.requireInt("objectid"),
                transform = parseTransform(parser.attribute("transform"))
            )

            "item" -> state.items += ThreeMfItem(
                objectId = parser.requireInt("objectid"),
                transform = parseTransform(parser.attribute("transform"))
            )
        }
    }

    private fun onEnd(parser: XmlPullParser, state: ParseState) {
        when (parser.name) {
            "basematerials", "colorgroup" -> {
                state.paletteId?.let { state.palettes[it] = state.palette.toIntArray() }
                state.paletteId = null
                state.palette = ArrayList()
            }

            "object" -> endObject(state)
        }
    }

    private fun startPalette(parser: XmlPullParser, state: ParseState) {
        state.paletteId = parser.attribute("id")?.toIntOrNull()
        state.palette = ArrayList()
    }

    private fun startObject(parser: XmlPullParser, state: ParseState) {
        state.objectId = parser.requireInt("id")
        state.objectName = parser.attribute("name")
        state.objectPaletteId = parser.attribute("pid")?.toIntOrNull()
        state.objectColor = state.colorAt(state.objectPaletteId, parser.attribute("pindex"))
        state.positions = FloatArrayBuilder()
        state.indices = IntArrayBuilder()
        state.triangleColors = IntArrayBuilder()
        state.anyTriangleColor = false
        state.components = ArrayList()
    }

    private fun endObject(state: ParseState) {
        val id = state.objectId ?: return
        val mesh = if (state.indices.size > 0) {
            ThreeMfMesh(
                positions = state.positions.toArray(),
                indices = state.indices.toArray(),
                triangleColors = state.triangleColors.toArray().takeIf { state.anyTriangleColor }
            )
        } else {
            null
        }
        state.objects += ThreeMfObject(
            id = id,
            name = state.objectName,
            mesh = mesh,
            components = state.components.toList(),
            color = state.objectColor
        )
        state.objectId = null
    }

    private fun readVertex(parser: XmlPullParser, state: ParseState) {
        state.positions.add(parser.requireFloat("x"))
        state.positions.add(parser.requireFloat("y"))
        state.positions.add(parser.requireFloat("z"))
    }

    private fun readTriangle(parser: XmlPullParser, state: ParseState) {
        val vertexCount = state.positions.size / 3
        val v1 = parser.requireIndex("v1", vertexCount)
        val v2 = parser.requireIndex("v2", vertexCount)
        val v3 = parser.requireIndex("v3", vertexCount)
        state.indices.add(v1)
        state.indices.add(v2)
        state.indices.add(v3)
        // A triangle's own `pid` overrides the object's; `p1` indexes into it. Per-vertex p2/p3
        // (a colour gradient across the triangle) collapses to p1 — a flat-shaded print preview
        // gains nothing from interpolating them, and glTF would need per-vertex COLOR_0 to carry it.
        val paletteId = parser.attribute("pid")?.toIntOrNull() ?: state.objectPaletteId
        val color = state.colorAt(paletteId, parser.attribute("p1"))
        val resolved = if (color != NoColor) color else state.objectColor
        state.triangleColors.add(resolved)
        if (color != NoColor) state.anyTriangleColor = true
    }

    private fun ParseState.colorAt(paletteId: Int?, index: String?): Int {
        val colors = palettes[paletteId ?: return NoColor] ?: return NoColor
        val at = index?.toIntOrNull() ?: 0
        return colors.getOrElse(at) { NoColor }
    }

    private fun XmlPullParser.requireInt(attributeName: String): Int =
        attribute(attributeName)?.toIntOrNull()
            ?: threeMfError("3MF <$name> has no valid \"$attributeName\" attribute")

    private fun XmlPullParser.requireFloat(attributeName: String): Float =
        attribute(attributeName)?.toFloatOrNull()
            ?: threeMfError("3MF <$name> has no valid \"$attributeName\" attribute")

    private fun XmlPullParser.requireIndex(attributeName: String, vertexCount: Int): Int {
        val index = requireInt(attributeName)
        if (index !in 0 until vertexCount) {
            threeMfError(
                "3MF <triangle $attributeName=\"$index\"> is out of range " +
                    "(mesh has $vertexCount vertices)"
            )
        }
        return index
    }

    /**
     * `#RRGGBB` or `#RRGGBBAA` (the 3MF `displaycolor` / `color` form, sRGB) → packed
     * `0xRRGGBBAA`. An absent or unparseable value yields [NoColor].
     */
    fun parseColor(value: String?): Int {
        val hex = value?.trim()?.removePrefix("#") ?: return NoColor
        if (hex.length < RGB_HEX_LENGTH) return NoColor
        val rgb = hex.take(RGB_HEX_LENGTH).toLongOrNull(radix = 16) ?: return NoColor
        val alpha = hex.drop(RGB_HEX_LENGTH).takeIf { it.length >= 2 }
            ?.take(2)?.toIntOrNull(radix = 16) ?: 0xFF
        return ((rgb.toInt() shl 8) or alpha)
    }

    /**
     * The 3MF `transform` attribute: 12 numbers, row-major, describing a 4×3 matrix whose rows are
     * the three basis vectors then the translation (3MF multiplies **row** vectors, `v * M`).
     * Returned as the 16-float **column-major** matrix glTF and every renderer here expect —
     * which, for this layout, is the same twelve numbers in the same order plus the `(0,0,0,1)`
     * fourth column.
     */
    fun parseTransform(value: String?): FloatArray {
        val parts = value?.trim()?.split(WHITESPACE)?.filter { it.isNotEmpty() }
            ?: return identityMatrix()
        if (parts.size != TRANSFORM_VALUES) {
            threeMfError("3MF transform must have $TRANSFORM_VALUES values, got ${parts.size}")
        }
        val m = parts.map {
            it.toFloatOrNull() ?: threeMfError("3MF transform has a non-numeric value \"$it\"")
        }
        return floatArrayOf(
            m[0], m[1], m[2], 0f,
            m[3], m[4], m[5], 0f,
            m[6], m[7], m[8], 0f,
            m[9], m[10], m[11], 1f
        )
    }

    private val WHITESPACE = Regex("\\s+")
    private const val TRANSFORM_VALUES = 12
    private const val RGB_HEX_LENGTH = 6
}

/** Growable `FloatArray`, so a streamed mesh never boxes a single vertex coordinate. */
internal class FloatArrayBuilder(initialCapacity: Int = 256) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Growable `IntArray`, the index/colour counterpart of [FloatArrayBuilder]. */
internal class IntArrayBuilder(initialCapacity: Int = 256) {
    private var data = IntArray(initialCapacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}
