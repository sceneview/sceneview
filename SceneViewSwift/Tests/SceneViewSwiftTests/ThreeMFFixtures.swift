import Foundation
import Compression
import simd

/// Hand-built 3MF packages for the loader tests.
///
/// A real ZIP writer, not a canned blob: the reader's job is to walk a central
/// directory and inflate deflate streams, so a fixture that skipped either would test
/// nothing. Entries can be stored or deflated per test, and the CRCs are real, so the
/// fixtures also open in any other ZIP tool if a failure ever needs inspecting by hand.
enum ThreeMFFixtures {

    // MARK: - Package assembly

    struct FileEntry {
        let name: String
        let data: Data
        let deflate: Bool

        init(_ name: String, _ text: String, deflate: Bool = true) {
            self.name = name
            self.data = Data(text.utf8)
            self.deflate = deflate
        }

        init(_ name: String, data: Data, deflate: Bool = true) {
            self.name = name
            self.data = data
            self.deflate = deflate
        }
    }

    /// The `[Content_Types].xml` every OPC package opens with. Its presence is also what
    /// makes the format sniffer call a zip a 3MF rather than a USDZ.
    static let contentTypes = FileEntry("[Content_Types].xml", """
    <?xml version="1.0" encoding="UTF-8"?>
    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
      <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
      <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
    </Types>
    """, deflate: false)

    /// `_rels/.rels` naming the root model part.
    static func relationships(target: String = "/3D/3dmodel.model") -> FileEntry {
        FileEntry("_rels/.rels", """
        <?xml version="1.0" encoding="UTF-8"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rel0" Target="\(target)"
            Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
        </Relationships>
        """)
    }

    /// Builds a `.3mf` package from entries.
    static func package(_ entries: [FileEntry]) -> Data {
        var payload = Data()
        var directory = Data()
        var count = 0

        for entry in entries {
            let nameBytes = Array(entry.name.utf8)
            let crc = crc32(entry.data)
            let stored = entry.deflate ? deflate(entry.data) : nil
            let method: UInt16 = stored == nil ? 0 : 8
            let body = stored ?? entry.data
            let localOffset = payload.count

            append32(0x0403_4B50, to: &payload)         // local file header signature
            append16(20, to: &payload)                  // version needed
            append16(0, to: &payload)                   // flags
            append16(method, to: &payload)
            append16(0, to: &payload)                   // mod time
            append16(0x21, to: &payload)                // mod date (1980-01-01)
            append32(crc, to: &payload)
            append32(UInt32(body.count), to: &payload)
            append32(UInt32(entry.data.count), to: &payload)
            append16(UInt16(nameBytes.count), to: &payload)
            append16(0, to: &payload)                   // extra length
            payload.append(contentsOf: nameBytes)
            payload.append(body)

            append32(0x0201_4B50, to: &directory)       // central directory signature
            append16(20, to: &directory)                // version made by
            append16(20, to: &directory)                // version needed
            append16(0, to: &directory)                 // flags
            append16(method, to: &directory)
            append16(0, to: &directory)
            append16(0x21, to: &directory)
            append32(crc, to: &directory)
            append32(UInt32(body.count), to: &directory)
            append32(UInt32(entry.data.count), to: &directory)
            append16(UInt16(nameBytes.count), to: &directory)
            append16(0, to: &directory)                 // extra length
            append16(0, to: &directory)                 // comment length
            append16(0, to: &directory)                 // disk number start
            append16(0, to: &directory)                 // internal attributes
            append32(0, to: &directory)                 // external attributes
            append32(UInt32(localOffset), to: &directory)
            directory.append(contentsOf: nameBytes)
            count += 1
        }

        let directoryOffset = payload.count
        var archive = payload
        archive.append(directory)
        append32(0x0605_4B50, to: &archive)             // end of central directory
        append16(0, to: &archive)                       // disk number
        append16(0, to: &archive)                       // disk with central directory
        append16(UInt16(count), to: &archive)
        append16(UInt16(count), to: &archive)
        append32(UInt32(directory.count), to: &archive)
        append32(UInt32(directoryOffset), to: &archive)
        append16(0, to: &archive)                       // comment length
        return archive
    }

