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

    func finishDelete() {
        hasToken = false
        pendingDelete?(true)
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

    override func setUp() async throws {
        suiteName = "PushCenterTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        fcm = FakeMessaging()
        status = .authorized
        registerCount = 0
    }

    override func tearDown() async throws {
        defaults.removePersistentDomain(forName: suiteName)
    }

    private func makeCenter() -> PushCenter {
        PushCenter(
            defaults: defaults,
            messaging: fcm,
            system: PushSystem(
                authorizationStatus: { [unowned self] in self.status },
                requestAuthorization: { true },
                registerForRemoteNotifications: { [unowned self] in self.registerCount += 1 }
            )
        )
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
        let center = makeCenter()

        await center.applyNotificationsSwitch(true)
        await center.applyNotificationsSwitch(false)

        XCTAssertEqual(fcm.fetchCount, 0)
        XCTAssertTrue(fcm.subscribed.isEmpty)
        XCTAssertTrue(fcm.unsubscribed.isEmpty)
        XCTAssertEqual(fcm.deleteCount, 0)
    }
}
