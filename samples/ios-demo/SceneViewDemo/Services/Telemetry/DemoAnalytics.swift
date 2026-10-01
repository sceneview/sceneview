import Foundation
import SceneViewSwift

// App-only analytics facade for the SceneView demo.
//
// The SceneViewSwift SDK carries no telemetry; everything here lives in the demo app
// target and nowhere else. Call sites talk to `DemoAnalytics.shared` only, never to
// Firebase: the backend is swapped at launch (`FirebaseTelemetry.start()`) and stays a
// no-op when Firebase is not configured (no `GoogleService-Info.plist` in the bundle),
// when the user turned "Share usage statistics" off, and in unit tests.
//
// The event taxonomy is shared byte-for-byte with the Android demo. Do not rename an
// event or a parameter here without renaming it there: the two apps report into the
// same Firebase project and the dashboards join them on these strings.

/// One analytics parameter value. Firebase accepts strings and numbers; the taxonomy
/// only needs strings and integers.
enum AnalyticsParam: Sendable, Equatable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral {
    case string(String)
    case int(Int)

    init(stringLiteral value: String) { self = .string(value) }
    init(integerLiteral value: Int) { self = .int(value) }

    /// The value handed to Firebase (`NSString` / `NSNumber` bridged).
    var firebaseValue: Any {
        switch self {
        case .string(let value): return value
        case .int(let value): return value
        }
    }
}

/// A named event with its parameters, built only through the taxonomy factories below.
struct AnalyticsEvent: Sendable, Equatable {
    let name: String
    let params: [String: AnalyticsParam]
}

/// Where a sample was opened from (`sample_open.source`).
enum SampleOpenSource: String, Sendable {
    case home, search, deeplink, push, other
}

/// `outbound_link.target`.
enum OutboundTarget: String, Sendable {
    case github, store, docs, other

    /// Classifies a URL the app is about to open outside itself.
    static func classify(_ url: URL) -> OutboundTarget {
        let host = (url.host ?? "").lowercased()
        if host.hasSuffix("github.com") { return .github }
        if host.hasSuffix("apps.apple.com") || host.hasSuffix("play.google.com") || url.scheme == "itms-apps" {
            return .store
        }
        if host.hasSuffix("sceneview.github.io") || host.hasPrefix("docs.") || host.contains("developer.apple.com") {
            return .docs
        }
        return .other
    }
}

/// `push_prompt_result.result`.
enum PushPromptResult: String, Sendable {
    case granted, denied
    case notNow = "not_now"
}

