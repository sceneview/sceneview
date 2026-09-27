import Foundation

// A minimal Arrow IPC stream encoder for `.rrd` chunks: the handful of column types Rerun
// components use, no nulls except where a struct needs them, no dictionaries, no
// compression. Spec: https://arrow.apache.org/docs/format/Columnar.html (IPC streaming
// format, metadata version V5, flatbuffers `Schema.fbs` / `Message.fbs`).

/// An Arrow logical type, restricted to what the `.rrd` writer emits.
indirect enum RerunArrowType: Equatable, Sendable {
    case uint8
    case uint32
    case float32
    /// `duration[ns]`: Rerun's type for a duration timeline.
    case durationNanoseconds
    case fixedSizeBinary(Int)
    case utf8
    case list(RerunArrowField)
    case fixedSizeList(RerunArrowField, Int)
    case structure([RerunArrowField])
}

/// An Arrow field: name, type, nullability and key/value metadata.
struct RerunArrowField: Equatable, Sendable {
    var name: String
    var type: RerunArrowType
    var nullable: Bool
    var metadata: [String: String] = [:]

    /// The conventional `item` child of a list type.
    static func item(_ type: RerunArrowType, nullable: Bool) -> RerunArrowField {
        RerunArrowField(name: "item", type: type, nullable: nullable)
    }
}

/// One Arrow array's physical layout: its buffers in IPC order (validity first) and its
/// children, depth-first — exactly what a record batch's `nodes` and `buffers` describe.
struct RerunArrowArray: Sendable {
    var length: Int
    var nullCount: Int = 0
    var buffers: [Data]
    var children: [RerunArrowArray] = []

    /// A primitive array without nulls (`uint8`, `uint32`, `float32`, `duration`...).
    static func primitive<T: BitwiseCopyable>(_ values: [T]) -> RerunArrowArray {
        RerunArrowArray(length: values.count, buffers: [Data(), littleEndianData(values)])
    }

    /// A primitive array where every slot is null.
    static func allNull(count: Int, byteWidth: Int) -> RerunArrowArray {
        RerunArrowArray(
            length: count,
            nullCount: count,
            buffers: [Data(count: (count + 7) / 8), Data(count: count * byteWidth)]
        )
    }

    /// `fixed_size_binary[width]` from contiguous bytes.
    static func fixedSizeBinary(_ bytes: Data, width: Int) -> RerunArrowArray {
        RerunArrowArray(length: bytes.count / width, buffers: [Data(), bytes])
    }

    /// `utf8` strings.
    static func utf8(_ strings: [String]) -> RerunArrowArray {
        var offsets: [Int32] = [0]
        var values = Data()
        for string in strings {
            values.append(contentsOf: Array(string.utf8))
            offsets.append(Int32(values.count))
        }
        return RerunArrowArray(length: strings.count, buffers: [Data(), littleEndianData(offsets), values])
    }

    /// A `list<...>` whose row `i` holds `counts[i]` consecutive child values.
    static func list(counts: [Int], child: RerunArrowArray) -> RerunArrowArray {
        var offsets: [Int32] = [0]
        offsets.reserveCapacity(counts.count + 1)
        var total: Int32 = 0
        for count in counts {
            total += Int32(count)
            offsets.append(total)
        }
        return RerunArrowArray(length: counts.count, buffers: [Data(), littleEndianData(offsets)], children: [child])
    }

    /// A `fixed_size_list<...>[size]` over `child`.
    static func fixedSizeList(size: Int, child: RerunArrowArray) -> RerunArrowArray {
        RerunArrowArray(length: child.length / size, buffers: [Data()], children: [child])
    }

    /// A `struct<...>` of `length` rows.
    static func structure(length: Int, children: [RerunArrowArray]) -> RerunArrowArray {
        RerunArrowArray(length: length, buffers: [Data()], children: children)
    }

    static func littleEndianData<T: BitwiseCopyable>(_ values: [T]) -> Data {
        // Every Apple platform is little-endian, as Arrow IPC's `Endianness.Little` needs.
        values.withUnsafeBytes { Data($0) }
    }
}

/// Encodes one schema and one record batch as an Arrow IPC stream (what arrow-rs'
/// `StreamReader` and pyarrow's `ipc.open_stream` read).
enum RerunArrowIPC {
    static let continuation: UInt32 = 0xFFFF_FFFF

