import Foundation
import simd

/// A parsed glTF 2.0 file: its JSON and every buffer it references, resolved to bytes.
///
/// Reads both containers — a binary `.glb` (12-byte header, JSON chunk, optional BIN
/// chunk) and a JSON `.gltf` whose buffers and images are `data:` URIs or files next to
/// it. Pure Foundation: no RealityKit, safe on any thread, so a background task does
/// the whole parse and the main actor only creates RealityKit resources.
struct GLTFDocument {

    /// The decoded JSON.
    let root: GLTFRoot
    /// `buffers[i]` resolved to bytes.
    let buffers: [Data]
    /// The directory external URIs are resolved against, `nil` for in-memory data.
    let baseURL: URL?

    /// Required extensions the importer implements. A file that requires anything
    /// else is refused with ``ModelLoadingError/unsupportedRequiredExtension(_:)``
    /// rather than rendered wrong or empty — `KHR_draco_mesh_compression` being the one
    /// people will actually meet.
    static let supportedRequiredExtensions: Set<String> = [
        "KHR_materials_unlit",
        "KHR_materials_emissive_strength",
        "KHR_texture_transform",
        // Allows non-float attribute component types — the accessor reader handles
        // every component type, normalized or not.
        "KHR_mesh_quantization",
        // ImageIO decodes WebP on every supported OS.
        "EXT_texture_webp",
    ]

    // MARK: - Reading

    /// Reads a `.glb` or `.gltf` file. External buffers and images resolve relative to
    /// the file's directory.
    static func read(contentsOf url: URL) throws -> GLTFDocument {
        let data: Data
        do {
            data = try Data(contentsOf: url, options: .mappedIfSafe)
        } catch {
            throw ModelLoadingError.unreadableFile(url)
        }
        return try read(data: data, baseURL: url.deletingLastPathComponent())
    }

    /// Reads glTF from memory — GLB bytes or JSON text, told apart by the GLB magic.
    static func read(data: Data, baseURL: URL?) throws -> GLTFDocument {
        var jsonData = data
        var binChunk: Data?

        if data.count >= 4, data.prefix(4) == GLTFDocument.glbMagic {
            (jsonData, binChunk) = try splitGLB(data)
        }

        let root: GLTFRoot
        do {
            root = try JSONDecoder().decode(GLTFRoot.self, from: jsonData)
        } catch {
            throw ModelLoadingError.malformed(reason: "glTF JSON could not be decoded (\(error.localizedDescription))")
        }

        guard root.asset.version.hasPrefix("2") else {
            throw ModelLoadingError.malformed(
                reason: "glTF \(root.asset.version) is not supported — only glTF 2.0 is"
            )
        }
        for name in root.extensionsRequired ?? [] where !supportedRequiredExtensions.contains(name) {
            throw ModelLoadingError.unsupportedRequiredExtension(name)
        }

        var buffers: [Data] = []
        for (index, buffer) in (root.buffers ?? []).enumerated() {
            let bytes: Data
            if let uri = buffer.uri {
                bytes = try resolve(uri: uri, baseURL: baseURL, what: "buffer \(index)")
            } else if index == 0, let binChunk {
                bytes = binChunk
            } else {
                throw ModelLoadingError.malformed(reason: "buffer \(index) has no uri and no GLB BIN chunk")
            }
            guard bytes.count >= buffer.byteLength else {
                throw ModelLoadingError.malformed(
                    reason: "buffer \(index) holds \(bytes.count) bytes, declares \(buffer.byteLength)"
                )
            }
            buffers.append(bytes)
        }
        return GLTFDocument(root: root, buffers: buffers, baseURL: baseURL)
    }

    static let glbMagic = Data("glTF".utf8)

