import CryptoKit
import Foundation
@testable import SceneViewDepthML

/// A 4×3 Core ML "depth model" written byte by byte, so the tests need neither
/// the network nor coremltools: one linear layer, `depth = 2·pixel + 1`, on a
/// grayscale image input, with a `[1, 3, 4]` float32 multi-array output — the
/// interface `DepthAnythingV2Estimator` reads from Depth Anything V2.
enum TinyDepthModel {
    static let width = 4
    static let height = 3
    static let alpha: Float = 2
    static let beta: Float = 1

    /// `Manifest.json` and `Data/com.apple.CoreML/model.mlmodel` of the
    /// `.mlpackage`, keyed by their path inside the package.
    static func packageFiles(imageInput: Bool = true) -> [String: Data] {
        let manifest = """
        {
          "fileFormatVersion": "1.0.0",
          "itemInfoEntries": {
            "6A8D3E0E-0B5D-4C3A-9E58-2B0C7E0E7A11": {
              "author": "com.apple.CoreML",
              "description": "CoreML Model Specification",
              "name": "model.mlmodel",
              "path": "com.apple.CoreML/model.mlmodel"
            }
          },
          "rootModelIdentifier": "6A8D3E0E-0B5D-4C3A-9E58-2B0C7E0E7A11"
        }
        """
        return [
            "Manifest.json": Data(manifest.utf8),
            "Data/com.apple.CoreML/model.mlmodel": specification(imageInput: imageInput)
        ]
    }

    /// Writes the package to `directory/<name>.mlpackage`.
    static func writePackage(named name: String, in directory: URL, imageInput: Bool = true) throws -> URL {
        let package = directory.appendingPathComponent("\(name).mlpackage", isDirectory: true)
        for (path, data) in packageFiles(imageInput: imageInput) {
            let url = package.appendingPathComponent(path)
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(),
                                                    withIntermediateDirectories: true)
            try data.write(to: url)
        }
        return package
    }

    /// A pinned model whose files the stub URL protocol serves from
    /// `https://models.test/<name>/`.
    static func remote(name: String, files: [String: Data], corrupt: String? = nil) -> PinnedRemoteModel {
        PinnedRemoteModel(
            name: name,
            revision: "0123456789abcdef0123456789abcdef01234567",
            packageURL: URL(string: "https://models.test/\(name)/")!,
            files: files.keys.sorted().map { path in
                let data = files[path]!
                let hash = path == corrupt
                    ? String(repeating: "0", count: 64)
                    : SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                return PinnedModelFile(path: path, sha256: hash, size: Int64(data.count))
            },
            attribution: "test")
    }

    // MARK: - Protobuf (CoreML.Specification.Model)

    private static func specification(imageInput: Bool) -> Data {
        let inputType: Data = imageInput
            // FeatureType.imageType = 4 { width = 1, height = 2, colorSpace = 3 (GRAYSCALE = 10) }
            ? message(4, varintField(1, UInt64(width)) + varintField(2, UInt64(height)) + varintField(3, 10))
            // FeatureType.multiArrayType = 5 { shape = 1, dataType = 2 (FLOAT32) }
            : message(5, packed(1, [1, UInt64(height), UInt64(width)]) + varintField(2, 65568))
        let input = message(1, string(1, "image") + message(3, inputType))
        // FeatureType.multiArrayType = 5 { shape = [1, H, W], dataType = FLOAT32 }
        let outputType = message(5, packed(1, [1, UInt64(height), UInt64(width)]) + varintField(2, 65568))
        let output = message(10, string(1, "depth") + message(3, outputType))
        let description = message(2, input + output)
        // NeuralNetworkLayer { name = 1, input = 2, output = 3, activation = 130 { linear = 5 { alpha = 1, beta = 2 } } }
        let linear = message(5, floatField(1, alpha) + floatField(2, beta))
        let layer = message(1, string(1, "scale") + string(2, "image") + string(3, "depth") + message(130, linear))
        let network = message(500, layer)
        return varintField(1, 4) + description + network
    }

    private static func varint(_ value: UInt64) -> Data {
        var v = value
        var out = Data()
        repeat {
            var byte = UInt8(v & 0x7F)
            v >>= 7
            if v != 0 { byte |= 0x80 }
            out.append(byte)
        } while v != 0
        return out
    }

    private static func key(_ field: UInt64, _ wireType: UInt64) -> Data { varint(field << 3 | wireType) }
    private static func varintField(_ field: UInt64, _ value: UInt64) -> Data { key(field, 0) + varint(value) }
    private static func message(_ field: UInt64, _ body: Data) -> Data { key(field, 2) + varint(UInt64(body.count)) + body }
    private static func string(_ field: UInt64, _ value: String) -> Data { message(field, Data(value.utf8)) }
    private static func packed(_ field: UInt64, _ values: [UInt64]) -> Data {
        message(field, values.reduce(into: Data()) { $0 += varint($1) })
    }

    private static func floatField(_ field: UInt64, _ value: Float) -> Data {
        var bits = value.bitPattern.littleEndian
        return key(field, 5) + Data(bytes: &bits, count: 4)
    }
}
