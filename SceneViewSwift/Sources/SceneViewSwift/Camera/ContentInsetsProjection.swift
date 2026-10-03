#if os(iOS) || os(macOS) || os(visionOS)
import simd

/// The projection change that keeps the subject inside the part of a
/// ``SceneView`` a panel leaves visible — the math behind
/// ``SceneView/contentInsets(_:)``.
///
/// The view is **not** resized: the scene keeps rendering edge to edge, under
/// the panel's glass. Only the projection moves, by a translation and a
/// uniform scale applied *after* the perspective divide:
///
/// ```
/// ndc' = scale · ndc + shift
/// ```
///
/// The contract, shared with Android's `contentPadding`: **the visible
/// rectangle is the camera's viewport.** It behaves exactly as a view of that
/// size and position would.
///
/// - `shift` puts the optical centre — where the camera's forward axis lands —
///   at the centre of the visible rectangle.
/// - `scale` is `visibleHeight / viewHeight`: the vertical field of view the
///   camera was configured for spans the visible rectangle instead of the
///   whole view. It needs no knowledge of the scene, so it works when the host
///   drives the camera itself: whatever was inside the view vertically is
///   inside the visible rectangle. A side panel leaves the height alone, and
///   the subject only slides.
///
/// The camera pose is untouched, which is what lets the same call serve a
/// host-driven camera and an orbit the user has already moved.
///
/// Kept free of SwiftUI and RealityKit so the whole contract is unit-tested.
struct ContentInsetsProjection: Equatable {
    /// Translation of the image in normalized device coordinates
    /// (`-1 ... 1`, x to the right, y **up**).
    var shift: SIMD2<Float>
    /// Uniform scale of the image about the optical centre.
    var scale: Float
    /// Width and height of the visible rectangle as fractions of the view's.
    var visibleFraction: SIMD2<Float>

    /// No insets: the projection RealityKit already builds.
    static let identity = ContentInsetsProjection(
        shift: .zero, scale: 1, visibleFraction: SIMD2<Float>(1, 1)
    )

    /// The smallest share of the view the visible rectangle may shrink to on
    /// either axis. Insets that would cover more are scaled back, so a panel
    /// reported taller than the view cannot collapse the image to a point.
    static let minimumVisibleFraction: Float = 0.1

    var isIdentity: Bool {
        shift == .zero && scale == 1
    }

    init(shift: SIMD2<Float>, scale: Float, visibleFraction: SIMD2<Float>) {
        self.shift = shift
        self.scale = scale
        self.visibleFraction = visibleFraction
    }

    /// Resolves insets, in points, against a view of the given size.
    ///
    /// Negative or non-finite insets count as zero. `left` / `right` are
    /// physical edges: resolve `leading` / `trailing` against the layout
    /// direction before calling.
    init(viewWidth: Float, viewHeight: Float,
         top: Float, left: Float, bottom: Float, right: Float) {
        guard viewWidth.isFinite, viewHeight.isFinite, viewWidth > 0, viewHeight > 0 else {
            self = .identity
            return
        }
        func sanitized(_ value: Float) -> Float { value.isFinite ? max(value, 0) : 0 }
        // Two opposite insets that leave less than the minimum are scaled back
        // together, keeping their ratio — and so the side the subject leans to.
        func fitted(_ a: Float, _ b: Float, in length: Float) -> (Float, Float) {
            let a = sanitized(a), b = sanitized(b)
            let allowed = length * (1 - Self.minimumVisibleFraction)
            guard a + b > allowed else { return (a, b) }
            let factor = allowed / (a + b)
            return (a * factor, b * factor)
        }
        let (l, r) = fitted(left, right, in: viewWidth)
        let (t, b) = fitted(top, bottom, in: viewHeight)
        let visibleWidth = viewWidth - l - r
        let visibleHeight = viewHeight - t - b
        shift = SIMD2<Float>((l - r) / viewWidth, (b - t) / viewHeight)
        scale = visibleHeight / viewHeight
        visibleFraction = SIMD2<Float>(visibleWidth / viewWidth, visibleHeight / viewHeight)
    }

