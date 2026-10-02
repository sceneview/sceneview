import CoreML
import CoreVideo
import Foundation
import SceneViewSwift

/// Depth Anything V2 **Small** on Core ML — the default
/// ``SceneViewSwift/MonocularDepthEstimator`` for ``SceneViewSwift/DepthSource``.
///
/// Runs Apple's Core ML build (`apple/coreml-depth-anything-v2-small`,
/// Apache-2.0, 518×392 input; 8-bit weights by default, 25.4 MB) with
/// `.cpuAndNeuralEngine`. The model
/// returns relative inverse depth; the SDK scales it to metres against
/// ARKit's anchors on every frame.
///
/// The package bundles **no model**: get a compiled model from
/// ``DepthModelStore`` (pinned download with SHA-256 checks, an app bundle
/// resource, or an On-Demand Resources tag), then:
///
/// ```swift
/// let estimator = try await DepthModelStore.shared.estimator(from: .pinnedDownload())
/// ARSceneView(configuration: .init(planeDetection: .both))
///     .depthSource(.auto(ml: estimator))
///     .onDepthFrame { frame in /* frame?.source == .ml */ }
/// ```
///
/// Only the Small variant is supported: Base and Large are not
/// Apache-2.0 licensed.
public final class DepthAnythingV2Estimator: MonocularDepthEstimator, @unchecked Sendable {
    public let identifier: String
    public let inputSize: (width: Int, height: Int)
    public let inputPixelFormat: OSType
    public let outputKind: MonocularDepthOutputKind = .affineInverse

    private let model: MLModel
    private let inputName: String
    private let outputName: String
    private let lock = NSLock()

    /// - Parameters:
    ///   - compiledModelURL: a `.mlmodelc` directory (see ``DepthModelStore``).
    ///   - computeUnits: `.cpuAndNeuralEngine` by default — the GPU is left to
    ///     RealityKit.
    public init(compiledModelURL: URL, computeUnits: MLComputeUnits = .cpuAndNeuralEngine) throws {
        let configuration = MLModelConfiguration()
        configuration.computeUnits = computeUnits
        let model = try MLModel(contentsOf: compiledModelURL, configuration: configuration)
        let description = model.modelDescription
        // Feature names differ between conversions: read them from the model.
        guard let input = description.inputDescriptionsByName.values
            .first(where: { $0.type == .image }),
              let constraint = input.imageConstraint else {
            throw DepthModelError.unexpectedModelInterface("no image input")
        }
        guard let output = description.outputDescriptionsByName.values
            .sorted(by: { $0.name < $1.name })
            .first(where: { $0.type == .image || $0.type == .multiArray }) else {
            throw DepthModelError.unexpectedModelInterface("no image or multi-array output")
        }
        self.model = model
        self.inputName = input.name
        self.outputName = output.name
        self.inputSize = (constraint.pixelsWide, constraint.pixelsHigh)
        self.inputPixelFormat = constraint.pixelFormatType == 0
            ? kCVPixelFormatType_32BGRA : constraint.pixelFormatType
        // The compiled folder's name tells the F16 and 8-bit builds apart, so
        // each keeps its own first-launch benchmark.
        let variant = compiledModelURL.deletingPathExtension().lastPathComponent
            .components(separatedBy: "@").first ?? "model"
        self.identifier = "depth-anything-v2-small:\(variant)@\(constraint.pixelsWide)x\(constraint.pixelsHigh)"
    }

