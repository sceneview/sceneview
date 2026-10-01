#if os(iOS)
import SwiftUI
import UIKit
import UserNotifications
import os
#if canImport(FirebaseMessaging)
import FirebaseMessaging
#endif

/// "What's new" pushes: permission, topics, the pre-prompt, and routing a tapped
/// notification to its sample.
///
/// Every Firebase call is gated on `FirebaseTelemetry.isConfigured`: a build without
/// `GoogleService-Info.plist` shows no pre-prompt, subscribes to nothing and never
/// touches `Messaging.messaging()` (which would assert without a configured app).
///
/// FCM stays dormant until the user says yes: `FirebaseMessagingAutoInitEnabled` is NO
/// in Info.plist, so no FCM token exists before the system permission is granted.
/// Granted + switch on: auto-init on, subscribe to the topics. Switch off (or permission
/// revoked in iOS Settings): leave the topics, delete the FCM token, auto-init off.
///
/// Data payload (identical on Android): `sample` — a demo id, opened on tap (an unknown
/// id opens Home) — and `campaign`, reported in `push_opened`.
@MainActor
final class PushCenter: NSObject, ObservableObject {
    static let shared = PushCenter()

    /// `UserDefaults` key of About → "Notifications". Default ON.
    static let notificationsKey = "push.notificationsEnabled"
    /// Set once the topics were subscribed, so turning push off only undoes what was done
    /// (and a build that never had push never creates an FCM token to delete one).
    static let subscribedKey = "push.topicsSubscribed"

    nonisolated private static let log = Logger(subsystem: "io.github.sceneview.demo", category: "push")
    private static let topics: [String] = {
        #if DEBUG
        return ["all", "new_samples", "qa"]
        #else
        return ["all", "new_samples"]
        #endif
    }()

    /// A tapped notification's target, consumed by `ContentView`. `""` means "Home".
    @Published var pendingTap: PushTap?
    /// Drives the pre-prompt sheet (`ContentView`).
    @Published var prePromptPresented = false
    /// Mirror of the system authorization, for About.
    @Published private(set) var authorization: UNAuthorizationStatus = .notDetermined
    @Published private(set) var notificationsEnabled: Bool

    private let defaults: UserDefaults
    private let policy: PushPrePromptPolicy
    private var promptAnswered = false

    struct PushTap: Equatable, Identifiable {
        let id = UUID()
        let sampleId: String?
        let campaign: String?
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.policy = PushPrePromptPolicy(store: DefaultsPushPrePromptStore(defaults: defaults))
        self.notificationsEnabled = defaults.object(forKey: Self.notificationsKey) as? Bool ?? true
        super.init()
    }

    /// Whether push can work in this build at all.
    var isAvailable: Bool { FirebaseTelemetry.isConfigured }

    // MARK: Launch

    /// From `application(_:didFinishLaunchingWithOptions:)`, after `FirebaseTelemetry.start()`.
    /// The notification-center delegate must be set before launch ends so a tap that
    /// cold-starts the app is delivered.
    func didFinishLaunching(_ application: UIApplication) {
        UNUserNotificationCenter.current().delegate = self
        guard isAvailable else { return }
        #if canImport(FirebaseMessaging)
        Messaging.messaging().delegate = self
        #endif
        // Nothing is asked at launch. An already-authorized install re-registers so
        // the APNs token (hence the FCM token and topics) stays fresh; a permission
        // revoked in iOS Settings since the last launch leaves the topics here.
        Task {
            await refreshAuthorization(registerIfAllowed: true)
            syncTopics()
        }
    }

    func didRegister(deviceToken: Data) {
        guard isAvailable else { return }
        #if canImport(FirebaseMessaging)
        Messaging.messaging().apnsToken = deviceToken
        #endif
    }

