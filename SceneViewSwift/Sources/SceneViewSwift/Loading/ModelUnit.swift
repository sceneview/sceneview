import Foundation

/// The length unit a model file's coordinates are expressed in.
///
/// RealityKit works in **metres**: a `ModelEntity` whose mesh spans `0.2` along X is
/// 20 cm wide in AR. Most mesh interchange formats do not carry a unit at all — an STL
/// exported by a slicer is a pile of numbers that happen to mean millimetres, and the
/// exact same numbers in an OBJ from a photogrammetry pipeline mean metres. Loading
/// either one without saying which is meant produces a model that is 1000× off, which
/// is why every loader entry point in SceneViewSwift takes a `ModelUnit`.
///
/// ```swift
/// // A 3D-printing STL: coordinates are millimetres (the format's default).
/// let part = try await ModelNode.load(contentsOf: stlURL)
///
/// // A photogrammetry OBJ authored in centimetres.
/// let scan = try await ModelNode.load(contentsOf: objURL, unit: .centimeters)
/// ```
///
/// Formats that *do* carry a unit — 3MF (`<model unit="millimeter">`) and USD
/// (`metersPerUnit`) — read it from the file, and an explicit `unit:` argument
/// overrides it.
///
/// - SeeAlso: ``ModelFormat/defaultUnit``
public enum ModelUnit: String, Sendable, CaseIterable, Codable {
    /// 1 µm = 0.000001 m. The 3MF core spec's `micron`.
    case micrometers
    /// 1 mm = 0.001 m. The de-facto unit of STL and of every slicer.
    case millimeters
    /// 1 cm = 0.01 m.
    case centimeters
    /// 1 in = 0.0254 m.
    case inches
    /// 1 ft = 0.3048 m.
    case feet
    /// The RealityKit unit — coordinates are used as-is.
    case meters

    /// How many metres one unit is worth. Multiply a source coordinate by this to get
    /// the RealityKit coordinate.
    public var metersPerUnit: Float {
        switch self {
        case .micrometers: return 0.000_001
        case .millimeters: return 0.001
        case .centimeters: return 0.01
        case .inches: return 0.0254
        case .feet: return 0.3048
        case .meters: return 1
        }
    }

    /// Parses the value of the 3MF core spec's `<model unit="…">` attribute.
    ///
    /// The spec (3MF Core 1.4, §3.2) allows exactly `micron`, `millimeter`, `centimeter`,
    /// `inch`, `foot`, `meter`, and says a missing attribute means `millimeter`. Anything
    /// else returns `nil` so the caller can decide between "reject the file" and "fall
    /// back to the spec default"; ``ThreeMFDocument`` chooses the latter, matching what
    /// slicers do with a mildly malformed file.
    ///
    /// - Parameter threeMFUnit: The raw attribute value, case-insensitive.
    public init?(threeMFUnit: String) {
        switch threeMFUnit.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "micron": self = .micrometers
        case "millimeter": self = .millimeters
        case "centimeter": self = .centimeters
        case "inch": self = .inches
        case "foot": self = .feet
        case "meter": self = .meters
        default: return nil
        }
    }

    /// The unit closest to a USD `metersPerUnit` value.
    ///
    /// USD stores a scale factor rather than a named unit, so this snaps to the nearest
    /// named case within a 1 % relative tolerance and returns `nil` for anything else
    /// (a USD authored at, say, 0.3 m/unit has no name here — scale it yourself).
    public init?(metersPerUnit: Float) {
        guard metersPerUnit.isFinite, metersPerUnit > 0 else { return nil }
        for candidate in ModelUnit.allCases
        where abs(candidate.metersPerUnit - metersPerUnit) <= candidate.metersPerUnit * 0.01 {
            self = candidate
            return
        }
        return nil
    }

    /// A short human-readable symbol — `"mm"`, `"cm"`, `"in"`, `"ft"`, `"m"`, `"µm"`.
    ///
    /// Meant for a unit picker in a viewer UI ("this STL has no unit — is it mm or in?"),
    /// which is the interaction every honest STL viewer has to offer.
    public var symbol: String {
        switch self {
        case .micrometers: return "µm"
        case .millimeters: return "mm"
        case .centimeters: return "cm"
        case .inches: return "in"
        case .feet: return "ft"
        case .meters: return "m"
        }
    }

    /// Converts a length from this unit to `other`.
    public func convert(_ value: Float, to other: ModelUnit) -> Float {
        value * metersPerUnit / other.metersPerUnit
    }
}
