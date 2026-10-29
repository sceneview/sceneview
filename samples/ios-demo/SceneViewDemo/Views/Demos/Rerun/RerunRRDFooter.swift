import CryptoKit
import Foundation

/// The end of a `.rrd` file: an index of its chunks, one manifest per store. Viewers that
/// scan the file do not need it, but `rerun rrd verify` refuses a file without one, and tools
/// that jump to a chunk (a catalog, a remote viewer) read nothing else. Rerun **0.38.1**,
/// crate `re_log_encoding`. Android's `RerunRrdFooter`, byte for byte.
///
/// Bytes, after the last message:
///
/// 1. An `End` message — the usual 16-byte header (kind `0`, payload length), then a
///    `rerun.log_msg.v1alpha1.RrdFooter`: `repeated RrdManifest manifests = 1`, the stores in
///    file order (the default layout's, then the recording's).
/// 2. A 32-byte `StreamFooter`: the payload's offset in the file and its length (two `u64`),
///    its checksum (`u32`, xxHash32 seeded with ``crcSeed``), `RRF2`, `FOOT`, and the number
///    of such entries (`u32`, always `1`). All little-endian.
///
/// An `RrdManifest`: `store_id = 1`; `sorbet_schema = 2` (`Schema { arrow_schema = 1 }`, a
/// schema-only IPC stream of every chunk's columns, sorted by name); `sorbet_schema_sha256 =
/// 3`, the SHA-256 of that schema message in arrow-rs's own encoding
/// (``RerunArrowSchemaMessage``); `data = 4`, a `DataframePart` holding one uncompressed
/// record batch with a row per chunk, in file order.
///
/// Nothing before the footer points at it, so a file cut short is still a valid stream.
final class RerunRRDFooter {
    /// The `StreamFooter`'s size: one 20-byte entry, `RRF2`, `FOOT` and the entry count.
    static let streamFooterSize = 32
    /// The seed of the footer's xxHash32 checksum: "RERUN" read as a base-26 number.
    static let crcSeed: UInt32 = 7_850_921

    private var manifests: [Manifest] = []

    /// The index of one store's chunks. `storeId` is the store's encoded `StoreId`.
    func manifest(storeId: Data) -> Manifest {
        let manifest = Manifest(storeId: storeId)
        manifests.append(manifest)
        return manifest
    }

    /// Closes the stream: the `End` message carrying every manifest, then the `StreamFooter`.
    func append(to out: inout Data) {
        var footer = RerunProtobufWriter()
        for manifest in manifests where manifest.chunkCount > 0 { footer.bytes(1, manifest.encoded()) }
        let payload = footer.data
        RerunArrowIPC.appendLittleEndian(UInt64(0), to: &out) // MessageKind.End
        RerunArrowIPC.appendLittleEndian(UInt64(payload.count), to: &out)
        let start = out.count
        out.append(payload)
        RerunArrowIPC.appendLittleEndian(UInt64(start), to: &out)
        RerunArrowIPC.appendLittleEndian(UInt64(payload.count), to: &out)
        RerunArrowIPC.appendLittleEndian(RerunXxHash32.hash(payload, seed: Self.crcSeed), to: &out)
        out.append(Data("RRF2".utf8))
        out.append(Data("FOOT".utf8))
        RerunArrowIPC.appendLittleEndian(UInt32(1), to: &out)
    }

    /// One component of one archetype, ordered as Rerun's `ComponentDescriptor` is —
    /// archetype, component, component type — which is the order of the manifest's columns.
    fileprivate struct Descriptor: Hashable, Comparable {
        var archetype: String
        var component: String
        var componentType: String

        static func < (lhs: Self, rhs: Self) -> Bool {
            if lhs.archetype != rhs.archetype { return RerunUTF8Order.less(lhs.archetype, rhs.archetype) }
            if lhs.component != rhs.component { return RerunUTF8Order.less(lhs.component, rhs.component) }
            return RerunUTF8Order.less(lhs.componentType, rhs.componentType)
        }

        /// Field metadata of a manifest column about this component on `index` (a timeline, or static).
        func metadata(index: String) -> [String: String] {
            [
                "rerun:archetype": archetype,
                "rerun:component": component,
                "rerun:component_type": componentType,
                "rerun:index": index,
            ]
        }
    }

