#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import Metal
import RealityKit
#if os(iOS) || os(macOS)
import MetalPerformanceShaders
#endif

/// Bloom settings for a ``SceneView`` — light bleeding out of the brightest pixels.
///
/// Mirrors the fields SceneView Android sets on Filament's `View.bloomOptions` —
/// `strength`, `levels`, `resolution` and `threshold` — so a scene is tuned with the same
/// numbers on both platforms. ``thresholdLevel`` is iOS-only: Filament thresholds its HDR
/// frame at `1.0`, while this pass sees the displayed frame (see below).
///
/// Bloom is what turns bright unlit or emissive colours into glow: a pixel brighter than
/// the threshold spills a soft halo onto its neighbours. The pass measures brightness in
/// **display space**, `0` (black) to `1` (white), after RealityKit's tone mapping — so the
/// default threshold of `0.6` blooms what *looks* bright, however it got there.
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
/// visionOS support: RealityKit does not allow custom post-processing there. A frame in a
/// format the pass does not handle — an extended-range (EDR) frame, for one — is passed
/// through untouched: the worst case is a frame without glow.
public struct BloomOptions: Equatable, Sendable {

    /// How much of the blurred highlight is added back onto the frame. `0` turns bloom off;
    /// Android's `BloomOptions.strength` default is `0.1`, a glow-driven scene uses `0.3–0.6`.
    public var strength: Float

    /// Number of blur levels, each half the size of the previous one. More levels spread the
    /// glow wider. Clamped to `1...8`. Same meaning as Filament's `levels`.
    public var levels: Int

    /// Height, in pixels, of the first blur level; its width follows the frame's aspect ratio.
    /// Lower spreads the glow into broader, smoother halos, higher keeps it tight around small
    /// highlights. Same meaning and default as Filament's `resolution`; clamped to `64...2048`
    /// and never above half the frame height.
    public var resolution: Int

    /// When `true` (the default), only the part of a pixel above ``thresholdLevel`` blooms —
    /// the Filament `threshold = true` behaviour. When `false`, every pixel contributes and
    /// the whole frame softens.
    public var threshold: Bool

    /// Brightness above which a pixel starts to bloom when ``threshold`` is on, in display
    /// space: `0` is black, `1` is white; the default is `0.6`. iOS-only — Filament has no
    /// such field and thresholds its HDR frame at `1.0`.
    public var thresholdLevel: Float

    public init(strength: Float = 0.1, levels: Int = 6, resolution: Int = 384, threshold: Bool = true,
                thresholdLevel: Float = 0.6) {
        self.strength = strength
        self.levels = levels
        self.resolution = resolution
        self.threshold = threshold
        self.thresholdLevel = thresholdLevel
    }

    /// Bloom switched off.
    public static let disabled = BloomOptions(strength: 0)

    var isEnabled: Bool { strength > 0 }
}

// MARK: - The post-process pass

#if os(iOS) || os(macOS)

/// What the bloom pass does with a frame, decided from its formats and sizes alone.
enum BloomFramePlan: Equatable {
    /// Bloom it: MPS reads `readAs` and writes `writeAs` (raw views of an sRGB frame).
    case bloom(readAs: MTLPixelFormat, writeAs: MTLPixelFormat)
    /// Copy the frame to the target untouched — no glow, but a correct frame.
    case passThrough
    /// Neither is possible (source and target differ): leave the target alone.
    case skip

    /// The formats the pass blooms, and the raw format MPS reads and writes them through.
    /// Anything else — extended-range `bgra10_xr` / EDR, packed or depth formats — is passed
    /// through: MPS may not write it, and a display-space threshold means nothing above 1.
    static func rawFormat(_ format: MTLPixelFormat) -> MTLPixelFormat? {
        switch format {
        case .bgra8Unorm, .bgra8Unorm_srgb: .bgra8Unorm
        case .rgba8Unorm, .rgba8Unorm_srgb: .rgba8Unorm
        case .rgba16Float: .rgba16Float
        default: nil
        }
    }

    static func decide(enabled: Bool,
                       source: MTLPixelFormat, sourceSize: (width: Int, height: Int),
                       target: MTLPixelFormat, targetSize: (width: Int, height: Int)) -> BloomFramePlan {
        let sameShape = source == target && sourceSize == targetSize
        guard enabled, sourceSize.width > 1, sourceSize.height > 1,
              let read = rawFormat(source), let write = rawFormat(target), sourceSize == targetSize
        else { return sameShape ? .passThrough : .skip }
        return .bloom(readAs: read, writeAs: write)
    }
}

