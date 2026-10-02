import Foundation

/// One metric anchor for the scale fit: the model's raw output `d` sampled
/// where a world point of known depth `z` (metres, along the optical axis)
/// projects, with a confidence weight.
struct DepthAnchorSample: Sendable, Hashable, Codable {
    var d: Float
    var z: Float
    var confidence: Float

    init(d: Float, z: Float, confidence: Float = 1) {
        self.d = d
        self.z = z
        self.confidence = confidence
    }
}

/// The robust affine fit that turns a relative depth map into metres.
///
/// Depth Anything V2 predicts inverse depth up to an unknown scale and shift
/// that change **every frame** (the network normalises per image), so the fit
/// runs on every ML frame:
///
/// ```
/// minimise  Σ wᵢ (s·dᵢ + t − 1/zᵢ)²  +  λs (s − ŝ)²  +  λt (t − t̂)²
/// wᵢ = cᵢ · zᵢ²         (turns the 1/z error into a relative error in z)
/// (ŝ, t̂) = the smoothed fit of the previous ML frame (optional prior)
/// ```
///
/// A deterministic two-point consensus drops gross outliers first, then
/// closed-form 2×2 normal equations and Huber IRLS iterations rejecting
/// anchors whose relative depth error exceeds 20 %. A fit is only returned
/// when it is well conditioned: at least 12 inliers, an inlier depth range of
/// at least 1.5×, and a positive scale. The same algorithm and the same JSON
/// test vectors exist on Android (`DepthScaleFit`, internal there too).
enum AffineInverseDepthFit {
    struct Parameters: Sendable, Hashable {
        /// Weight of the temporal prior relative to the data term (λ = α · data).
        var priorWeight: Double = 0.05
        /// Huber threshold on the relative depth error.
        var huberDelta: Double = 0.05
        /// Anchors whose predicted depth is off by more than this are dropped.
        var outlierRelativeError: Double = 0.2
        /// IRLS refinements after the first solve.
        var iterations: Int = 3
        var minimumInliers: Int = 12
        var minimumDepthRatio: Float = 1.5

        init() {}
    }

    struct Result: Sendable, Hashable {
        let scale: Float
        let shift: Float
        let inlierCount: Int
        /// RMS of the inliers' relative depth error.
        let relativeRMSError: Float
        /// Depth range covered by the inliers, in metres.
        let inlierDepthRange: ClosedRange<Float>
    }

