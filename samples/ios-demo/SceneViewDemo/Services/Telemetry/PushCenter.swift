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
/// Every Firebase call is gated on `FirebaseTelemetry.isConfigured`, and the pre-prompt on
/// `FirebaseTelemetry.hasBundledConfig`: a build without
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
///
/// Usage-statistics consent (`ConsentStore`): the pre-prompt is never shown while that
/// consent is pending, nor in the session it was answered in, so the two questions never
/// come back to back. In the consent zone Firebase may not be configured yet: a yes at the
/// system prompt (not before it) configures it (`PushTelemetryGate.startFirebase`) with
/// collection still off — the push permission is a consent of its own.
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
    private let messaging: any PushMessaging
    private let system: PushSystem
    private let gate: PushTelemetryGate
    private var promptAnswered = false
    private var messagingAttached = false
    /// An explicit token request is out (switch back ON after `deleteToken`).
    private var tokenRequestInFlight = false
    /// Unsubscribes + `deleteToken` are out: nothing subscribes until they land,
    /// or the new subscriptions would ride the token being deleted.
    private var tokenDeletionInFlight = false
    private var pendingUnsubscribes = 0

    struct PushTap: Equatable, Identifiable {
        let id = UUID()
        let sampleId: String?
        let campaign: String?
    }

    init(defaults: UserDefaults = .standard,
         messaging: any PushMessaging = FirebasePushMessaging(),
         system: PushSystem = .live,
         gate: PushTelemetryGate = .live) {
        self.defaults = defaults
        self.policy = PushPrePromptPolicy(store: DefaultsPushPrePromptStore(defaults: defaults))
        self.messaging = messaging
        self.system = system
        self.gate = gate
        self.notificationsEnabled = defaults.object(forKey: Self.notificationsKey) as? Bool ?? true
        super.init()
    }

    /// Whether push can work in this build at all.
    var isAvailable: Bool { messaging.isAvailable }

    /// The topics this build subscribes to (`qa` in Debug builds only).
    static var subscribedTopics: [String] { topics }

    /// `-push_preprompt off` (UI tests, Maestro, scripted captures): the pre-prompt never
    /// shows and Home returns are not counted. The Notifications switch still works.
    static let prePromptDisabledByLaunchArgument: Bool = {
        let args = CommandLine.arguments
        if args.contains(where: { ["push_preprompt=off", "-push_preprompt=off"].contains($0.lowercased()) }) {
            return true
        }
        for flag in ["-push_preprompt", "push_preprompt"] {
            if let index = args.firstIndex(of: flag), index + 1 < args.count,
               ["off", "false", "0"].contains(args[index + 1].lowercased()) {
                return true
            }
        }
        // `-push_preprompt off` also lands in the arguments domain of UserDefaults.
        if let value = UserDefaults.standard.string(forKey: "push_preprompt") {
            return ["off", "false", "0"].contains(value.lowercased())
        }
        return false
    }()

    // MARK: Launch

    /// From `application(_:didFinishLaunchingWithOptions:)`, after
    /// `FirebaseTelemetry.startAtLaunch()`. The notification-center delegate must be set
    /// before launch ends so a tap that cold-starts the app is delivered.
    func didFinishLaunching(_ application: UIApplication) {
        UNUserNotificationCenter.current().delegate = self
        firebaseDidStart()
    }

    /// Firebase is configured (at launch, or later by a consent): FCM gets its delegate,
    /// and an already-authorized install re-registers so the APNs token (hence the FCM
    /// token and topics) stays fresh; a permission revoked in iOS Settings since the last
    /// launch leaves the topics here. Nothing is asked. Idempotent.
    func firebaseDidStart() {
        guard isAvailable, !messagingAttached else { return }
        messagingAttached = true
        #if canImport(FirebaseMessaging)
        if FirebaseTelemetry.isConfigured { Messaging.messaging().delegate = self }
        #endif
        Task {
            await refreshAuthorization(registerIfAllowed: true)
            syncTopics()
        }
    }

    /// Push was allowed: configure Firebase if the consent zone left it off. Collection
    /// stays as the usage consent says (off without it).
    private func ensureFirebase() {
        guard !isAvailable, gate.firebaseCanStart() else { return }
        gate.startFirebase()
        firebaseDidStart()
    }

    /// Whether Firebase has to be configured at the next launch: push is allowed, or an
    /// opt-out has not finished yet (topics left, token deleted). The flag goes back to
    /// false only once nothing is left to undo with FCM, so an opt-out that failed is
    /// retried at the next launch, as Android's `pushDisablePending`.
    private func rememberPushNeedsFirebase() {
        if pushAllowed {
            gate.setPushNeedsFirebase(true)
        } else if !tokenDeletionInFlight, !defaults.bool(forKey: Self.subscribedKey) {
            gate.setPushNeedsFirebase(false)
        }
    }

    func didRegister(deviceToken: Data) {
        guard isAvailable else { return }
        messaging.setAPNsToken(deviceToken)
    }

    func refreshAuthorization(registerIfAllowed: Bool = false) async {
        authorization = await system.authorizationStatus()
        DemoAnalytics.shared.setUserProperty(notifEnabledValue, for: .notifEnabled)
        if registerIfAllowed, pushAllowed, isAvailable {
            system.registerForRemoteNotifications()
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
        guard !Self.prePromptDisabledByLaunchArgument else { return }
        policy.onReturnedHome()
        Task {
            await refreshAuthorization()
            guard policy.shouldShow(eligible: prePromptEligible) else { return }
            // Let the demo's cover finish its dismissal before a sheet goes up.
            try? await Task.sleep(for: .milliseconds(450))
            policy.onShown()
            promptAnswered = false
            prePromptPresented = true
            DemoAnalytics.shared.log(.pushPromptShown)
        }
    }

    /// Push could matter now: Firebase is (or can be) configured, the Notifications switch
    /// is on, iOS has not asked yet — and the usage consent is settled, in an earlier
    /// session (never pending, never answered in this one).
    var prePromptEligible: Bool {
        (isAvailable || gate.firebaseCanStart())
            && gate.consentSettled()
            && notificationsEnabled
            && authorization == .notDetermined
    }

    /// "Notify me": the system prompt, then the result.
    func prePromptAccepted() {
        markPrePromptAccepted()
        Task { await answerPrePrompt() }
    }

    /// `prePromptAccepted`, awaited to the end (tests).
    func acceptPrePrompt() async {
        markPrePromptAccepted()
        await answerPrePrompt()
    }

    /// Synchronous, so the sheet's `onDismiss` sees the answer and does not count a "Not now".
    private func markPrePromptAccepted() {
        promptAnswered = true
        prePromptPresented = false
    }

    private func answerPrePrompt() async {
        let granted = await requestSystemPermission()
        policy.onAnswered()
        DemoAnalytics.shared.log(.pushPromptResult(granted ? .granted : .denied))
    }

    /// "Not now", or the sheet swiped away.
    func prePromptDismissed() {
        guard !promptAnswered else { return }
        promptAnswered = true
        prePromptPresented = false
        policy.onLater()
        DemoAnalytics.shared.log(.pushPromptResult(.notNow))
    }

    /// The system prompt. Firebase is configured only once it answered yes: before it,
    /// `FirebaseApp.configure()` would already reach firebaseinstallations.googleapis.com
    /// for a user who then refuses (#4268).
    @discardableResult
    private func requestSystemPermission() async -> Bool {
        let granted = await system.requestAuthorization()
        await refreshAuthorization()
        if pushAllowed { ensureFirebase() }
        await refreshAuthorization(registerIfAllowed: true)
        syncTopics()
        return granted
    }

    // MARK: Settings

    /// About → "Notifications".
    func setNotificationsEnabled(_ enabled: Bool) {
        recordSwitch(enabled)
        Task { await syncAfterSwitch(enabled) }
    }

    /// `setNotificationsEnabled`, awaited to the end (tests).
    func applyNotificationsSwitch(_ enabled: Bool) async {
        recordSwitch(enabled)
        await syncAfterSwitch(enabled)
    }

    private func recordSwitch(_ enabled: Bool) {
        notificationsEnabled = enabled
        defaults.set(enabled, forKey: Self.notificationsKey)
        DemoAnalytics.shared.log(.settingsChanged(key: "notifications", value: enabled ? "true" : "false"))
    }

    private func syncAfterSwitch(_ enabled: Bool) async {
        if enabled {
            await refreshAuthorization()
            if authorization == .notDetermined {
                // The switch itself is the user's request: no pre-prompt in front of it.
                let granted = await requestSystemPermission()
                policy.onAnswered()
                DemoAnalytics.shared.log(.pushPromptResult(granted ? .granted : .denied))
            } else {
                // Already granted (OFF → ON): register again and resubscribe. The
                // token was deleted when the switch went OFF, so `syncTopics` asks
                // FCM for a new one first.
                if pushAllowed { ensureFirebase() }
                await refreshAuthorization(registerIfAllowed: true)
                syncTopics()
            }
        } else {
            await refreshAuthorization()
            syncTopics()
        }
    }

    /// Brings FCM in line with the switch and the system permission. Allowed: auto-init
    /// on, a token, then the topics. Not allowed after having been: leave the topics,
    /// then delete the FCM token.
    func syncTopics() {
        rememberPushNeedsFirebase()
        guard isAvailable else { return }
        if pushAllowed {
            messaging.setAutoInit(true)
            // A deletion still running would take these subscriptions with it; its
            // completion calls back here.
            guard !tokenDeletionInFlight else { return }
            guard messaging.hasToken else {
                requestToken()
                return
            }
            for topic in Self.topics {
                messaging.subscribe(topic) { ok in
                    #if DEBUG
                    Self.log.debug("topic \(topic, privacy: .public) subscribed ok=\(ok)")
                    #endif
                }
            }
            defaults.set(true, forKey: Self.subscribedKey)
        } else {
            messaging.setAutoInit(false)
            guard defaults.bool(forKey: Self.subscribedKey), !tokenDeletionInFlight else { return }
            // Kept until `deleteToken` lands: a failed opt-out is retried at the next launch.
            gate.setPushNeedsFirebase(true)
            tokenDeletionInFlight = true
            pendingUnsubscribes = Self.topics.count
            for topic in Self.topics {
                messaging.unsubscribe(topic) { [weak self] ok in
                    #if DEBUG
                    Self.log.debug("topic \(topic, privacy: .public) unsubscribed ok=\(ok)")
                    #endif
                    guard let self else { return }
                    pendingUnsubscribes -= 1
                    // The token goes last: an unsubscribe needs it.
                    if pendingUnsubscribes == 0 { deleteToken() }
                }
            }
        }
    }

    private func deleteToken() {
        messaging.deleteToken { [weak self] ok in
            #if DEBUG
            Self.log.debug("FCM token deleted ok=\(ok)")
            #endif
            guard let self else { return }
            tokenDeletionInFlight = false
            if ok {
                defaults.set(false, forKey: Self.subscribedKey)
                if !pushAllowed { gate.setPushNeedsFirebase(false) }
            } else {
                Self.log.notice("FCM token deletion failed: retried at the next launch")
            }
            // Switched back ON while the deletion ran: subscribe again now.
            if pushAllowed { syncTopics() }
        }
    }

    /// No FCM token. The first one comes from auto-init once APNs answers
    /// (`didReceiveRegistrationToken` calls back here). After a `deleteToken`, FCM does
    /// not fetch a replacement on its own: ask, as soon as APNs has a token.
    private func requestToken() {
        guard messaging.hasAPNsToken, !tokenRequestInFlight else { return }
        tokenRequestInFlight = true
        messaging.fetchToken { [weak self] ok in
            guard let self else { return }
            tokenRequestInFlight = false
            #if DEBUG
            Self.log.debug("FCM token requested ok=\(ok)")
            #endif
            if ok { syncTopics() }
        }
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

// MARK: - Seams

/// The FCM calls `PushCenter` makes, behind a seam so the switch logic is unit tested.
/// Completions are delivered on the main actor.
@MainActor
protocol PushMessaging: AnyObject {
    /// Firebase is configured in this process.
    var isAvailable: Bool { get }
    var hasToken: Bool { get }
    var hasAPNsToken: Bool { get }
    func setAPNsToken(_ token: Data)
    func setAutoInit(_ enabled: Bool)
    func fetchToken(_ completion: @escaping @MainActor (Bool) -> Void)
    func subscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void)
    func unsubscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void)
    func deleteToken(_ completion: @escaping @MainActor (Bool) -> Void)
}

