import Foundation
import Compression

/// A read-only ZIP reader, just large enough for a 3MF container.
///
/// SceneViewSwift takes no third-party dependencies, and Foundation has no public ZIP
/// API — `NSFileCoordinator`'s archive support is macOS-only and writes to disk. So this
/// reads the central directory itself and inflates entries with the `Compression`
/// framework, which is a few hundred lines and keeps the package dependency-free.
///
/// Scope is deliberately narrow: the stored (0) and deflate (8) methods, which is
/// everything a 3MF writer emits. It never writes files, and it resolves entries by the
/// exact name recorded in the archive — there is no path joining, so a `../` in an entry
/// name is a name that matches nothing rather than a traversal.
struct ZipArchiveReader {

    /// One entry in the central directory.
    struct Entry {
        /// The name as recorded in the archive, e.g. `3D/3dmodel.model`.
        let name: String
        /// 0 = stored, 8 = deflate.
        let compressionMethod: UInt16
        let compressedSize: Int
        let uncompressedSize: Int
        /// Byte offset of this entry's local file header.
        let localHeaderOffset: Int
    }

    private let data: Data
    /// Entries by name, in central-directory order.
    let entries: [Entry]

    /// Refuses to inflate an entry larger than this. A 3MF's XML is text: 256 MB of it
    /// is already far past anything a printer or a phone will do something useful with,
    /// and the cap is what stops a deliberately crafted archive from claiming a
    /// multi-gigabyte uncompressed size and having the allocation attempted.
    static let maximumEntrySize = 256 * 1024 * 1024

    /// Parses the archive's central directory.
    ///
    /// - Throws: ``ModelLoadingError/malformed(reason:)`` when the file is not a
    ///   readable ZIP.
    init(data: Data) throws {
        self.data = data
        self.entries = try Self.readCentralDirectory(data)
    }

    /// The entry with this exact name, or `nil`.
    func entry(named name: String) -> Entry? {
        entries.first { $0.name == name }
    }

    /// The decompressed content of an entry.
    ///
    /// - Throws: ``ModelLoadingError/malformed(reason:)`` for a truncated entry, an
    ///   unsupported compression method, or a failed inflate.
    func contents(of entry: Entry) throws -> Data {
        guard entry.uncompressedSize <= Self.maximumEntrySize else {
            throw ModelLoadingError.malformed(
                reason: "archive entry \(entry.name) is \(entry.uncompressedSize) bytes, over the limit"
            )
        }
        // The local header repeats the name and extra fields, and its lengths are the
        // ones that count — a writer may pad the local extra field differently from the
        // central one, so the payload offset cannot be derived from the central entry.
        let headerStart = entry.localHeaderOffset
        guard headerStart >= 0, headerStart + 30 <= data.count,
              readUInt32(at: headerStart) == 0x0403_4B50 else {
            throw ModelLoadingError.malformed(reason: "bad local header for \(entry.name)")
        }
        let nameLength = Int(readUInt16(at: headerStart + 26))
        let extraLength = Int(readUInt16(at: headerStart + 28))
        let payloadStart = headerStart + 30 + nameLength + extraLength
        guard payloadStart + entry.compressedSize <= data.count else {
            throw ModelLoadingError.malformed(reason: "truncated entry \(entry.name)")
        }
        let payload = data.subdata(in: payloadStart..<(payloadStart + entry.compressedSize))

        switch entry.compressionMethod {
        case 0:
            return payload
        case 8:
            return try inflate(payload, uncompressedSize: entry.uncompressedSize, name: entry.name)
        default:
            throw ModelLoadingError.malformed(
                reason: "entry \(entry.name) uses unsupported compression method \(entry.compressionMethod)"
            )
        }
    }

    /// The decompressed content of the entry with this name, or `nil` when absent.
    func contents(named name: String) throws -> Data? {
        guard let entry = entry(named: name) else { return nil }
        return try contents(of: entry)
    }

    // MARK: - Inflate

    /// Raw DEFLATE, via the `Compression` framework.
    ///
    /// `COMPRESSION_ZLIB` is Apple's name for *raw* DEFLATE — no zlib header, no
    /// checksum — which is exactly what a ZIP entry stores. Reaching for a
    /// header-expecting decoder here is the classic way to get an empty result with no
    /// error.
    private func inflate(_ payload: Data, uncompressedSize: Int, name: String) throws -> Data {
        guard uncompressedSize > 0 else { return Data() }
        var destination = Data(count: uncompressedSize)
        let written: Int = destination.withUnsafeMutableBytes { destinationBuffer in
            payload.withUnsafeBytes { sourceBuffer -> Int in
                guard let destinationBase = destinationBuffer.bindMemory(to: UInt8.self).baseAddress,
                      let sourceBase = sourceBuffer.bindMemory(to: UInt8.self).baseAddress
                else { return 0 }
                return compression_decode_buffer(
                    destinationBase, uncompressedSize,
                    sourceBase, payload.count,
                    nil, COMPRESSION_ZLIB
                )
            }
        }
        guard written == uncompressedSize else {
            throw ModelLoadingError.malformed(
                reason: "entry \(name) inflated to \(written) of \(uncompressedSize) bytes"
            )
        }
        return destination
    }

