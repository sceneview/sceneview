package io.github.sceneview.demo.demos.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

/**
 * The footer [RerunRrdWriter] closes a `.rrd` with ([RerunRrdFooter]), read back from the file's
 * end the way Rerun's tools do: it is where its locator says, every manifest row points at a real
 * chunk, and a file that lost it — or part of it — still reads.
 */
class RerunRrdFooterTest {
    private val codec = FakeRerunImageCodec
    private val recordingId = UUID(0x0123_4567_89AB_CDEFL, 0x0FED_CBA9_8765_4321L)
    private val scene: RerunExportScene by lazy {
        RerunExportAdapter.scene(FakeRerunImageCodec.showcase(), "Recorded room")!!
    }
    private val data: ByteArray by lazy { RerunRrdWriter.write(scene, codec, recordingId = recordingId) }
    private val bytes: RrdBytes by lazy { RrdBytes(data) }

    // ── Reading the file back ─────────────────────────────────────────────────

    /** An Arrow IPC stream read whole: its schema and, when it has one, its record batch. */
    private class Table(val schema: RrdSchema, val rowCount: Int, private val columns: List<RrdArrowArray>) {
        fun field(name: String): RrdArrowField = schema.fields.single { it.name == name }

        private fun column(name: String): RrdArrowArray = columns.single { it.name == name }

        fun text(name: String, row: Int): String = column(name).let { it.byteRange(it.range(row)).decodeToString() }

        fun flag(name: String, row: Int): Boolean =
            column(name).values!!.u8(row / Byte.SIZE_BITS) shr (row % Byte.SIZE_BITS) and 1 == 1

        /** An integer, a duration or a sequence number; `null` for a null slot. */
        fun number(name: String, row: Int): Long? = column(name).let { if (it.isNull(row)) null else it.integer(row) }

        fun binary(name: String, row: Int, width: Int): ByteArray = column(name).values!!.copy(row * width, width)
    }

    private fun table(ipc: RrdBytes): Table {
        var position = 0
        var schema: RrdSchema? = null
        var rowCount = 0
        var columns = emptyList<RrdArrowArray>()
        // Each message: the continuation marker, its length, the message, then its body. Length 0 ends the stream.
        while (ipc.i32(position + 4) != 0) {
            assertEquals(-1, ipc.i32(position))
            val length = ipc.i32(position + 4)
            val flat = RrdFlatBuffer(ipc.slice(position + 8, length))
            val message = flat.root()
            val body = ipc.slice(position + 8 + length, flat.i64(message, 3).toInt())
            position += 8 + length + body.count
            val header = flat.table(message, 2)!!
            if (flat.u8(message, 1) == 1) {
                schema = RrdSchema(flat, header)
            } else {
                rowCount = flat.i64(header, 0).toInt()
                columns = batch(flat, header, body, schema!!)
            }
        }
        assertEquals(-1, ipc.i32(position))
        assertEquals("nothing after the end-of-stream marker", ipc.count, position + 8)
        return Table(schema!!, rowCount, columns)
    }

    /** A `RecordBatch`: its length (0), then its nodes (1) and buffers (2), both vectors of two int64. */
    private fun batch(flat: RrdFlatBuffer, header: Int, body: RrdBytes, schema: RrdSchema): List<RrdArrowArray> {
        fun <T> pairs(slot: Int, make: (Long, Long) -> T): List<T> {
            val (count, start) = flat.vector(header, slot, 16)!!
            return List(count) { make(flat.bytes.i64(start + 16 * it), flat.bytes.i64(start + 16 * it + 8)) }
        }
        val reader = RrdBatchReader(
            pairs(1) { count, nulls -> count.toInt() to nulls.toInt() },
            pairs(2) { offset, size -> body.slice(offset.toInt(), size.toInt()) },
        )
        return schema.fields.map(reader::array)
    }

