package io.github.sceneview.demo.demos.internal

import java.io.ByteArrayOutputStream
import java.util.UUID

/** One Rerun chunk: rows of components on one entity, on the `time` timeline or static. */
internal class RerunChunk(
    val entityPath: String,
    val components: List<RerunComponentColumn>,
    /** Nanoseconds on [RerunRrdWriter.TIMELINE], one per row; `null` for static data. */
    val times: List<Long>? = null,
) {
    val rowCount: Int get() = times?.size ?: 1

    /** The chunk as a sorbet record batch in an Arrow IPC stream. */
    fun arrowIpc(chunkId: RerunTuid, rowIds: List<RerunTuid>): ByteArray {
        val fields = arrayListOf(
            RerunArrowField(
                name = "rerun.controls.RowId",
                type = RerunArrowType.FixedSizeBinary(RerunTuid.SIZE),
                nullable = false,
                metadata = mapOf(
                    "ARROW:extension:metadata" to """{"namespace":"row"}""",
                    "ARROW:extension:name" to "rerun.datatypes.TUID",
                    "rerun:is_sorted" to "true",
                    "rerun:kind" to "control",
                ),
            ),
        )
        val ids = ByteArrayOutputStream()
        rowIds.forEach { ids.write(it.bigEndianBytes()) }
        val columns = arrayListOf(RerunArrowArray.fixedSizeBinary(ids.toByteArray(), RerunTuid.SIZE))
        times?.let { times ->
            val sorted = times.zipWithNext().all { (a, b) -> a <= b }
            fields += RerunArrowField(
                name = RerunRrdWriter.TIMELINE,
                type = RerunArrowType.DurationNanoseconds,
                nullable = true,
                metadata = mapOf(
                    "rerun:index_name" to RerunRrdWriter.TIMELINE,
                    "rerun:is_sorted" to sorted.toString(),
                    "rerun:kind" to "index",
                ),
            )
            columns += RerunArrowArray.int64s(times.toLongArray())
        }
        for (component in components.sortedBy { it.fieldName }) {
            check(component.array.length == rowCount) { "${component.fieldName}: one list per row" }
            fields += component.field
            columns += component.array
        }
        return RerunArrowIpc.stream(
            fields = fields,
            columns = columns,
            rowCount = rowCount,
            metadata = mapOf(
                "rerun:entity_path" to entityPath,
                "rerun:id" to "chunk_$chunkId",
                "sorbet:version" to RerunRrdWriter.SORBET_VERSION,
            ),
        )
    }
}

