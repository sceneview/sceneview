#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A wrapper for adding lights to a RealityKit scene.
///
/// Mirrors SceneView Android's `LightNode` — supports directional, point,
/// and spot lights with configurable intensity, color, and shadow casting.
///
/// ```swift
/// SceneView { content in
///     let sun = LightNode.directional(
///         color: .white,
///         intensity: 1000,
///         castsShadow: true
///     )
///     sun.entity.look(at: .zero, from: [2, 4, 2], relativeTo: nil)
///     content.add(sun.entity)
/// }
/// ```
///
/// `LightNode` wraps a RealityKit `Entity` (a reference type that is
/// main-actor-bound). The struct is therefore marked `@MainActor` so the
/// compiler enforces the threading contract — the previously-declared
/// `Sendable` conformance was unsound (#928 follow-up from the v4.1.0
/// post-ship audit).
@MainActor
public struct LightNode {
    /// The underlying RealityKit entity holding the light component.
    public let entity: Entity

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    /// Orientation as a quaternion.
    public var rotation: simd_quatf {
        get { entity.orientation }
        nonmutating set { entity.orientation = newValue }
    }

    /// Creates a directional light (like the sun).
    ///
    /// - Parameters:
    ///   - color: Light color.
    ///   - intensity: Luminous intensity in lux.
    ///   - castsShadow: Whether this light casts shadows.
    /// - Returns: A configured `LightNode`.
    public static func directional(
        color: LightNode.Color = .white,
        intensity: Float = 1000,
        castsShadow: Bool = true
    ) -> LightNode {
        let light = DirectionalLight()
        // The `isRealWorldProxy:` initializer is `@available(visionOS, unavailable)`
        // — that parameter models a light standing in for real-world illumination
        // in passthrough AR, which has no analogue on a virtual visionOS scene.
        // visionOS gets the plain `init(color:intensity:)` (equivalent to
        // `isRealWorldProxy: false`).
        #if os(visionOS)
        light.light = DirectionalLightComponent(
            color: color.platformColor,
            intensity: intensity
        )
        #else
        light.light = DirectionalLightComponent(
            color: color.platformColor,
            intensity: intensity,
            isRealWorldProxy: false
        )
        #endif
        if castsShadow {
            light.shadow = DirectionalLightComponent.Shadow(
                maximumDistance: 8,
                depthBias: 5.0
            )
        }
        return LightNode(entity: light)
    }

    /// Creates a "fill" directional light — the soft secondary light in a key+fill setup.
    ///
    /// Mirrors SceneView Android's `LightNode.fill(...)` / `rememberFillLightNode()`
    /// (`sceneview/src/main/java/io/github/sceneview/SceneFactories.kt`). Pairs with
    /// ``directional(color:intensity:castsShadow:)`` (the main / key light) to produce
    /// the soft, balanced lighting that matches RealityKit's default 2-light look:
    /// - Default `intensity` is `3 000` lux — about 30 % of the typical 10 000-lux main
    ///   light (the Android `DEFAULT_FILL_LIGHT_COLOR_INTENSITY`).
    /// - Default `castsShadow` is `false` — only the main light contributes shadows by
    ///   default. Override to `true` if you want soft secondary shadows.
    ///
    /// **Orientation**: this factory does NOT bake in a direction (consistent with the
    /// other factories on `LightNode`). Call ``lookAt(_:)`` or set ``rotation`` after
    /// creation. The canonical Android fill direction is `(0.5, -0.5, 0.5)` (upper-
    /// back-left → down-front-right); the default ``SceneView`` fallback applies that
    /// when this factory is the default fill slot.
    ///
    /// ```swift
    /// // Standalone use:
    /// let fill = LightNode.fill(intensity: 3_000)
    ///     .lookAt(.zero)                           // point toward scene centre
    /// scene.add(fill.entity)
    ///
    /// // With SceneView (closes Android parity):
    /// SceneView { /* ... */ }
    ///   .fillLight(.custom(LightNode.fill()))     // explicit
    ///   // OR
    ///   .fillLight(nil)                            // disable for single-light look
    /// ```
    ///
    /// - Parameters:
    ///   - color: Light color. Default neutral white (≈ 6500 K).
    ///   - intensity: Luminous intensity in lux. Default `3_000`.
    ///   - castsShadow: Whether the fill light casts shadows. Default `false`.
    /// - Returns: A configured ``LightNode``.
    public static func fill(
        color: LightNode.Color = .white,
        intensity: Float = 3_000,
        castsShadow: Bool = false
    ) -> LightNode {
        let light = DirectionalLight()
        // The `isRealWorldProxy:` initializer is `@available(visionOS, unavailable)`
        // — that parameter models a light standing in for real-world illumination
        // in passthrough AR, which has no analogue on a virtual visionOS scene.
        // visionOS gets the plain `init(color:intensity:)` (equivalent to
        // `isRealWorldProxy: false`).
        #if os(visionOS)
        light.light = DirectionalLightComponent(
            color: color.platformColor,
            intensity: intensity
        )
        #else
        light.light = DirectionalLightComponent(
            color: color.platformColor,
            intensity: intensity,
            isRealWorldProxy: false
        )
        #endif
        if castsShadow {
            light.shadow = DirectionalLightComponent.Shadow(
                maximumDistance: 8,
                depthBias: 5.0
            )
        }
        return LightNode(entity: light)
    }

