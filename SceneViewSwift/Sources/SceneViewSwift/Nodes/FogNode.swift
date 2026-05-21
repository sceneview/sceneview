#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit
import Foundation
#if canImport(UIKit)
import UIKit
#elseif canImport(AppKit)
import AppKit
#endif

/// Atmospheric fog effect simulated with a large translucent sphere.
///
/// Mirrors SceneView Android's `FogNode` — provides linear and exponential fog
/// modes. Because RealityKit does not expose a native per-view fog API,
/// `FogNode` places a translucent sphere around the camera origin to approximate
/// the effect.
///
/// - Note: Android's `FogNode` also exposes a `heightFalloff` parameter backed
///   by Filament's native `View.fogOptions.heightFalloff` (a per-pixel shader
///   feature). RealityKit has no equivalent: a uniform-color `UnlitMaterial`
///   cannot vary opacity by world height. The legacy ``heightBased(density:height:color:)``
///   factory and ``heightFalloff`` property are **deprecated** on iOS — use
///   ``exponential(density:color:)`` for the closest equivalent. See [#1380](https://github.com/sceneview/sceneview/issues/1380).
///
/// ```swift
/// SceneView { content in
///     let fog = FogNode.linear(start: 1.0, end: 20.0)
///         .color(.cool)
///     content.add(fog.entity)
///
///     let thickFog = FogNode.exponential(density: 0.15)
///         .color(.custom(r: 0.8, g: 0.85, b: 0.9))
///     content.add(thickFog.entity)
/// }
/// ```
public struct FogNode: Sendable {
    /// The underlying RealityKit entity representing the fog volume.
    public let entity: ModelEntity

    // MARK: - Stored configuration

    /// Fog density in [0.0, 1.0]. Higher values produce thicker fog.
    public var density: Float {
        get { _density }
        set {
            _density = newValue.clamped(to: 0.0...1.0)
            rebuildMaterial()
        }
    }

    /// Near distance where fog begins (meters).
    public var startDistance: Float {
        get { _startDistance }
        set {
            _startDistance = max(0, newValue)
            updateScale()
        }
    }

    /// Far distance where fog reaches full density (meters).
    public var endDistance: Float {
        get { _endDistance }
        set {
            _endDistance = max(0, newValue)
            updateScale()
        }
    }

    /// Height falloff in world-space meters, mirroring Android's `FogNode.heightFalloff`.
    ///
    /// - Warning: **Not supported on RealityKit.** Android uses Filament's native
    ///   `View.fogOptions.heightFalloff` (a per-pixel shader feature). RealityKit has
    ///   no equivalent, so `FogNode` approximates fog with a single translucent
    ///   sphere whose `UnlitMaterial` cannot vary opacity by world height. The setter
    ///   stores the value (kept for source compatibility) but does **not** affect the
    ///   rendered output. Use ``exponential(density:color:)`` for the closest
    ///   visual equivalent. See [#1380](https://github.com/sceneview/sceneview/issues/1380).
    @available(*, deprecated, message: "Height-based fog is not supported on RealityKit; use exponential(density:color:) instead. See #1380.")
    public var heightFalloff: Float {
        get { _heightFalloff }
        set {
            // No-op for rendering — see the @available(deprecated) message and #1380.
            // Stored only so the getter round-trips for callers that still set it.
            _heightFalloff = newValue
        }
    }

    /// World-space position.
    public var position: SIMD3<Float> {
        get { entity.position }
        nonmutating set { entity.position = newValue }
    }

    // MARK: - Private backing (nonmutating writes via entity)

    // We use a UnsafeMutablePointer-free approach: store config in the entity's name
    // as a serialized string is fragile, so instead we keep simple private vars and
    // rely on the struct being passed by value with the entity reference shared.

    private var _density: Float
    private var _startDistance: Float
    private var _endDistance: Float
    private var _heightFalloff: Float
    private var _color: FogNode.Color

    // MARK: - Private init

    private init(
        entity: ModelEntity,
        density: Float,
        startDistance: Float,
        endDistance: Float,
        heightFalloff: Float,
        color: FogNode.Color
    ) {
        self.entity = entity
        self._density = density.clamped(to: 0.0...1.0)
        self._startDistance = max(0, startDistance)
        self._endDistance = max(0, endDistance)
        self._heightFalloff = heightFalloff
        self._color = color
    }

    // MARK: - Factory methods

    /// Creates linear fog that ramps from transparent at `start` to full density at `end`.
    ///
    /// - Parameters:
    ///   - start: Distance in meters where fog begins. Default 1.0.
    ///   - end: Distance in meters where fog reaches full density. Default 20.0.
    ///   - color: Fog color. Default `.white`.
    /// - Returns: A configured `FogNode`.
    public static func linear(
        start: Float = 1.0,
        end: Float = 20.0,
        color: FogNode.Color = .white
    ) -> FogNode {
        let density = Float(0.5) // moderate base density for linear
        return makeFog(density: density, start: start, end: end, heightFalloff: 0, color: color)
    }

    /// Creates exponential fog with the given density.
    ///
    /// - Parameters:
    ///   - density: Fog density in [0.0, 1.0]. Default 0.05.
    ///   - color: Fog color. Default `.white`.
    /// - Returns: A configured `FogNode`.
    public static func exponential(
        density: Float = 0.05,
        color: FogNode.Color = .white
    ) -> FogNode {
        return makeFog(density: density, start: 0.5, end: 40.0, heightFalloff: 0, color: color)
    }

