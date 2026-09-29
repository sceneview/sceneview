#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import Metal
import RealityKit
#if os(iOS) || os(macOS)
import MetalPerformanceShaders
#endif

/// Bloom settings for a ``SceneView`` — light bleeding out of the brightest pixels.
///
/// Mirrors the fields SceneView Android sets on Filament's `View.bloomOptions`
/// (`enabled`, `strength`, `levels`, `threshold`), so a scene is tuned with the same numbers on
/// both platforms. Bloom is what turns bright unlit or emissive colours into glow: a pixel
/// brighter than the threshold spills a soft halo onto its neighbours.
///
/// ```swift
/// SceneView { root in /* emissive / unlit content */ }
///     .bloom(BloomOptions(strength: 0.45))
/// ```
///
/// ## Availability
///
/// The pass runs on **iOS 26 / macOS 26 and later** through RealityKit's
/// `RealityViewCameraContent.renderingEffects.customPostProcessing`. RealityKit exposes no
/// post-process hook on a `RealityView` before that release, so on iOS 18–25 the modifier
/// is accepted and does nothing — the scene renders exactly as without it. There is no
/// visionOS support: RealityKit does not allow custom post-processing there.
public struct BloomOptions: Equatable, Sendable {

    /// How much of the blurred highlight is added back onto the frame. `0` turns bloom off;
    /// Android's `BloomOptions.strength` default is `0.1`, a glow-driven scene uses `0.3–0.6`.
    public var strength: Float

    /// Number of blur levels, each half the size of the previous one. More levels spread the
    /// glow wider. Clamped to `1...8`. Same meaning as Filament's `levels`.
    public var levels: Int

    /// When `true` (the default), only the part of a pixel above ``thresholdLevel`` blooms —
    /// the Filament `threshold = true` behaviour. When `false`, every pixel contributes and
    /// the whole frame softens.
    public var threshold: Bool

    /// Brightness above which a pixel starts to bloom when ``threshold`` is on, in the colour
    /// units of the frame RealityKit hands to the pass (`1.0` = white).
    public var thresholdLevel: Float

    public init(strength: Float = 0.1, levels: Int = 6, threshold: Bool = true, thresholdLevel: Float = 0.6) {
        self.strength = strength
        self.levels = levels
        self.threshold = threshold
        self.thresholdLevel = thresholdLevel
    }

    /// Bloom switched off.
    public static let disabled = BloomOptions(strength: 0)

    var isEnabled: Bool { strength > 0 }
}

// MARK: - The post-process pass

#if os(iOS) || os(macOS)

/// The bloom pass handed to `RealityViewCameraContent.renderingEffects.customPostProcessing`.
///
/// Built from Metal Performance Shaders kernels only — no shader source is compiled, so the
/// SDK needs neither the Metal Toolchain at build time nor a runtime compile: a half-size
/// bright pass (`MPSImageBilinearScale`, then `MPSImageAdd` with a bias of minus the threshold,
/// clamped at zero), a chain of `levels` half-size copies each softened by `MPSImageGaussianBlur`, summed
/// back up level by level, and added onto the frame with `MPSImageAdd`. RealityKit hands the pass
/// an sRGB frame, which MPS cannot write, so the glow is measured and added in display space.
@available(iOS 26.0, macOS 26.0, *)
struct BloomPostProcess: PostProcessEffect {
    /// Kernels, the texture chain and the live options, shared with the view.
    let resources: BloomResources

    mutating func prepare(for device: any MTLDevice) {
        resources.prepare(device: device)
    }

    mutating func postProcess(context: borrowing PostProcessEffectContext<any MTLCommandBuffer>) {
        resources.encode(
            commandBuffer: context.commandBuffer,
            source: context.sourceColorTexture,
            target: context.targetColorTexture
        )
    }
}

