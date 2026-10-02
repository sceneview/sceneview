// PushCenterTests.swift
//
// Unit tests for About → "Notifications" in `PushCenter`
// (`SceneViewDemo/Services/Telemetry/PushCenter.swift`), against a fake FCM:
// switching OFF leaves the topics then deletes the FCM token; switching back ON with
// the permission already granted fetches a new token (auto-init does not after a
// `deleteToken`) and subscribes again.

import XCTest
import UserNotifications
@testable import SceneViewDemo

@MainActor
private final class FakeMessaging: PushMessaging {
    var isAvailable = true
    var hasToken = false
    var hasAPNsToken = true
    var autoInit = false
    var fetchCount = 0
    var subscribed: [String] = []
    var unsubscribed: [String] = []
    var deleteCount = 0
    /// When false, `deleteToken` holds its completion until `finishDelete()`.
    var completeDeleteImmediately = true
    private var pendingDelete: (@MainActor (Bool) -> Void)?

    func setAPNsToken(_ token: Data) { hasAPNsToken = true }
    func setAutoInit(_ enabled: Bool) { autoInit = enabled }

    func fetchToken(_ completion: @escaping @MainActor (Bool) -> Void) {
        fetchCount += 1
        hasToken = true
        completion(true)
    }

    func subscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) {
        subscribed.append(topic)
        completion(true)
    }

    func unsubscribe(_ topic: String, _ completion: @escaping @MainActor (Bool) -> Void) {
        unsubscribed.append(topic)
        completion(true)
    }

    func deleteToken(_ completion: @escaping @MainActor (Bool) -> Void) {
        deleteCount += 1
        if completeDeleteImmediately {
            hasToken = false
            completion(true)
        } else {
            pendingDelete = completion
        }
    }

    func finishDelete(success: Bool = true) {
        if success { hasToken = false }
        pendingDelete?(success)
        pendingDelete = nil
    }
}

