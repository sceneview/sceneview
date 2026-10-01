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

/// Starts Firebase when — and only when — the build carries a `GoogleService-Info.plist`.
///
/// The plist is never committed (public repo). The "Embed Firebase config" build phase
/// copies it into the bundle from `SV_GOOGLE_SERVICE_INFO_PLIST` (see `Config.xcconfig`);
/// a build without it — every fresh clone, every fork, every CI compile check — ships
/// no plist, `FirebaseApp.configure()` is never called, and `DemoAnalytics` keeps its
/// no-op backend. `FirebaseApp.configure()` raises an Objective-C exception without a
/// plist, so the presence check is what guarantees such a build cannot crash.
enum FirebaseTelemetry {
    private static let log = Logger(subsystem: "io.github.sceneview.demo", category: "telemetry")

    /// Whether Firebase was configured in this process.
    nonisolated(unsafe) private(set) static var isConfigured = false

    /// The bundled Firebase options file, if the build embedded one.
    static var bundledConfigURL: URL? {
        Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist")
    }

    /// Unit tests run inside the app process: they must never start Firebase.
    private static var isRunningUnitTests: Bool {
        ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil
    }

    /// Configures Firebase and installs the Firebase analytics backend. Idempotent.
    /// Call it from `application(_:didFinishLaunchingWithOptions:)` (iOS) or the app's
    /// first scene task (macOS), before any event is logged.
    static func start() {
        guard !isConfigured else { return }
        #if canImport(FirebaseCore) && canImport(FirebaseAnalytics)
        guard !isRunningUnitTests else { return }
        guard let url = bundledConfigURL,
              let options = FirebaseOptions(contentsOfFile: url.path),
              !options.googleAppID.isEmpty else {
            log.notice("Firebase disabled: no GoogleService-Info.plist in this build")
            return
        }
        FirebaseApp.configure(options: options)
        isConfigured = true

        // Consent mode: analytics only. The app links FirebaseAnalyticsCore (no IDFA,
        // no AdSupport) and never shows ATT; ad signals are denied here as well as by
        // the GOOGLE_ANALYTICS_DEFAULT_* keys in Info.plist, which cover the events
        // logged before this line runs.
        Analytics.setConsent([
            .analyticsStorage: .granted,
            .adStorage: .denied,
            .adUserData: .denied,
            .adPersonalization: .denied,
        ])
        DemoAnalytics.shared.install(FirebaseAnalyticsBackend())
        log.notice("Firebase configured (usage stats \(DemoAnalytics.shared.usageStatsEnabled ? "on" : "off", privacy: .public))")
        #else
        log.notice("Firebase SDK not linked in this build")
        #endif
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
        Analytics.setAnalyticsCollectionEnabled(enabled)
        #if canImport(FirebaseCrashlytics)
        Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(enabled)
        #endif
    }

    func resetAnalyticsData() {
        Analytics.resetAnalyticsData()
    }
}
#endif