    /** The `ArrowMsg` whose protobuf payload is the [size] bytes at [offset]: a chunk. */
    private inner class Written(val offset: Int, val size: Int) {
        private val message = RrdProto(bytes.slice(offset, size))
        val storeKind: Long? = message.message(1)!!.varint(1)
        val ipc: RrdBytes = message.bytes(5)!!
        val uncompressedSize: Long? = message.varint(3)
        val isStatic: Boolean = message.varint(7) == 1L
        val table: Table = table(ipc)
        val entityPath: String = table.schema.metadata.getValue("rerun:entity_path")

        /** `Tuid { fixed64 time_ns = 1; fixed64 inc = 2 }`, as the 16 big-endian bytes a manifest holds. */
        val chunkId: ByteArray = message.bytes(6)!!.let { RerunTuid(it.u64(1), it.u64(10)).bigEndianBytes() }
    }

    /** Where the `End` message's header starts, found by walking the messages from the stream header. */
    private fun endHeader(): Int = scan().second

    /** Every chunk met by a full scan, in file order, and where the `End` message starts. */
    private fun scan(): Pair<List<Written>, Int> {
        var position = 12
        val chunks = ArrayList<Written>()
        while (bytes.u64(position) != 0L) {
            val length = bytes.u64(position + 8).toInt()
            if (bytes.u64(position) == 2L) chunks += Written(position + 16, length)
            position += 16 + length
        }
        return chunks to position
    }

    /** One `RrdManifest` of the footer. */
    private inner class Manifest(message: RrdProto) {
        private val store = message.message(1)!!
        val storeKind: Long? = store.varint(1)
        val storeId: String = store.bytes(2)!!.let { String(it.copy(0, it.count)) }
        val applicationId: String = store.message(3)!!.bytes(1)!!.let { String(it.copy(0, it.count)) }
        val schemaStream: RrdBytes = message.message(2)!!.bytes(1)!!
        val schema: RrdSchema = table(schemaStream).schema
        val sha256: ByteArray = message.bytes(3)!!.let { it.copy(0, it.count) }

        /** The `DataframePart`: encoder version (1), payload (2), compression (3), uncompressed size (4). */
        val part: RrdProto = message.message(4)!!
        val rows: Table = table(part.bytes(2)!!)
    }