@MainActor
final class PushCenterTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var fcm: FakeMessaging!
    private var status: UNAuthorizationStatus = .authorized
    private var registerCount = 0
    private var authorizationGranted = true
    /// `firebaseStarts` when the system prompt went up (nil: never asked).
    private var firebaseStartsAtPrompt: Int?

    override func setUp() async throws {
        suiteName = "PushCenterTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        fcm = FakeMessaging()
        status = .authorized
        registerCount = 0
        authorizationGranted = true
        firebaseStartsAtPrompt = nil
        consentSettled = true
        firebaseCanStart = true
        firebaseStarts = 0
        pushNeedsFirebase = nil
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    /// The usage consent and Firebase's start-up, as the push code sees them.
    private var consentSettled = true
    private var firebaseCanStart = true
    private var firebaseStarts = 0
    private var pushNeedsFirebase: Bool?

    private func makeCenter() -> PushCenter {
        PushCenter(
            defaults: defaults,
            messaging: fcm,
            system: PushSystem(
                authorizationStatus: { [unowned self] in self.status },
                requestAuthorization: { [unowned self] in
                    self.firebaseStartsAtPrompt = self.firebaseStarts
                    self.status = self.authorizationGranted ? .authorized : .denied
                    return self.authorizationGranted
                },
                registerForRemoteNotifications: { [unowned self] in self.registerCount += 1 }
            ),
            gate: PushTelemetryGate(
                firebaseCanStart: { [unowned self] in self.firebaseCanStart },
                startFirebase: { [unowned self] in
                    self.firebaseStarts += 1
                    self.fcm.isAvailable = true
                },
                consentSettled: { [unowned self] in self.consentSettled },
                setPushNeedsFirebase: { [unowned self] in self.pushNeedsFirebase = $0 }
            )
        )
    }

    // MARK: Consent guard

    /// The usage consent is still pending (or was answered in this session): no push
    /// pre-prompt, whatever else holds.
    func testPrePromptNotEligibleWhileConsentUnknown() async {
        status = .notDetermined
        consentSettled = false
        let center = makeCenter()
        await center.refreshAuthorization()

        XCTAssertFalse(center.prePromptEligible)

        consentSettled = true
        XCTAssertTrue(center.prePromptEligible, "eligible once the consent is settled, in a later session")
    }

    /// Consent zone, refused: "Notify me" configures Firebase only once the system prompt
    /// said yes, never while it is up (#4268).
    func testNotifyMeConfiguresFirebaseAfterPermissionIsGranted() async {
        status = .notDetermined
        fcm.isAvailable = false
        let center = makeCenter()
        await center.refreshAuthorization()
        XCTAssertTrue(center.prePromptEligible)

        await center.acceptPrePrompt()

        XCTAssertEqual(firebaseStartsAtPrompt, 0, "nothing configured before the system prompt")
        XCTAssertEqual(firebaseStarts, 1)
        XCTAssertTrue(fcm.isAvailable)
        XCTAssertEqual(pushNeedsFirebase, true)
    }

    /// "Notify me", then "Don't Allow": Firebase is never configured, nothing is kept for
    /// the next launch.
    func testNotifyMeDenialNeverConfiguresFirebase() async {
        status = .notDetermined
        authorizationGranted = false
        fcm.isAvailable = false
        let center = makeCenter()
        await center.refreshAuthorization()

        await center.acceptPrePrompt()

        XCTAssertEqual(firebaseStartsAtPrompt, 0)
        XCTAssertEqual(firebaseStarts, 0)
        XCTAssertFalse(fcm.isAvailable)
        XCTAssertNotEqual(pushNeedsFirebase, true)
    }

    /// The switch turned ON before iOS asked, then "Don't Allow": same as the pre-prompt.
    func testSwitchOnThenDenialNeverConfiguresFirebase() async {
        status = .notDetermined
        authorizationGranted = false
        fcm.isAvailable = false
        let center = makeCenter()

        await center.applyNotificationsSwitch(true)

        XCTAssertEqual(firebaseStartsAtPrompt, 0)
        XCTAssertEqual(firebaseStarts, 0)
        XCTAssertNotEqual(pushNeedsFirebase, true)
    }

    /// No config bundled: no pre-prompt, and Firebase is never started for push.
    func testNoPrePromptWithoutABundledConfig() async {
        status = .notDetermined
        fcm.isAvailable = false
        firebaseCanStart = false
        let center = makeCenter()
        await center.refreshAuthorization()

        XCTAssertFalse(center.prePromptEligible)
        await center.acceptPrePrompt()
        XCTAssertEqual(firebaseStarts, 0)
    }

    /// Push allowed: Firebase must start at the next launch even without the usage consent.
    func testAllowedPushIsRememberedForTheNextLaunch() async {
        let center = makeCenter()
        await center.refreshAuthorization()
        center.syncTopics()
        XCTAssertEqual(pushNeedsFirebase, true)

        await center.applyNotificationsSwitch(false)
        XCTAssertEqual(pushNeedsFirebase, false)
    }

    /// Granted, token present: the topics are subscribed.
    private func makeSubscribedCenter() async -> PushCenter {
        fcm.hasToken = true
        let center = makeCenter()
        await center.refreshAuthorization()
        center.syncTopics()
        XCTAssertEqual(Set(fcm.subscribed), Set(PushCenter.subscribedTopics))
        XCTAssertTrue(defaults.bool(forKey: PushCenter.subscribedKey))
        fcm.subscribed = []
        return center
    }

    func testSwitchOffLeavesTopicsThenDeletesToken() async {
        let center = await makeSubscribedCenter()

        await center.applyNotificationsSwitch(false)

        XCTAssertEqual(Set(fcm.unsubscribed), Set(PushCenter.subscribedTopics))
        XCTAssertEqual(fcm.deleteCount, 1)
        XCTAssertFalse(fcm.autoInit)
        XCTAssertFalse(fcm.hasToken)
        XCTAssertFalse(defaults.bool(forKey: PushCenter.subscribedKey))
        XCTAssertEqual(pushNeedsFirebase, false)
    }

    func testFailedTokenDeletionIsRetriedOnNextLaunch() async {
        let center = await makeSubscribedCenter()
        fcm.completeDeleteImmediately = false

        await center.applyNotificationsSwitch(false)
        XCTAssertTrue(defaults.bool(forKey: PushCenter.subscribedKey))
        XCTAssertEqual(pushNeedsFirebase, true)

        fcm.finishDelete(success: false)
        XCTAssertTrue(defaults.bool(forKey: PushCenter.subscribedKey))
        XCTAssertEqual(pushNeedsFirebase, true)

        fcm.completeDeleteImmediately = true
        let relaunchedCenter = makeCenter()
        await relaunchedCenter.refreshAuthorization()
        relaunchedCenter.syncTopics()

        XCTAssertEqual(fcm.deleteCount, 2)
        XCTAssertFalse(defaults.bool(forKey: PushCenter.subscribedKey))
        XCTAssertEqual(pushNeedsFirebase, false)
    }

    /// Allowed but never subscribed (no FCM token yet), then OFF: nothing to undo, so
    /// Firebase is not kept for the next launch.
    func testSwitchOffBeforeAnySubscriptionForgetsFirebase() async {
        fcm.hasAPNsToken = false
        let center = makeCenter()
        await center.refreshAuthorization()
        center.syncTopics()
        XCTAssertEqual(pushNeedsFirebase, true)
        XCTAssertFalse(defaults.bool(forKey: PushCenter.subscribedKey))

        await center.applyNotificationsSwitch(false)

        XCTAssertEqual(fcm.deleteCount, 0)
        XCTAssertEqual(pushNeedsFirebase, false)
    }

    /// The bug: OFF → ON with the permission already granted did not resubscribe.
    func testSwitchBackOnWithPermissionGrantedFetchesTokenAndResubscribes() async {
        let center = await makeSubscribedCenter()
        await center.applyNotificationsSwitch(false)
        XCTAssertFalse(fcm.hasToken)

        await center.applyNotificationsSwitch(true)

        XCTAssertEqual(fcm.fetchCount, 1, "a new FCM token must be requested after deleteToken")
        XCTAssertTrue(fcm.autoInit)
        XCTAssertEqual(Set(fcm.subscribed), Set(PushCenter.subscribedTopics))
        XCTAssertTrue(defaults.bool(forKey: PushCenter.subscribedKey))
        XCTAssertEqual(registerCount, 1, "APNs registration is renewed on the way back ON")
    }

    /// No APNs token yet: FCM cannot mint one; `didReceiveRegistrationToken` will call back.
    func testNoTokenRequestWithoutAPNsToken() async {
        fcm.hasAPNsToken = false
        let center = makeCenter()

        await center.applyNotificationsSwitch(true)

        XCTAssertEqual(fcm.fetchCount, 0)
        XCTAssertTrue(fcm.subscribed.isEmpty)
        XCTAssertTrue(fcm.autoInit)
    }

    /// Back ON while the deletion is still running: nothing subscribes on the token
    /// being deleted; once it is gone, a new one is fetched and the topics follow.
    func testSwitchBackOnDuringDeletionWaitsForIt() async {
        let center = await makeSubscribedCenter()
        fcm.completeDeleteImmediately = false

        await center.applyNotificationsSwitch(false)
        XCTAssertEqual(fcm.deleteCount, 1)
        await center.applyNotificationsSwitch(true)
        XCTAssertTrue(fcm.subscribed.isEmpty)
        XCTAssertEqual(fcm.fetchCount, 0)

        fcm.finishDelete()

        XCTAssertEqual(fcm.fetchCount, 1)
        XCTAssertEqual(Set(fcm.subscribed), Set(PushCenter.subscribedTopics))
        XCTAssertEqual(fcm.deleteCount, 1)
    }

    /// Permission denied in iOS Settings: the switch stays ON but nothing subscribes.
    func testSwitchOnWithPermissionDeniedSubscribesNothing() async {
        status = .denied
        let center = makeCenter()

        await center.applyNotificationsSwitch(true)

        XCTAssertEqual(fcm.fetchCount, 0)
        XCTAssertTrue(fcm.subscribed.isEmpty)
        XCTAssertFalse(fcm.autoInit)
        XCTAssertEqual(registerCount, 0)
    }

    /// A build without `GoogleService-Info.plist` never touches FCM.
    func testUnconfiguredFirebaseTouchesNothing() async {
        fcm.isAvailable = false
        firebaseCanStart = false
        let center = makeCenter()

        await center.applyNotificationsSwitch(true)
        await center.applyNotificationsSwitch(false)

        XCTAssertEqual(fcm.fetchCount, 0)
        XCTAssertTrue(fcm.subscribed.isEmpty)
        XCTAssertTrue(fcm.unsubscribed.isEmpty)
        XCTAssertEqual(fcm.deleteCount, 0)
    }
}