    /// Fits `1/z = s·d + t` for ``MonocularDepthOutputKind/affineInverse``
    /// output. Returns `nil` for metric output (nothing to fit) and when the
    /// anchors cannot support a trustworthy fit.
    static func fit(
        _ anchors: [DepthAnchorSample],
        kind: MonocularDepthOutputKind = .affineInverse,
        prior: (scale: Float, shift: Float)? = nil,
        parameters: Parameters = Parameters()
    ) -> Result? {
        guard kind == .affineInverse, anchors.count >= parameters.minimumInliers else { return nil }
        let n = anchors.count
        let d = anchors.map { Double($0.d) }
        let z = anchors.map { Double($0.z) }
        let c = anchors.map { Double($0.confidence) }
        // Target and base weight per anchor: both make the residual relative in z.
        let y = z.map { 1 / $0 }
        func baseWeight(_ i: Int) -> Double { c[i] * z[i] * z[i] }
        var inlier = (0..<n).map { z[$0] > 0 && z[$0].isFinite && c[$0] > 0 && d[$0].isFinite }

        func relativeDepthError(_ i: Int, _ s: Double, _ t: Double) -> Double {
            let p = s * d[i] + t
            guard p > minimumInverseDepth else { return .infinity }
            return abs(1 / p - z[i]) / z[i]
        }

        // Consensus seed: with z²-weighting, a few far outliers dominate a
        // plain least-squares start. A deterministic two-point consensus picks
        // the hypothesis most anchors agree with and drops the rest before
        // IRLS (same LCG, seed and hypothesis count as Android).
        var bestScore = 0.0
        var seed: (Double, Double)?
        var state = consensusSeed
        for _ in 0..<consensusHypotheses {
            state = state &* lcgMultiplier &+ lcgIncrement
            let i = Int((state >> 33) % UInt64(n))
            state = state &* lcgMultiplier &+ lcgIncrement
            let j = Int((state >> 33) % UInt64(n))
            let dd = d[i] - d[j]
            guard i != j, inlier[i], inlier[j], abs(dd) >= 1e-6 else { continue }
            let cs = (y[i] - y[j]) / dd
            let ct = y[i] - cs * d[i]
            guard cs > 0 else { continue }
            var score = 0.0
            for k in 0..<n where inlier[k] && relativeDepthError(k, cs, ct) <= parameters.outlierRelativeError {
                score += c[k]
            }
            if score > bestScore {
                bestScore = score
                seed = (cs, ct)
            }
        }
        if let seed {
            for i in 0..<n where inlier[i] && relativeDepthError(i, seed.0, seed.1) > parameters.outlierRelativeError {
                inlier[i] = false
            }
        }
        var w = (0..<n).map { inlier[$0] ? baseWeight($0) : 0 }

        func solve() -> (Double, Double)? {
            var sw = 0.0, swd = 0.0, swdd = 0.0, swy = 0.0, swdy = 0.0
            for i in 0..<n where w[i] > 0 {
                sw += w[i]
                swd += w[i] * d[i]
                swdd += w[i] * d[i] * d[i]
                swy += w[i] * y[i]
                swdy += w[i] * d[i] * y[i]
            }
            guard sw > 0 else { return nil }
            // Normalised by the total weight: the prior's strength does not
            // depend on how many anchors there are.
            var a11 = swdd / sw, a22 = 1.0, b1 = swdy / sw, b2 = swy / sw
            let a12 = swd / sw
            if let prior, parameters.priorWeight > 0 {
                let ls = parameters.priorWeight * a11
                let lt = parameters.priorWeight * a22
                a11 += ls; a22 += lt
                b1 += ls * Double(prior.scale)
                b2 += lt * Double(prior.shift)
            }
            let det = a11 * a22 - a12 * a12
            guard det != 0, abs(det) >= 1e-9 * a11 * a22 else { return nil }
            return ((b1 * a22 - a12 * b2) / det, (a11 * b2 - a12 * b1) / det)
        }

        guard var (s, t) = solve() else { return nil }
        for round in 0..<parameters.iterations {
            for i in 0..<n where inlier[i] {
                let e = relativeDepthError(i, s, t)
                // The first round only down-weights (Huber): a poor first
                // solve under heavy outliers must not reject good anchors.
                if round > 0, e > parameters.outlierRelativeError {
                    inlier[i] = false
                    w[i] = 0
                } else {
                    w[i] = baseWeight(i) * (e <= parameters.huberDelta ? 1 : parameters.huberDelta / e)
                }
            }
            guard let next = solve() else { return nil }
            (s, t) = next
        }
        // Final pass: drop what the converged fit still disagrees with.
        var count = 0
        var sumSquares = 0.0
        var zMin = Float.infinity, zMax: Float = 0
        for i in 0..<n where inlier[i] {
            let e = relativeDepthError(i, s, t)
            guard e <= parameters.outlierRelativeError else { continue }
            count += 1
            sumSquares += e * e
            zMin = min(zMin, Float(z[i]))
            zMax = max(zMax, Float(z[i]))
        }
        guard count >= parameters.minimumInliers,
              zMax / zMin >= parameters.minimumDepthRatio,
              s > 0, s.isFinite, t.isFinite else { return nil }
        return Result(
            scale: Float(s),
            shift: Float(t),
            inlierCount: count,
            relativeRMSError: Float((sumSquares / Double(count)).squareRoot()),
            inlierDepthRange: zMin...zMax
        )
    }

    /// Below this, `s·d + t` is treated as infinitely far (Android: `MIN_INVERSE_DEPTH`).
    static let minimumInverseDepth = 1e-4

    private static let consensusHypotheses = 64
    private static let consensusSeed: UInt64 = 0x2545_F491_4F6C_DD1D
    private static let lcgMultiplier: UInt64 = 6_364_136_223_846_793_005
    private static let lcgIncrement: UInt64 = 1_442_695_040_888_963_407
}

/// Light temporal smoothing of the fit on `(log s, t)`, with a short hold
/// when a frame's own fit is rejected.
///
/// The network renormalises every image, so `(s, t)` legitimately moves with
/// the framing: the gain stays high (the new fit dominates). A rejected fit
/// keeps the previous one for at most ``maxHeldFrames`` ML frames; after that
/// the output is `nil` and depth stops until a new valid fit — never a stale
/// scale held forever.
struct DepthScaleSmoother: Sendable {
    struct Output: Sendable, Hashable {
        let scale: Float
        let shift: Float
        let inlierCount: Int
        let relativeRMSError: Float
        let inlierDepthRange: ClosedRange<Float>
        let held: Bool
    }

