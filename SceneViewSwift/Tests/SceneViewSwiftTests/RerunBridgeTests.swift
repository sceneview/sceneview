#if os(iOS)
import XCTest
@testable import SceneViewSwift

/// Tests for ``RerunBridge``'s coalesced `eventCount` publishing (#2331 iOS7).
///
/// The bridge used to schedule one `DispatchQueue.main.async { eventCount += 1 }`
/// per emitted line (~420/s under a busy ARKit stream). The count is now bumped
/// on the bridge's I/O queue and published to the `@Published var eventCount`
/// in a coalesced fashion. These tests assert the observable contract is
/// preserved: after N flushes, `eventCount` converges to N — no count is lost,
/// no count is double-applied — while the per-line main-thread hop is gone.
///
/// `eventCount` is `@Published` (main-actor observed in SwiftUI), so the
/// assertions poll on the main run loop until the coalesced publish lands.
final class RerunBridgeTests: XCTestCase {

    /// N flushes through the real coalescing path must leave `eventCount == N`.
    func testEventCountConvergesToTotalAfterBurst() {
        let bridge = RerunBridge()
        XCTAssertEqual(bridge.eventCount, 0, "fresh bridge starts at 0")

        bridge.testOnlyRecordSent(420)

        let exp = expectation(description: "eventCount reaches 420")
        waitUntil(exp) { bridge.eventCount == 420 }
        wait(for: [exp], timeout: 5)
        XCTAssertEqual(bridge.eventCount, 420)
    }

    /// Multiple separate bursts accumulate — `eventCount` is cumulative and
    /// never resets between bursts (matching the pre-change behaviour, where
    /// the counter only ever incremented).
    func testEventCountAccumulatesAcrossBursts() {
        let bridge = RerunBridge()

        bridge.testOnlyRecordSent(10)
        let first = expectation(description: "reaches 10")
        waitUntil(first) { bridge.eventCount == 10 }
        wait(for: [first], timeout: 5)

        bridge.testOnlyRecordSent(15)
        let second = expectation(description: "reaches 25")
        waitUntil(second) { bridge.eventCount == 25 }
        wait(for: [second], timeout: 5)

        XCTAssertEqual(bridge.eventCount, 25, "cumulative across bursts, never reset")
    }

    /// A single flush still lands — the coalescing must not swallow the only
    /// event when there is no burst to batch it with.
    func testSingleFlushPublishesOne() {
        let bridge = RerunBridge()
        bridge.testOnlyRecordSent(1)
        let exp = expectation(description: "reaches 1")
        waitUntil(exp) { bridge.eventCount == 1 }
        wait(for: [exp], timeout: 5)
        XCTAssertEqual(bridge.eventCount, 1)
    }

    // MARK: - Helpers

    /// Polls `condition` on the main run loop and fulfils `exp` once it holds.
    /// Used instead of a fixed sleep so the test is robust to scheduling
    /// jitter on the coalesced publish without being slow on the happy path.
    private func waitUntil(_ exp: XCTestExpectation, _ condition: @escaping () -> Bool) {
        func poll() {
            if condition() {
                exp.fulfill()
                return
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.01) { poll() }
        }
        poll()
    }
}
#endif