/** One component column: a list per row, each list holding that row's instances. */
internal class RerunComponentColumn private constructor(
    val archetype: String,
    val name: String,
    val componentType: String,
    /** The type of one instance (the list's `item`). */
    val item: RerunArrowField,
    counts: List<Int>,
    values: RerunArrowArray,
) {
    val array: RerunArrowArray = RerunArrowArray.list(counts, values)

    val fieldName: String get() = "$archetype:$name"

    val field: RerunArrowField
        get() = RerunArrowField(
            name = fieldName,
            type = RerunArrowType.ListOf(item),
            nullable = true,
            metadata = mapOf(
                "rerun:archetype" to "rerun.archetypes.$archetype",
                "rerun:component" to fieldName,
                "rerun:component_type" to "rerun.components.$componentType",
                "rerun:kind" to "data",
            ),
        )

    companion object {
        private fun sizedItem(type: RerunArrowType, size: Int) = RerunArrowField.item(
            RerunArrowType.FixedSizeList(RerunArrowField.item(type, nullable = false), size),
            nullable = true,
        )

        /** `fixed_size_list<float32>[size]` instances; each row is a flat list of floats. */
        fun vectors(archetype: String, name: String, type: String, rows: List<FloatArray>, size: Int) =
            RerunComponentColumn(
                archetype, name, type, sizedItem(RerunArrowType.Float32, size),
                rows.map { it.size / size },
                RerunArrowArray.fixedSizeList(
                    size,
                    RerunArrowArray.floats(concat(rows.map { it.toList() }).toFloatArray()),
                ),
            )

        fun u8Vectors(archetype: String, name: String, type: String, rows: List<ByteArray>, size: Int) =
            RerunComponentColumn(
                archetype, name, type, sizedItem(RerunArrowType.UInt8, size),
                rows.map { it.size / size },
                RerunArrowArray.fixedSizeList(
                    size,
                    RerunArrowArray.uint8s(concat(rows.map { it.toList() }).toByteArray()),
                ),
            )

        fun u32Vectors(archetype: String, name: String, type: String, rows: List<IntArray>, size: Int) =
            RerunComponentColumn(
                archetype, name, type, sizedItem(RerunArrowType.UInt32, size),
                rows.map { it.size / size },
                RerunArrowArray.fixedSizeList(
                    size,
                    RerunArrowArray.uint32s(concat(rows.map { it.toList() }).toIntArray()),
                ),
            )

        fun floats(archetype: String, name: String, type: String, rows: List<FloatArray>) = RerunComponentColumn(
            archetype, name, type, RerunArrowField.item(RerunArrowType.Float32, nullable = true),
            rows.map { it.size },
            RerunArrowArray.floats(concat(rows.map { it.toList() }).toFloatArray()),
        )

        /** `Color` instances, `0xRRGGBBAA`. */
        fun colors(archetype: String, rows: List<List<Int>>) = RerunComponentColumn(
            archetype, "colors", "Color", RerunArrowField.item(RerunArrowType.UInt32, nullable = true),
            rows.map { it.size },
            RerunArrowArray.uint32s(concat(rows).toIntArray()),
        )

        fun strings(archetype: String, name: String, type: String, rows: List<List<String>>) = RerunComponentColumn(
            archetype, name, type, RerunArrowField.item(RerunArrowType.Utf8, nullable = true),
            rows.map { it.size },
            RerunArrowArray.utf8(concat(rows)),
        )

        /** Byte-blob instances (`list<uint8>`): encoded images, raw texture buffers. */
        fun blobs(archetype: String, name: String, type: String, rows: List<List<ByteArray>>): RerunComponentColumn {
            val blobs = concat(rows)
            val bytes = ByteArrayOutputStream()
            blobs.forEach { bytes.write(it) }
            val all = bytes.toByteArray()
            return RerunComponentColumn(
                archetype, name, type,
                RerunArrowField.item(
                    RerunArrowType.ListOf(RerunArrowField.item(RerunArrowType.UInt8, nullable = false)),
                    nullable = true,
                ),
                rows.map { it.size },
                RerunArrowArray.list(blobs.map { it.size }, RerunArrowArray.uint8s(all)),
            )
        }

        /** `LineStrip3D` instances: each strip a list of 3D points. */
        fun strips(archetype: String, rows: List<List<List<Vec3>>>): RerunComponentColumn {
            val strips = concat(rows)
            val point = sizedItem(RerunArrowType.Float32, 3).copy(nullable = false)
            val flat = RerunRrdWriter.flatten(concat(strips))
            return RerunComponentColumn(
                archetype, "strips", "LineStrip3D",
                RerunArrowField.item(RerunArrowType.ListOf(point), nullable = true),
                rows.map { it.size },
                RerunArrowArray.list(
                    strips.map { it.size },
                    RerunArrowArray.fixedSizeList(3, RerunArrowArray.floats(flat)),
                ),
            )
        }

        /** One `ImageFormat` instance: RGB, or RGBA when [rgba], 8 bits per channel (`pixel_format` null). */
        fun imageFormat(
            archetype: String,
            name: String,
            width: Int,
            height: Int,
            rgba: Boolean = false,
        ): RerunComponentColumn {
            val fields = listOf(
                RerunArrowField("width", RerunArrowType.UInt32, nullable = false),
                RerunArrowField("height", RerunArrowType.UInt32, nullable = false),
                RerunArrowField("pixel_format", RerunArrowType.UInt8, nullable = true),
                RerunArrowField("color_model", RerunArrowType.UInt8, nullable = true),
                RerunArrowField("channel_datatype", RerunArrowType.UInt8, nullable = true),
            )
            val value = RerunArrowArray.structure(
                1,
                listOf(
                    RerunArrowArray.uint32s(intArrayOf(width)),
                    RerunArrowArray.uint32s(intArrayOf(height)),
                    RerunArrowArray.allNull(1, 1),
                    RerunArrowArray.uint8s(byteArrayOf(if (rgba) COLOR_MODEL_RGBA else COLOR_MODEL_RGB)),
                    RerunArrowArray.uint8s(byteArrayOf(CHANNEL_U8)),
                ),
            )
            return RerunComponentColumn(
                archetype, name, "ImageFormat",
                RerunArrowField.item(RerunArrowType.Struct(fields), nullable = true),
                listOf(1),
                value,
            )
        }

        /** Rerun's `ColorModel.RGB`. */
        const val COLOR_MODEL_RGB: Byte = 2

        /** Rerun's `ColorModel.RGBA`. */
        const val COLOR_MODEL_RGBA: Byte = 3

        /** Rerun's `ChannelDatatype.U8`. */
        const val CHANNEL_U8: Byte = 6

        private fun <T> concat(lists: List<List<T>>): List<T> = lists.flatten()
    }
}

/** A Rerun TUID: 64-bit time and 64-bit counter, stored big-endian so bytes sort by time. */
internal data class RerunTuid(val timeNanos: Long, val inc: Long) {
    fun bigEndianBytes(): ByteArray = ByteArray(SIZE) { i ->
        val value = if (i < Long.SIZE_BYTES) timeNanos else inc
        (value ushr (Byte.SIZE_BITS * (Long.SIZE_BYTES - 1 - i % Long.SIZE_BYTES))).toByte()
    }

    /** Rerun's text form: time in upper-case hex, counter in lower-case hex. */
    override fun toString(): String = hex(timeNanos).uppercase() + hex(inc)

    private fun hex(value: Long) = java.lang.Long.toHexString(value).padStart(HEX_DIGITS, '0')

    companion object {
        const val SIZE = 16
        private const val HEX_DIGITS = 16
    }
}

/**
 * Deterministic, strictly increasing TUIDs: a fixed clock and a counter seeded from the recording
 * id, so one scene and one recording id always give the same bytes.
 */
internal class RerunTuidSequence(recordingId: UUID) {
    // Top bits cleared: room to count without wrapping.
    private var inc = recordingId.mostSignificantBits and SEED_MASK

    fun next(): RerunTuid = RerunTuid(RerunRrdWriter.TUID_EPOCH_NANOS, ++inc)

    private companion object {
        const val SEED_MASK = 0x0000_FFFF_FFFF_FFFFL
    }
}
