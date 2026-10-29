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
//
// No event carries a model file, a pose, a camera frame or anything else that could
// identify a person. The one piece of typed text is `search.search_term`, and only after
// `SearchTerm.normalize` has lowercased it, cut it to 50 characters and redacted anything
// that looks like an email address, a link or a long number.

/// Which field a search was typed in — `search.surface`.
enum SearchSurface: String, Sendable {
    /// The Home grid's demo filter — local, instant.
    case samples
    /// The Explore tab's online model catalogs.
    case models
}

/// What a search field may send as `search_term`: the only typed text the taxonomy
/// carries. Shared rule for rule with Android (`SearchTelemetry.kt`, `SearchTerm`).
enum SearchTerm {
    static let minimumLength = 2
    static let maximumLength = 50
    static let redacted = "(redacted)"
    /// How long a field must stay still before its query counts as a search.
    static let settleDelay: Duration = .milliseconds(1_500)

    /// The term to send for `raw`: trimmed, whitespace collapsed, lowercased, cut to
    /// `maximumLength` characters (`Character`s, so never inside an emoji); `nil` below
    /// `minimumLength`; `redacted` when it looks like an email address, a link, a long
    /// number or a phone number written with separators.
    static func normalize(_ raw: String) -> String? {
        let term = raw.lowercased().split(whereSeparator: \.isWhitespace).joined(separator: " ")
        guard term.count >= minimumLength else { return nil }
        if looksPersonal(term) { return redacted }
        return String(term.prefix(maximumLength)).trimmingCharacters(in: .whitespaces)
    }

    /// An email address, a link, a long number (an order id) or a phone number — six
    /// digits or more once the separators people type between them (`06 12…`, `+33 6…`,
    /// `06.12…`, `(0)6-12…`) are dropped: never sent as typed.
    private static func looksPersonal(_ term: String) -> Bool {
        if term.contains("@") || term.contains("http") || term.contains("www.") { return true }
        if term.range(of: "[0-9]{5,}", options: .regularExpression) != nil { return true }
        let digits = term.replacingOccurrences(of: "[\\s.\\-+()]", with: "", options: .regularExpression)
        return digits.range(of: "[0-9]{6,}", options: .regularExpression) != nil
    }

    /// `search.result_bucket` for `count` results, `error` when the search never answered.
    static func resultBucket(_ count: Int?) -> String {
        guard let count else { return "error" }
        switch count {
        case ...0: return "0"
        case 1: return "1"
        case 2...5: return "2-5"
        case 6...20: return "6-20"
        default: return "21+"
        }
    }
}

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

/// `support_prompt_shown.source`.
enum SupportPromptSource: String, Sendable, CaseIterable {
    case auto, settings
}

/// The final answer for one display of the support sheet.
enum SupportPromptResult: String, Sendable, CaseIterable {
    case tierSmall = "tier_small"
    case tierMedium = "tier_medium"
    case tierLarge = "tier_large"
    case purchaseCancelled = "purchase_cancelled"
    case purchaseFailed = "purchase_failed"
    case notNow = "not_now"
    case never, dismissed

    fileprivate var isCompletedPurchase: Bool {
        self == .tierSmall || self == .tierMedium || self == .tierLarge
    }

    static func completedPurchase(productId: String) -> SupportPromptResult? {
        guard let tier = productId.split(separator: ".").last else { return nil }
        switch tier {
        case "small": return .tierSmall
        case "medium": return .tierMedium
        case "large": return .tierLarge
        default: return nil
        }
    }
}

