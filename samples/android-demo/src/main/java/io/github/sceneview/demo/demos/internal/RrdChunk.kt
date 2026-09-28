package io.github.sceneview.demo.demos.internal

import io.github.sceneview.demo.demos.internal.RerunRrdReader.Failure

/**
 * One Rerun chunk read back: rows of components on one entity, on the `time` timeline or static.
 * Columns are found by their `rerun:component` metadata (`Points3D:positions`), never by position.
 */
internal class RrdChunk(
    val entityPath: String,
    val rowCount: Int,
    /** Nanoseconds on the `time` timeline, one per row (`null` for a row not on it); `null` for a static chunk. */
    val times: List<Long?>?,
    private val columns: Map<String, RrdArrowArray>,
) {
    /** Rerun's `ImageFormat` struct, as far as a texture needs it. */
    data class TexelFormat(
        val width: Int,
        val height: Int,
        val pixelFormat: Long?,
        val colorModel: Long?,
        val channelDatatype: Long?,
    )

    /**
     * `list<...>` rows: [read] gets the row's instance range; `null` for a null row, `null`
     * altogether when the chunk has no such component.
     */
    private fun <T> rows(
        component: String,
        check: (RrdArrowArray) -> Boolean,
        read: (RrdArrowArray, IntRange) -> T,
    ): List<T?>? {
        val list = columns[component] ?: return null
        val item = list.children.firstOrNull()
        if (list.type !is RrdArrowType.ListType || item == null || !check(item)) {
            rrdFail(Failure.UnexpectedLayout(component))
        }
        return List(rowCount) { row -> if (list.isNull(row)) null else read(item, list.range(row)) }
    }

    /** `list<fixed_size_list<float32>[size]>`: each row's instances, flattened. */
    fun floatVectors(component: String, size: Int): List<FloatArray?>? = rows(
        component,
        check = { it.type == RrdArrowType.FixedSizeList(size) && it.children.firstOrNull()?.type == FLOAT32 },
    ) { item, range ->
        val floats = item.children.first()
        val first = range.first * size
        FloatArray((range.last + 1 - range.first) * size) { floats.float32(first + it) }
    }

    /** `list<float32>` (radii): each row's instances. */
    fun floats(component: String): List<FloatArray?>? = rows(
        component,
        check = { it.type == FLOAT32 },
    ) { item, range -> FloatArray(range.last + 1 - range.first) { item.float32(range.first + it) } }

    /** `list<uint32>` (colours, `0xRRGGBBAA`), as `Int` bit patterns. */
    fun uint32s(component: String): List<IntArray?>? = rows(
        component,
        check = { (it.type as? RrdArrowType.IntType)?.bits == Int.SIZE_BITS },
    ) { item, range -> IntArray(range.last + 1 - range.first) { item.integer(range.first + it).toInt() } }

    /** `list<utf8>`. */
    fun strings(component: String): List<List<String>?>? = rows(
        component,
        check = { it.type is RrdArrowType.Utf8 },
    ) { item, range -> range.map { item.byteRange(item.range(it)).decodeToString() } }

    /** `list<list<uint8>>` (Rerun's `Blob`) or `list<binary>`: each instance's bytes. */
    fun blobs(component: String): List<List<ByteArray>?>? = rows(
        component,
        check = { item ->
            when (item.type) {
                is RrdArrowType.Binary -> true
                is RrdArrowType.ListType -> item.children.firstOrNull()?.type == RrdArrowType.IntType(8, false)
                else -> false
            }
        },
    ) { item, range ->
        range.map { instance ->
            val bytes = item.range(instance)
            if (item.type is RrdArrowType.Binary) {
                item.byteRange(bytes)
            } else {
                item.children.firstOrNull()?.byteRange(bytes) ?: ByteArray(0)
            }
        }
    }

    /** `list<list<fixed_size_list<float32>[3]>>` (`LineStrip3D`): each strip's points. */
    fun strips(component: String): List<List<List<Vec3>>?>? = rows(
        component,
        check = { item ->
            val point = item.children.firstOrNull()
            item.type is RrdArrowType.ListType && point != null &&
                point.type == RrdArrowType.FixedSizeList(3) && point.children.firstOrNull()?.type == FLOAT32
        },
    ) { item, range ->
        val floats = item.children.first().children.first()
        range.map { strip ->
            item.range(strip).map { p ->
                Vec3(floats.float32(3 * p), floats.float32(3 * p + 1), floats.float32(3 * p + 2))
            }
        }
    }

    /** `list<struct<width, height, pixel_format, color_model, channel_datatype>>`. */
    fun texelFormats(component: String): List<List<TexelFormat>?>? = rows(
        component,
        check = { it.type == RrdArrowType.Struct && it.child("width") != null && it.child("height") != null },
    ) { item, range -> range.mapNotNull { index -> texelFormat(item, index) } }

    private fun texelFormat(item: RrdArrowArray, index: Int): TexelFormat? {
        fun value(name: String): Long? {
            val child = item.child(name) ?: return null
            return if (child.isNull(index)) null else child.integer(index)
        }
        if (item.isNull(index)) return null
        val width = value("width")?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        val height = value("height")?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        return TexelFormat(
            width.toInt(),
            height.toInt(),
            value("pixel_format"),
            value("color_model"),
            value("channel_datatype"),
        )
    }

    companion object {
        private val FLOAT32 = RrdArrowType.FloatType(32)
        private const val NODE_SIZE = 16

        /** `null` for a chunk on an entity the replay does not read: it is not decoded. */
        fun of(schema: RrdSchema, flat: RrdFlatBuffer, batch: Int, body: RrdBytes): RrdChunk? {
            val path = schema.metadata["rerun:entity_path"] ?: return null
            val entityPath = path.removePrefix("/")
            if (!RerunRrdContents.reads(entityPath)) return null

            // `RecordBatch`: length (0), nodes (1), buffers (2), compression (3).
            val length = flat.i64(batch, 0)
            if (length < 0 || length > Int.MAX_VALUE) rrdFail(Failure.Malformed("record batch length"))
            val rowCount = length.toInt()
            if (flat.table(batch, 3) != null) rrdFail(Failure.Compressed())
            val reader = RrdBatchReader(nodes(flat, batch), buffers(flat, batch, body))
            val columns = HashMap<String, RrdArrowArray>()
            var times: List<Long?>? = null
            var hasIndex = false
            for (field in schema.fields) {
                val array = reader.array(field)
                if (array.length < rowCount) rrdFail(Failure.Malformed("${field.name}: fewer values than rows"))
                when (field.metadata["rerun:kind"]) {
                    "control" -> Unit // Row ids.
                    "index" -> {
                        hasIndex = true
                        if ((field.metadata["rerun:index_name"] ?: field.name) == RerunRrdWriter.TIMELINE) {
                            times = nanoseconds(array, rowCount)
                        }
                    }
                    else -> columns[field.metadata["rerun:component"] ?: field.name] = array
                }
            }
            // Rows on another timeline only: none of them has a time on ours.
            if (hasIndex && times == null) times = List(rowCount) { null }
            return RrdChunk(entityPath, rowCount, times, columns)
        }

        private fun nodes(flat: RrdFlatBuffer, batch: Int): List<Pair<Int, Int>> {
            val (count, start) = flat.vector(batch, 1, NODE_SIZE) ?: return emptyList()
            return List(count) { index ->
                val length = flat.bytes.i64(start + NODE_SIZE * index)
                val nulls = flat.bytes.i64(start + NODE_SIZE * index + 8)
                val valid = length in 0..Int.MAX_VALUE && nulls in 0..length
                if (!valid) {
                    rrdFail(Failure.Malformed("record batch node"))
                }
                length.toInt() to nulls.toInt()
            }
        }

        private fun buffers(flat: RrdFlatBuffer, batch: Int, body: RrdBytes): List<RrdBytes> {
            val (count, start) = flat.vector(batch, 2, NODE_SIZE) ?: return emptyList()
            return List(count) { index ->
                val offset = flat.bytes.i64(start + NODE_SIZE * index)
                val size = flat.bytes.i64(start + NODE_SIZE * index + 8)
                val inside = offset in 0..body.count.toLong() && size in 0..body.count.toLong()
                if (!inside) rrdFail(Failure.Truncated())
                body.slice(offset.toInt(), size.toInt())
            }
        }

        private fun nanoseconds(array: RrdArrowArray, rows: Int): List<Long?> {
            val perUnit = when (val type = array.type) {
                is RrdArrowType.Time -> type.nanosPerUnit
                RrdArrowType.IntType(64, true) -> 1L
                else -> rrdFail(Failure.UnexpectedLayout("${array.name}: not a time index"))
            }
            return List(rows) { row ->
                if (array.isNull(row)) {
                    null
                } else {
                    val value = array.integer(row)
                    val nanos = value * perUnit
                    nanos.takeIf { nanos / perUnit == value } // `null` on overflow.
                }
            }
        }
    }
}
