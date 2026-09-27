import Foundation

/// A minimal protobuf encoder: the three wire types `.rrd` messages use (varint, 64-bit,
/// length-delimited). Fields are written in the order they are appended.
struct RerunProtobufWriter {
    private(set) var data = Data()

    mutating func varint(_ value: UInt64) {
        var value = value
        while value >= 0x80 {
            data.append(UInt8(truncatingIfNeeded: value) | 0x80)
            value >>= 7
        }
        data.append(UInt8(value))
    }

    mutating func key(_ field: Int, wireType: UInt64) {
        varint(UInt64(field) << 3 | wireType)
    }

    /// `uint64`, `int32` (non-negative), `bool` and enum fields.
    mutating func uint64(_ field: Int, _ value: UInt64) {
        key(field, wireType: 0)
        varint(value)
    }

    mutating func int32(_ field: Int, _ value: Int32) {
        key(field, wireType: 0)
        // Negative int32s are sign-extended to ten bytes, per the protobuf spec.
        varint(UInt64(bitPattern: Int64(value)))
    }

    mutating func bool(_ field: Int, _ value: Bool) {
        uint64(field, value ? 1 : 0)
    }

    mutating func fixed64(_ field: Int, _ value: UInt64) {
        key(field, wireType: 1)
        withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) }
    }

    mutating func bytes(_ field: Int, _ value: Data) {
        key(field, wireType: 2)
        varint(UInt64(value.count))
        data.append(value)
    }

    mutating func string(_ field: Int, _ value: String) {
        bytes(field, Data(value.utf8))
    }

    /// A nested message, built by `body`.
    mutating func message(_ field: Int, _ body: (inout RerunProtobufWriter) -> Void) {
        var nested = RerunProtobufWriter()
        body(&nested)
        bytes(field, nested.data)
    }
}