    /** `RrdFooter { repeated RrdManifest manifests = 1 }`, found through the file's last 32 bytes. */
    private fun manifests(): List<Manifest> {
        val locator = data.size - RerunRrdFooter.STREAM_FOOTER_SIZE
        val payload = bytes.slice(bytes.u64(locator).toInt(), bytes.u64(locator + 8).toInt())
        val manifests = ArrayList<Manifest>()
        var position = 0
        while (position < payload.count) {
            assertEquals(0x0A, payload.u8(position)) // Field 1, length-delimited.
            var length = 0
            var shift = 0
            do {
                val byte = payload.u8(++position)
                length = length or ((byte and 0x7F) shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            manifests += Manifest(RrdProto(payload.slice(position + 1, length)))
            position += 1 + length
        }
        return manifests
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    // ── The footer ────────────────────────────────────────────────────────────

    @Test
    fun `xxHash32 gives the reference values`() {
        fun hash(text: String, seed: Int = 0) = RerunXxHash32.hash(text.encodeToByteArray(), seed)
        assertEquals(0x02CC5D05, hash(""))
        assertEquals(0x550D7456, hash("a"))
        assertEquals(0x32D153FF, hash("abc"))
        // Two 16-byte stripes, a 4-byte step and three single bytes.
        assertEquals(0xE2293B2F.toInt(), hash("Nobody inspects the spammish repetition"))
        assertEquals(0x67BB40DD, hash("xxhash", seed = 20141025))
    }

    @Test
    fun `the file ends with the footer and the 32 bytes that locate it`() {
        val locator = data.size - RerunRrdFooter.STREAM_FOOTER_SIZE
        val start = bytes.u64(locator).toInt()
        val length = bytes.u64(locator + 8).toInt()
        // The entry points at the payload of the `End` message, which closes the stream...
        assertEquals(endHeader() + 16, start)
        assertEquals(0L, bytes.u64(start - 16))
        assertEquals(length.toLong(), bytes.u64(start - 8))
        // ...and the locator follows it at once, with the payload's checksum.
        assertEquals(locator, start + length)
        assertEquals(
            RerunXxHash32.hash(data.copyOfRange(start, locator), RerunRrdFooter.CRC_SEED),
            bytes.i32(locator + 16),
        )
        assertEquals("RRF2FOOT", String(data, locator + 20, 8))
        assertEquals(1, bytes.i32(locator + 28))
    }

    @Test
    fun `one manifest per store, the layout's then the recording's`() {
        val manifests = manifests()
        assertEquals(listOf(2L, 1L), manifests.map { it.storeKind })
        assertEquals(
            listOf(RerunRrdBlueprint.storeId(recordingId), recordingId.toString()),
            manifests.map { it.storeId },
        )
        for (manifest in manifests) {
            assertEquals("sceneview_ar_replay", manifest.applicationId)
            assertEquals(1L, manifest.part.varint(1)) // Encoder version 0.
            assertEquals(1L, manifest.part.varint(3)) // Not compressed.
            assertEquals(manifest.part.bytes(2)!!.count.toLong(), manifest.part.varint(4))
        }
    }

    @Test
    fun `every manifest row points at its chunk`() {
        val written = scan().first
        val manifests = manifests()
        assertEquals(written.size, manifests.sumOf { it.rows.rowCount })
        for (manifest in manifests) {
            val scanned = written.filter { it.storeKind == manifest.storeKind }
            val rows = manifest.rows
            assertEquals(scanned.size, rows.rowCount)
            scanned.forEachIndexed { row, expected ->
                // Jump to the chunk as a tool would: by the row's offset and size alone.
                val chunk = Written(
                    rows.number("chunk_byte_offset", row)!!.toInt(),
                    rows.number("chunk_byte_size", row)!!.toInt(),
                )
                assertEquals(expected.offset, chunk.offset)
                assertEquals(expected.size, chunk.size)
                assertEquals(manifest.storeKind, chunk.storeKind)
                assertArrayEquals(chunk.chunkId, rows.binary("chunk_id", row, RerunTuid.SIZE))
                assertEquals(chunk.entityPath, rows.text("chunk_entity_path", row))
                assertEquals(chunk.isStatic, rows.flag("chunk_is_static", row))
                assertEquals(chunk.table.rowCount.toLong(), rows.number("chunk_num_rows", row))
                assertEquals(chunk.ipc.count.toLong(), rows.number("chunk_byte_size_uncompressed", row))
                assertEquals(chunk.uncompressedSize, rows.number("chunk_byte_size_uncompressed", row))
            }
        }
    }

    @Test
    fun `the manifest says what each chunk holds, and when`() {
        val rows = manifests().last().rows
        val chunks = RerunRrdWriter.chunks(scene, codec)
        val components = chunks.flatMap { it.components }.map { it.fieldName }.distinct()
        val overTime = chunks.filter { it.times != null }.flatMap { it.components }.map { it.fieldName }.distinct()
        assertTrue(overTime.isNotEmpty() && overTime.size < components.size)
        assertEquals(chunks.size, rows.rowCount)
        chunks.forEachIndexed { row, chunk ->
            val held = chunk.components.map { it.fieldName }
            val times = chunk.times
            assertEquals(times?.min(), rows.number("time:start", row))
            assertEquals(times?.max(), rows.number("time:end", row))
            for (component in components) {
                assertEquals(times == null && component in held, rows.flag("$component:has_static_data", row))
            }
            for (component in overTime) {
                val logged = times?.takeIf { component in held }
                assertEquals(logged?.min(), rows.number("time:$component:start", row))
                assertEquals(logged?.max(), rows.number("time:$component:end", row))
                assertEquals(logged?.size?.toLong() ?: 0L, rows.number("time:$component:num_rows", row))
            }
        }
        // Those columns and no other, the chunk's own first.
        val names = rows.schema.fields.map { it.name }
        assertEquals(
            listOf(
                "chunk_entity_path", "chunk_id", "chunk_is_static", "chunk_num_rows",
                "chunk_byte_offset", "chunk_byte_size", "chunk_byte_size_uncompressed",
            ),
            names.take(7),
        )
        assertEquals(7 + components.size + 2 + 3 * overTime.size, names.size)
        assertEquals(RrdArrowType.Time(1), rows.field("time:start").type)
        assertEquals(mapOf("rerun:index" to "time"), rows.field("time:start").metadata)
        assertEquals(
            mapOf(
                "rerun:archetype" to "rerun.archetypes.Points3D",
                "rerun:component" to "Points3D:positions",
                "rerun:component_type" to "rerun.components.Position3D",
                "rerun:index" to "rerun:static",
            ),
            rows.field("Points3D:positions:has_static_data").metadata,
        )
        assertEquals("time", rows.field("time:Points3D:positions:num_rows").metadata["rerun:index"])
    }

    @Test
    fun `the layout's chunks are indexed on the blueprint timeline`() {
        val rows = manifests().first().rows
        assertTrue(rows.rowCount > 0)
        for (row in 0 until rows.rowCount) {
            assertEquals(false, rows.flag("chunk_is_static", row))
            assertEquals(0L, rows.number("blueprint:start", row))
            assertEquals(0L, rows.number("blueprint:end", row))
        }
        assertEquals(RrdArrowType.IntType(64, true), rows.field("blueprint:start").type)
    }

    @Test
    fun `the schema names every column of the store, and its hash is the one Rerun computes`() {
        val (layout, recording) = manifests()
        val names = recording.schema.fields.map { it.name }
        assertEquals(names.sortedWith(RerunUtf8Order), names)
        val columns = RerunRrdWriter.chunks(scene, codec).flatMap { chunk ->
            // A chunk's column is named after its component; the store's also says on which entity.
            val entity = if (chunk.entityPath == "/__properties") "property" else chunk.entityPath
            chunk.components.map { "$entity:${it.fieldName}" }
        }
        assertEquals(columns.toSet() + setOf("rerun.controls.RowId", "time"), names.toSet())
        assertTrue("property:RecordingInfo:name" in names)

        val map = recording.schema.fields.single { it.name == "/world/points:Points3D:positions" }
        assertEquals(
            mapOf(
                "rerun:archetype" to "rerun.archetypes.Points3D",
                "rerun:component" to "Points3D:positions",
                "rerun:component_type" to "rerun.components.Position3D",
                "rerun:entity_path" to "/world/points",
                "rerun:is_static" to "true",
                "rerun:kind" to "data",
            ),
            map.metadata,
        )
        val live = recording.schema.fields.single { it.name == "/world/points/live:Points3D:positions" }
        assertEquals(null, live.metadata["rerun:is_static"])
        assertEquals(RrdArrowType.ListType(false), live.type)

        for (manifest in listOf(layout, recording)) {
            // The stored schema is the hashed one: its message, a multiple of 64 bytes, then the end marker.
            val stream = manifest.schemaStream
            val message = stream.copy(0, stream.count - 8)
            assertEquals(0, message.size % 64)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(message), manifest.sha256)
        }
        // What `rerun rrd filter` (rerun-cli 0.38.1) writes in its own footer for these two stores.
        assertEquals("9fd6c078b406544e2de1be64845af030db7671e7e06e602795bf8c03db91e720", layout.sha256.hex())
        assertEquals("b8424d0c4d68984362c57402fe2f4824435f6824fdad2cbded20c486a3ad14c6", recording.sha256.hex())
    }

    // ── A file cut short ──────────────────────────────────────────────────────

    @Test
    fun `a file without its footer, or cut anywhere inside it, reads the same`() {
        fun read(file: ByteArray) = RrdStream.chunks(file).map { it.entityPath to it.rowCount }
        val whole = read(data)
        assertTrue(whole.size > 5)
        val footer = endHeader()
        val cuts = listOf(
            footer, // No footer at all: what a process killed before closing the file leaves.
            footer + 1, footer + 8, footer + 15, // Inside the `End` header.
            footer + 16, footer + 17, (footer + data.size) / 2, data.size - 33, // Inside the manifests.
            data.size - 32, data.size - 31, data.size - 1, // Inside the locator.
        )
        for (cut in cuts) assertEquals("cut at $cut of ${data.size}", whole, read(data.copyOf(cut)))
        assertEquals("Recorded room", RerunRrdReader.recording(data.copyOf(footer), codec).title)
    }
}