    /// The projection a view asks for, given who owns it.
    ///
    /// Identity when `ownsProjection` is false — the camera modes handed to
    /// Apple's `realityViewCameraControls(_:)` — whatever the insets: SceneView
    /// neither draws nor hit-tests through an offset there. Rendering, framing
    /// and the tap ray all resolve through here, so they change together.
    static func resolved(
        ownsProjection: Bool, viewWidth: Float, viewHeight: Float,
        top: Float, left: Float, bottom: Float, right: Float
    ) -> ContentInsetsProjection {
        guard ownsProjection else { return .identity }
        return ContentInsetsProjection(
            viewWidth: viewWidth, viewHeight: viewHeight,
            top: top, left: left, bottom: bottom, right: right
        )
    }

    /// The symmetric frustum that sees exactly the visible rectangle.
    ///
    /// The fit-to-bounds pass fits the content to this frustum instead of the
    /// full view's, so an auto-framed subject fills the visible rectangle with
    /// the same margin it had in the full view.
    ///
    /// - Parameters:
    ///   - fovYDegrees: The camera's vertical field of view over the full view.
    ///   - aspect: The full view's `width / height`.
    func visibleFrustum(fovYDegrees: Float, aspect: Float) -> ViewFrustum {
        guard !isIdentity || visibleFraction != SIMD2<Float>(1, 1) else {
            return ViewFrustum(fovYDegrees: fovYDegrees, aspect: aspect)
        }
        let tanY = tan(fovYDegrees * .pi / 360)
        // A view-space direction at vertical tangent `t` lands `scale · t / tanY`
        // above the optical centre, and the visible rectangle reaches
        // `visibleFraction.y` above it.
        let visibleTanY = tanY * visibleFraction.y / scale
        return ViewFrustum(
            fovYDegrees: min(2 * atan(visibleTanY) * 180 / .pi, 179),
            aspect: aspect * visibleFraction.x / visibleFraction.y
        )
    }

    /// The perspective projection with the shift and scale folded in, for
    /// `ProjectiveTransformCameraComponent`.
    ///
    /// Reverse-Z with an infinite far plane — RealityKit's own convention:
    /// depth is `1` on the near plane and tends to `0` at infinity.
    func matrix(fovYDegrees: Float, aspect: Float, near: Float) -> simd_float4x4 {
        let yScale = 1 / tan(fovYDegrees * .pi / 360)
        let xScale = yScale / aspect
        // clip.w = -z, so adding `shift · clip.w` to clip.xy translates the
        // image by `shift` after the divide.
        return simd_float4x4(columns: (
            SIMD4<Float>(scale * xScale, 0, 0, 0),
            SIMD4<Float>(0, scale * yScale, 0, 0),
            SIMD4<Float>(-shift.x, -shift.y, 0, -1),
            SIMD4<Float>(0, 0, near, 0)
        ))
    }

    /// Where a camera-space point lands in normalized device coordinates, or
    /// `nil` behind the camera. The camera looks down its local `-Z`.
    func normalizedDeviceCoordinates(
        ofViewPoint point: SIMD3<Float>, fovYDegrees: Float, aspect: Float
    ) -> SIMD2<Float>? {
        guard point.z < 0 else { return nil }
        let tanY = tan(fovYDegrees * .pi / 360)
        let tanX = tanY * aspect
        let centred = SIMD2<Float>(point.x / (-point.z * tanX), point.y / (-point.z * tanY))
        return centred * scale + shift
    }

    /// The camera-space direction of the ray through a point of the view —
    /// the inverse of ``normalizedDeviceCoordinates(ofViewPoint:fovYDegrees:aspect:)``.
    ///
    /// Hit-testing goes through this, so a tap lands on what is drawn under
    /// the finger while the image is shifted.
    func viewRayDirection(
        throughNormalizedDeviceCoordinates ndc: SIMD2<Float>, fovYDegrees: Float, aspect: Float
    ) -> SIMD3<Float> {
        let tanY = tan(fovYDegrees * .pi / 360)
        let tanX = tanY * aspect
        let centred = (ndc - shift) / scale
        return simd_normalize(SIMD3<Float>(centred.x * tanX, centred.y * tanY, -1))
    }
}