    /// Schema message, record batch message, then the end-of-stream marker.
    static func stream(
        fields: [RerunArrowField],
        columns: [RerunArrowArray],
        rowCount: Int,
        metadata: [String: String]
    ) -> Data {
        precondition(fields.count == columns.count, "one column per field")

        var nodes: [(length: Int, nullCount: Int)] = []
        var buffers: [(offset: Int, length: Int)] = []
        var body = Data()
        func flatten(_ array: RerunArrowArray) {
            nodes.append((array.length, array.nullCount))
            for buffer in array.buffers {
                buffers.append((body.count, buffer.count))
                body.append(buffer)
                padTo8(&body)
            }
            array.children.forEach(flatten)
        }
        columns.forEach(flatten)

        let schema = RerunFlatBuffer.Node.table([
            0: .int16(0), // Endianness.Little
            1: .node(.vector(fields.map(fieldTable))),
            2: .node(.vector(keyValues(metadata))),
        ])
        var nodeBytes = Data()
        for node in nodes {
            appendLittleEndian(Int64(node.length), to: &nodeBytes)
            appendLittleEndian(Int64(node.nullCount), to: &nodeBytes)
        }
        var bufferBytes = Data()
        for buffer in buffers {
            appendLittleEndian(Int64(buffer.offset), to: &bufferBytes)
            appendLittleEndian(Int64(buffer.length), to: &bufferBytes)
        }
        let recordBatch = RerunFlatBuffer.Node.table([
            0: .int64(Int64(rowCount)),
            1: .node(.structs(count: nodes.count, alignment: 8, bytes: nodeBytes)),
            2: .node(.structs(count: buffers.count, alignment: 8, bytes: bufferBytes)),
        ])

        var out = Data()
        appendMessage(header: schema, headerType: 1, body: Data(), to: &out) // MessageHeader.Schema
        appendMessage(header: recordBatch, headerType: 3, body: body, to: &out) // MessageHeader.RecordBatch
        appendLittleEndian(continuation, to: &out)
        appendLittleEndian(UInt32(0), to: &out)
        return out
    }

    private static func appendMessage(header: RerunFlatBuffer.Node, headerType: UInt8, body: Data, to out: inout Data) {
        let message = RerunFlatBuffer.Node.table([
            0: .int16(4), // MetadataVersion.V5
            1: .uint8(headerType),
            2: .node(header),
            3: .int64(Int64(body.count)),
        ])
        var metadata = Data(RerunFlatBuffer.serialize(message))
        // The prefix is 8 bytes, so padding the flatbuffer to 8 keeps the body 8-aligned.
        padTo8(&metadata)
        appendLittleEndian(continuation, to: &out)
        appendLittleEndian(Int32(metadata.count), to: &out)
        out.append(metadata)
        out.append(body)
    }

    private static func fieldTable(_ field: RerunArrowField) -> RerunFlatBuffer.Node {
        let (typeID, typeTable, children) = typeDescription(field.type)
        var slots: [Int: RerunFlatBuffer.Slot] = [
            0: .node(.string(field.name)),
            1: .bool(field.nullable),
            2: .uint8(typeID),
            3: .node(typeTable),
            5: .node(.vector(children.map(fieldTable))),
        ]
        if !field.metadata.isEmpty {
            slots[6] = .node(.vector(keyValues(field.metadata)))
        }
        return .table(slots)
    }

    /// The `Type` union discriminant, its table, and the child fields.
    private static func typeDescription(
        _ type: RerunArrowType
    ) -> (UInt8, RerunFlatBuffer.Node, [RerunArrowField]) {
        switch type {
        case .uint8: return (2, .table([0: .int32(8), 1: .bool(false)]), []) // Int
        case .uint32: return (2, .table([0: .int32(32), 1: .bool(false)]), [])
        case .float32: return (3, .table([0: .int16(1)]), []) // FloatingPoint SINGLE
        case .durationNanoseconds: return (18, .table([0: .int16(3)]), []) // Duration NANOSECOND
        case .fixedSizeBinary(let width): return (15, .table([0: .int32(Int32(width))]), [])
        case .utf8: return (5, .table([:]), [])
        case .list(let item): return (12, .table([:]), [item])
        case .fixedSizeList(let item, let size): return (16, .table([0: .int32(Int32(size))]), [item])
        case .structure(let fields): return (13, .table([:]), fields) // Struct_
        }
    }

    /// Sorted by key so the output is deterministic.
    private static func keyValues(_ metadata: [String: String]) -> [RerunFlatBuffer.Node] {
        metadata.keys.sorted().map { key in
            .table([0: .node(.string(key)), 1: .node(.string(metadata[key] ?? ""))])
        }
    }

    static func padTo8(_ data: inout Data) {
        let remainder = data.count % 8
        if remainder != 0 { data.append(Data(count: 8 - remainder)) }
    }

    static func appendLittleEndian<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }
}

