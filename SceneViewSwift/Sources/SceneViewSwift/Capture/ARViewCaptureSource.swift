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
///
/// The frame is read from the post-process target when Metal allows it, so effects of the
/// previous callback are in the video. When RealityKit hands over a write-only target, the
/// tap reads the frame from before the post-process instead: the video then misses the
/// ``ARSceneView/cameraExposure(_:)`` adjustment, the rest of the frame being the same.
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
        tap.flush()
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

    /// Whether the tap runs another owner's callback inside it, for tests.
    var wrapsAnotherCallback: Bool { inner != nil }

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
    // Render thread only, except `textureCache`, also flushed from the main thread under `lock`.
    private var textureCache: CVMetalTextureCache?
    private var ciContext: CIContext?
    private var device: MTLDevice?

    var sink: (any VideoFrameSink)? {
        get { lock.lock(); defer { lock.unlock() }; return currentSink }
        set { lock.lock(); currentSink = newValue; lock.unlock() }
    }

    /// Lets the texture cache release the encoder buffers it wraps once a recording stops.
    func flush() {
        lock.lock()
        defer { lock.unlock() }
        if let textureCache { CVMetalTextureCacheFlush(textureCache, 0) }
    }

    func capture(_ context: ARView.PostProcessContext) {
        // The target holds the frame with every effect applied; read it when Metal allows,
        // otherwise read the source (the frame before the post-process, see the type's doc).
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
            var cache: CVMetalTextureCache?
            CVMetalTextureCacheCreate(nil, nil, device, nil, &cache)
            lock.lock()
            textureCache = cache
            lock.unlock()
            ciContext = CIContext(mtlDevice: device, options: [.cacheIntermediates: false])
        }
        lock.lock()
        let textureCache = self.textureCache
        lock.unlock()
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
        // A view turned mid-recording (portrait to landscape) is letterboxed in the size
        // fixed at the start instead of stretched; the even-edge rounding is filled.
        let bounds = CGRect(x: 0, y: 0, width: width, height: height)
        let fitted = image.transformed(by: Self.aspectFit(image.extent.size, in: bounds.size))
            .composited(over: CIImage(color: .black).cropped(to: bounds))
        ciContext.render(
            fitted,
            to: destination,
            commandBuffer: commandBuffer,
            bounds: bounds,
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
        )
        return cvTexture
    }

    /// Fits `size` inside `box`. Aspect ratios less than 1 % apart (the video size rounded
    /// to even edges, 1179 x 2556 into 884 x 1920) fill the box, so no thin dark bar runs
    /// along an edge; a real aspect change (a view turned mid-recording) is scaled by one
    /// factor, centred, letterboxed.
    static func aspectFit(_ size: CGSize, in box: CGSize) -> CGAffineTransform {
        guard size.width > 0, size.height > 0, box.width > 0, box.height > 0 else { return .identity }
        let scaleX = box.width / size.width, scaleY = box.height / size.height
        if abs(scaleX / scaleY - 1) < 0.01 { return CGAffineTransform(scaleX: scaleX, y: scaleY) }
        let scale = min(scaleX, scaleY)
        return CGAffineTransform(
            translationX: (box.width - size.width * scale) / 2,
            y: (box.height - size.height * scale) / 2
        ).scaledBy(x: scale, y: scale)
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
