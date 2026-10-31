import Foundation
import simd

/// Reads glTF accessors into flat Swift arrays.
///
/// Handles every component type (`BYTE` … `FLOAT`), the `normalized` flag, interleaved
/// buffer views (`byteStride`), accessors with no buffer view (all zeros, as the spec
/// says) and sparse accessors (a dense base patched by index/value pairs). Every read
/// is bounds-checked: a file that lies about its sizes throws
/// ``ModelLoadingError/malformed(reason:)`` instead of reading past a buffer.
struct GLTFAccessorReader {

    let document: GLTFDocument

    enum ComponentType: Int {
        case byte = 5120, unsignedByte = 5121, short = 5122, unsignedShort = 5123
        case unsignedInt = 5125, float = 5126

        var size: Int {
            switch self {
            case .byte, .unsignedByte: return 1
            case .short, .unsignedShort: return 2
            case .unsignedInt, .float: return 4
            }
        }
    }

    static func componentCount(of type: String) -> Int? {
        switch type {
        case "SCALAR": return 1
        case "VEC2": return 2
        case "VEC3": return 3
        case "VEC4": return 4
        case "MAT2": return 4
        case "MAT3": return 9
        case "MAT4": return 16
        default: return nil
        }
    }

    // MARK: - Public reads

    /// Every element of an accessor as floats, flattened — `count × components` values.
    /// Normalized integers map to `0…1` / `-1…1`; non-normalized integers keep their value.
    func floats(_ index: Int) throws -> (values: [Float], components: Int) {
        let accessor = try self.accessor(index)
        guard let components = Self.componentCount(of: accessor.type),
              let type = ComponentType(rawValue: accessor.componentType) else {
            throw ModelLoadingError.malformed(reason: "accessor \(index) has an unknown type")
        }
        let normalized = accessor.normalized ?? false
        var values = [Float](repeating: 0, count: accessor.count * components)

        if let view = accessor.bufferView {
            try forEachComponent(
                view: view, byteOffset: accessor.byteOffset ?? 0, count: accessor.count,
                components: components, type: type, accessorIndex: index
            ) { element, component, raw in
                values[element * components + component] = Self.decode(raw, type: type, normalized: normalized)
            }
        }

        if let sparse = accessor.sparse, sparse.count > 0 {
            let targets = try integers(
                view: sparse.indices.bufferView, byteOffset: sparse.indices.byteOffset ?? 0,
                count: sparse.count, componentTypeRaw: sparse.indices.componentType, accessorIndex: index
            )
            try forEachComponent(
                view: sparse.values.bufferView, byteOffset: sparse.values.byteOffset ?? 0,
                count: sparse.count, components: components, type: type, accessorIndex: index,
                packed: true
            ) { element, component, raw in
                let target = Int(targets[element])
                guard target < accessor.count else { return }
                values[target * components + component] = Self.decode(raw, type: type, normalized: normalized)
            }
        }
        return (values, components)
    }

    /// An accessor as `SIMD2`s; throws when it is not `VEC2`.
    func vec2(_ index: Int) throws -> [SIMD2<Float>] {
        let (v, c) = try floats(index)
        guard c == 2 else { throw typeMismatch(index, expected: "VEC2") }
        return stride(from: 0, to: v.count, by: 2).map { SIMD2(v[$0], v[$0 + 1]) }
    }

    /// An accessor as `SIMD3`s; throws when it is not `VEC3`.
    func vec3(_ index: Int) throws -> [SIMD3<Float>] {
        let (v, c) = try floats(index)
        guard c == 3 else { throw typeMismatch(index, expected: "VEC3") }
        return stride(from: 0, to: v.count, by: 3).map { SIMD3(v[$0], v[$0 + 1], v[$0 + 2]) }
    }

    /// An accessor as `SIMD4`s. A `VEC3` colour is widened with alpha 1.
    func vec4(_ index: Int, widenVec3: Bool = false) throws -> [SIMD4<Float>] {
        let (v, c) = try floats(index)
        if c == 3, widenVec3 {
            return stride(from: 0, to: v.count, by: 3).map { SIMD4(v[$0], v[$0 + 1], v[$0 + 2], 1) }
        }
        guard c == 4 else { throw typeMismatch(index, expected: "VEC4") }
        return stride(from: 0, to: v.count, by: 4).map { SIMD4(v[$0], v[$0 + 1], v[$0 + 2], v[$0 + 3]) }
    }

    /// A `MAT4` accessor — glTF matrices are column-major, like `simd_float4x4`.
    func matrices(_ index: Int) throws -> [simd_float4x4] {
        let (v, c) = try floats(index)
        guard c == 16 else { throw typeMismatch(index, expected: "MAT4") }
        return stride(from: 0, to: v.count, by: 16).map { o in
            simd_float4x4(
                SIMD4(v[o], v[o + 1], v[o + 2], v[o + 3]),
                SIMD4(v[o + 4], v[o + 5], v[o + 6], v[o + 7]),
                SIMD4(v[o + 8], v[o + 9], v[o + 10], v[o + 11]),
                SIMD4(v[o + 12], v[o + 13], v[o + 14], v[o + 15])
            )
        }
    }