    /// Creates a point light (omni-directional).
    ///
    /// - Parameters:
    ///   - color: Light color.
    ///   - intensity: Luminous intensity in lumens.
    ///   - attenuationRadius: Maximum influence distance in meters.
    /// - Returns: A configured `LightNode`.
    public static func point(
        color: LightNode.Color = .white,
        intensity: Float = 1000,
        attenuationRadius: Float = 10.0
    ) -> LightNode {
        let light = PointLight()
        light.light = PointLightComponent(
            color: color.platformColor,
            intensity: intensity,
            attenuationRadius: attenuationRadius
        )
        return LightNode(entity: light)
    }

    /// Creates a spot light.
    ///
    /// - Parameters:
    ///   - color: Light color.
    ///   - intensity: Luminous intensity in lumens.
    ///   - innerAngle: Inner cone angle in radians.
    ///   - outerAngle: Outer cone angle in radians.
    ///   - attenuationRadius: Maximum influence distance in meters.
    /// - Returns: A configured `LightNode`.
    public static func spot(
        color: LightNode.Color = .white,
        intensity: Float = 1000,
        innerAngle: Float = .pi / 6,
        outerAngle: Float = .pi / 4,
        attenuationRadius: Float = 10.0
    ) -> LightNode {
        let light = SpotLight()
        light.light = SpotLightComponent(
            color: color.platformColor,
            intensity: intensity,
            innerAngleInDegrees: innerAngle * 180 / .pi,
            outerAngleInDegrees: outerAngle * 180 / .pi,
            attenuationRadius: attenuationRadius
        )
        return LightNode(entity: light)
    }

