import Foundation

/// How big ``ARPlacementController`` makes a model at 100 % — the size the pinch's 100 % detent,
/// ``ARPlacementController/scale(to:)`` with `1` and ``ARPlacementController/baseSize`` refer to.
///
/// Kotlin twin: `io.github.sceneview.ar.PlacementSize` (`RealWorld`, `FitInto(meters)`).
///
/// ```swift
/// // A measured product: a 1.8 m figure stands 1.8 m tall.
/// controller.setModel(node.entity, ticket: ticket, size: .realWorld)
/// // An asset whose units are unknown: its longest side becomes 30 cm.
/// controller.setModel(node.entity, ticket: ticket, size: .fitInto(meters: 0.3))
/// ```
///
/// Either way the model's bottom centre rests on the surface contact point, and pinch scaling
/// happens about that point, so the model never sinks into or floats above the floor.
public enum PlacementSize: Equatable, Sendable {
    /// The asset's authored size: USDZ and glTF are in metres, so nothing is rescaled and a 1 m
    /// cube is 1 m. Read ``ARPlacementController/isAtRealWorldSize`` to tell the user when what
    /// they see is the object's true size.
    case realWorld

    /// Scales the model uniformly so its longest side measures `meters` — a preview for assets
    /// whose units are unknown or not metres. The fit is measured on the asset as loaded, before
    /// placement. On iOS the longest side is measured on the entity's world bounds as loaded, after
    /// its own rotation; Android measures before its `assetRotation`. `meters` must be finite and
    /// greater than zero, or
    /// ``ARPlacementController/setModel(_:ticket:size:)`` refuses the model.
    case fitInto(meters: Float)

    /// The 30 cm longest-side preview ``ARPlacementController/setModel(_:ticket:previewSize:)``
    /// uses by default — the same default as Android's `scaleToUnits = 0.3f`. A Swift
    /// convenience: Android has no `PlacementSize.preview` and spells it `FitInto(0.3f)`.
    public static let preview = PlacementSize.fitInto(meters: 0.3)

    /// Whether this size can be applied: always for ``realWorld``; for ``fitInto(meters:)``,
    /// only with a finite, positive target.
    public var isValid: Bool {
        switch self {
        case .realWorld: return true
        case .fitInto(let meters): return meters.isFinite && meters > 0
        }
    }

    /// The uniform factor that brings an asset whose longest side measures `longestSide` metres to
    /// this size: `1` for ``realWorld``, `meters / longestSide` for ``fitInto(meters:)``.
    /// `nil` for an invalid size or an asset with no usable extent.
    func scaleFactor(longestSide: Float) -> Float? {
        guard isValid, longestSide.isFinite, longestSide > 0 else { return nil }
        switch self {
        case .realWorld: return 1
        case .fitInto(let meters):
            let factor = meters / longestSide
            return factor.isFinite && factor > 0 ? factor : nil
        }
    }

    /// The `previewSize` value of the older ``ARPlacementController/setModel(_:ticket:previewSize:)``
    /// spelling: `nil` keeps authored units.
    init(previewSize: Float?) {
        self = previewSize.map { .fitInto(meters: $0) } ?? .realWorld
    }
}