/// The bloom pass handed to `RealityViewCameraContent.renderingEffects.customPostProcessing`.
///
/// Built from Metal Performance Shaders kernels only — no shader source is compiled, so the
/// SDK needs neither the Metal Toolchain at build time nor a runtime compile: a half-size
/// bright pass at ``BloomOptions/resolution`` (`MPSImageBilinearScale`, then `MPSImageAdd` with a bias of minus the threshold,
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
    private var frameCopyView: MTLTexture?
    /// Raw views of the drawables RealityKit renders into, which it cycles through: one view
    /// per drawable instead of one per frame. A view keeps its drawable alive, so the cache is
    /// dropped when it outgrows a swap chain (a resize brings new drawables).
    private var targetViews: [ObjectIdentifier: MTLTexture] = [:]
    private var chainKey: (Int, Int, Int, Int) = (0, 0, 0, 0)

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
        let plan = BloomFramePlan.decide(
            enabled: options.isEnabled && supported,
            source: source.pixelFormat, sourceSize: (source.width, source.height),
            target: target.pixelFormat, targetSize: (target.width, target.height))
        guard case .bloom(let readAs, let writeAs) = plan,
              let scale, let blur, let brightPass, let sum, let composite,
              let output = targetView(target, as: writeAs),
              let frame = rawFrame(source, as: readAs, commandBuffer: commandBuffer)
        else {
            if plan != .skip { passThrough(commandBuffer: commandBuffer, source: source, target: target) }
            return
        }
        let original = source
        let source = frame
        let levels = max(1, min(options.levels, 8))
        let firstHeight = min(max(options.resolution, 64), 2048, max(source.height / 2, 1))
        guard ensureChain(width: source.width, height: source.height, firstHeight: firstHeight, levels: levels),
              let full else {
            passThrough(commandBuffer: commandBuffer, source: original, target: target)
            return
        }

        // Frame → first level, then keep only what exceeds the threshold: max(c − t, 0).
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
    /// display space — what looks bright is what blooms.
    private func targetView(_ texture: MTLTexture, as raw: MTLPixelFormat) -> MTLTexture? {
        if texture.pixelFormat == raw { return texture.usage.contains(.shaderWrite) ? texture : nil }
        let key = ObjectIdentifier(texture)
        if let view = targetViews[key], view.pixelFormat == raw { return view }
        guard texture.usage.contains(.pixelFormatView),
              let view = texture.makeTextureView(pixelFormat: raw) else { return nil }
        if targetViews.count >= 8 { targetViews.removeAll() }
        targetViews[key] = view
        return view
    }

    /// A raw view of the frame. RealityKit's source texture does not allow views, so an sRGB
    /// frame is first copied, byte for byte, into a same-format texture that does.
    private func rawFrame(_ source: MTLTexture, as raw: MTLPixelFormat, commandBuffer: MTLCommandBuffer) -> MTLTexture? {
        if source.pixelFormat == raw { return source }
        guard let device else { return nil }
        if frameCopy?.width != source.width || frameCopy?.height != source.height
            || frameCopy?.pixelFormat != source.pixelFormat || frameCopyView?.pixelFormat != raw {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: source.pixelFormat, width: source.width, height: source.height, mipmapped: false)
            descriptor.usage = [.shaderRead, .pixelFormatView]
            descriptor.storageMode = .private
            frameCopy = device.makeTexture(descriptor: descriptor)
            frameCopyView = frameCopy?.makeTextureView(pixelFormat: raw)
        }
        guard let frameCopy, let frameCopyView, let blit = commandBuffer.makeBlitCommandEncoder() else { return nil }
        blit.copy(from: source, to: frameCopy)
        blit.endEncoding()
        return frameCopyView
    }

    private func ensureChain(width: Int, height: Int, firstHeight: Int, levels: Int) -> Bool {
        if chainKey == (width, height, firstHeight, levels), level.count == levels { return true }
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
        var h = firstHeight
        var w = max(width * firstHeight / max(height, 1), 1)
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
        chainKey = (width, height, firstHeight, levels)
        return true
    }

    /// A blit copy only works between textures of one format and size; anything else is left
    /// alone rather than risk a Metal validation trap.
    private func passThrough(commandBuffer: MTLCommandBuffer, source: MTLTexture, target: MTLTexture) {
        guard source.pixelFormat == target.pixelFormat, source.width == target.width,
              source.height == target.height,
              let blit = commandBuffer.makeBlitCommandEncoder() else { return }
        blit.copy(from: source, to: target)
        blit.endEncoding()
    }
}

#endif
#endif