/// Metal state of the bloom pass. The Metal objects are only touched from RealityKit's render
/// callbacks, which are serialised; `options` is written from the main actor and read by the
/// render callback under a lock — hence the unchecked `Sendable`.
final class BloomResources: @unchecked Sendable {
    private let lock = NSLock()
    private var storedOptions = BloomOptions.disabled
    /// The options the next frame renders with.
    var options: BloomOptions {
        get { lock.withLock { storedOptions } }
        set { lock.withLock { storedOptions = newValue } }
    }
    private var device: MTLDevice?
    private var supported = false
    private var scale: MPSImageBilinearScale?
    private var blur: MPSImageGaussianBlur?
    private var brightPass: MPSImageAdd?
    private var sum: MPSImageAdd?
    private var composite: MPSImageAdd?

    /// Per level: the downsampled bright pass, its blurred copy, and the running sum.
    private var level: [MTLTexture] = []
    private var blurred: [MTLTexture] = []
    private var summed: [MTLTexture] = []
    /// Upsampling scratch per level, and the full-size glow added onto the frame.
    private var upsampled: [MTLTexture] = []
    private var full: MTLTexture?
    /// Same-format copy of an sRGB frame, so it can be read through a raw view.
    private var frameCopy: MTLTexture?
    private var chainKey: (Int, Int, Int) = (0, 0, 0)

    func prepare(device: MTLDevice) {
        guard self.device == nil else { return }
        self.device = device
        supported = MPSSupportsMTLDevice(device)
        guard supported else { return }
        scale = MPSImageBilinearScale(device: device)
        blur = MPSImageGaussianBlur(device: device, sigma: 2.5)
        blur?.edgeMode = .clamp
        brightPass = MPSImageAdd(device: device)
        sum = MPSImageAdd(device: device)
        composite = MPSImageAdd(device: device)
    }

    func encode(commandBuffer: MTLCommandBuffer, source: MTLTexture, target: MTLTexture) {
        let options = self.options
        if device == nil { prepare(device: commandBuffer.device) }
        guard options.isEnabled, supported,
              let scale, let blur, let brightPass, let sum, let composite,
              let output = Self.rawView(target),
              let frame = rawFrame(source, commandBuffer: commandBuffer)
        else {
            passThrough(commandBuffer: commandBuffer, source: source, target: target)
            return
        }
        let source = frame
        let levels = max(1, min(options.levels, 8))
        guard ensureChain(width: source.width, height: source.height, levels: levels), let full else {
            passThrough(commandBuffer: commandBuffer, source: source, target: target)
            return
        }

        // Frame → half size, then keep only what exceeds the threshold: max(c − t, 0).
        scale.encode(commandBuffer: commandBuffer, sourceTexture: source, destinationTexture: upsampled[0])
        brightPass.primaryScale = 1
        brightPass.secondaryScale = 0
        brightPass.bias = options.threshold ? -options.thresholdLevel : 0
        brightPass.minimumValue = 0
        brightPass.encode(commandBuffer: commandBuffer, primaryTexture: upsampled[0],
                          secondaryTexture: upsampled[0], destinationTexture: level[0])

        // Each level is half the previous one, blurred: the glow spreads wider at every step.
        for index in 1..<levels {
            scale.encode(commandBuffer: commandBuffer, sourceTexture: level[index - 1], destinationTexture: level[index])
        }
        for index in 0..<levels {
            blur.encode(commandBuffer: commandBuffer, sourceTexture: level[index], destinationTexture: blurred[index])
        }

        // Sum the levels back up, smallest first.
        var running = blurred[levels - 1]
        if levels > 1 {
            for index in stride(from: levels - 2, through: 0, by: -1) {
                scale.encode(commandBuffer: commandBuffer, sourceTexture: running, destinationTexture: upsampled[index])
                sum.primaryScale = 1
                sum.secondaryScale = 1
                sum.bias = 0
                sum.encode(commandBuffer: commandBuffer, primaryTexture: blurred[index],
                           secondaryTexture: upsampled[index], destinationTexture: summed[index])
                running = summed[index]
            }
        }

        // Frame + strength × glow (averaged over the levels, as Filament normalises its chain).
        scale.encode(commandBuffer: commandBuffer, sourceTexture: running, destinationTexture: full)
        composite.primaryScale = 1
        composite.secondaryScale = options.strength * 2 / Float(levels)
        composite.bias = 0
        composite.encode(commandBuffer: commandBuffer, primaryTexture: source,
                         secondaryTexture: full, destinationTexture: output)
    }

