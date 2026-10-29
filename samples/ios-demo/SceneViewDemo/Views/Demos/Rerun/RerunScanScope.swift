import Foundation
import simd

// What a 3D Scan captures (#4325), the iOS twin of Android's `ScanScope.kt`: one object, one
// room, or an outdoor area. The choice is asked first, remembered, and every part of the capture
// that depends on it reads it from here: the coaching, the photo spacing and how far from the
// phone the points and the surfaces are kept. Nothing in a scope is a label only — each field is
// passed to the recorder (``RerunCaptureRecorder/Configuration``) or to the replay.
//
// What iOS does not have, and so does not pretend to:
// - **A whole home** (Android's `Home`): room by room, with a plan of each room and their total.
//   iOS has no floor plan yet, so the scope is not offered; a file Android made of one reads as
//   a room scan.
// - **A floor plan** for a room: iOS rebuilds the room's surfaces and points, not a dimensioned
//   plan, so the Room card says what iOS gives.
// - **The model's voxel** (Android's `modelVoxelM`): iOS does not mesh a TSDF model from depth.

/// What is being scanned. The raw value is what the choice is remembered under, and the
/// manifest's top-level `scope` key — the same keys Android writes.
enum RerunScanScope: String, CaseIterable, Sendable {
    /// A single item on a table or the floor: a close orbit, kept to arm's length.
    case object
    /// One room: walls, floor and ceiling — what 3D Scan always did.
    case room
    /// Android's room-by-room home. Never offered on iOS (no floor plan); read as a room.
    case home
    /// Outside: a garden, a yard, a terrace. The ground and what stands on it.
    case area

    /// The scopes the chooser offers on iOS, in Android's order.
    static let offered: [RerunScanScope] = [.object, .room, .area]

    /// The scope asked first when nothing was chosen before.
    static let `default` = RerunScanScope.room

    /// The scope remembered under `key`; ``default`` for none, an unknown key, or one iOS does not
    /// offer.
    static func from(key: String?) -> RerunScanScope {
        guard let scope = key.flatMap(RerunScanScope.init(rawValue:)), offered.contains(scope) else { return .default }
        return scope
    }

    /// The scope a capture manifest says it was taken in; ``room`` for a manifest without the key —
    /// every scan written before it was — and for Android's whole home, scanned as rooms.
    static func of(manifest: Data) -> RerunScanScope {
        guard let root = (try? JSONSerialization.jsonObject(with: manifest)) as? [String: Any] else { return .room }
        let scope = (root["scope"] as? String).flatMap(RerunScanScope.init(rawValue:)) ?? .room
        return scope == .home ? .room : scope
    }

    /// Whether the replay maps the ground the capture covered, seen from above (the Map view): rooms
    /// and an outdoor area do; an object stands on a few square centimetres.
    var mapsGround: Bool { self != .object }

    /// Depth further than this from the phone is left out: for an object, the room behind it.
    /// `nil` keeps everything ARKit gives, as a room's scan always did.
    var farM: Float? { self == .object ? 1.5 : nil }

    /// The recorder's settings for this scope: Android's `ScanPreset` where iOS has the setting.
    var configuration: RerunCaptureRecorder.Configuration {
        var configuration = RerunCaptureRecorder.Configuration()
        if self == .object {
            // A photo every 8 cm or 6°: an orbit close to an object needs them denser.
            configuration.keyframeTravel = 0.08
            configuration.keyframeTurnDegrees = 6
        }
        configuration.farM = farM
        // A room's file carries no key, byte for byte as before (Android's `withScope`).
        configuration.scopeKey = self == .room ? nil : rawValue
        return configuration
    }
}

/// The words of the scope choice and of each scope's capture: Android's `ScanScopeCopy`, verbatim
/// where iOS gives the same result.
enum RerunScanScopeCopy {
    static let chooserTitle = "What are you scanning?"
    static let chooserBody = "The guidance and the 3D result are set for what you pick."
    /// Android's `ScanCopy.PRIVACY`, on an iPhone.
    static let privacy = "Saved on your iPhone. Nothing leaves it unless you share it."

