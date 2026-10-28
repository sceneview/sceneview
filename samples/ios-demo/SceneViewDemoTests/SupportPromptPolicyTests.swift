// SupportPromptPolicyTests.swift
//
// Unit tests for the "Support SceneView" prompt policy
// (`SceneViewDemo/Services/Support/SupportPromptPolicy.swift`): never in the first two
// sessions, at most once every 14 days, ended for good by "Don't ask again" or a tip.

import XCTest
@testable import SceneViewDemo

private final class MemoryStore: SupportPromptStore {
    var sessions = 0
    var lastBackground: Date?
    var lastShown: Date?
    var neverAsk = false
    var supported = false
}

final class SupportPromptPolicyTests: XCTestCase {
    private var store: MemoryStore!
    private var now: Date!

    override func setUp() {
        super.setUp()
        store = MemoryStore()
        now = Date(timeIntervalSince1970: 2_000_000)
    }

    /// A cold launch: a fresh policy over the same store, as a new process would build.
    @discardableResult
    private func launch() -> SupportPromptPolicy {
        let policy = SupportPromptPolicy(store: store, now: { [unowned self] in self.now })
        policy.onBecameActive()
        return policy
    }

    func testNeverInTheFirstTwoSessions() {
        XCTAssertFalse(launch().shouldShow)
        XCTAssertFalse(launch().shouldShow)
    }

    func testShownFromTheThirdSession() {
        launch(); launch()
        XCTAssertTrue(launch().shouldShow)
    }

    func testShortTripToTheBackgroundIsTheSameSession() {
        let policy = launch()
        policy.onEnteredBackground()
        now += 5 * 60
        policy.onBecameActive()
        XCTAssertEqual(store.sessions, 1)
    }

    func testLongTripToTheBackgroundIsANewSession() {
        let policy = launch()
        for _ in 0..<2 {
            policy.onEnteredBackground()
            now += SupportPromptPolicy.sessionGap
            policy.onBecameActive()
        }
        XCTAssertEqual(store.sessions, 3)
        XCTAssertTrue(policy.shouldShow)
    }

    func testAtMostOnceEveryFourteenDays() {
        launch(); launch()
        let policy = launch()
        policy.onShown()
        XCTAssertFalse(policy.shouldShow)
        now += SupportPromptPolicy.interval - 60
        XCTAssertFalse(policy.shouldShow)
        now += 60
        XCTAssertTrue(policy.shouldShow)
    }

    func testDontAskAgainEndsIt() {
        launch(); launch()
        let policy = launch()
        policy.onNeverAsk()
        now += 365 * 24 * 60 * 60
        XCTAssertFalse(launch().shouldShow)
    }

    func testATipEndsIt() {
        launch(); launch()
        let policy = launch()
        policy.onSupported()
        now += 365 * 24 * 60 * 60
        XCTAssertFalse(launch().shouldShow)
    }
}