    /// MPS writes from compute kernels, and an sRGB texture is not shader-writable. So the pass
    /// reads and writes raw (non-sRGB) views of the frame: the glow is measured and added in
    /// display space — what looks bright is what blooms. `nil` when no such view can be made.
    private static func rawView(_ texture: MTLTexture) -> MTLTexture? {
        let raw: MTLPixelFormat
        switch texture.pixelFormat {
        case .bgra8Unorm_srgb: raw = .bgra8Unorm
        case .rgba8Unorm_srgb: raw = .rgba8Unorm
        case .bgra10_xr_srgb: raw = .bgra10_xr
        case .bgr10_xr_srgb: raw = .bgr10_xr
        default: return texture
        }
        guard texture.usage.contains(.pixelFormatView) else { return nil }
        return texture.makeTextureView(pixelFormat: raw)
    }

    /// A raw view of the frame. RealityKit's source texture does not allow views, so an sRGB
    /// frame is first copied, byte for byte, into a same-format texture that does.
    private func rawFrame(_ source: MTLTexture, commandBuffer: MTLCommandBuffer) -> MTLTexture? {
        if let view = Self.rawView(source) { return view }
        guard let device else { return nil }
        if frameCopy?.width != source.width || frameCopy?.height != source.height
            || frameCopy?.pixelFormat != source.pixelFormat {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: source.pixelFormat, width: source.width, height: source.height, mipmapped: false)
            descriptor.usage = [.shaderRead, .pixelFormatView]
            descriptor.storageMode = .private
            frameCopy = device.makeTexture(descriptor: descriptor)
        }
        guard let frameCopy, let blit = commandBuffer.makeBlitCommandEncoder() else { return nil }
        blit.copy(from: source, to: frameCopy)
        blit.endEncoding()
        return Self.rawView(frameCopy)
    }

    private func ensureChain(width: Int, height: Int, levels: Int) -> Bool {
        if chainKey == (width, height, levels), level.count == levels { return true }
        guard let device else { return false }
        func make(_ w: Int, _ h: Int) -> MTLTexture? {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rgba16Float, width: max(w, 1), height: max(h, 1), mipmapped: false)
            descriptor.usage = [.shaderRead, .shaderWrite]
            descriptor.storageMode = .private
            return device.makeTexture(descriptor: descriptor)
        }
        var level: [MTLTexture] = []
        var blurred: [MTLTexture] = []
        var summed: [MTLTexture] = []
        var upsampled: [MTLTexture] = []
        var w = width / 2
        var h = height / 2
        for _ in 0..<levels {
            guard let a = make(w, h), let b = make(w, h), let c = make(w, h), let d = make(w, h) else { return false }
            level.append(a)
            blurred.append(b)
            summed.append(c)
            upsampled.append(d)
            w /= 2
            h /= 2
        }
        guard let full = make(width, height) else { return false }
        self.level = level
        self.blurred = blurred
        self.summed = summed
        self.upsampled = upsampled
        self.full = full
        chainKey = (width, height, levels)
        return true
    }

    private func passThrough(commandBuffer: MTLCommandBuffer, source: MTLTexture, target: MTLTexture) {
        guard let blit = commandBuffer.makeBlitCommandEncoder() else { return }
        blit.copy(from: source, to: target)
        blit.endEncoding()
    }
}

#endif
#endif