    /// What the footer keeps of one written chunk: where it lies, how many rows, the time it
    /// spans, and which components it carries.
    fileprivate struct Row {
        var entityPath: String
        var chunkId: RerunTuid
        /// `nil` for a static chunk.
        var timeline: RerunTimeline?
        var rowCount: Int
        var byteOffset: Int
        var byteSize: Int
        var uncompressedSize: Int
        var timeStart: Int64
        var timeEnd: Int64
        var components: [Descriptor]

        func start(on timeline: RerunTimeline) -> Int64? {
            self.timeline == timeline && rowCount > 0 ? timeStart : nil
        }

        func end(on timeline: RerunTimeline) -> Int64? {
            self.timeline == timeline && rowCount > 0 ? timeEnd : nil
        }
    }

    /// One data column of the store's schema: a component on an entity, across its chunks.
    fileprivate struct DataColumn {
        var entityPath: String
        var descriptor: Descriptor
        var item: RerunArrowField
        /// Some chunk logs it as static data.
        var isStatic: Bool
        /// No chunk holds a single instance of it.
        var isEmpty: Bool
    }

    /// The chunk index of one store, filled as its chunks are written.
    final class Manifest {
        private let storeId: Data
        private var rows: [Row] = []
        private var descriptors: Set<Descriptor> = []
        private var columnNames: [String] = []
        private var dataColumns: [String: DataColumn] = [:]

        fileprivate init(storeId: Data) { self.storeId = storeId }

        var chunkCount: Int { rows.count }

        /// Records `chunk`, written with id `chunkId` as an `ArrowMsg` whose protobuf payload —
        /// without its 16-byte header — is `byteSize` bytes at `byteOffset`, and whose Arrow IPC
        /// stream is `uncompressedSize` bytes.
        func add(chunk: RerunChunk, chunkId: RerunTuid, byteOffset: Int, byteSize: Int, uncompressedSize: Int) {
            let isStatic = chunk.times == nil
            let components = chunk.components.map { column -> Descriptor in
                let descriptor = Descriptor(
                    archetype: column.archetypeName, component: column.fieldName, componentType: column.componentTypeName
                )
                descriptors.insert(descriptor)
                let isEmpty = column.array.children.first?.length == 0
                let name = "\(Self.columnPrefix(chunk.entityPath)):\(column.fieldName)"
                if var known = dataColumns[name] {
                    known.isStatic = known.isStatic || isStatic
                    known.isEmpty = known.isEmpty && isEmpty
                    dataColumns[name] = known
                } else {
                    columnNames.append(name)
                    dataColumns[name] = DataColumn(
                        entityPath: chunk.entityPath, descriptor: descriptor, item: column.item,
                        isStatic: isStatic, isEmpty: isEmpty
                    )
                }
                return descriptor
            }
            rows.append(Row(
                entityPath: chunk.entityPath,
                chunkId: chunkId,
                timeline: isStatic ? nil : chunk.timeline,
                rowCount: chunk.rowCount,
                byteOffset: byteOffset,
                byteSize: byteSize,
                uncompressedSize: uncompressedSize,
                timeStart: chunk.times?.min() ?? 0,
                timeEnd: chunk.times?.max() ?? 0,
                components: components
            ))
        }

        /// The `RrdManifest` message.
        func encoded() -> Data {
            let fields = sorbetFields()
            let data = dataframe()
            var manifest = RerunProtobufWriter()
            manifest.bytes(1, storeId)
            manifest.message(2) { $0.bytes(1, RerunArrowSchemaMessage.stream(fields)) }
            manifest.bytes(3, RerunArrowSchemaMessage.sha256(fields))
            manifest.message(4) { part in
                part.uint64(1, 1) // ENCODER_VERSION_V0
                part.bytes(2, data)
                part.uint64(3, 1) // COMPRESSION_NONE
                part.uint64(4, UInt64(data.count))
            }
            return manifest.data
        }