extension AnalyticsEvent {
    static func screenView(name: String, screenClass: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "screen_view", params: ["screen_name": .string(name), "screen_class": .string(screenClass)])
    }

    static func sampleOpen(sampleId: String, category: String, source: SampleOpenSource) -> AnalyticsEvent {
        AnalyticsEvent(name: "sample_open", params: [
            "sample_id": .string(sampleId), "category": .string(category), "source": .string(source.rawValue),
        ])
    }

    static func sampleClose(sampleId: String, durationSeconds: Int) -> AnalyticsEvent {
        AnalyticsEvent(name: "sample_close", params: ["sample_id": .string(sampleId), "duration_s": .int(max(0, durationSeconds))])
    }

    static func sampleInteraction(sampleId: String, control: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "sample_interaction", params: ["sample_id": .string(sampleId), "control": .string(control)])
    }

    static func modelLoadFailed(sampleId: String, reason: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "model_load_failed", params: ["sample_id": .string(sampleId), "reason": .string(clip(reason))])
    }

    static func arSessionCreated(sampleId: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "ar_session_created", params: ["sample_id": .string(sampleId)])
    }

    static func arTrackingReady(sampleId: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "ar_tracking_ready", params: ["sample_id": .string(sampleId)])
    }

    static func arFirstPlacement(sampleId: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "ar_first_placement", params: ["sample_id": .string(sampleId)])
    }

    static func arTrackingLost(sampleId: String, reason: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "ar_tracking_lost", params: ["sample_id": .string(sampleId), "reason": .string(clip(reason))])
    }

    static func arSessionFailed(sampleId: String, reason: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "ar_session_failed", params: ["sample_id": .string(sampleId), "reason": .string(clip(reason))])
    }

    static func outboundLink(target: OutboundTarget, sampleId: String? = nil) -> AnalyticsEvent {
        var params: [String: AnalyticsParam] = ["target": .string(target.rawValue)]
        if let sampleId { params["sample_id"] = .string(sampleId) }
        return AnalyticsEvent(name: "outbound_link", params: params)
    }

    static let pushPromptShown = AnalyticsEvent(name: "push_prompt_shown", params: [:])

    static func pushPromptResult(_ result: PushPromptResult) -> AnalyticsEvent {
        AnalyticsEvent(name: "push_prompt_result", params: ["result": .string(result.rawValue)])
    }

    static func pushOpened(campaign: String?, sampleId: String?) -> AnalyticsEvent {
        var params: [String: AnalyticsParam] = [:]
        params["campaign"] = .string(clip(campaign ?? "none"))
        params["sample_id"] = .string(sampleId ?? "none")
        return AnalyticsEvent(name: "push_opened", params: params)
    }

    static func settingsChanged(key: String, value: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "settings_changed", params: ["key": .string(key), "value": .string(value)])
    }

    /// Firebase truncates parameter values at 100 characters; clip first so an error
    /// description never loses its head.
    private static func clip(_ value: String) -> String {
        String(value.prefix(100))
    }
}

/// User properties (`ar_supported`, `app_theme`, `notif_enabled`).
enum AnalyticsUserProperty: String, Sendable {
    case arSupported = "ar_supported"
    case appTheme = "app_theme"
    case notifEnabled = "notif_enabled"
}

/// The seam between the facade and a concrete SDK.
protocol AnalyticsBackend: Sendable {
    func log(_ event: AnalyticsEvent)
    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty)
    /// Turns Analytics and Crashlytics collection on or off.
    func setCollectionEnabled(_ enabled: Bool)
    /// Clears the analytics app-instance ID and the data stored on the device.
    func resetAnalyticsData()
}

/// Used when Firebase is not configured, and in tests.
struct NoopAnalyticsBackend: AnalyticsBackend {
    func log(_ event: AnalyticsEvent) {}
    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty) {}
    func setCollectionEnabled(_ enabled: Bool) {}
    func resetAnalyticsData() {}
}

/// The single analytics entry point of the demo app.
final class DemoAnalytics: @unchecked Sendable {
    static let shared = DemoAnalytics()

    /// `UserDefaults` key of the "Share usage statistics" switch. Default ON.
    static let usageStatsKey = "telemetry.usageStatsEnabled"

    private let lock = NSLock()
    private var backend: any AnalyticsBackend
    private let defaults: UserDefaults
    /// Open samples, keyed by id, with their start time (for `sample_close.duration_s`).
    private var openSamples: [String: Date] = [:]
    private let now: @Sendable () -> Date

    init(backend: any AnalyticsBackend = NoopAnalyticsBackend(),
         defaults: UserDefaults = .standard,
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.backend = backend
        self.defaults = defaults
        self.now = now
    }

    /// Whether the user allows usage statistics (Analytics + Crashlytics).
    var usageStatsEnabled: Bool {
        defaults.object(forKey: Self.usageStatsKey) as? Bool ?? true
    }

    /// Swaps the backend (called once at launch by `FirebaseTelemetry`).
    func install(_ backend: any AnalyticsBackend) {
        lock.lock(); self.backend = backend; lock.unlock()
        backend.setCollectionEnabled(usageStatsEnabled)
    }

