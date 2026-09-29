#if os(iOS) || os(macOS) || os(visionOS)
import Foundation
import Metal
import RealityKit

/// Bloom settings for a ``SceneView`` — light bleeding out of the brightest pixels.
///
/// Mirrors the fields SceneView Android sets on Filament's `View.bloomOptions`
/// (`enabled`, `strength`, `levels`, `threshold`), so the same numbers give the same look on
/// both platforms. Bloom is what turns emissive or unlit colours **above 1.0** into glow: a
/// pixel brighter than the threshold spills a soft halo onto its neighbours.
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

    /// Brightness above which a pixel starts to bloom when ``threshold`` is on, in the
    /// colour units of the rendered frame (`1.0` = the brightest non-HDR white).
    public var thresholdLevel: Float

    public init(strength: Float = 0.1, levels: Int = 6, threshold: Bool = true, thresholdLevel: Float = 0.9) {
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
/// A classic dual-filter bloom, all in compute: a thresholded 13-tap downsample of the frame
/// into a half-resolution chain of `levels` textures, a tent-filter upsample that sums the
/// levels back up, and a composite that adds `strength × bloom` onto the frame.
@available(iOS 26.0, macOS 26.0, *)
struct BloomPostProcess: PostProcessEffect {
    let options: BloomOptions
    /// Pipelines and the texture chain survive across frames and across `options` changes.
    let resources: BloomResources

    mutating func prepare(for device: any MTLDevice) {
        resources.prepare(device: device)
    }

    mutating func postProcess(context: borrowing PostProcessEffectContext<any MTLCommandBuffer>) {
        resources.encode(
            options: options,
            commandBuffer: context.commandBuffer,
            source: context.sourceColorTexture,
            target: context.targetColorTexture
        )
    }
}

/// Metal state of the bloom pass. Only touched from RealityKit's render callbacks, which are
/// serialised, hence the unchecked `Sendable`.
final class BloomResources: @unchecked Sendable {
    private var device: MTLDevice?
    private var prefilter: MTLComputePipelineState?
    private var downsample: MTLComputePipelineState?
    private var upsample: MTLComputePipelineState?
    private var composite: MTLComputePipelineState?
    private var down: [MTLTexture] = []
    private var up: [MTLTexture] = []
    private var chainKey: (Int, Int, Int) = (0, 0, 0)
    private var spikeLogged = false // SPIKE-LOG

    func prepare(device: MTLDevice) {
        guard self.device == nil else { return }
        self.device = device
        do {
            let library = try device.makeLibrary(source: Self.kernels, options: nil)
            func pipeline(_ name: String) throws -> MTLComputePipelineState? {
                guard let function = library.makeFunction(name: name) else { return nil }
                return try device.makeComputePipelineState(function: function)
            }
            prefilter = try pipeline("sv_bloom_prefilter")
            downsample = try pipeline("sv_bloom_downsample")
            upsample = try pipeline("sv_bloom_upsample")
            composite = try pipeline("sv_bloom_composite")
        } catch {
            prefilter = nil
        }
    }

    func encode(options: BloomOptions, commandBuffer: MTLCommandBuffer, source: MTLTexture, target: MTLTexture) {
        if device == nil { prepare(device: commandBuffer.device) }
        if !spikeLogged { // SPIKE-LOG
            spikeLogged = true
            NSLog("[SVBloom] source=%lu target=%lu targetUsage=%lu size=%dx%d", source.pixelFormat.rawValue, target.pixelFormat.rawValue, target.usage.rawValue, source.width, source.height)
        }
        guard options.isEnabled,
              let prefilter, let downsample, let upsample, let composite,
              let encoder = commandBuffer.makeComputeCommandEncoder()
        else {
            passThrough(commandBuffer: commandBuffer, source: source, target: target)
            return
        }
        let levels = max(1, min(options.levels, 8))
        ensureChain(width: source.width, height: source.height, levels: levels)
        var params = SIMD4<Float>(
            options.threshold ? options.thresholdLevel : 0,
            options.threshold ? max(options.thresholdLevel * 0.5, 1e-3) : 0,
            options.strength,
            Float(levels)
        )

        // Frame → level 0, thresholded.
        run(encoder, prefilter, inputs: [source], output: down[0], params: &params)
        // Level i → level i + 1.
        for i in 1..<levels {
            run(encoder, downsample, inputs: [down[i - 1]], output: down[i], params: &params)
        }
        // Sum back up: up[i] = down[i] + tent(up[i + 1]).
        var lower = down[levels - 1]
        for i in stride(from: levels - 2, through: 0, by: -1) {
            run(encoder, upsample, inputs: [lower, down[i]], output: up[i], params: &params)
            lower = up[i]
        }
        // target = source + strength × bloom.
        run(encoder, composite, inputs: [source, lower], output: target, params: &params)
        encoder.endEncoding()
    }

    private func run(
        _ encoder: MTLComputeCommandEncoder,
        _ pipeline: MTLComputePipelineState,
        inputs: [MTLTexture],
        output: MTLTexture,
        params: inout SIMD4<Float>
    ) {
        encoder.setComputePipelineState(pipeline)
        for (index, texture) in inputs.enumerated() {
            encoder.setTexture(texture, index: index)
        }
        encoder.setTexture(output, index: 2)
        encoder.setBytes(&params, length: MemoryLayout<SIMD4<Float>>.stride, index: 0)
        let width = pipeline.threadExecutionWidth
        let height = max(1, pipeline.maxTotalThreadsPerThreadgroup / width)
        encoder.dispatchThreads(
            MTLSize(width: output.width, height: output.height, depth: 1),
            threadsPerThreadgroup: MTLSize(width: width, height: height, depth: 1)
        )
    }

    private func ensureChain(width: Int, height: Int, levels: Int) {
        guard chainKey != (width, height, levels) || down.count != levels, let device else { return }
        chainKey = (width, height, levels)
        down = []
        up = []
        var w = max(1, width / 2)
        var h = max(1, height / 2)
        for _ in 0..<levels {
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(
                pixelFormat: .rgba16Float, width: w, height: h, mipmapped: false
            )
            descriptor.usage = [.shaderRead, .shaderWrite]
            descriptor.storageMode = .private
            if let d = device.makeTexture(descriptor: descriptor) { down.append(d) }
            if let u = device.makeTexture(descriptor: descriptor) { up.append(u) }
            w = max(1, w / 2)
            h = max(1, h / 2)
        }
    }

    private func passThrough(commandBuffer: MTLCommandBuffer, source: MTLTexture, target: MTLTexture) {
        guard let blit = commandBuffer.makeBlitCommandEncoder() else { return }
        blit.copy(from: source, to: target)
        blit.endEncoding()
    }

    /// Compiled at first use, so the package ships no `.metallib`.
    static let kernels = """
    #include <metal_stdlib>
    using namespace metal;

    constexpr sampler linearClamp(filter::linear, address::clamp_to_edge);

    static float3 sv_down13(texture2d<float, access::sample> src, float2 uv, float2 texel) {
        float3 a = src.sample(linearClamp, uv + texel * float2(-2, -2)).rgb;
        float3 b = src.sample(linearClamp, uv + texel * float2( 0, -2)).rgb;
        float3 c = src.sample(linearClamp, uv + texel * float2( 2, -2)).rgb;
        float3 d = src.sample(linearClamp, uv + texel * float2(-2,  0)).rgb;
        float3 e = src.sample(linearClamp, uv).rgb;
        float3 f = src.sample(linearClamp, uv + texel * float2( 2,  0)).rgb;
        float3 g = src.sample(linearClamp, uv + texel * float2(-2,  2)).rgb;
        float3 h = src.sample(linearClamp, uv + texel * float2( 0,  2)).rgb;
        float3 i = src.sample(linearClamp, uv + texel * float2( 2,  2)).rgb;
        float3 j = src.sample(linearClamp, uv + texel * float2(-1, -1)).rgb;
        float3 k = src.sample(linearClamp, uv + texel * float2( 1, -1)).rgb;
        float3 l = src.sample(linearClamp, uv + texel * float2(-1,  1)).rgb;
        float3 m = src.sample(linearClamp, uv + texel * float2( 1,  1)).rgb;
        return e * 0.125 + (a + c + g + i) * 0.03125 + (b + d + f + h) * 0.0625 + (j + k + l + m) * 0.125;
    }

    kernel void sv_bloom_prefilter(texture2d<float, access::sample> src [[texture(0)]],
                                   texture2d<float, access::write> dst [[texture(2)]],
                                   constant float4 &p [[buffer(0)]],
                                   uint2 gid [[thread_position_in_grid]]) {
        if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
        float2 size = float2(dst.get_width(), dst.get_height());
        float2 uv = (float2(gid) + 0.5) / size;
        float3 c = sv_down13(src, uv, 1.0 / float2(src.get_width(), src.get_height()));
        c = max(c, 0.0);
        if (p.x > 0.0) {
            // Soft-knee threshold on the brightest channel, so hues survive.
            float bright = max(c.r, max(c.g, c.b));
            float knee = p.y;
            float soft = clamp(bright - p.x + knee, 0.0, 2.0 * knee);
            soft = soft * soft / (4.0 * knee + 1e-5);
            float contribution = max(soft, bright - p.x) / max(bright, 1e-5);
            c *= contribution;
        }
        dst.write(float4(c, 1.0), gid);
    }

    kernel void sv_bloom_downsample(texture2d<float, access::sample> src [[texture(0)]],
                                    texture2d<float, access::write> dst [[texture(2)]],
                                    constant float4 &p [[buffer(0)]],
                                    uint2 gid [[thread_position_in_grid]]) {
        if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
        float2 uv = (float2(gid) + 0.5) / float2(dst.get_width(), dst.get_height());
        float3 c = sv_down13(src, uv, 1.0 / float2(src.get_width(), src.get_height()));
        dst.write(float4(c, 1.0), gid);
    }

    kernel void sv_bloom_upsample(texture2d<float, access::sample> lower [[texture(0)]],
                                  texture2d<float, access::sample> same [[texture(1)]],
                                  texture2d<float, access::write> dst [[texture(2)]],
                                  constant float4 &p [[buffer(0)]],
                                  uint2 gid [[thread_position_in_grid]]) {
        if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
        float2 uv = (float2(gid) + 0.5) / float2(dst.get_width(), dst.get_height());
        float2 t = 1.0 / float2(lower.get_width(), lower.get_height());
        float3 s = lower.sample(linearClamp, uv).rgb * 4.0;
        s += (lower.sample(linearClamp, uv + float2(-t.x, 0)).rgb + lower.sample(linearClamp, uv + float2(t.x, 0)).rgb
            + lower.sample(linearClamp, uv + float2(0, -t.y)).rgb + lower.sample(linearClamp, uv + float2(0, t.y)).rgb) * 2.0;
        s += lower.sample(linearClamp, uv + float2(-t.x, -t.y)).rgb + lower.sample(linearClamp, uv + float2(t.x, -t.y)).rgb
            + lower.sample(linearClamp, uv + float2(-t.x, t.y)).rgb + lower.sample(linearClamp, uv + float2(t.x, t.y)).rgb;
        float3 c = same.sample(linearClamp, uv).rgb + s / 16.0;
        dst.write(float4(c, 1.0), gid);
    }

    kernel void sv_bloom_composite(texture2d<float, access::sample> src [[texture(0)]],
                                   texture2d<float, access::sample> bloom [[texture(1)]],
                                   texture2d<float, access::write> dst [[texture(2)]],
                                   constant float4 &p [[buffer(0)]],
                                   uint2 gid [[thread_position_in_grid]]) {
        if (gid.x >= dst.get_width() || gid.y >= dst.get_height()) return;
        float4 c = src.read(gid);
        float2 uv = (float2(gid) + 0.5) / float2(dst.get_width(), dst.get_height());
        // The upsample chain sums `levels` blurred copies; average them before scaling.
        float3 b = bloom.sample(linearClamp, uv).rgb / max(p.w, 1.0);
        dst.write(float4(c.rgb + b * p.z, c.a), gid);
    }
    """
}
#endif
#endif