        /// The timelines the store's chunks are indexed on, by name.
        private func timelines() -> [RerunTimeline] {
            var seen: [RerunTimeline] = []
            for row in rows { if let timeline = row.timeline, !seen.contains(timeline) { seen.append(timeline) } }
            return seen.sorted { RerunUTF8Order.less($0.name, $1.name) }
        }

        /// The store's schema, as Rerun's `SchemaBuilder` derives it from the chunks: the row id,
        /// one index column per timeline, and one data column per component of each entity.
        /// Sorted by name.
        func sorbetFields() -> [RerunArrowField] {
            var fields = [RerunArrowField(
                name: "rerun.controls.RowId",
                type: .fixedSizeBinary(16),
                nullable: false,
                metadata: [
                    "ARROW:extension:metadata": #"{"namespace":"row"}"#,
                    "ARROW:extension:name": "rerun.datatypes.TUID",
                    "rerun:kind": "control",
                ]
            )]
            for timeline in timelines() {
                fields.append(RerunArrowField(
                    name: timeline.name, type: timeline.type, nullable: true,
                    metadata: ["rerun:index_name": timeline.name, "rerun:kind": "index"]
                ))
            }
            for name in columnNames {
                guard let column = dataColumns[name] else { continue }
                var metadata = [
                    "rerun:archetype": column.descriptor.archetype,
                    "rerun:component": column.descriptor.component,
                    "rerun:component_type": column.descriptor.componentType,
                    "rerun:entity_path": column.entityPath,
                    "rerun:kind": "data",
                ]
                if column.isStatic { metadata["rerun:is_static"] = "true" }
                if column.isEmpty { metadata["rerun:is_semantically_empty"] = "true" }
                fields.append(RerunArrowField(name: name, type: .list(column.item), nullable: true, metadata: metadata))
            }
            return fields.sorted { RerunUTF8Order.less($0.name, $1.name) }
        }

        /// The manifest's record batch, a row per chunk, as Rerun's `RrdManifestBuilder` lays
        /// it out: the chunk's entity, id, static flag, row count, offset and sizes; a
        /// `<Component>:has_static_data` flag per component; each timeline's `:start` and
        /// `:end`; then `<timeline>:<Component>:start`, `:end` and `:num_rows` for every
        /// component logged on that timeline.
        func dataframe() -> Data {
            var fields: [RerunArrowField] = []
            var arrays: [RerunArrowArray] = []
            func column(_ name: String, _ type: RerunArrowType, _ array: RerunArrowArray) {
                fields.append(RerunArrowField(name: name, type: type, nullable: false))
                arrays.append(array)
            }
            func counts(_ name: String, _ value: (Row) -> Int) {
                column(name, .uint64, .primitive(rows.map { Int64(value($0)) }))
            }

            column("chunk_entity_path", .utf8, .utf8(rows.map(\.entityPath)))
            column("chunk_id", .fixedSizeBinary(16),
                   .fixedSizeBinary(rows.reduce(into: Data()) { $0.append($1.chunkId.bigEndianBytes) }, width: 16))
            column("chunk_is_static", .bool, .bools(rows.map { $0.timeline == nil }))
            counts("chunk_num_rows") { $0.rowCount }
            counts("chunk_byte_offset") { $0.byteOffset }
            counts("chunk_byte_size") { $0.byteSize }
            counts("chunk_byte_size_uncompressed") { $0.uncompressedSize }

            for descriptor in descriptors.sorted() {
                fields.append(RerunArrowField(
                    name: "\(descriptor.component):has_static_data", type: .bool, nullable: false,
                    metadata: descriptor.metadata(index: "rerun:static")
                ))
                arrays.append(.bools(rows.map { $0.timeline == nil && $0.components.contains(descriptor) }))
            }
            let timelines = timelines()
            for timeline in timelines {
                let metadata = ["rerun:index": timeline.name]
                fields.append(RerunArrowField(name: "\(timeline.name):start", type: timeline.type, nullable: true, metadata: metadata))
                arrays.append(.nullableInt64s(rows.map { $0.start(on: timeline) }))
                fields.append(RerunArrowField(name: "\(timeline.name):end", type: timeline.type, nullable: true, metadata: metadata))
                arrays.append(.nullableInt64s(rows.map { $0.end(on: timeline) }))
            }
            for timeline in timelines {
                var logged: [Descriptor] = []
                for row in rows where row.timeline == timeline {
                    for descriptor in row.components where !logged.contains(descriptor) { logged.append(descriptor) }
                }
                for descriptor in logged.sorted() {
                    let name = "\(timeline.name):\(descriptor.component)"
                    let metadata = descriptor.metadata(index: timeline.name)
                    func holds(_ row: Row) -> Bool { row.timeline == timeline && row.components.contains(descriptor) }
                    fields.append(RerunArrowField(name: "\(name):start", type: timeline.type, nullable: true, metadata: metadata))
                    arrays.append(.nullableInt64s(rows.map { holds($0) ? $0.start(on: timeline) : nil }))
                    fields.append(RerunArrowField(name: "\(name):end", type: timeline.type, nullable: true, metadata: metadata))
                    arrays.append(.nullableInt64s(rows.map { holds($0) ? $0.end(on: timeline) : nil }))
                    fields.append(RerunArrowField(name: "\(name):num_rows", type: .uint64, nullable: true, metadata: metadata))
                    arrays.append(.primitive(rows.map { holds($0) ? Int64($0.rowCount) : 0 }))
                }
            }
            return RerunArrowIPC.stream(fields: fields, columns: arrays, rowCount: rows.count, metadata: [:])
        }