    /// Splits a GLB into its JSON and BIN chunks.
    private static func splitGLB(_ data: Data) throws -> (json: Data, bin: Data?) {
        let bytes = [UInt8](data.prefix(12))
        guard bytes.count == 12 else { throw ModelLoadingError.malformed(reason: "GLB header truncated") }
        let version = readUInt32(data, at: 4)
        guard version == 2 else {
            throw ModelLoadingError.malformed(reason: "GLB version \(version) is not supported — only glTF 2.0 is")
        }
        let total = min(Int(readUInt32(data, at: 8)), data.count)

        var offset = 12
        var json: Data?
        var bin: Data?
        while offset + 8 <= total {
            let length = Int(readUInt32(data, at: offset))
            let type = readUInt32(data, at: offset + 4)
            let start = offset + 8
            guard length >= 0, start + length <= total else {
                throw ModelLoadingError.malformed(reason: "GLB chunk overruns the file")
            }
            let chunk = data.subdata(in: (data.startIndex + start)..<(data.startIndex + start + length))
            switch type {
            case 0x4E4F_534A: if json == nil { json = chunk }   // "JSON"
            case 0x004E_4942: if bin == nil { bin = chunk }     // "BIN\0"
            default: break                                      // unknown chunks are skipped
            }
            // Chunks are 4-byte aligned; a writer that forgot the padding still parses.
            offset = start + length
        }
        guard let json else { throw ModelLoadingError.malformed(reason: "GLB has no JSON chunk") }
        return (json, bin)
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32 {
        data.withUnsafeBytes { raw in
            UInt32(littleEndian: raw.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
        }
    }

    /// Resolves a buffer or image URI: `data:` inline, or a relative path that must
    /// stay inside the file's own directory.
    ///
    /// Absolute paths, other schemes and `..` escapes are refused: a `.gltf` downloaded
    /// from the network is untrusted, and letting it name arbitrary files in the app's
    /// sandbox would turn a model load into a local-file read.
    static func resolve(uri: String, baseURL: URL?, what: String) throws -> Data {
        if uri.hasPrefix("data:") {
            guard let comma = uri.firstIndex(of: ",") else {
                throw ModelLoadingError.malformed(reason: "\(what): malformed data URI")
            }
            let header = uri[uri.startIndex..<comma]
            let payload = String(uri[uri.index(after: comma)...])
            if header.hasSuffix(";base64") {
                guard let data = Data(base64Encoded: payload, options: .ignoreUnknownCharacters) else {
                    throw ModelLoadingError.malformed(reason: "\(what): data URI is not valid base64")
                }
                return data
            }
            return Data((payload.removingPercentEncoding ?? payload).utf8)
        }

        let decoded = uri.removingPercentEncoding ?? uri
        guard let baseURL else {
            throw ModelLoadingError.malformed(reason: "\(what) references \"\(decoded)\" but the glTF was not read from a file")
        }
        guard !decoded.contains("://"), !decoded.hasPrefix("/") else {
            throw ModelLoadingError.malformed(reason: "\(what): only relative or data: URIs are supported, got \"\(decoded)\"")
        }
        let directory = baseURL.standardizedFileURL
        let target = directory.appendingPathComponent(decoded).standardizedFileURL
        let directoryPath = directory.path.hasSuffix("/") ? directory.path : directory.path + "/"
        guard target.path.hasPrefix(directoryPath) else {
            throw ModelLoadingError.malformed(reason: "\(what): \"\(decoded)\" points outside the model's folder")
        }
        do {
            return try Data(contentsOf: target, options: .mappedIfSafe)
        } catch {
            throw ModelLoadingError.malformed(reason: "\(what): \"\(decoded)\" was not found next to the model")
        }
    }

    // MARK: - Buffer views and images

    /// The bytes of a buffer view.
    func bufferViewData(_ index: Int) throws -> Data {
        guard let view = root.bufferViews?[safe: index] else {
            throw ModelLoadingError.malformed(reason: "bufferView \(index) does not exist")
        }
        guard let buffer = buffers[safe: view.buffer] else {
            throw ModelLoadingError.malformed(reason: "bufferView \(index) points at missing buffer \(view.buffer)")
        }
        let start = view.byteOffset ?? 0
        guard start >= 0, view.byteLength >= 0, start + view.byteLength <= buffer.count else {
            throw ModelLoadingError.malformed(reason: "bufferView \(index) overruns its buffer")
        }
        return buffer.subdata(in: (buffer.startIndex + start)..<(buffer.startIndex + start + view.byteLength))
    }

    /// The encoded bytes (PNG, JPEG, WebP…) of an image.
    func imageData(_ index: Int) throws -> Data {
        guard let image = root.images?[safe: index] else {
            throw ModelLoadingError.malformed(reason: "image \(index) does not exist")
        }
        if let view = image.bufferView { return try bufferViewData(view) }
        if let uri = image.uri { return try GLTFDocument.resolve(uri: uri, baseURL: baseURL, what: "image \(index)") }
        throw ModelLoadingError.malformed(reason: "image \(index) has neither a uri nor a bufferView")
    }
}

extension Array {
    /// `nil` instead of a trap for an index the file made up.
    subscript(safe index: Int) -> Element? {
        index >= 0 && index < count ? self[index] : nil
    }
}
