#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// A node that drives a directional light based on time-of-day, simulating
/// sun movement across the sky with atmospheric color temperature changes.
///
/// Mirrors SceneView Android's `DynamicSkyNode` — places a directional light
/// whose direction, color, and intensity update based on the hour of day.
/// The sun rises in the east at 06:00, reaches zenith at noon, and sets
/// in the west at 18:00.
///
/// ### Color model
/// | Time range          | Color                                  |
/// |---------------------|----------------------------------------|
/// | Night (< 6, > 18)  | near-black (0.02, 0.02, 0.06)         |
/// | Sunrise / sunset    | warm orange-red (lerp from noon color) |
/// | Midday              | near-white (1.0, 0.98, 0.95)          |
///
/// ```swift
/// @State private var hour: Float = 12
///
/// SceneView { content in
///     let sky = DynamicSkyNode.noon()
///     content.add(sky.entity)
/// }
/// ```
public struct DynamicSkyNode: Sendable {
    /// The underlying RealityKit entity holding the directional light.
    public let entity: Entity

    /// Current time of day in hours [0, 24).
    public private(set) var timeOfDay: Float

    /// Maximum sun illuminance in lux at solar noon.
    public private(set) var sunIntensity: Float

    /// Atmospheric turbidity [1, 10]. Higher values produce warmer horizon colors.
    public private(set) var turbidity: Float

    // MARK: - Initializer

    /// Creates a dynamic sky node for the given time of day.
    ///
    /// - Parameters:
    ///   - timeOfDay: Hour of day in [0, 24). 0 = midnight, 6 = sunrise,
    ///                12 = noon, 18 = sunset. Default 12 (noon).
    ///   - turbidity: Atmospheric haze factor [1, 10]. Default 2.
    ///   - sunIntensity: Maximum illuminance in lux at solar noon. Default 1000.
    ///   - castsShadow: Whether the sun casts shadows. Default true.
    public init(
        timeOfDay: Float = 12,
        turbidity: Float = 2,
        sunIntensity: Float = 1000,
        castsShadow: Bool = true
    ) {
        self.timeOfDay = timeOfDay.truncatingRemainder(dividingBy: 24)
        self.turbidity = turbidity.clamped(to: 1...10)
        self.sunIntensity = sunIntensity

        let light = DirectionalLight()
        self.entity = light

        // Apply initial state
        let state = DynamicSkyNode.computeSunState(
            timeOfDay: self.timeOfDay,
            turbidity: self.turbidity,
            sunIntensity: self.sunIntensity
        )
        DynamicSkyNode.applyState(state, to: light, castsShadow: castsShadow)
    }

    // MARK: - Presets

    /// Creates a sunrise sky (06:00).
    public static func sunrise(
        turbidity: Float = 2,
        sunIntensity: Float = 1000,
        castsShadow: Bool = true
    ) -> DynamicSkyNode {
        DynamicSkyNode(
            timeOfDay: 6,
            turbidity: turbidity,
            sunIntensity: sunIntensity,
            castsShadow: castsShadow
        )
    }

    /// Creates a noon sky (12:00).
    public static func noon(
        turbidity: Float = 2,
        sunIntensity: Float = 1000,
        castsShadow: Bool = true
    ) -> DynamicSkyNode {
        DynamicSkyNode(
            timeOfDay: 12,
            turbidity: turbidity,
            sunIntensity: sunIntensity,
            castsShadow: castsShadow
        )
    }

    /// Creates a sunset sky (18:00).
    public static func sunset(
        turbidity: Float = 2,
        sunIntensity: Float = 1000,
        castsShadow: Bool = true
    ) -> DynamicSkyNode {
        DynamicSkyNode(
            timeOfDay: 18,
            turbidity: turbidity,
            sunIntensity: sunIntensity,
            castsShadow: castsShadow
        )
    }

    /// Creates a night sky (0:00).
    public static func night(
        turbidity: Float = 2,
        sunIntensity: Float = 1000,
        castsShadow: Bool = true
    ) -> DynamicSkyNode {
        DynamicSkyNode(
            timeOfDay: 0,
            turbidity: turbidity,
            sunIntensity: sunIntensity,
            castsShadow: castsShadow
        )
    }

    // MARK: - Builder methods

    /// Returns self with the time of day updated.
    @discardableResult
    public func time(_ timeOfDay: Float) -> DynamicSkyNode {
        var copy = self
        copy.timeOfDay = timeOfDay.truncatingRemainder(dividingBy: 24)
        copy.applyCurrentState()
        return copy
    }

    /// Returns self with the sun intensity updated.
    @discardableResult
    public func intensity(_ sunIntensity: Float) -> DynamicSkyNode {
        var copy = self
        copy.sunIntensity = sunIntensity
        copy.applyCurrentState()
        return copy
    }