        /// The entity path, or `property` for the recording's properties (`/__properties`, where
        /// `RecordingInfo` lives), which Rerun shows that way.
        private static func columnPrefix(_ entityPath: String) -> String {
            if entityPath == "/__properties" { return "property" }
            if entityPath.hasPrefix("/__properties/") {
                return "property:" + entityPath.dropFirst("/__properties/".count)
            }
            return entityPath
        }
    }
}

/// Rust's `str` order: byte-wise on UTF-8.
enum RerunUTF8Order {
    static func less(_ a: String, _ b: String) -> Bool {
        a.utf8.lexicographicallyPrecedes(b.utf8)
    }
}

/// xxHash32 (https://xxhash.com), the checksum of a `.rrd` footer.
enum RerunXxHash32 {
    private static let prime1: UInt32 = 0x9E37_79B1
    private static let prime2: UInt32 = 0x85EB_CA77
    private static let prime3: UInt32 = 0xC2B2_AE3D
    private static let prime4: UInt32 = 0x27D4_EB2F
    private static let prime5: UInt32 = 0x1656_67B1

    static func hash(_ data: Data, seed: UInt32) -> UInt32 {
        let bytes = [UInt8](data)
        func word(_ at: Int) -> UInt32 {
            UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24
        }
        func rotl(_ x: UInt32, _ r: UInt32) -> UInt32 { x << r | x >> (32 - r) }
        func round(_ acc: UInt32, _ input: UInt32) -> UInt32 { rotl(acc &+ input &* prime2, 13) &* prime1 }

        var position = 0
        var hash: UInt32
        if bytes.count >= 16 {
            var a = seed &+ prime1 &+ prime2, b = seed &+ prime2, c = seed, d = seed &- prime1
            while position <= bytes.count - 16 {
                a = round(a, word(position))
                b = round(b, word(position + 4))
                c = round(c, word(position + 8))
                d = round(d, word(position + 12))
                position += 16
            }
            hash = rotl(a, 1) &+ rotl(b, 7) &+ rotl(c, 12) &+ rotl(d, 18)
        } else {
            hash = seed &+ prime5
        }
        hash = hash &+ UInt32(truncatingIfNeeded: bytes.count)
        while position <= bytes.count - 4 {
            hash = rotl(hash &+ word(position) &* prime3, 17) &* prime4
            position += 4
        }
        while position < bytes.count {
            hash = rotl(hash &+ UInt32(bytes[position]) &* prime5, 11) &* prime1
            position += 1
        }
        hash = (hash ^ hash >> 15) &* prime2
        hash = (hash ^ hash >> 13) &* prime3
        return hash ^ hash >> 16
    }
}

