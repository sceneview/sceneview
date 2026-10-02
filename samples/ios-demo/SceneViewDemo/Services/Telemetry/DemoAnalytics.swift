import Foundation
import SceneViewSwift

// App-only analytics facade for the SceneView demo.
//
// The SceneViewSwift SDK carries no telemetry; everything here lives in the demo app
// target and nowhere else. Call sites talk to `DemoAnalytics.shared` only, never to
// Firebase: the backend is swapped by `FirebaseTelemetry.start()` and stays a no-op when
// Firebase is not configured (no `GoogleService-Info.plist` in the bundle; in the EEA, UK
// and Switzerland, before the consent), when the user refused or turned "Share usage
// statistics" off, and in unit tests.
//
// The event taxonomy is shared byte-for-byte with the Android demo. Do not rename an
// event or a parameter here without renaming it there: the two apps report into the
// same Firebase project and the dashboards join them on these strings.
// `sample_open`: sample_id + received entry_id + category slug + source, and mode only for umbrellas.

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

    static func sampleOpen(sampleId: String, entryId: String? = nil, category: String,
                           source: SampleOpenSource, mode: String? = nil) -> AnalyticsEvent {
        var params: [String: AnalyticsParam] = [
            "sample_id": .string(sampleId), "entry_id": .string(entryId ?? sampleId),
            "category": .string(category), "source": .string(source.rawValue),
        ]
        if let mode { params["mode"] = .string(mode) }
        return AnalyticsEvent(name: "sample_open", params: params)
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
    /// Deletes the crash reports cached on the device and not sent yet.
    func deleteUnsentReports()
}

/// Used when Firebase is not configured, and in tests.
struct NoopAnalyticsBackend: AnalyticsBackend {
    func log(_ event: AnalyticsEvent) {}
    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty) {}
    func setCollectionEnabled(_ enabled: Bool) {}
    func resetAnalyticsData() {}
    func deleteUnsentReports() {}
}

/// The single analytics entry point of the demo app.
final class DemoAnalytics: @unchecked Sendable {
    static let shared = DemoAnalytics(consent: .shared)

    /// The pre-consent key of the "Share usage statistics" switch. Only the migration
    /// reads it now (an explicit OFF becomes a refusal): the answer lives in `ConsentStore`.
    static let usageStatsKey = ConsentStore.legacyUsageStatsKey

    private let lock = NSLock()
    private var backend: any AnalyticsBackend
    private let consent: ConsentStore
    /// Open samples, keyed by id, with their start time (for `sample_close.duration_s`).
    private var openSamples: [String: Date] = [:]
    private let now: @Sendable () -> Date

    /// Without `consent`, a store on `defaults` outside the consent zone: collection on
    /// until the switch goes off.
    init(backend: any AnalyticsBackend = NoopAnalyticsBackend(),
         defaults: UserDefaults = .standard,
         consent: ConsentStore? = nil,
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.backend = backend
        self.consent = consent ?? ConsentStore(defaults: defaults, requiresConsent: false, now: now)
        self.now = now
    }

    /// Whether usage statistics (Analytics + Crashlytics) may be collected: the consent in
    /// the EEA, the UK and Switzerland, the opt-out switch elsewhere (`ConsentStore`).
    var usageStatsEnabled: Bool {
        consent.collectionAllowed
    }

    /// Swaps the backend (called once, by `FirebaseTelemetry.start()`). Collection follows
    /// the consent; the Info.plist defaults keep it off until this line.
    func install(_ backend: any AnalyticsBackend) {
        lock.lock(); self.backend = backend; lock.unlock()
        backend.setCollectionEnabled(usageStatsEnabled)
    }

    /// The consent answer, from the sheet or About's "Share usage statistics" switch.
    ///
    /// Yes: `startBackend` configures Firebase if it was not yet (strict mode, collection
    /// still off), crash reports cached before the yes are deleted, the consent is recorded
    /// with its time, then collection goes on and `settings_changed analytics=true` is
    /// logged.
    ///
    /// No: as on Android, the opt-out is logged while collection is still on
    /// (`settings_changed analytics=false`, the last event this install reports — nothing
    /// is logged when nothing was allowed), then collection goes off, the analytics ID is
    /// reset and the unsent crash reports are deleted.
    func setUsageStatsEnabled(_ enabled: Bool, startBackend: () -> Void = {}) {
        if enabled {
            startBackend()
            let backend = currentBackend
            backend.deleteUnsentReports()
            consent.record(.granted)
            backend.setCollectionEnabled(true)
            log(.settingsChanged(key: "analytics", value: "true"))
        } else {
            log(.settingsChanged(key: "analytics", value: "false"))
            consent.record(.denied)
            let backend = currentBackend
            backend.setCollectionEnabled(false)
            backend.resetAnalyticsData()
            backend.deleteUnsentReports()
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
    func sampleOpened(_ sampleId: String, entryId: String? = nil, category: String,
                      source: SampleOpenSource, mode: String? = nil) {
        lock.lock(); openSamples[sampleId] = now(); lock.unlock()
        log(.screenView(name: sampleId, screenClass: "Sample"))
        log(.sampleOpen(sampleId: sampleId, entryId: entryId, category: category, source: source, mode: mode))
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

    /// The stable category slug of a sample (`sample_open.category`).
    static func category(for section: DemoSection?) -> String {
        section?.analyticsSlug ?? "unknown"
    }

    /// Initial mode of the iOS catalogue's routable umbrella card.
    static func initialMode(for sampleId: String, tab: String? = nil) -> String? {
        guard sampleId == "cosmos" else { return nil }
        return tab == "spacetime" || tab == "1" ? "spacetime" : "starlight"
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