/// A tiny FlatBuffers serializer, written front to back: every table, vector and string is
/// laid out after whatever points at it (FlatBuffers offsets are unsigned and point
/// forward), each vtable right before its table. Alignment is relative to the buffer start.
enum RerunFlatBuffer {
    indirect enum Node {
        /// Field slot index → value.
        case table([Int: Slot])
        case string(String)
        /// A vector of tables (or strings).
        case vector([Node])
        /// A vector of fixed-size structs, already packed.
        case structs(count: Int, alignment: Int, bytes: Data)
    }

    enum Slot {
        case uint8(UInt8)
        case bool(Bool)
        case int16(Int16)
        case int32(Int32)
        case int64(Int64)
        case node(Node)

        var size: Int {
            switch self {
            case .uint8, .bool: return 1
            case .int16: return 2
            case .int32, .node: return 4
            case .int64: return 8
            }
        }
    }

    /// The whole buffer: a root offset, then the root table.
    static func serialize(_ root: Node) -> [UInt8] {
        var writer = Writer()
        writer.bytes = [0, 0, 0, 0]
        let rootPosition = writer.write(root)
        writer.patchOffset(at: 0, to: rootPosition)
        return writer.bytes
    }

    private struct Writer {
        var bytes: [UInt8] = []

        mutating func pad(to alignment: Int) {
            while bytes.count % alignment != 0 { bytes.append(0) }
        }

        mutating func put<T: FixedWidthInteger>(_ value: T) {
            withUnsafeBytes(of: value.littleEndian) { bytes.append(contentsOf: $0) }
        }

        mutating func store<T: FixedWidthInteger>(_ value: T, at position: Int) {
            withUnsafeBytes(of: value.littleEndian) { raw in
                for (index, byte) in raw.enumerated() { bytes[position + index] = byte }
            }
        }

        mutating func patchOffset(at position: Int, to target: Int) {
            store(UInt32(target - position), at: position)
        }

        /// Writes `node` at the end of the buffer and returns the position offsets target.
        mutating func write(_ node: Node) -> Int {
            switch node {
            case .string(let string):
                pad(to: 4)
                let position = bytes.count
                let utf8 = Array(string.utf8)
                put(UInt32(utf8.count))
                bytes.append(contentsOf: utf8)
                bytes.append(0)
                return position

            case .vector(let items):
                pad(to: 4)
                let position = bytes.count
                put(UInt32(items.count))
                let slots = bytes.count
                bytes.append(contentsOf: [UInt8](repeating: 0, count: 4 * items.count))
                for (index, item) in items.enumerated() {
                    let child = write(item)
                    patchOffset(at: slots + 4 * index, to: child)
                }
                return position

            case .structs(let count, let alignment, let data):
                pad(to: 4)
                // The elements follow the 4-byte length and must be aligned themselves.
                while (bytes.count + 4) % alignment != 0 { bytes.append(0) }
                let position = bytes.count
                put(UInt32(count))
                bytes.append(contentsOf: data)
                return position

            case .table(let slots):
                // Largest fields first keeps the inline part tightly packed and aligned.
                let order = slots.keys.sorted { lhs, rhs in
                    let (a, b) = (slots[lhs]!.size, slots[rhs]!.size)
                    return a != b ? a > b : lhs < rhs
                }
                var inlineOffset: [Int: Int] = [:]
                var inlineSize = 4 // the soffset to the vtable
                var maxAlignment = 4
                for id in order {
                    let size = slots[id]!.size
                    inlineSize = (inlineSize + size - 1) / size * size
                    inlineOffset[id] = inlineSize
                    inlineSize += size
                    maxAlignment = max(maxAlignment, size)
                }
                let slotCount = (slots.keys.max() ?? -1) + 1

                pad(to: 2)
                let vtable = bytes.count
                put(UInt16(4 + 2 * slotCount))
                put(UInt16(inlineSize))
                for id in 0..<slotCount { put(UInt16(inlineOffset[id] ?? 0)) }

                pad(to: maxAlignment)
                let table = bytes.count
                put(Int32(table - vtable))
                bytes.append(contentsOf: [UInt8](repeating: 0, count: inlineSize - 4))

                var pending: [(position: Int, node: Node)] = []
                for id in order {
                    let position = table + inlineOffset[id]!
                    switch slots[id]! {
                    case .uint8(let value): store(value, at: position)
                    case .bool(let value): store(UInt8(value ? 1 : 0), at: position)
                    case .int16(let value): store(value, at: position)
                    case .int32(let value): store(value, at: position)
                    case .int64(let value): store(value, at: position)
                    case .node(let child): pending.append((position, child))
                    }
                }
                for (position, child) in pending {
                    let target = write(child)
                    patchOffset(at: position, to: target)
                }
                return table
            }
        }
    }
}