    /// An index accessor (`SCALAR` of an unsigned integer type) as `UInt32`s.
    func indices(_ index: Int) throws -> [UInt32] {
        let accessor = try self.accessor(index)
        guard accessor.type == "SCALAR" else { throw typeMismatch(index, expected: "SCALAR") }
        if accessor.sparse == nil, let view = accessor.bufferView {
            return try integers(
                view: view, byteOffset: accessor.byteOffset ?? 0, count: accessor.count,
                componentTypeRaw: accessor.componentType, accessorIndex: index
            )
        }
        // Sparse or view-less index buffers are legal and vanishingly rare: go through
        // the general float path (exact for every index below 2^24).
        return try floats(index).values.map { UInt32(max($0, 0)) }
    }

    /// Element count of an accessor, without reading it.
    func count(_ index: Int) throws -> Int { try accessor(index).count }

    // MARK: - Internals

    private func accessor(_ index: Int) throws -> GLTFAccessorDef {
        guard let accessor = document.root.accessors?[safe: index] else {
            throw ModelLoadingError.malformed(reason: "accessor \(index) does not exist")
        }
        return accessor
    }

    private func typeMismatch(_ index: Int, expected: String) -> ModelLoadingError {
        .malformed(reason: "accessor \(index) is not \(expected)")
    }

    private func integers(
        view: Int, byteOffset: Int, count: Int, componentTypeRaw: Int, accessorIndex: Int
    ) throws -> [UInt32] {
        guard let type = ComponentType(rawValue: componentTypeRaw),
              type == .unsignedByte || type == .unsignedShort || type == .unsignedInt else {
            throw ModelLoadingError.malformed(reason: "accessor \(accessorIndex): indices must be unsigned integers")
        }
        var out = [UInt32](repeating: 0, count: count)
        try forEachComponent(
            view: view, byteOffset: byteOffset, count: count, components: 1, type: type,
            accessorIndex: accessorIndex
        ) { element, _, raw in
            out[element] = raw.integer
        }
        return out
    }

    /// One raw component, before normalization.
    private enum Raw {
        case int(Int32), uint(UInt32), float(Float)
        var integer: UInt32 {
            switch self {
            case .int(let v): return UInt32(max(v, 0))
            case .uint(let v): return v
            case .float(let v): return UInt32(max(v, 0))
            }
        }
    }

    private static func decode(_ raw: Raw, type: ComponentType, normalized: Bool) -> Float {
        switch raw {
        case .float(let v):
            return v
        case .int(let v):
            guard normalized else { return Float(v) }
            switch type {
            case .byte: return max(Float(v) / 127, -1)
            case .short: return max(Float(v) / 32767, -1)
            default: return Float(v)
            }
        case .uint(let v):
            guard normalized else { return Float(v) }
            switch type {
            case .unsignedByte: return Float(v) / 255
            case .unsignedShort: return Float(v) / 65535
            case .unsignedInt: return Float(Double(v) / 4_294_967_295)
            default: return Float(v)
            }
        }
    }

    /// Walks `count` elements of `components` components in a buffer view.
    ///
    /// `packed` ignores the view's `byteStride` — sparse value arrays are always tightly
    /// packed. Matrix columns of 1- and 2-byte types are padded to 4 bytes per the spec;
    /// that padding is honoured for `MAT3` (`MAT2` of bytes is not used by any attribute).
    private func forEachComponent(
        view viewIndex: Int, byteOffset: Int, count: Int, components: Int, type: ComponentType,
        accessorIndex: Int, packed: Bool = false, _ body: (Int, Int, Raw) -> Void
    ) throws {
        guard count > 0 else { return }
        guard let view = document.root.bufferViews?[safe: viewIndex],
              let buffer = document.buffers[safe: view.buffer] else {
            throw ModelLoadingError.malformed(reason: "accessor \(accessorIndex) points at a missing bufferView")
        }
        let size = type.size
        // MAT3 columns of 1- and 2-byte components are padded to 4 bytes (spec 3.6.2.4).
        let isMatrix = components == 9 && size < 4
        let rows = isMatrix ? 3 : components
        let columnStride = isMatrix ? ((3 * size + 3) / 4) * 4 : components * size
        let elementSize = isMatrix ? columnStride * 3 : components * size
        let stride = packed ? elementSize : (view.byteStride ?? elementSize)

        let viewStart = view.byteOffset ?? 0
        let first = viewStart + byteOffset
        let last = first + stride * (count - 1) + elementSize
        guard byteOffset >= 0, stride > 0, last <= viewStart + view.byteLength, last <= buffer.count else {
            throw ModelLoadingError.malformed(reason: "accessor \(accessorIndex) overruns its bufferView")
        }

        buffer.withUnsafeBytes { raw in
            for element in 0..<count {
                let base = first + element * stride
                for component in 0..<components {
                    let offset: Int
                    if isMatrix {
                        offset = base + (component / rows) * columnStride + (component % rows) * size
                    } else {
                        offset = base + component * size
                    }
                    let value: Raw
                    switch type {
                    case .byte: value = .int(Int32(raw.loadUnaligned(fromByteOffset: offset, as: Int8.self)))
                    case .unsignedByte: value = .uint(UInt32(raw.loadUnaligned(fromByteOffset: offset, as: UInt8.self)))
                    case .short: value = .int(Int32(Int16(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: Int16.self))))
                    case .unsignedShort: value = .uint(UInt32(UInt16(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt16.self))))
                    case .unsignedInt: value = .uint(UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self)))
                    case .float:
                        value = .float(Float(bitPattern: UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self))))
                    }
                    body(element, component, value)
                }
            }
        }
    }
}
