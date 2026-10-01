// PushPrePromptPolicyTests.swift
//
// Unit tests for the push pre-prompt policy
// (`SceneViewDemo/Services/Telemetry/PushPrePromptPolicy.swift`), same rules as the
// Android demo: never on first launch, on the 2nd return to Home after a sample,
// "Not now" snoozes 7 days, 3 showings at most, settled once the system prompt was
// answered.

import XCTest
@testable import SceneViewDemo

private final class MemoryStore: PushPrePromptStore {
    var homeReturns = 0
    var timesShown = 0
    var snoozedUntil: Date?
    var settled = false
}

final class PushPrePromptPolicyTests: XCTestCase {
    private var store: MemoryStore!
    private var now: Date!
    private var policy: PushPrePromptPolicy!

    override func setUp() {
        super.setUp()
        store = MemoryStore()
        now = Date(timeIntervalSince1970: 2_000_000)
        policy = PushPrePromptPolicy(store: store, now: { [unowned self] in self.now })
    }

    func testNotShownOnFirstLaunch() {
        XCTAssertFalse(policy.shouldShow(eligible: true))
    }

    func testShownOnSecondReturnHome() {
        policy.onReturnedHome()
        XCTAssertFalse(policy.shouldShow(eligible: true))
        policy.onReturnedHome()
        XCTAssertTrue(policy.shouldShow(eligible: true))
    }

    func testNeverShownWhenNotEligible() {
        policy.onReturnedHome()
        policy.onReturnedHome()
        XCTAssertFalse(policy.shouldShow(eligible: false))
    }

    func testShowingResetsTheReturnCount() {
        policy.onReturnedHome()
        policy.onReturnedHome()
        policy.onShown()
        XCTAssertEqual(store.homeReturns, 0)
        XCTAssertEqual(store.timesShown, 1)
        XCTAssertFalse(policy.shouldShow(eligible: true))
    }

    func testNotNowSnoozesSevenDays() {
        policy.onReturnedHome(); policy.onReturnedHome()
        policy.onShown()
        policy.onLater()
        policy.onReturnedHome(); policy.onReturnedHome()
        now = now.addingTimeInterval(PushPrePromptPolicy.snooze - 60)
        XCTAssertFalse(policy.shouldShow(eligible: true))
        now = now.addingTimeInterval(120)
        XCTAssertTrue(policy.shouldShow(eligible: true))
    }

    func testAtMostThreeShowings() {
        for _ in 0..<PushPrePromptPolicy.maxShows {
            policy.onReturnedHome(); policy.onReturnedHome()
            XCTAssertTrue(policy.shouldShow(eligible: true))
            policy.onShown()
            policy.onLater()
            now = now.addingTimeInterval(PushPrePromptPolicy.snooze + 1)
        }
        policy.onReturnedHome(); policy.onReturnedHome()
        XCTAssertFalse(policy.shouldShow(eligible: true))
        XCTAssertEqual(store.timesShown, 3)
    }

    func testSettledAfterSystemAnswer() {
        policy.onReturnedHome(); policy.onReturnedHome()
        policy.onShown()
        policy.onAnswered()
        now = now.addingTimeInterval(30 * 24 * 3600)
        policy.onReturnedHome(); policy.onReturnedHome()
        XCTAssertFalse(policy.shouldShow(eligible: true))
    }

    func testDefaultsStoreRoundTrip() {
        let suite = "PushPrePromptPolicyTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = DefaultsPushPrePromptStore(defaults: defaults)
        XCTAssertEqual(store.homeReturns, 0)
        XCTAssertNil(store.snoozedUntil)
        XCTAssertFalse(store.settled)
        store.homeReturns = 2
        store.timesShown = 1
        store.snoozedUntil = Date(timeIntervalSince1970: 42)
        store.settled = true
        let reread = DefaultsPushPrePromptStore(defaults: defaults)
        XCTAssertEqual(reread.homeReturns, 2)
        XCTAssertEqual(reread.timesShown, 1)
        XCTAssertEqual(reread.snoozedUntil, Date(timeIntervalSince1970: 42))
        XCTAssertTrue(reread.settled)
    }
}
