#if os(iOS)
import CoreImage
import CoreVideo
import Metal
import QuartzCore
import RealityKit
import UIKit

/// Captures what an `ARView` renders — the view behind a ``SceneView``'s `RealityView`, or an
/// ``ARSceneView`` with its camera feed — never the SwiftUI views drawn over it.
///
/// Photos come from `ARView.snapshot`. Video frames come from a tap on
/// `renderCallbacks.postProcess`, installed only while recording: it runs the callback that was
/// there before (the exposure filter of ``ARSceneView/cameraExposure(_:)``), or copies the frame
/// through when there was none, then scales the finished frame into the encoder's pixel buffer
/// on the same command buffer. Stopping puts the previous callback back.
@MainActor
final class ARViewCaptureSource: SceneCaptureSource {
    private(set) weak var arView: ARView?
    private let tap = FrameTap()
    private var installed = false
    private var inner: ((ARView.PostProcessContext) -> Void)?

    init(arView: ARView) {
        self.arView = arView
    }

    var pixelSize: CGSize {
        guard let arView else { return .zero }
        // The render surface's drawable is the frame size RealityKit renders at.
        for surface in arView.subviews {
            if let layer = surface.layer as? CAMetalLayer, layer.drawableSize.width > 0 {
                return layer.drawableSize
            }
        }
        let scale = arView.window?.screen.scale ?? arView.traitCollection.displayScale
        return CGSize(width: arView.bounds.width * scale, height: arView.bounds.height * scale)
    }

    func snapshot() async -> UIImage? {
        guard let arView, arView.bounds.width > 0, arView.bounds.height > 0 else { return nil }
        return await SceneSnapshot.capture(from: arView)
    }

    func startStreaming(into sink: any VideoFrameSink) {
        tap.sink = sink
        guard !installed, let arView else { return }
        inner = arView.renderCallbacks.postProcess
        arView.renderCallbacks.postProcess = makeWrapper()
        installed = true
    }

    func stopStreaming() {
        tap.sink = nil
        guard installed else { return }
        installed = false
        arView?.renderCallbacks.postProcess = inner
        inner = nil
    }

    /// The owner of the view has just replaced the post-process callback (``ARSceneView``'s
    /// exposure filter): run the new one inside the tap instead of losing the tap.
    func postProcessReplaced() {
        guard installed, let arView else { return }
        inner = arView.renderCallbacks.postProcess
        arView.renderCallbacks.postProcess = makeWrapper()
    }

    private func makeWrapper() -> (ARView.PostProcessContext) -> Void {
        let inner = self.inner
        let tap = self.tap
        return { context in
            if let inner {
                inner(context)
            } else {
                ARSceneView.passThrough(context)
            }
            tap.capture(context)
        }
    }
}

/// The render-thread half of ``ARViewCaptureSource``: scales each finished frame into a
/// pixel buffer from the encoder and hands it over once the GPU is done with it.
final class FrameTap: @unchecked Sendable {
    private let lock = NSLock()
    private var currentSink: (any VideoFrameSink)?
    // Render thread only.
    private var textureCache: CVMetalTextureCache?
    private var ciContext: CIContext?
    private var device: MTLDevice?

    var sink: (any VideoFrameSink)? {
        get { lock.lock(); defer { lock.unlock() }; return currentSink }
        set { lock.lock(); currentSink = newValue; lock.unlock() }
    }

    func capture(_ context: ARView.PostProcessContext) {
        // The target holds the frame with every effect applied; read it when Metal allows,
        // otherwise read the source (the frame before the post-process).
        let target = context.targetColorTexture
        let source = !target.isFramebufferOnly && target.usage.contains(.shaderRead)
            ? target : context.sourceColorTexture
        capture(source, device: context.device, commandBuffer: context.commandBuffer)
    }

    /// Scales `source` into the next encoder buffer on `commandBuffer` and appends that buffer
    /// once the command buffer completes. Split from the `ARView` entry point for tests.
    func capture(_ source: MTLTexture, device: MTLDevice, commandBuffer: MTLCommandBuffer) {
        guard let sink else { return }
        let time = CACurrentMediaTime()
        guard let buffer = sink.reserveFrame(at: time) else { return }
        guard let texture = encodeCopy(source, device: device, commandBuffer: commandBuffer, into: buffer) else {
            sink.discardFrame()
            return
        }
        commandBuffer.addCompletedHandler { _ in
            // Holding the CVMetalTexture until the GPU is done keeps the buffer's backing alive.
            withExtendedLifetime(texture) { sink.append(buffer, at: time) }
        }
    }

    /// Encodes the copy of `source` into `buffer`; returns the Metal wrapper of the buffer to
    /// keep alive until the command buffer completes.
    private func encodeCopy(
        _ source: MTLTexture, device: MTLDevice, commandBuffer: MTLCommandBuffer, into buffer: CVPixelBuffer
    ) -> CVMetalTexture? {
        if self.device !== device {
            self.device = device
            textureCache = nil
            CVMetalTextureCacheCreate(nil, nil, device, nil, &textureCache)
            ciContext = CIContext(mtlDevice: device, options: [.cacheIntermediates: false])
        }
        guard let textureCache, let ciContext else { return nil }

        let width = CVPixelBufferGetWidth(buffer), height = CVPixelBufferGetHeight(buffer)
        var cvTexture: CVMetalTexture?
        guard CVMetalTextureCacheCreateTextureFromImage(
            nil, textureCache, buffer, nil, .bgra8Unorm,
            width, height, 0, &cvTexture
        ) == kCVReturnSuccess, let cvTexture, let destination = CVMetalTextureGetTexture(cvTexture) else { return nil }

        // Core Image cannot render into an sRGB-format texture, so the destination is plain
        // BGRA and Core Image does the gamma encoding: an sRGB or float source reads as linear
        // values, an 8-bit unorm one holds already-encoded sRGB.
        let sourceSpace = Self.readsLinear(source.pixelFormat)
            ? CGColorSpace(name: CGColorSpace.extendedLinearSRGB)
            : CGColorSpace(name: CGColorSpace.sRGB)
        guard let sourceSpace,
              let image = CIImage(mtlTexture: source, options: [.colorSpace: sourceSpace]) else { return nil }
        let scaled = image.transformed(by: CGAffineTransform(
            scaleX: CGFloat(width) / image.extent.width,
            y: CGFloat(height) / image.extent.height
        ))
        ciContext.render(
            scaled,
            to: destination,
            commandBuffer: commandBuffer,
            bounds: CGRect(x: 0, y: 0, width: width, height: height),
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        )
        return cvTexture
    }

    /// Whether sampling a texture of this format gives linear values (sRGB formats are decoded
    /// by the sampler, float formats hold linear light) rather than gamma-encoded sRGB.
    static func readsLinear(_ format: MTLPixelFormat) -> Bool {
        switch format {
        case .bgra8Unorm_srgb, .rgba8Unorm_srgb, .rgba16Float, .rgba32Float, .bgr10_xr_srgb, .bgra10_xr_srgb:
            true
        default:
            false
        }
    }
}
#endif