extension AnalyticsEvent {
    static func screenView(name: String, screenClass: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "screen_view", params: ["screen_name": .string(name), "screen_class": .string(screenClass)])
    }

    static func sampleOpen(sampleId: String, entryId: String? = nil, category: String,
                           source: SampleOpenSource, mode: String? = nil) -> AnalyticsEvent {
        var params: [String: AnalyticsParam] = [
            "sample_id": .string(clip(sampleId)), "entry_id": .string(clip(entryId ?? sampleId)),
            "category": .string(clip(category)), "source": .string(source.rawValue),
        ]
        if let mode { params["mode"] = .string(clip(mode)) }
        return AnalyticsEvent(name: "sample_open", params: params)
    }

    static func sampleClose(sampleId: String, durationSeconds: Int) -> AnalyticsEvent {
        AnalyticsEvent(name: "sample_close", params: ["sample_id": .string(sampleId), "duration_s": .int(max(0, durationSeconds))])
    }

    static func sampleInteraction(sampleId: String, control: String) -> AnalyticsEvent {
        AnalyticsEvent(
            name: "sample_interaction",
            params: ["sample_id": .string(clip(sampleId)), "control": .string(clip(control))]
        )
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

    static func supportPromptShown(source: SupportPromptSource) -> AnalyticsEvent {
        AnalyticsEvent(name: "support_prompt_shown", params: ["source": .string(source.rawValue)])
    }

    static func supportPromptResult(_ result: SupportPromptResult) -> AnalyticsEvent {
        AnalyticsEvent(name: "support_prompt_result", params: ["result": .string(result.rawValue)])
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

    /// GA4's recommended `search` event, so the term lands in the built-in "Search term"
    /// dimension. `term` comes out of `SearchTerm.normalize` — screens go through
    /// `DemoAnalytics.searchSettled`, never through this factory.
    static func search(term: String, surface: SearchSurface, resultBucket: String) -> AnalyticsEvent {
        AnalyticsEvent(name: "search", params: [
            "search_term": .string(clip(term)),
            "surface": .string(surface.rawValue),
            "result_bucket": .string(resultBucket),
        ])
    }

    /// Firebase truncates parameter values at 100 characters; clip first so an error
    /// description never loses its head.
    static let maxParamValueLength = 100

    private static func clip(_ value: String) -> String {
        String(value.prefix(maxParamValueLength))
    }
}

/// One displayed support sheet: one shown event and exactly one result when it closes.
final class SupportPromptAnalyticsSession {
    private let source: SupportPromptSource
    private let log: (AnalyticsEvent) -> Void
    private var didShow = false
    private var didClose = false
    private var purchaseResult: SupportPromptResult?

    init(source: SupportPromptSource,
         log: @escaping (AnalyticsEvent) -> Void = { DemoAnalytics.shared.log($0) }) {
        self.source = source
        self.log = log
    }

    func shown() {
        guard !didShow else { return }
        didShow = true
        log(.supportPromptShown(source: source))
    }

    /// Remembers a StoreKit answer; the event waits until the sheet actually closes.
    func recordPurchaseResult(_ result: SupportPromptResult) {
        guard didShow, !didClose, purchaseResult?.isCompletedPurchase != true else { return }
        purchaseResult = result
    }

    /// Explicit prompt answers win over a preceding cancelled or failed purchase attempt.
    func close(result: SupportPromptResult? = nil) {
        guard didShow, !didClose else { return }
        didClose = true
        log(.supportPromptResult(result ?? purchaseResult ?? .dismissed))
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
    /// Per field, the last search logged and whether it failed, so the same search
    /// settling twice counts once — Android's `SearchLogger`, rule for rule.
    private var lastSearches: [SearchSurface: (term: String, failed: Bool)] = [:]
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

    // MARK: Search

    /// Logs `raw` as a `search` on `surface` that found `resultCount` results (`nil`:
    /// the search failed). A repeat of that field's last search — a re-render, a result
    /// tapped right after the settle, a return from the other field — is dropped; a new
    /// term counts again, and so does the first answer a Retry brings after an `error`.
    @discardableResult
    func searchSettled(_ raw: String, surface: SearchSurface, resultCount: Int?) -> Bool {
        guard let term = SearchTerm.normalize(raw) else { return false }
        let failed = resultCount == nil
        lock.lock()
        let last = lastSearches[surface]
        let repeated = last?.term == term && last?.failed == failed
        if !repeated { lastSearches[surface] = (term, failed) }
        lock.unlock()
        guard !repeated else { return false }
        log(.search(term: term, surface: surface, resultBucket: SearchTerm.resultBucket(resultCount)))
        return true
    }

    /// The field on `surface` was emptied: its next search counts, even if it repeats.
    func searchCleared(_ surface: SearchSurface) {
        lock.lock()
        lastSearches[surface] = nil
        lock.unlock()
    }

    static func modeControl(_ mode: String) -> String { "mode_\(mode)" }

    /// The stable category slug of a sample (`sample_open.category`).
    static func category(for section: DemoSection?) -> String {
        section?.analyticsSlug ?? "unknown"
    }

    /// Initial mode of the iOS catalogue's routable umbrella card.
    static let modeSampleIds: Set<String> = [
        "ar-placement", "camera-gestures", "cosmos", "lighting", "materials",
        "model-viewer", "rolling-balls",
    ]

    @MainActor
    static func initialMode(for sampleId: String, tab: String? = nil) -> String? {
        if sampleId == "cosmos" {
            return CosmosDemo.analyticsMode(spacetime: tab == "spacetime" || tab == "1")
        }
        if sampleId == "lighting" {
            return LightingDemo.Rig.initial(tab).analyticsMode
        }
        let modes: [DemoMode]
        switch sampleId {
        case "ar-placement": modes = ArPlacementScene.modes
        case "camera-gestures": modes = CameraGesturesScene.modes
        case "materials": modes = MaterialsScene.modes
        case "model-viewer": modes = ModelViewerScene.modes
        case "rolling-balls": modes = RollingBallsScene.modes
        default: return nil
        }
        let requested = tab.flatMap { token in modes.first { $0.matches(token.lowercased()) } }
        return (requested ?? modes.first)?.id
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
