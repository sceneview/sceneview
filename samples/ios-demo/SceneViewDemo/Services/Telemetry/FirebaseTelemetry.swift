import Foundation
import os

#if canImport(FirebaseCore)
import FirebaseCore
#endif
#if canImport(FirebaseAnalytics)
import FirebaseAnalytics
#endif
#if canImport(FirebaseCrashlytics)
import FirebaseCrashlytics
#endif

/// Starts Firebase when — and only when — the build carries a `GoogleService-Info.plist`
/// and, in the EEA, the UK and Switzerland, once the user said yes.
///
/// The plist is never committed (public repo). The "Embed Firebase config" build phase
/// copies it into the bundle from `SV_GOOGLE_SERVICE_INFO_PLIST` (see `Config.xcconfig`);
/// a build without it — every fresh clone, every fork, every CI compile check — ships
/// no plist, `FirebaseApp.configure()` is never called, and `DemoAnalytics` keeps its
/// no-op backend. `FirebaseApp.configure()` raises an Objective-C exception without a
/// plist, so the presence check is what guarantees such a build cannot crash.
///
/// Strict consent mode (`ConsentStore`): `startAtLaunch()` does nothing until collection
/// is allowed (a yes in the zone, no opt-out outside it), or push was accepted. `configure()` alone
/// reaches firebaseinstallations.googleapis.com with every collection flag off
/// (firebase-ios-sdk #15513), so not calling it is the only way nothing leaves the device
/// before the answer. Collection itself is off by default (Info.plist
/// `FIREBASE_ANALYTICS_COLLECTION_ENABLED`, `FirebaseCrashlyticsCollectionEnabled` and
/// `GOOGLE_ANALYTICS_DEFAULT_ALLOW_ANALYTICS_STORAGE`, all NO), and a start that must not
/// collect first clears what the SDKs saved earlier (`FirebaseLeftovers`), since a value
/// set through their API beats those defaults; `DemoAnalytics.install` turns collection on
/// only with the consent.
enum FirebaseTelemetry {
    private static let log = Logger(subsystem: "io.github.sceneview.demo", category: "telemetry")

    /// Whether Firebase was configured in this process.
    nonisolated(unsafe) private(set) static var isConfigured = false

    /// The bundled Firebase options file, if the build embedded one.
    static var bundledConfigURL: URL? {
        Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist")
    }

    /// Firebase can be started in this build: SDK linked, config bundled, not a unit test.
    /// The consent sheet, the push pre-prompt and About's privacy switches exist only then.
    static var hasBundledConfig: Bool {
        #if canImport(FirebaseCore) && canImport(FirebaseAnalytics)
        return !isRunningUnitTests && bundledConfigURL != nil
        #else
        return false
        #endif
    }

    /// Unit tests run inside the app process: they must never start Firebase.
    private static var isRunningUnitTests: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// At launch (`DemoAppDelegate` on iOS, `SceneViewDemoApp.init` on macOS): starts
    /// Firebase unless the consent is still due (strict mode, see the type comment).
    static func startAtLaunch(consent: ConsentStore = .shared) {
        let region = Locale.current.region?.identifier ?? "none"
        let zone = TimeZone.current.identifier
        log.notice("consent zone \(consent.requiresConsent, privacy: .public) (region \(region, privacy: .public), time zone \(zone, privacy: .public)), consent \(consent.state.rawValue, privacy: .public)")
        guard consent.shouldStartFirebaseAtLaunch else {
            log.notice("Firebase not configured at launch: consent \(consent.state.rawValue, privacy: .public), collection off, push off")
            return
        }
        start(consent: consent)
    }

    /// Configures Firebase and installs the Firebase analytics backend. Idempotent.
    /// Collection follows the consent (`DemoAnalytics.install`).
    static func start(consent: ConsentStore = .shared) {
        guard !isConfigured else { return }
        #if canImport(FirebaseCore) && canImport(FirebaseAnalytics)
        guard !isRunningUnitTests else { return }
        guard let url = bundledConfigURL,
              let options = FirebaseOptions(contentsOfFile: url.path),
              !options.googleAppID.isEmpty else {
            log.notice("Firebase disabled: no GoogleService-Info.plist in this build")
            return
        }
        configure(collecting: consent.collectionAllowed) { FirebaseApp.configure(options: options) }
        isConfigured = true

        // Consent mode, basic. The app links FirebaseAnalyticsCore (no IDFA, no
        // AdSupport) and never shows ATT: ad signals stay denied for good. Analytics
        // storage follows the consent (`FirebaseAnalyticsBackend.setCollectionEnabled`)
        // and is denied until then by the Info.plist defaults.
        DemoAnalytics.shared.install(FirebaseAnalyticsBackend())
        log.notice("Firebase configured (usage stats \(DemoAnalytics.shared.usageStatsEnabled ? "on" : "off", privacy: .public))")
        #else
        log.notice("Firebase SDK not linked in this build")
        #endif
    }

    /// Runs `configure`, first clearing the collection settings Firebase saved earlier
    /// (`FirebaseLeftovers`) whenever this start must not collect: push turned on without
    /// the consent. Not only after an upgrade: an install that collected outside the zone
    /// and later counts as inside it holds the same saved ON.
    static func configure(collecting: Bool,
                          clearLeftovers: () -> Void = { FirebaseLeftovers.clear() },
                          _ configure: () -> Void) {
        if !collecting { clearLeftovers() }
        configure()
    }
}

#if canImport(FirebaseCore) && canImport(FirebaseAnalytics)
/// Firebase implementation of the facade. Only installed after `FirebaseApp.configure`.
struct FirebaseAnalyticsBackend: AnalyticsBackend {
    func log(_ event: AnalyticsEvent) {
        Analytics.logEvent(event.name, parameters: event.params.mapValues(\.firebaseValue))
    }

    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty) {
        Analytics.setUserProperty(value, forName: property.rawValue)
    }

    func setCollectionEnabled(_ enabled: Bool) {
        Analytics.setConsent([
            .analyticsStorage: enabled ? .granted : .denied,
            .adStorage: .denied,
            .adUserData: .denied,
            .adPersonalization: .denied,
        ])
        Analytics.setAnalyticsCollectionEnabled(enabled)
        #if canImport(FirebaseCrashlytics)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled)
        #endif
    }

    func resetAnalyticsData() {
        Analytics.resetAnalyticsData()
    }

    func deleteUnsentReports() {
        #if canImport(FirebaseCrashlytics)
        Crashlytics.crashlytics().deleteUnsentReports()
        #endif
    }
}
#endif