    func refreshAuthorization(registerIfAllowed: Bool = false) async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authorization = settings.authorizationStatus
        DemoAnalytics.shared.setUserProperty(notifEnabledValue, for: .notifEnabled)
        if registerIfAllowed, pushAllowed, isAvailable {
            UIApplication.shared.registerForRemoteNotifications()
        }
    }

    /// The switch is on and iOS lets the app notify.
    private var pushAllowed: Bool {
        notificationsEnabled && (authorization == .authorized || authorization == .provisional)
    }

    private var notifEnabledValue: String { pushAllowed ? "true" : "false" }

    // MARK: Pre-prompt

    /// Home is back after a sample closed (`DemoCover.onDisappear`).
    func homeReturnedAfterSample() {
        policy.onReturnedHome()
        Task {
            await refreshAuthorization()
            let eligible = isAvailable && notificationsEnabled && authorization == .notDetermined
            guard policy.shouldShow(eligible: eligible) else { return }
            // Let the demo's cover finish its dismissal before a sheet goes up.
            try? await Task.sleep(for: .milliseconds(450))
            policy.onShown()
            promptAnswered = false
            prePromptPresented = true
            DemoAnalytics.shared.log(.pushPromptShown)
        }
    }

    /// "Notify me": the system prompt, then the result.
    func prePromptAccepted() {
        promptAnswered = true
        prePromptPresented = false
        Task {
            let granted = await requestSystemPermission()
            policy.onAnswered()
            DemoAnalytics.shared.log(.pushPromptResult(granted ? .granted : .denied))
        }
    }

    /// "Not now", or the sheet swiped away.
    func prePromptDismissed() {
        guard !promptAnswered else { return }
        promptAnswered = true
        prePromptPresented = false
        policy.onLater()
        DemoAnalytics.shared.log(.pushPromptResult(.notNow))
    }

    @discardableResult
    private func requestSystemPermission() async -> Bool {
        let granted = (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        await refreshAuthorization(registerIfAllowed: true)
        syncTopics()
        return granted
    }

    // MARK: Settings

    /// About → "Notifications".
    func setNotificationsEnabled(_ enabled: Bool) {
        notificationsEnabled = enabled
        defaults.set(enabled, forKey: Self.notificationsKey)
        DemoAnalytics.shared.log(.settingsChanged(key: "notifications", value: enabled ? "true" : "false"))
        Task {
            if enabled {
                await refreshAuthorization()
                if authorization == .notDetermined {
                    // The switch itself is the user's request: no pre-prompt in front of it.
                    let granted = await requestSystemPermission()
                    policy.onAnswered()
                    DemoAnalytics.shared.log(.pushPromptResult(granted ? .granted : .denied))
                } else {
                    await refreshAuthorization(registerIfAllowed: true)
                }
            } else {
                await refreshAuthorization()
                syncTopics()
            }
        }
    }

    /// Brings FCM in line with the switch and the system permission. Allowed: auto-init
    /// on and the topics subscribed (FCM queues them until its token exists). Not
    /// allowed after having been: leave the topics, then delete the FCM token.
    func syncTopics() {
        guard isAvailable else { return }
        #if canImport(FirebaseMessaging)
        let messaging = Messaging.messaging()
        if pushAllowed {
            messaging.isAutoInitEnabled = true
            // No token yet: auto-init fetches one and `didReceiveRegistrationToken`
            // calls back here, so subscribing now would only fail.
            guard messaging.fcmToken != nil else { return }
            for topic in Self.topics {
                messaging.subscribe(toTopic: topic) { error in
                    #if DEBUG
                    Self.log.debug("topic \(topic, privacy: .public) subscribed ok=\(error == nil)")
                    #endif
                }
            }
            defaults.set(true, forKey: Self.subscribedKey)
        } else {
            messaging.isAutoInitEnabled = false
            guard defaults.bool(forKey: Self.subscribedKey) else { return }
            defaults.set(false, forKey: Self.subscribedKey)
            let group = DispatchGroup()
            for topic in Self.topics {
                group.enter()
                messaging.unsubscribe(fromTopic: topic) { error in
                    #if DEBUG
                    Self.log.debug("topic \(topic, privacy: .public) unsubscribed ok=\(error == nil)")
                    #endif
                    group.leave()
                }
            }
            // The token goes last: an unsubscribe needs it.
            group.notify(queue: .main) {
                Messaging.messaging().deleteToken { error in
                    #if DEBUG
                    Self.log.debug("FCM token deleted ok=\(error == nil)")
                    #endif
                }
            }
        }
        #endif
    }

    // MARK: Taps

    fileprivate func opened(sampleId: String?, campaign: String?) {
        DemoAnalytics.shared.log(.pushOpened(campaign: campaign, sampleId: sampleId))
        pendingTap = PushTap(sampleId: sampleId, campaign: campaign)
    }
}

extension PushCenter: UNUserNotificationCenterDelegate {
    /// A push while the app is open is shown as a banner too.
    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        let sample = (info["sample"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let campaign = (info["campaign"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        #if canImport(FirebaseMessaging)
        if FirebaseTelemetry.isConfigured {
            // FCM's own delivery / open metrics (the swizzling proxy is off).
            Messaging.messaging().appDidReceiveMessage(info)
        }
        #endif
        completionHandler()
        Task { @MainActor in PushCenter.shared.opened(sampleId: sample, campaign: campaign) }
    }
}

#if canImport(FirebaseMessaging)
extension PushCenter: MessagingDelegate {
    nonisolated func messaging(_ messaging: Messaging, didReceiveRegistrationToken fcmToken: String?) {
        #if DEBUG
        if let fcmToken { Self.log.debug("FCM token: \(fcmToken, privacy: .public)") }
        #endif
        guard fcmToken != nil else { return }
        Task { @MainActor in PushCenter.shared.syncTopics() }
    }
}
#endif

/// The app delegate: HD pack background transfers (inherited), Firebase start, APNs.
final class DemoAppDelegate: HDPackAppDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        FirebaseTelemetry.start()
        PushCenter.shared.didFinishLaunching(application)
        return true
    }

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushCenter.shared.didRegister(deviceToken: deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        Logger(subsystem: "io.github.sceneview.demo", category: "push")
            .notice("APNs registration failed: \(DemoAnalytics.reason(for: error), privacy: .public)")
    }
}
#endif