/// The system side: notification permission and APNs registration.
struct PushSystem {
    var authorizationStatus: @MainActor () async -> UNAuthorizationStatus
    var requestAuthorization: @MainActor () async -> Bool
    var registerForRemoteNotifications: @MainActor () -> Void

    static let live = PushSystem(
        authorizationStatus: { await UNUserNotificationCenter.current().notificationSettings().authorizationStatus },
        requestAuthorization: {
            (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])) ?? false
        },
        registerForRemoteNotifications: { UIApplication.shared.registerForRemoteNotifications() }
    )
}

/// What push needs from the usage-statistics consent and from Firebase's start-up, behind
/// a seam so the consent guard is unit tested.
struct PushTelemetryGate {
    /// The build can configure Firebase (config bundled), even if it has not yet.
    var firebaseCanStart: @MainActor () -> Bool
    /// Configures Firebase now, collection as the usage consent says.
    var startFirebase: @MainActor () -> Void
    /// The usage consent is settled and was not answered in this session.
    var consentSettled: @MainActor () -> Bool
    /// Firebase must be configured at the next launch for push to keep working.
    var setPushNeedsFirebase: @MainActor (Bool) -> Void

    static let live = PushTelemetryGate(
        firebaseCanStart: { FirebaseTelemetry.hasBundledConfig },
        startFirebase: { FirebaseTelemetry.start() },
        consentSettled: { TelemetryConsent.shared.settledBeforeThisSession },
        setPushNeedsFirebase: { ConsentStore.shared.pushNeedsFirebase = $0 }
    )
}