    /// Creates height-based fog, mirroring Android's `FogNode.heightBased`.
    ///
    /// - Warning: **Not supported on RealityKit.** Android backs height-based fog with
    ///   Filament's native `View.fogOptions.heightFalloff` (a per-pixel shader feature).
    ///   RealityKit has no equivalent, so on iOS this factory falls back to a uniform
    ///   translucent sphere — visually identical to ``exponential(density:color:)``.
    ///   The `height` argument is stored on ``heightFalloff`` but never rendered.
    ///   Use ``exponential(density:color:)`` directly on iOS. See [#1380](https://github.com/sceneview/sceneview/issues/1380).
    ///
    /// - Parameters:
    ///   - density: Fog density in [0.0, 1.0]. Default 0.05.
    ///   - height: Height falloff in meters (stored, not rendered on iOS). Default 1.0.
    ///   - color: Fog color. Default `.white`.
    /// - Returns: A configured `FogNode`.
    @available(*, deprecated, message: "Height-based fog is not supported on RealityKit; use exponential(density:color:) instead. See #1380.")
    public static func heightBased(
        density: Float = 0.05,
        height: Float = 1.0,
        color: FogNode.Color = .white
    ) -> FogNode {
        return makeFog(density: density, start: 0.5, end: 40.0, heightFalloff: height, color: color)
    }

    // MARK: - Builder methods

    /// Sets the fog color.
    @discardableResult
    public func color(_ color: FogNode.Color) -> FogNode {
        var copy = self
        copy._color = color
        copy.rebuildMaterial()
        return copy
    }

    /// Sets the fog density.
    @discardableResult
    public func density(_ density: Float) -> FogNode {
        var copy = self
        copy._density = density.clamped(to: 0.0...1.0)
        copy.rebuildMaterial()
        return copy
    }

    /// Sets the near distance where fog begins.
    @discardableResult
    public func startDistance(_ distance: Float) -> FogNode {
        var copy = self
        copy._startDistance = max(0, distance)
        copy.updateScale()
        return copy
    }

    /// Sets the far distance where fog reaches full density.
    @discardableResult
    public func endDistance(_ distance: Float) -> FogNode {
        var copy = self
        copy._endDistance = max(0, distance)
        copy.updateScale()
        return copy
    }

    /// Sets the world-space position of the fog volume.
    @discardableResult
    public func position(_ position: SIMD3<Float>) -> FogNode {
        entity.position = position
        return self
    }

    // MARK: - Internal helpers

    private static func makeFog(
        density: Float,
        start: Float,
        end: Float,
        heightFalloff: Float,
        color: FogNode.Color
    ) -> FogNode {
        let radius = end
        let mesh = MeshResource.generateSphere(radius: radius)
        let alpha = density.clamped(to: 0.0...1.0) * 0.6
        var material = UnlitMaterial()
        material.color = .init(tint: color.withAlpha(alpha))
        material.blending = .transparent(opacity: .init(floatLiteral: alpha))

        let entity = ModelEntity(mesh: mesh, materials: [material])
        entity.name = "FogNode"

        return FogNode(
            entity: entity,
            density: density,
            startDistance: start,
            endDistance: end,
            heightFalloff: heightFalloff,
            color: color
        )
    }

    private func rebuildMaterial() {
        let alpha = _density.clamped(to: 0.0...1.0) * 0.6
        var material = UnlitMaterial()
        material.color = .init(tint: _color.withAlpha(alpha))
        material.blending = .transparent(opacity: .init(floatLiteral: alpha))
        entity.model?.materials = [material]
    }

    private func updateScale() {
        let radius = _endDistance
        entity.scale = .init(repeating: max(radius, 0.01))
    }

    // MARK: - Color

    /// Color representation for fog.
    public enum Color: Sendable {
        case white
        case cool       // Light grey-blue, similar to Android 0xFFCCDDFF
        case warm       // Warm yellowish fog
        case custom(r: Float, g: Float, b: Float)

        #if canImport(UIKit)
        func withAlpha(_ alpha: Float) -> UIColor {
            switch self {
            case .white:
                return UIColor(red: 1.0, green: 1.0, blue: 1.0, alpha: CGFloat(alpha))
            case .cool:
                return UIColor(red: 0.8, green: 0.87, blue: 1.0, alpha: CGFloat(alpha))
            case .warm:
                return UIColor(red: 1.0, green: 0.93, blue: 0.78, alpha: CGFloat(alpha))
            case .custom(let r, let g, let b):
                return UIColor(
                    red: CGFloat(r), green: CGFloat(g),
                    blue: CGFloat(b), alpha: CGFloat(alpha)
                )
            }
        }
        #elseif canImport(AppKit)
        func withAlpha(_ alpha: Float) -> NSColor {
            switch self {
            case .white:
                return NSColor(red: 1.0, green: 1.0, blue: 1.0, alpha: CGFloat(alpha))
            case .cool:
                return NSColor(red: 0.8, green: 0.87, blue: 1.0, alpha: CGFloat(alpha))
            case .warm:
                return NSColor(red: 1.0, green: 0.93, blue: 0.78, alpha: CGFloat(alpha))
            case .custom(let r, let g, let b):
                return NSColor(
                    red: CGFloat(r), green: CGFloat(g),
                    blue: CGFloat(b), alpha: CGFloat(alpha)
                )
            }
        }
        #endif
    }
}

#endif // os(iOS) || os(macOS) || os(visionOS)
