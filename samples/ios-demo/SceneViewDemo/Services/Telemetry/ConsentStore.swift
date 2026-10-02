import Foundation
import SwiftUI
import os

/// The usage-statistics consent: one answer covers Analytics and Crashlytics (one purpose,
/// "improve the app"). Same keys and rules as the Android demo's `TelemetryPrefs`.
///
/// - In the zone (`ConsentRegion`): nothing is collected until `granted`, and Firebase is
///   not even configured before it (`shouldStartFirebaseAtLaunch`), except for push the
///   user already turned on: `FirebaseApp.configure()` reaches
///   firebaseinstallations.googleapis.com with every collection flag off
///   (firebase-ios-sdk #15513).
/// - Outside the zone: collection stays on by default; About's switch is the opt-out.
///
/// Bumping `currentVersion` asks again in the zone: an answer recorded under an older
/// version reads as `unknown` there.
final class ConsentStore: @unchecked Sendable {
    enum State: String, Sendable { case unknown, granted, denied }

    static let currentVersion = 1

    static let stateKey = "telemetry.consent.state"
    static let atKey = "telemetry.consent.at"
    static let versionKey = "telemetry.consent.version"
    /// The pre-consent "Share usage statistics" switch (default ON). Read once, by the
    /// migration: an explicit OFF becomes `denied`.
    static let legacyUsageStatsKey = "telemetry.usageStatsEnabled"
    /// Push needs Firebase configured at launch even without the usage consent (the push
    /// permission is a consent of its own, and FCM runs with collection off).
    static let pushNeedsFirebaseKey = "telemetry.firebaseForPush"
    /// `PushCenter.subscribedKey`, read by the migration (PushCenter is iOS only).
    static let legacyPushSubscribedKey = "push.topicsSubscribed"

    static let shared: ConsentStore = {
        let override = LaunchOverride.parse(CommandLine.arguments, defaults: .standard)
        let store = ConsentStore(requiresConsent: requiresConsent(override: override, inRegion: ConsentRegion.current))
        if let override { store.apply(override) }
        return store
    }()

    /// `-telemetry_consent ask` puts the install in the zone wherever it runs, as on Android.
    static func requiresConsent(override: LaunchOverride?, inRegion: Bool) -> Bool {
        override == .ask || inRegion
    }

    /// This install is in the zone.
    let requiresConsent: Bool
    private let defaults: UserDefaults
    private let now: @Sendable () -> Date

    init(defaults: UserDefaults = .standard,
         requiresConsent: Bool,
         now: @escaping @Sendable () -> Date = { Date() }) {
        self.defaults = defaults
        self.requiresConsent = requiresConsent
        self.now = now
        migrate()
    }

    /// Tester installs from before the consent: an explicit "Share usage statistics" OFF
    /// is a refusal; every other install is `unknown` (asked once, in the zone). An
    /// install already subscribed to push keeps Firebase at launch so its pushes still
    /// arrive whatever the answer, with collection off until a yes: the collection
    /// settings that build saved in Firebase's storage are cleared before `configure()`
    /// (`FirebaseLeftovers`).
    private func migrate() {
        guard defaults.object(forKey: Self.stateKey) == nil else { return }
        if defaults.object(forKey: Self.pushNeedsFirebaseKey) == nil,
           defaults.bool(forKey: Self.legacyPushSubscribedKey) {
            defaults.set(true, forKey: Self.pushNeedsFirebaseKey)
        }
        if defaults.object(forKey: Self.legacyUsageStatsKey) as? Bool == false {
            record(.denied)
        }
    }

    /// The answer as stored, whatever its version.
    var storedState: State {
        defaults.string(forKey: Self.stateKey).flatMap(State.init(rawValue:)) ?? .unknown
    }

    /// The answer that counts: one recorded under an older `consent_version` is `unknown`.
    var state: State {
        let stored = storedState
        guard stored != .unknown, defaults.integer(forKey: Self.versionKey) >= Self.currentVersion else {
            return .unknown
        }
        return stored
    }

    var consentedAt: Date? { defaults.object(forKey: Self.atKey) as? Date }

    /// The consent sheet should be shown.
    var needsPrompt: Bool { requiresConsent && state == .unknown }

    /// Analytics and Crashlytics may collect. In the zone, only after a yes; outside it,
    /// unless the user opted out.
    var collectionAllowed: Bool {
        requiresConsent ? state == .granted : storedState != .denied
    }

    var pushNeedsFirebase: Bool {
        get { defaults.bool(forKey: Self.pushNeedsFirebaseKey) }
        set { defaults.set(newValue, forKey: Self.pushNeedsFirebaseKey) }
    }

    /// `FirebaseApp.configure()` runs at launch only when something allows it, as on
    /// Android: collection (a yes in the zone, no opt-out outside it), or push the user
    /// turned on (FCM needs Firebase; the push permission is a consent of its own). A start
    /// for push alone has collection off, with the settings Firebase saved earlier cleared
    /// first (`FirebaseTelemetry.start`, `FirebaseLeftovers`).
    var shouldStartFirebaseAtLaunch: Bool {
        collectionAllowed || pushNeedsFirebase
    }