    /// The first prediction specialises the model for the Neural Engine
    /// (1–5 s the first time, cached by the system afterwards).
    public func warmUp() throws {
        var buffer: CVPixelBuffer?
        CVPixelBufferCreate(nil, inputSize.width, inputSize.height, inputPixelFormat,
                            [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary, &buffer)
        guard let buffer else { throw DepthModelError.unexpectedModelInterface("cannot allocate input") }
        _ = try estimate(buffer)
    }

    public func estimate(_ pixelBuffer: CVPixelBuffer) throws -> MonocularDepthEstimate {
        lock.lock()
        defer { lock.unlock() }
        let input = try MLDictionaryFeatureProvider(dictionary: [inputName: MLFeatureValue(pixelBuffer: pixelBuffer)])
        let result = try model.prediction(from: input)
        guard let value = result.featureValue(for: outputName) else {
            throw DepthModelError.unexpectedModelInterface("missing output \(outputName)")
        }
        if let image = value.imageBufferValue { return try Self.read(image) }
        if let array = value.multiArrayValue { return try Self.read(array) }
        throw DepthModelError.unexpectedModelInterface("unsupported output type")
    }

    static func read(_ buffer: CVPixelBuffer) throws -> MonocularDepthEstimate {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
        let rowBytes = CVPixelBufferGetBytesPerRow(buffer)
        guard let base = CVPixelBufferGetBaseAddress(buffer) else {
            throw DepthModelError.unexpectedModelInterface("unreadable output")
        }
        var values = [Float](repeating: 0, count: w * h)
        switch CVPixelBufferGetPixelFormatType(buffer) {
        #if arch(arm64)
        case kCVPixelFormatType_OneComponent16Half:
            for y in 0..<h {
                let row = (base + y * rowBytes).assumingMemoryBound(to: Float16.self)
                for x in 0..<w { values[y * w + x] = Float(row[x]) }
            }
        #endif
        case kCVPixelFormatType_OneComponent32Float, kCVPixelFormatType_DepthFloat32:
            for y in 0..<h {
                let row = (base + y * rowBytes).assumingMemoryBound(to: Float32.self)
                for x in 0..<w { values[y * w + x] = row[x] }
            }
        case kCVPixelFormatType_OneComponent8:
            for y in 0..<h {
                let row = (base + y * rowBytes).assumingMemoryBound(to: UInt8.self)
                for x in 0..<w { values[y * w + x] = Float(row[x]) }
            }
        default:
            throw DepthModelError.unexpectedModelInterface("output pixel format")
        }
        return MonocularDepthEstimate(width: w, height: h, values: values)
    }

    static func read(_ array: MLMultiArray) throws -> MonocularDepthEstimate {
        // [1, H, W], [1, 1, H, W] or [H, W]: the last two dimensions are the map.
        let shape = array.shape.map(\.intValue)
        guard shape.count >= 2 else { throw DepthModelError.unexpectedModelInterface("output shape \(shape)") }
        let h = shape[shape.count - 2], w = shape[shape.count - 1]
        let strides = array.strides.map(\.intValue)
        let sy = strides[strides.count - 2], sx = strides[strides.count - 1]
        var values = [Float](repeating: 0, count: w * h)
        func fill<T>(_ type: T.Type, _ convert: (T) -> Float) {
            let pointer = array.dataPointer.assumingMemoryBound(to: T.self)
            for y in 0..<h { for x in 0..<w { values[y * w + x] = convert(pointer[y * sy + x * sx]) } }
        }
        switch array.dataType {
        #if arch(arm64)
        case .float16: fill(Float16.self) { Float($0) }
        #endif
        case .float32: fill(Float32.self) { $0 }
        case .double: fill(Double.self) { Float($0) }
        default: throw DepthModelError.unexpectedModelInterface("output data type")
        }
        return MonocularDepthEstimate(width: w, height: h, values: values)
    }
}

public enum DepthModelError: Error, LocalizedError, Sendable {
    case unexpectedModelInterface(String)
    case checksumMismatch(file: String)
    case download(String)
    case onDemandResourceMissing(tag: String)

    public var errorDescription: String? {
        switch self {
        case .unexpectedModelInterface(let detail): return "The depth model has an unexpected interface (\(detail))."
        case .checksumMismatch(let file): return "Checksum mismatch for \(file); the download was discarded."
        case .download(let detail): return "The depth model could not be downloaded (\(detail))."
        case .onDemandResourceMissing(let tag): return "On-Demand Resources tag \(tag) holds no model."
        }
    }
}