    /// Returns self positioned at the given coordinates.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> DynamicSkyNode {
        entity.position = position
        return self
    }

    // MARK: - Computed properties

    /// The current sun direction as a unit vector.
    public var sunDirection: SIMD3<Float> {
        DynamicSkyNode.computeSunState(
            timeOfDay: timeOfDay,
            turbidity: turbidity,
            sunIntensity: sunIntensity
        ).direction
    }

    /// The current sun color as RGB components in [0, 1].
    public var sunColor: SIMD3<Float> {
        DynamicSkyNode.computeSunState(
            timeOfDay: timeOfDay,
            turbidity: turbidity,
            sunIntensity: sunIntensity
        ).color
    }

    /// The current effective intensity (scaled by elevation).
    public var effectiveIntensity: Float {
        DynamicSkyNode.computeSunState(
            timeOfDay: timeOfDay,
            turbidity: turbidity,
            sunIntensity: sunIntensity
        ).intensity
    }

    /// Whether the sun is above the horizon (between 06:00 and 18:00).
    public var isDaytime: Bool {
        timeOfDay >= 6 && timeOfDay <= 18
    }

    // MARK: - Sun computation

    /// Intermediate state from sun position calculations.
    internal struct SunState {
        let direction: SIMD3<Float>
        let color: SIMD3<Float>
        let intensity: Float
        let elevation: Float
    }

    /// Compute sun direction, color, and intensity for a given time.
    ///
    /// Maps timeOfDay to a sine-based elevation:
    /// - 6h: sun on the eastern horizon (elevation = 0)
    /// - 12h: sun overhead (elevation = 1)
    /// - 18h: sun on the western horizon (elevation = 0)
    /// - Outside [6, 18]: below the horizon (elevation < 0)
    internal static func computeSunState(
        timeOfDay: Float,
        turbidity: Float,
        sunIntensity: Float
    ) -> SunState {
        // Sun elevation angle
        let hourAngle = ((timeOfDay - 6) / 12) * Float.pi
        let elevation = sin(hourAngle).clamped(to: -1...1)
        let azimuthCos = -cos(hourAngle)

        // Direction toward the sun, normalized
        let dirX = azimuthCos * 0.6
        let dirY = elevation
        let dirZ: Float = -0.5
        let len = max(sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ), 1e-6)
        let direction = SIMD3<Float>(dirX / len, dirY / len, dirZ / len)

        // Color
        let color = computeSunColor(elevation: elevation, turbidity: turbidity)

        // Intensity: scale by elevation, no direct light below horizon
        let effectiveIntensity = sunIntensity * max(0, elevation)

        return SunState(
            direction: direction,
            color: color,
            intensity: effectiveIntensity,
            elevation: elevation
        )
    }

    /// Compute sun color for a given elevation and turbidity.
    ///
    /// - elevation <= 0: night (faint blue tint)
    /// - elevation near 0: warm orange-red horizon
    /// - elevation = 1: near-white noon
    private static func computeSunColor(
        elevation: Float,
        turbidity: Float
    ) -> SIMD3<Float> {
        if elevation <= 0 {
            return SIMD3<Float>(0.02, 0.02, 0.06)
        }

        let horizonFactor = (1 - elevation) * (1 - elevation)
        let turbidityBoost = ((turbidity - 1) / 9).clamped(to: 0...1)

        // Warm sunrise/sunset color
        let warmR: Float = 1.0
        let warmG: Float = 0.45 + 0.05 * turbidityBoost
        let warmB: Float = 0.20 - 0.10 * turbidityBoost

        // Noon color (near-white, slightly warm)
        let noonR: Float = 1.0
        let noonG: Float = 0.98
        let noonB: Float = 0.95

        return SIMD3<Float>(
            noonR + (warmR - noonR) * horizonFactor,
            noonG + (warmG - noonG) * horizonFactor,
            noonB + (warmB - noonB) * horizonFactor
        )
    }

    /// Apply computed state to the directional light entity.
    private static func applyState(
        _ state: SunState,
        to light: DirectionalLight,
        castsShadow: Bool
    ) {
        #if canImport(UIKit)
        let color = UIColor(
            red: CGFloat(state.color.x),
            green: CGFloat(state.color.y),
            blue: CGFloat(state.color.z),
            alpha: 1.0
        )
        #elseif canImport(AppKit)
        let color = NSColor(
            red: CGFloat(state.color.x),
            green: CGFloat(state.color.y),
            blue: CGFloat(state.color.z),
            alpha: 1.0
        )
        #endif

        // `isRealWorldProxy:` is `@available(visionOS, unavailable)` — visionOS
        // uses the plain `init(color:intensity:)` (equivalent to `false`).
        #if os(visionOS)
        light.light = DirectionalLightComponent(
            color: color,
            intensity: state.intensity
        )
        #else
        light.light = DirectionalLightComponent(
            color: color,
            intensity: state.intensity,
            isRealWorldProxy: false
        )
        #endif
        if castsShadow {
            light.shadow = DirectionalLightComponent.Shadow(
                maximumDistance: 8,
                depthBias: 5.0
            )
        }

        // Orient the light to point in the sun direction
        let target = light.position + state.direction
        light.look(at: target, from: light.position, relativeTo: nil)
    }

    /// Re-apply current state to the entity.
    private func applyCurrentState() {
        let state = DynamicSkyNode.computeSunState(
            timeOfDay: timeOfDay,
            turbidity: turbidity,
            sunIntensity: sunIntensity
        )
        guard let light = entity as? DirectionalLight else { return }
        DynamicSkyNode.applyState(state, to: light, castsShadow: true)
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