    static func title(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "An object"
        case .room: "A room"
        case .home: "A whole home"
        case .area: "An outdoor area"
        }
    }

    static func detail(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "One item on a table or the floor, like a chair or a vase."
        case .room: "One room, with its walls, floor and ceiling."
        case .home: "Several rooms, one after the other, without stopping."
        case .area: "A garden, a yard or a terrace: the ground and what stands on it."
        }
    }

    /// How the scan goes, and what it gives. Room differs from Android: iOS has no floor plan.
    static func how(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "Walk around it at arm's length. Anything further than 1.5 m is left out."
        case .room, .home: "Walk along the walls. You get a 3D model of the room."
        case .area: "Walk slowly across it. You get a 3D model and a map of the ground."
        }
    }

    /// The chooser's primary action.
    static func start(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "Scan an object"
        case .room: "Scan a room"
        case .home: "Scan your home"
        case .area: "Scan an area"
        }
    }

    /// What the capture's status line and the kept session call the scan.
    static func label(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "Object"
        case .room, .home: "Room"
        case .area: "Area"
        }
    }

    /// The coaching line while recording.
    static func scanning(_ scope: RerunScanScope) -> String {
        switch scope {
        case .object: "Circle the object slowly, at arm's length"
        case .room, .home: "Walk slowly along the walls, with some floor in view"
        case .area: "Walk slowly, with the ground in view"
        }
    }

    /// The line before Record: tap Record, then the scope's coaching.
    static func idle(_ scope: RerunScanScope) -> String {
        let line = scanning(scope)
        return "Tap Record, then " + line.prefix(1).lowercased() + line.dropFirst() + ". Tap a surface to place a Shiba."
    }
}

/// The last scope chosen, kept across launches.
enum RerunScanScopeStore {
    static let key = "rerun.scanScope"

    static func last(_ defaults: UserDefaults = .standard) -> RerunScanScope {
        RerunScanScope.from(key: defaults.string(forKey: key))
    }

    static func remember(_ scope: RerunScanScope, _ defaults: UserDefaults = .standard) {
        defaults.set(scope.rawValue, forKey: key)
    }
}

/// The object scope's distance cutoff, Android's `ScanCutoff`: applied to ARKit's feature points
/// and planes as they are recorded, so the live view, the saved file and its replay keep the same.
///
/// - A feature point further than ``farM`` from the phone that saw it is left out.
/// - A plane is cut to the square of half side ``farM`` centred under the middle of the phone's
///   path: circling an object puts that middle on the object, so its floor patch and its table top
///   stay and the walls across the room go. A plane cut to nothing is taken off.
struct RerunScanCutoff: Sendable, Equatable {
    let farM: Float
    private var eye = SIMD3<Float>(repeating: 0)
    private var sumX: Double = 0
    private var sumZ: Double = 0
    private var poses = 0

    init(farM: Float) {
        precondition(farM > 0, "farM \(farM)")
        self.farM = farM
    }

    /// Whether a pose has been seen: before one, nothing is cut.
    var following: Bool { poses > 0 }

    /// The phone stands at `position`: the points that follow are measured from it.
    mutating func follow(_ position: SIMD3<Float>) {
        guard position.x.isFinite, position.y.isFinite, position.z.isFinite else { return }
        eye = position
        sumX += Double(position.x)
        sumZ += Double(position.z)
        poses += 1
    }

    /// Whether `point` is within ``farM`` of the phone.
    func keeps(_ point: SIMD3<Float>) -> Bool {
        guard following else { return true }
        return simd_distance_squared(point, eye) <= farM * farM
    }

    /// The middle of the phone's path on the ground, `(x, z)`; `nil` before the first pose.
    var centre: SIMD2<Float>? {
        following ? SIMD2(Float(sumX / Double(poses)), Float(sumZ / Double(poses))) : nil
    }

    /// `polygon` cut to the square of half side ``farM`` round ``centre``; empty when nothing of
    /// it is left, unchanged before the first pose.
    func clip(_ polygon: [SIMD3<Float>]) -> [SIMD3<Float>] {
        guard let c = centre else { return polygon }
        var poly = polygon
        // Sutherland–Hodgman against the square's four vertical sides: an ARKit plane polygon
        // is convex, and a convex polygon cut by a half-space stays one.
        poly = Self.clip(poly, axis: 0, limit: c.x - farM, keepAbove: true)
        poly = Self.clip(poly, axis: 0, limit: c.x + farM, keepAbove: false)
        poly = Self.clip(poly, axis: 2, limit: c.y - farM, keepAbove: true)
        poly = Self.clip(poly, axis: 2, limit: c.y + farM, keepAbove: false)
        return poly.count < 3 ? [] : poly
    }

    private static func clip(_ poly: [SIMD3<Float>], axis: Int, limit: Float, keepAbove: Bool) -> [SIMD3<Float>] {
        guard !poly.isEmpty else { return poly }
        func inside(_ p: SIMD3<Float>) -> Bool { keepAbove ? p[axis] >= limit : p[axis] <= limit }
        if poly.allSatisfy(inside) { return poly }
        var out: [SIMD3<Float>] = []
        out.reserveCapacity(poly.count + 2)
        for i in poly.indices {
            let a = poly[i]
            let b = poly[(i + 1) % poly.count]
            if inside(a) { out.append(a) }
            if inside(a) != inside(b) {
                let t = (limit - a[axis]) / (b[axis] - a[axis])
                out.append(a + t * (b - a))
            }
        }
        return out
    }
}