/// An Arrow schema as the bytes **arrow-rs** writes for it — the same schema has many valid
/// FlatBuffers encodings, and a `.rrd` footer stores the SHA-256 of this particular one
/// (`sorbet_schema_sha256`), so ``RerunFlatBuffer``'s own layout would hash to something else.
/// Android's `RerunArrowSchemaMessage`, transcribed from arrow-ipc 58.3.0 and the Rust
/// `flatbuffers` 25.12.19 builder, the versions Rerun 0.38.1 pins.
enum RerunArrowSchemaMessage {
    private static let alignment = 64
    private static let prefixSize = 8
    private static let continuation: [UInt8] = [0xFF, 0xFF, 0xFF, 0xFF]

    /// The encapsulated schema message, as arrow-rs's `StreamWriter` frames it: continuation
    /// marker, length, the flatbuffer, zeros up to a multiple of 64 bytes.
    static func message(_ fields: [RerunArrowField]) -> Data {
        let flat = flatBuffer(fields)
        let aligned = (flat.count + prefixSize + alignment - 1) / alignment * alignment
        var out = Data(continuation)
        RerunArrowIPC.appendLittleEndian(Int32(aligned - prefixSize), to: &out)
        out.append(contentsOf: flat)
        out.append(Data(count: aligned - prefixSize - flat.count))
        return out
    }

    /// A schema-only IPC stream: ``message(_:)``, then the end-of-stream marker.
    static func stream(_ fields: [RerunArrowField]) -> Data {
        message(fields) + Data(continuation) + Data(count: 4)
    }

    /// Rerun's `sorbet_schema_sha256`: the SHA-256 of ``message(_:)`` with the fields sorted
    /// by name (as UTF-8 bytes).
    static func sha256(_ fields: [RerunArrowField]) -> Data {
        Data(SHA256.hash(data: message(fields.sorted { RerunUTF8Order.less($0.name, $1.name) })))
    }

    private static func flatBuffer(_ fields: [RerunArrowField]) -> [UInt8] {
        let builder = BackwardFlatBuffer()
        let fieldTables = fields.map { field(builder, $0) }
        let fieldVector = builder.vector(fieldTables)
        builder.startTable()
        builder.offsetSlot(schemaFields, fieldVector) // Endianness.Little is the default: not written.
        let schema = builder.endTable()
        builder.startTable()
        builder.int16Slot(messageVersion, metadataV5, default: 0)
        builder.uint8Slot(messageHeaderType, headerSchema, default: 0)
        builder.offsetSlot(messageHeader, schema)
        return builder.finish(builder.endTable())
    }

    /// `build_field`: metadata, name, type and children, then the `Field` table.
    private static func field(_ builder: BackwardFlatBuffer, _ field: RerunArrowField) -> Int {
        let metadata = field.metadata.isEmpty ? nil : keyValues(builder, field.metadata)
        let name = builder.string(field.name)
        let (typeId, type, children) = type(builder, field.type)
        builder.startTable()
        builder.offsetSlot(fieldName, name)
        builder.uint8Slot(fieldTypeType, typeId, default: 0)
        builder.uint8Slot(fieldNullable, field.nullable ? 1 : 0, default: 0)
        builder.offsetSlot(fieldChildren, children)
        builder.offsetSlot(fieldType, type)
        if let metadata { builder.offsetSlot(fieldMetadata, metadata) }
        return builder.endTable()
    }

    /// `metadata_to_fb`: one `KeyValue` table per entry, in key order.
    private static func keyValues(_ builder: BackwardFlatBuffer, _ metadata: [String: String]) -> Int {
        let tables = metadata.keys.sorted(by: RerunUTF8Order.less).map { key -> Int in
            let k = builder.string(key)
            let v = builder.string(metadata[key] ?? "")
            builder.startTable()
            builder.offsetSlot(keyValueKey, k)
            builder.offsetSlot(keyValueValue, v)
            return builder.endTable()
        }
        return builder.vector(tables)
    }