    /// Returns a copy positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> LightNode {
        entity.position = position
        return self
    }

    /// Points the light toward a target position.
    @discardableResult
    public func lookAt(_ target: SIMD3<Float>) -> LightNode {
        entity.look(at: target, from: entity.position, relativeTo: nil)
        return self
    }

    // MARK: - Shadow configuration

    /// Enables or disables shadow casting for this light.
    ///
    /// For directional lights, adds/removes a `DirectionalLightComponent.Shadow`.
    /// Point and spot lights do not support shadows in RealityKit.
    @discardableResult
    public func castsShadow(_ enabled: Bool) -> LightNode {
        if let directional = entity as? DirectionalLight {
            if enabled {
                if directional.shadow == nil {
                    directional.shadow = DirectionalLightComponent.Shadow(
                        maximumDistance: 8,
                        depthBias: 5.0
                    )
                }
            } else {
                // Caller intent: disable shadow. Sets the convenience property to
                // nil — note that on iOS 18 / RealityKit 4 the wrapper class
                // [`DirectionalLight`] caches the shadow internally and
                // `directional.shadow` may read back non-nil even after this
                // assignment (#883). The shadow render-side IS disabled though,
                // and this is the only API Apple ships for the runtime toggle —
                // tests that assert the read-back nil are XCTSkipped pending an
                // SDK fix.
                directional.shadow = nil
            }
        }
        return self
    }

    /// Configures a shadow for directional lights.
    ///
    /// - Important: **The `color` parameter is ignored on iOS.** RealityKit's
    ///   `DirectionalLightComponent.Shadow` does not expose a color property as of Xcode 26.x.
    ///   This method ensures a shadow is configured if not already present, but the color value
    ///   is discarded. Kept for Android API parity. Use `castsShadow(_:)` to enable/disable
    ///   shadows; use `shadowMaximumDistance(_:)` to tune shadow range.
    ///
    /// - Parameter color: Reserved for future use. **Ignored.**
    @available(*, deprecated, message: "Color parameter ignored on iOS — RealityKit's DirectionalLightComponent.Shadow does not support shadow color. Use castsShadow(_:) and shadowMaximumDistance(_:) instead.")
    @discardableResult
    public func shadowColor(_ color: LightNode.Color) -> LightNode {
        if let directional = entity as? DirectionalLight {
            if directional.shadow == nil {
                directional.shadow = DirectionalLightComponent.Shadow(
                    maximumDistance: 8,
                    depthBias: 5.0
                )
            }
            // Note: DirectionalLightComponent.Shadow does not have a color property
        }
        return self
    }

    /// Sets the maximum shadow rendering distance for directional lights.
    ///
    /// - Parameter distance: Maximum distance in meters at which shadows are rendered.
    @discardableResult
    public func shadowMaximumDistance(_ distance: Float) -> LightNode {
        if let directional = entity as? DirectionalLight {
            if directional.shadow == nil {
                directional.shadow = DirectionalLightComponent.Shadow(
                    maximumDistance: distance,
                    depthBias: 5.0
                )
            } else {
                // Re-create shadow with new distance to avoid deprecated property setter
                directional.shadow = DirectionalLightComponent.Shadow(
                    maximumDistance: distance,
                    depthBias: directional.shadow?.depthBias ?? 5.0
                )
            }
        }
        return self
    }

    // MARK: - Attenuation

    /// Sets the attenuation radius for point or spot lights.
    ///
    /// Controls how far the light's influence reaches. Has no effect on directional lights.
    ///
    /// - Parameter radius: Maximum influence distance in meters.
    @discardableResult
    public func attenuationRadius(_ radius: Float) -> LightNode {
        if let point = entity as? PointLight {
            point.light.attenuationRadius = radius
        } else if let spot = entity as? SpotLight {
            spot.light.attenuationRadius = radius
        }
        return self
    }

    /// Simple color representation for lights.
    public enum Color: Sendable {
        case white
        case warm      // ~3200K warm tungsten
        case cool      // ~6500K daylight
        case custom(r: Float, g: Float, b: Float)

        #if canImport(UIKit)
        var platformColor: UIColor {
            switch self {
            case .white:
                return .white
            case .warm:
                return UIColor(red: 1.0, green: 0.87, blue: 0.68, alpha: 1.0)
            case .cool:
                return UIColor(red: 0.79, green: 0.88, blue: 1.0, alpha: 1.0)
            case .custom(let r, let g, let b):
                return UIColor(
                    red: CGFloat(r), green: CGFloat(g),
                    blue: CGFloat(b), alpha: 1.0
                )
            }
        }
        #elseif canImport(AppKit)
        var platformColor: NSColor {
            switch self {
            case .white:
                return .white
            case .warm:
                return NSColor(red: 1.0, green: 0.87, blue: 0.68, alpha: 1.0)
            case .cool:
                return NSColor(red: 0.79, green: 0.88, blue: 1.0, alpha: 1.0)
            case .custom(let r, let g, let b):
                return NSColor(
                    red: CGFloat(r), green: CGFloat(g),
                    blue: CGFloat(b), alpha: 1.0
                )
            }
        }
        #endif
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