    /// Weight of the new fit, 0…1.
    var gain: Float
    var maxHeldFrames: Int
    private var current: Output?
    private var heldFrames = 0

    init(gain: Float = 0.6, maxHeldFrames: Int = 5) {
        self.gain = gain
        self.maxHeldFrames = maxHeldFrames
    }

    /// The prior for the next fit: the last smoothed value, if any.
    var prior: (scale: Float, shift: Float)? {
        current.map { ($0.scale, $0.shift) }
    }

    mutating func reset() {
        current = nil
        heldFrames = 0
    }

    mutating func update(with fit: AffineInverseDepthFit.Result?) -> Output? {
        guard let fit else {
            guard let previous = current else { return nil }
            heldFrames += 1
            if heldFrames > maxHeldFrames {
                reset()
                return nil
            }
            return Output(scale: previous.scale, shift: previous.shift,
                          inlierCount: previous.inlierCount,
                          relativeRMSError: previous.relativeRMSError,
                          inlierDepthRange: previous.inlierDepthRange, held: true)
        }
        heldFrames = 0
        let scale: Float
        let shift: Float
        if let previous = current {
            scale = exp(log(previous.scale) + gain * (log(fit.scale) - log(previous.scale)))
            shift = previous.shift + gain * (fit.shift - previous.shift)
        } else {
            scale = fit.scale
            shift = fit.shift
        }
        let output = Output(scale: scale, shift: shift, inlierCount: fit.inlierCount,
                            relativeRMSError: fit.relativeRMSError,
                            inlierDepthRange: fit.inlierDepthRange, held: false)
        current = output
        return output
    }
}

/// Turns a relative map plus a fit into the millimetre map and per-pixel
/// confidence of an ``ARDepthFrame``.
enum MonocularDepthConversion {
    /// Depths outside this window (metres) are reported as invalid.
    static let validRange: ClosedRange<Float> = 0.2...8

    /// - Returns: `width × height` millimetres (`0` = invalid) and confidences.
    static func convert(
        _ estimate: MonocularDepthEstimate,
        kind: MonocularDepthOutputKind,
        scale: Float,
        shift: Float,
        anchorDepthRange: ClosedRange<Float>,
        relativeRMSError: Float
    ) -> (millimetres: [UInt16], confidence: [UInt8]) {
        let w = estimate.width, h = estimate.height
        var metres = [Float](repeating: 0, count: w * h)
        // Extrapolating past twice the anchors' range is a guess, not a measure.
        let lower = max(validRange.lowerBound, anchorDepthRange.lowerBound / 2)
        let upper = min(validRange.upperBound, anchorDepthRange.upperBound * 2)
        for i in 0..<(w * h) {
            let v = estimate.values[i]
            let z: Float
            switch kind {
            case .affineInverse:
                let p = scale * v + shift
                z = p > Float(AffineInverseDepthFit.minimumInverseDepth) ? 1 / p : 0
            case .metric:
                z = v
            }
            metres[i] = (z.isFinite && z >= lower && z <= upper) ? z : 0
        }

        let fitFactor = max(0, min(1, 1 - relativeRMSError / 0.2))
        var millimetres = [UInt16](repeating: 0, count: w * h)
        var confidence = [UInt8](repeating: 0, count: w * h)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * w + x
                let z = metres[i]
                guard z > 0 else { continue }
                millimetres[i] = UInt16(min(Float(UInt16.max), (z * 1000).rounded()))
                // Depth edges are where a monocular map is least reliable.
                var gradient: Float = 0
                if x + 1 < w, metres[i + 1] > 0 { gradient = max(gradient, abs(metres[i + 1] - z)) }
                if y + 1 < h, metres[i + w] > 0 { gradient = max(gradient, abs(metres[i + w] - z)) }
                let edgeFactor = max(0, min(1, 1 - (gradient / z) / 0.15))
                let extrapolation: Float
                if z < anchorDepthRange.lowerBound {
                    let half = anchorDepthRange.lowerBound / 2
                    extrapolation = max(0, min(1, (z - half) / half))
                } else if z > anchorDepthRange.upperBound {
                    let top = anchorDepthRange.upperBound
                    extrapolation = max(0, min(1, (2 * top - z) / top))
                } else {
                    extrapolation = 1
                }
                confidence[i] = UInt8((255 * fitFactor * edgeFactor * extrapolation).rounded())
            }
        }
        return (millimetres, confidence)
    }
}