    /// `get_fb_field_type`: the `Type` union's discriminant, its table and the children vector.
    /// Which of the last two is written first differs per type, and so do the bytes.
    private static func type(_ builder: BackwardFlatBuffer, _ type: RerunArrowType) -> (Int, Int, Int) {
        switch type {
        case .uint8: return integer(builder, bits: 8, signed: false)
        case .uint32: return integer(builder, bits: 32, signed: false)
        case .uint64: return integer(builder, bits: 64, signed: false)
        case .int64: return integer(builder, bits: 64, signed: true)
        case .float32:
            let children = builder.vector([])
            builder.startTable()
            builder.int16Slot(slot0, precisionSingle, default: 0)
            return (typeFloatingPoint, builder.endTable(), children)
        case .durationNanoseconds:
            builder.startTable()
            builder.int16Slot(slot0, unitNanosecond, default: unitMillisecond)
            let table = builder.endTable()
            return (typeDuration, table, builder.vector([]))
        case .utf8:
            let table = emptyTable(builder)
            return (typeUtf8, table, builder.vector([]))
        case .bool:
            let table = emptyTable(builder)
            return (typeBool, table, builder.vector([]))
        case .fixedSizeBinary(let width):
            builder.startTable()
            builder.int32Slot(slot0, width, default: 0)
            let table = builder.endTable()
            return (typeFixedSizeBinary, table, builder.vector([]))
        case .list(let item):
            let child = field(builder, item)
            let table = emptyTable(builder)
            return (typeList, table, builder.vector([child]))
        case .fixedSizeList(let item, let size):
            let child = field(builder, item)
            builder.startTable()
            builder.int32Slot(slot0, size, default: 0)
            let table = builder.endTable()
            return (typeFixedSizeList, table, builder.vector([child]))
        case .structure(let fields):
            let children = fields.map { field(builder, $0) }
            let table = emptyTable(builder)
            return (typeStruct, table, builder.vector(children))
        }
    }

    /// `Int`: the (empty) children vector first, then `is_signed` before `bitWidth`.
    private static func integer(_ builder: BackwardFlatBuffer, bits: Int, signed: Bool) -> (Int, Int, Int) {
        let children = builder.vector([])
        builder.startTable()
        builder.uint8Slot(slot1, signed ? 1 : 0, default: 0)
        builder.int32Slot(slot0, bits, default: 0)
        return (typeInt, builder.endTable(), children)
    }

    private static func emptyTable(_ builder: BackwardFlatBuffer) -> Int {
        builder.startTable()
        return builder.endTable()
    }

    // Vtable offsets of the slots (4 + 2 × field index), from Arrow's `Schema.fbs` / `Message.fbs`.
    private static let slot0 = 4
    private static let slot1 = 6
    private static let keyValueKey = 4
    private static let keyValueValue = 6
    private static let fieldName = 4
    private static let fieldNullable = 6
    private static let fieldTypeType = 8
    private static let fieldType = 10
    private static let fieldChildren = 14
    private static let fieldMetadata = 16
    private static let schemaFields = 6
    private static let messageVersion = 4
    private static let messageHeaderType = 6
    private static let messageHeader = 8

    private static let metadataV5 = 4
    private static let headerSchema = 1
    private static let precisionSingle = 1
    private static let unitMillisecond = 1
    private static let unitNanosecond = 3

    // The `Type` union.
    private static let typeInt = 2
    private static let typeFloatingPoint = 3
    private static let typeUtf8 = 5
    private static let typeBool = 6
    private static let typeList = 12
    private static let typeStruct = 13
    private static let typeFixedSizeBinary = 15
    private static let typeFixedSizeList = 16
    private static let typeDuration = 18
}