    // MARK: - Model XML

    /// A `<mesh>` element for an axis-aligned box from the origin to `size`.
    static func boxMeshXML(size: SIMD3<Float>) -> String {
        let corners: [SIMD3<Float>] = [
            SIMD3(0, 0, 0), SIMD3(size.x, 0, 0), SIMD3(size.x, size.y, 0), SIMD3(0, size.y, 0),
            SIMD3(0, 0, size.z), SIMD3(size.x, 0, size.z),
            SIMD3(size.x, size.y, size.z), SIMD3(0, size.y, size.z)
        ]
        let faces = [
            (0, 3, 2), (0, 2, 1), (4, 5, 6), (4, 6, 7),
            (0, 1, 5), (0, 5, 4), (2, 3, 7), (2, 7, 6),
            (0, 4, 7), (0, 7, 3), (1, 2, 6), (1, 6, 5)
        ]
        let vertices = corners
            .map { "      <vertex x=\"\($0.x)\" y=\"\($0.y)\" z=\"\($0.z)\"/>" }
            .joined(separator: "\n")
        let triangles = faces
            .map { "      <triangle v1=\"\($0.0)\" v2=\"\($0.1)\" v3=\"\($0.2)\"/>" }
            .joined(separator: "\n")
        return """
            <mesh>
              <vertices>
        \(vertices)
              </vertices>
              <triangles>
        \(triangles)
              </triangles>
            </mesh>
        """
    }

    /// A complete root `.model` part.
    static func modelXML(
        unit: String = "millimeter",
        resources: String,
        build: String,
        extraNamespaces: String = ""
    ) -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <model unit="\(unit)" xml:lang="en-US"
          xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"\(extraNamespaces)>
          <resources>
        \(resources)
          </resources>
          <build>
        \(build)
          </build>
        </model>
        """
    }

    /// The simplest useful package: one box object, one build item.
    static func simpleBox(
        size: SIMD3<Float> = SIMD3(10, 20, 30),
        unit: String = "millimeter",
        deflate: Bool = true
    ) -> Data {
        let model = modelXML(
            unit: unit,
            resources: """
                <object id="1" type="model" name="cube">
            \(boxMeshXML(size: size))
                </object>
            """,
            build: """
                <item objectid="1"/>
            """
        )
        return package([
            contentTypes,
            relationships(),
            FileEntry("3D/3dmodel.model", model, deflate: deflate)
        ])
    }

    // MARK: - Byte helpers

    private static func append16(_ value: UInt16, to data: inout Data) {
        data.append(contentsOf: [UInt8(value & 0xFF), UInt8((value >> 8) & 0xFF)])
    }

    private static func append32(_ value: UInt32, to data: inout Data) {
        data.append(contentsOf: [
            UInt8(value & 0xFF),
            UInt8((value >> 8) & 0xFF),
            UInt8((value >> 16) & 0xFF),
            UInt8((value >> 24) & 0xFF)
        ])
    }

    /// Raw DEFLATE — `COMPRESSION_ZLIB` is Apple's name for the headerless stream a ZIP
    /// entry stores. Returns `nil` when the "compressed" form would not be smaller, in
    /// which case the caller stores the entry instead, exactly like a real writer.
    private static func deflate(_ input: Data) -> Data? {
        guard !input.isEmpty else { return nil }
        let capacity = input.count + 1024
        var output = Data(count: capacity)
        let written: Int = output.withUnsafeMutableBytes { destination in
            input.withUnsafeBytes { source -> Int in
                guard let destinationBase = destination.bindMemory(to: UInt8.self).baseAddress,
                      let sourceBase = source.bindMemory(to: UInt8.self).baseAddress
                else { return 0 }
                return compression_encode_buffer(
                    destinationBase, capacity,
                    sourceBase, input.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written > 0, written < input.count else { return nil }
        return output.prefix(written)
    }

    /// CRC-32 (IEEE), computed the slow bitwise way — a fixture builder has all the time
    /// in the world and this needs no 1 KB table in the test target.
    private static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                crc = (crc >> 1) ^ (0xEDB8_8320 & (0 &- (crc & 1)))
            }
        }
        return crc ^ 0xFFFF_FFFF
    }
}