/// `PushMessaging` on Firebase Cloud Messaging. Never touches `Messaging.messaging()`
/// unless Firebase was configured (it asserts otherwise).
@MainActor
final class FirebasePushMessaging: PushMessaging {
    var isAvailable: Bool { FirebaseTelemetry.isConfigured }

    #if canImport(FirebaseMessaging)
    private var fcm: Messaging? { isAvailable ? Messaging.messaging() : nil }

    var hasToken: Bool { fcm?.fcmToken != nil }
    var hasAPNsToken: Bool { fcm?.apnsToken != nil }
    func setAPNsToken(_ token: Data) { fcm?.apnsToken = token }
    func setAutoInit(_ enabled: Bool) { fcm?.isAutoInitEnabled = enabled }

    func fetchToken(_ completion: @escaping @MainActor (Bool) -> Void) {
        guard let fcm else { return completion(false) }
        fcm.token { token, error in
            let ok = token != nil && error == nil
            Task { @MainActor in completion(ok) }
        }
    }

    func subscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) {
        guard let fcm else { return completion(false) }
        fcm.subscribe(toTopic: topic) { error in
            Task { @MainActor in completion(error == nil) }
        }
    }

    func unsubscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) {
        guard let fcm else { return completion(false) }
        fcm.unsubscribe(fromTopic: topic) { error in
            Task { @MainActor in completion(error == nil) }
        }
    }

    func deleteToken(_ completion: @escaping @MainActor (Bool) -> Void) {
        guard let fcm else { return completion(false) }
        fcm.deleteToken { error in
            Task { @MainActor in completion(error == nil) }
        }
    }
    #else
    var hasToken: Bool { false }
    var hasAPNsToken: Bool { false }
    func setAPNsToken(_ token: Data) {}
    func setAutoInit(_ enabled: Bool) {}
    func fetchToken(_ completion: @escaping @MainActor (Bool) -> Void) { completion(false) }
    func subscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) { completion(false) }
    func unsubscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) { completion(false) }
    func deleteToken(_ completion: @escaping @MainActor (Bool) -> Void) { completion(false) }
    #endif
}

/// The app delegate: HD pack background transfers (inherited), Firebase start, APNs.
final class DemoAppDelegate: HDPackAppDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // In the EEA, UK and Switzerland, not before the user's yes (ConsentStore).
        FirebaseTelemetry.startAtLaunch()
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