/// The Rust `flatbuffers` builder's layout: the buffer grows towards its start, a table's
/// fields lie in the order they were pushed, default values are skipped, and a vtable
/// identical to an earlier one is shared instead of written again. Positions are distances
/// from the buffer's end.
private final class BackwardFlatBuffer {
    private var bytes = [UInt8](repeating: 0, count: 1024)
    private var head = 1024
    private var minAlign = 1
    private var vtables: [[UInt8]: Int] = [:]
    /// The open table's slots: vtable offset → position of the value.
    private var slots: [(slot: Int, position: Int)] = []
    private var tableStart = 0

    private var used: Int { bytes.count - head }

    private func makeSpace(_ count: Int) {
        if head < count {
            let size = max(bytes.count * 2, used + count)
            var grown = [UInt8](repeating: 0, count: size)
            grown.replaceSubrange((size - used)..<size, with: bytes[head...])
            head = size - used
            bytes = grown
        }
        head -= count
    }

    /// Zeros so that, once `length` more bytes are written, the position is a multiple of `alignment`.
    private func align(_ length: Int, _ alignment: Int) {
        minAlign = max(minAlign, alignment)
        makeSpace((alignment - (used + length) % alignment) % alignment)
    }

    @discardableResult
    private func push(_ value: Int, _ size: Int) -> Int {
        align(size, size)
        makeSpace(size)
        put(head, value, size)
        return used
    }

    private func put(_ at: Int, _ value: Int, _ size: Int) {
        for i in 0..<size { bytes[at + i] = UInt8(truncatingIfNeeded: value >> (8 * i)) }
    }

    /// A forward offset to `target`, relative to where the offset itself lands.
    private func pushOffset(_ target: Int) -> Int {
        align(4, 4)
        return push(4 + used - target, 4)
    }

    func string(_ text: String) -> Int {
        let data = Array(text.utf8)
        align(data.count + 1, 4)
        push(0, 1)
        makeSpace(data.count)
        bytes.replaceSubrange(head..<(head + data.count), with: data)
        return push(data.count, 4)
    }

    /// A vector of offsets to `targets` (tables or strings).
    func vector(_ targets: [Int]) -> Int {
        let size = 4 * targets.count
        align(size, 4)
        makeSpace(size)
        for (index, target) in targets.enumerated() {
            put(head + 4 * index, used - 4 * index - target, 4)
        }
        return push(targets.count, 4)
    }

    func startTable() {
        slots.removeAll()
        tableStart = used
    }

    func uint8Slot(_ slot: Int, _ value: Int, default defaultValue: Int) {
        if value != defaultValue { slots.append((slot, push(value, 1))) }
    }

    func int16Slot(_ slot: Int, _ value: Int, default defaultValue: Int) {
        if value != defaultValue { slots.append((slot, push(value, 2))) }
    }

    func int32Slot(_ slot: Int, _ value: Int, default defaultValue: Int) {
        if value != defaultValue { slots.append((slot, push(value, 4))) }
    }

    func offsetSlot(_ slot: Int, _ target: Int) {
        slots.append((slot, pushOffset(target)))
    }

    /// Closes the table: its vtable (shared when one with the same bytes exists), then the link to it.
    func endTable() -> Int {
        let table = push(0, 4)
        var vtable = [UInt8](repeating: 0, count: (slots.map(\.slot).max() ?? 2) + 2)
        func put16(_ at: Int, _ value: Int) {
            vtable[at] = UInt8(truncatingIfNeeded: value)
            vtable[at + 1] = UInt8(truncatingIfNeeded: value >> 8)
        }
        put16(0, vtable.count)
        put16(2, table - tableStart)
        for (slot, position) in slots { put16(slot, table - position) }
        let position: Int
        if let shared = vtables[vtable] {
            position = shared
        } else {
            makeSpace(vtable.count)
            bytes.replaceSubrange(head..<(head + vtable.count), with: vtable)
            position = used
            vtables[vtable] = position
        }
        put(bytes.count - table, position - table, 4)
        slots.removeAll()
        return table
    }

    /// The finished buffer: the root offset first, the whole a multiple of the widest alignment.
    func finish(_ root: Int) -> [UInt8] {
        align(4, minAlign)
        _ = pushOffset(root)
        return Array(bytes[head...])
    }
}