    /// Push is the only reason to configure Firebase at this launch and no topic was
    /// subscribed (no FCM token to undo): the launch leaves Firebase off until the system
    /// permission is read, so one granted then revoked in iOS Settings before any APNs
    /// token never configures it (`PushCenter.appBecameActive`).
    func launchStartWaitsForPushPermission(topicsSubscribed: Bool) -> Bool {
        !collectionAllowed && pushNeedsFirebase && !topicsSubscribed
    }

    /// Records an answer with its time and the current version. `unknown` forgets it.
    func record(_ state: State) {
        switch state {
        case .unknown:
            defaults.removeObject(forKey: Self.stateKey)
            defaults.removeObject(forKey: Self.atKey)
            defaults.removeObject(forKey: Self.versionKey)
        case .granted, .denied:
            defaults.set(state.rawValue, forKey: Self.stateKey)
            defaults.set(now(), forKey: Self.atKey)
            defaults.set(Self.currentVersion, forKey: Self.versionKey)
        }
    }

    // MARK: Launch argument

    /// `-telemetry_consent granted|denied|ask` (UI tests, Maestro, scripted captures):
    /// pre-answers the consent, or (`ask`) forgets the answer and forces the zone, so the
    /// sheet comes back wherever the device is.
    enum LaunchOverride: String, Sendable {
        case granted, denied, ask

        static func parse(_ arguments: [String], defaults: UserDefaults) -> LaunchOverride? {
            for argument in arguments {
                let lower = argument.lowercased()
                for prefix in ["telemetry_consent=", "-telemetry_consent="] where lower.hasPrefix(prefix) {
                    if let value = LaunchOverride(rawValue: String(lower.dropFirst(prefix.count))) { return value }
                }
            }
            for flag in ["-telemetry_consent", "telemetry_consent"] {
                if let index = arguments.firstIndex(of: flag), index + 1 < arguments.count,
                   let value = LaunchOverride(rawValue: arguments[index + 1].lowercased()) {
                    return value
                }
            }
            // `-telemetry_consent denied` also lands in the arguments domain of UserDefaults.
            return defaults.string(forKey: "telemetry_consent").flatMap { LaunchOverride(rawValue: $0.lowercased()) }
        }
    }

    func apply(_ override: LaunchOverride) {
        switch override {
        case .granted: record(.granted)
        case .denied: record(.denied)
        case .ask: record(.unknown)
        }
    }
}

/// Presents the consent sheet and applies the answer — from the sheet or from About's
/// "Share usage statistics" switch. iOS and macOS alike: both start Firebase.
@MainActor
final class TelemetryConsent: ObservableObject {
    static let shared = TelemetryConsent(store: .shared)

    private static let log = Logger(subsystem: "io.github.sceneview.demo", category: "telemetry")

    @Published var sheetPresented = false
    /// Mirror of `store.collectionAllowed`, for About's switch.
    @Published private(set) var collectionAllowed: Bool

    let store: ConsentStore
    /// The consent was answered in this process: the push pre-prompt waits for the next
    /// launch, so the two questions never come back to back.
    private(set) var answeredThisSession = false
    private var sheetAnswered = false

    init(store: ConsentStore) {
        self.store = store
        self.collectionAllowed = store.collectionAllowed
    }

    /// The push pre-prompt may be shown: the consent is not pending, and was not answered
    /// in this session.
    var settledBeforeThisSession: Bool {
        !store.needsPrompt && !answeredThisSession
    }

    /// Home is on screen with nothing over it. Opens the sheet after a beat, so Home is
    /// seen first. Never in a build without a Firebase config: nothing to consent to.
    func presentIfNeeded(homeIsIdle: @MainActor () -> Bool = { true }) async {
        guard store.needsPrompt, FirebaseTelemetry.hasBundledConfig, !sheetPresented else { return }
        try? await Task.sleep(for: .milliseconds(600))
        guard !Task.isCancelled, store.needsPrompt, !sheetPresented, homeIsIdle() else { return }
        sheetAnswered = false
        sheetPresented = true
        Self.log.notice("consent sheet shown (Firebase configured: \(FirebaseTelemetry.isConfigured, privacy: .public))")
    }

    /// "Share".
    func share() { answerSheet(true) }

    /// "Don't share".
    func decline() { answerSheet(false) }

    /// Swiped away: a refusal, never asked again until `consent_version` changes.
    func sheetDismissed() {
        guard !sheetAnswered else { return }
        answerSheet(false)
    }

    /// About → "Share usage statistics". ON is a consent (timestamped); OFF withdraws it.
    func setUsageStats(_ enabled: Bool) {
        answeredThisSession = true
        apply(enabled)
    }

    private func answerSheet(_ granted: Bool) {
        sheetAnswered = true
        answeredThisSession = true
        sheetPresented = false
        apply(granted)
    }

    private func apply(_ granted: Bool) {
        DemoAnalytics.shared.setUsageStatsEnabled(granted) {
            // Strict mode: the yes is what configures Firebase.
            FirebaseTelemetry.start()
            #if os(iOS)
            PushCenter.shared.firebaseDidStart()
            #endif
        }
        collectionAllowed = store.collectionAllowed
        Self.log.notice("consent \(granted ? "granted" : "denied", privacy: .public) (Firebase configured: \(FirebaseTelemetry.isConfigured, privacy: .public))")
    }
}