/// A symmetric perspective frustum: what the fit-to-bounds pass fits to.
struct ViewFrustum: Equatable {
    /// Vertical field of view, in degrees.
    var fovYDegrees: Float
    /// `width / height`.
    var aspect: Float
}

extension CameraControls {
    /// The orbit radius that shows the subject at the same zoom *relative to
    /// its fit* once the frustum changed — a rotation, a split-view resize, a
    /// panel opening over the view.
    ///
    /// The radius is scaled by `fit(new) / fit(old)`. A camera still at the
    /// fitted distance lands exactly on the new fit; a camera the user pinched
    /// to twice as close stays twice as close. The change is reversible:
    /// rotating back, or closing the panel, restores the radius it started
    /// from. Target and angles are not touched.
    ///
    /// - Parameters:
    ///   - boundsExtents: Extents of the content the last fit was computed for.
    ///   - old: The frustum the current radius was chosen in.
    ///   - new: The frustum to move to.
    ///   - clamped: `false` returns the radius before the zoom limits.
    /// - Returns: The radius, clamped to `[minRadius, maxRadius]`; the current
    ///   radius when either fit is degenerate.
    func radiusKeepingZoom(
        boundsExtents: SIMD3<Float>,
        from old: ViewFrustum,
        to new: ViewFrustum,
        margin: Float = CameraControls.defaultFitMargin,
        clamped: Bool = true
    ) -> Float {
        guard old != new else { return orbitRadius }
        // The zoom limits must not flatten the ratio: a fit that the ceiling
        // clamps in both frusta would otherwise read as "no change".
        var unclamped = self
        unclamped.minRadius = .leastNormalMagnitude
        unclamped.maxRadius = .greatestFiniteMagnitude
        let oldFit = unclamped.fitRadius(
            boundsExtents: boundsExtents, fovYDegrees: old.fovYDegrees,
            aspect: old.aspect, margin: margin
        )
        let newFit = unclamped.fitRadius(
            boundsExtents: boundsExtents, fovYDegrees: new.fovYDegrees,
            aspect: new.aspect, margin: margin
        )
        guard oldFit.isFinite, newFit.isFinite, oldFit > 0, newFit > 0 else { return orbitRadius }
        let radius = orbitRadius * newFit / oldFit
        guard radius.isFinite else { return orbitRadius }
        return clamped ? Swift.min(Swift.max(radius, minRadius), maxRadius) : radius
    }

    /// ``radiusKeepingZoom(boundsExtents:from:to:margin:clamped:)`` across a
    /// *sequence* of frustum changes, reversible even through the zoom limits.
    ///
    /// A landscape fit often sits below `minRadius`, so the radius is clamped
    /// there; scaling that clamped radius back on the return to portrait would
    /// leave the subject smaller than it started. `previous` carries the radius
    /// the last change asked for: as long as nothing else moved the radius
    /// since (it still equals `previous.applied`), the ratio is applied to what
    /// was asked, not to what the limits allowed.
    func radiusFollowingFrustum(
        boundsExtents: SIMD3<Float>,
        from old: ViewFrustum,
        to new: ViewFrustum,
        margin: Float = CameraControls.defaultFitMargin,
        previous: FrustumRadius? = nil
    ) -> FrustumRadius {
        var base = self
        if let previous, previous.applied == orbitRadius {
            base.orbitRadius = previous.asked
        }
        let asked = base.radiusKeepingZoom(
            boundsExtents: boundsExtents, from: old, to: new, margin: margin, clamped: false
        )
        return FrustumRadius(asked: asked, applied: Swift.min(Swift.max(asked, minRadius), maxRadius))
    }
}

/// The orbit radius a frustum change asked for, and the one the zoom limits
/// let through.
struct FrustumRadius: Equatable {
    var asked: Float
    var applied: Float
}
#endif