    // MARK: - Central directory

    private static func readCentralDirectory(_ data: Data) throws -> [Entry] {
        guard data.count >= 22 else {
            throw ModelLoadingError.malformed(reason: "file is too small to be a zip archive")
        }
        guard let eocd = findEndOfCentralDirectory(data) else {
            throw ModelLoadingError.malformed(reason: "no zip end-of-central-directory record")
        }

        let entryCount = Int(readUInt16(data, at: eocd + 10))
        let directoryOffset = Int(readUInt32(data, at: eocd + 16))
        // ZIP64 announces itself by saturating these 16- and 32-bit fields. Rather than
        // read a nonsense offset and fail confusingly further down, say so here. A 3MF
        // needs ZIP64 only past 4 GB or 65 535 parts, neither of which a printable model
        // reaches.
        guard entryCount != 0xFFFF, directoryOffset != 0xFFFF_FFFF else {
            throw ModelLoadingError.malformed(reason: "ZIP64 archives are not supported")
        }
        guard directoryOffset >= 0, directoryOffset < data.count else {
            throw ModelLoadingError.malformed(reason: "central directory offset out of range")
        }

        var entries: [Entry] = []
        entries.reserveCapacity(entryCount)
        var cursor = directoryOffset
        for _ in 0..<entryCount {
            guard cursor + 46 <= data.count, readUInt32(data, at: cursor) == 0x0201_4B50 else {
                throw ModelLoadingError.malformed(reason: "bad central directory entry")
            }
            let method = readUInt16(data, at: cursor + 10)
            let compressedSize = Int(readUInt32(data, at: cursor + 20))
            let uncompressedSize = Int(readUInt32(data, at: cursor + 24))
            let nameLength = Int(readUInt16(data, at: cursor + 28))
            let extraLength = Int(readUInt16(data, at: cursor + 30))
            let commentLength = Int(readUInt16(data, at: cursor + 32))
            let localOffset = Int(readUInt32(data, at: cursor + 42))
            guard cursor + 46 + nameLength <= data.count else {
                throw ModelLoadingError.malformed(reason: "central directory entry name out of range")
            }
            let nameBytes = data.subdata(in: (cursor + 46)..<(cursor + 46 + nameLength))
            // ZIP names are CP437 unless the UTF-8 flag is set; every 3MF writer uses
            // ASCII part names, so a name that is not valid UTF-8 is one we could not
            // have matched anyway — skip it rather than fail the whole archive.
            if let name = String(data: nameBytes, encoding: .utf8) {
                entries.append(Entry(
                    name: name,
                    compressionMethod: method,
                    compressedSize: compressedSize,
                    uncompressedSize: uncompressedSize,
                    localHeaderOffset: localOffset
                ))
            }
            cursor += 46 + nameLength + extraLength + commentLength
        }
        return entries
    }

    /// Scans backwards for the end-of-central-directory signature.
    ///
    /// Backwards because the record sits at the very end of the file *unless* the
    /// archive carries a comment, which can be up to 65 535 bytes long — so its position
    /// cannot be computed, only searched for.
    private static func findEndOfCentralDirectory(_ data: Data) -> Int? {
        let maximumCommentLength = 0xFFFF
        let lowerBound = Swift.max(0, data.count - maximumCommentLength - 22)
        var offset = data.count - 22
        while offset >= lowerBound {
            if readUInt32(data, at: offset) == 0x0605_4B50 { return offset }
            offset -= 1
        }
        return nil
    }

    // MARK: - Little-endian reads

    private func readUInt16(at offset: Int) -> UInt16 { Self.readUInt16(data, at: offset) }
    private func readUInt32(at offset: Int) -> UInt32 { Self.readUInt32(data, at: offset) }

    private static func readUInt16(_ data: Data, at offset: Int) -> UInt16 {
        guard offset >= 0, offset + 2 <= data.count else { return 0 }
        return UInt16(data[data.startIndex + offset])
            | UInt16(data[data.startIndex + offset + 1]) << 8
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        guard offset >= 0, offset + 4 <= data.count else { return 0 }
        return UInt32(data[data.startIndex + offset])
            | UInt32(data[data.startIndex + offset + 1]) << 8
            | UInt32(data[data.startIndex + offset + 2]) << 16
            | UInt32(data[data.startIndex + offset + 3]) << 24
    }
}