    /// Settings switch ("Share usage statistics"). Turning it off stops
    /// Analytics and Crashlytics collection and resets the analytics ID. As on Android,
    /// the opt-out is logged while collection is still on (`settings_changed
    /// analytics=false`), so it is the last event this install reports.
    func setUsageStatsEnabled(_ enabled: Bool) {
        let backend = currentBackend
        if !enabled {
            log(.settingsChanged(key: "analytics", value: "false"))
        }
        defaults.set(enabled, forKey: Self.usageStatsKey)
        backend.setCollectionEnabled(enabled)
        if enabled {
            log(.settingsChanged(key: "analytics", value: "true"))
        } else {
            backend.resetAnalyticsData()
        }
    }

    func log(_ event: AnalyticsEvent) {
        guard usageStatsEnabled else { return }
        currentBackend.log(event)
    }

    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty) {
        guard usageStatsEnabled else { return }
        currentBackend.setUserProperty(value, for: property)
    }

    // MARK: Sample lifecycle

    /// Logs `screen_view` + `sample_open` and starts the sample's clock.
    func sampleOpened(_ sampleId: String, category: String, source: SampleOpenSource) {
        lock.lock(); openSamples[sampleId] = now(); lock.unlock()
        log(.screenView(name: sampleId, screenClass: "Sample"))
        log(.sampleOpen(sampleId: sampleId, category: category, source: source))
    }

    /// Logs `sample_close` with the whole seconds spent in the sample. Ignored when the
    /// sample was never opened (a double close cannot double count).
    func sampleClosed(_ sampleId: String) {
        lock.lock()
        let start = openSamples.removeValue(forKey: sampleId)
        lock.unlock()
        guard let start else { return }
        log(.sampleClose(sampleId: sampleId, durationSeconds: Int(now().timeIntervalSince(start))))
    }

    func interaction(_ sampleId: String, _ control: String) {
        log(.sampleInteraction(sampleId: sampleId, control: control))
    }

    /// The Android category key of a sample (`sample_open.category`).
    static func category(for section: DemoSection?) -> String {
        guard let section else { return "unknown" }
        switch section {
        case .view3d: return "View 3D"
        case .create: return "Create & Record"
        case .placeAR: return "Place in AR"
        case .understand: return "Understand the World"
        case .devTools: return "Developer Tools"
        }
    }

    /// `model_load_failed.reason`, the Android keys: `asset_missing` (the file could not
    /// be fetched or opened), `decode_failed` (it was read but could not be turned into a
    /// model), `no_bounds` (it loaded with nothing to show), else `unknown`.
    static func modelLoadReason(for error: Error) -> String {
        switch error {
        case let error as ModelLoadingError:
            switch error {
            case .unreadableFile: return "asset_missing"
            case .emptyMesh: return "no_bounds"
            case .unsupportedFormat, .malformed, .unreadableGeometry: return "decode_failed"
            }
        case is SketchfabAssetResolver.Error, is SketchfabError, is GallerySourceError, is URLError:
            return "asset_missing"
        default:
            break
        }
        let ns = error as NSError
        if ns.domain == NSCocoaErrorDomain {
            switch ns.code {
            case NSFileNoSuchFileError, NSFileReadNoSuchFileError, NSFileReadNoPermissionError,
                 NSFileReadUnknownError:
                return "asset_missing"
            case NSFileReadCorruptFileError, NSPropertyListReadCorruptError:
                return "decode_failed"
            default:
                break
            }
        }
        if ns.domain == NSURLErrorDomain { return "asset_missing" }
        // RealityKit / ModelIO refusing a file that was read.
        if ["RealityKit", "RealityFoundation", "ModelIO"].contains(where: ns.domain.contains) {
            return "decode_failed"
        }
        return "unknown"
    }

    /// An error as `domain:code`, never a message that could carry a file path or a URL
    /// (`ar_session_failed.reason`, logs).
    static func reason(for error: Error) -> String {
        let ns = error as NSError
        return "\(ns.domain):\(ns.code)"
    }

    private var currentBackend: any AnalyticsBackend {
        lock.lock(); defer { lock.unlock() }
        return backend
    }
}
