// AppStoreUpdaterTests.swift
//
// Unit tests for `samples/ios-demo/SceneViewDemo/Services/AppStoreUpdater.swift`.
//
// **Wiring status (2026-05-15).** This file ships in the repo as a ready-to-use
// test fixture but the `samples/ios-demo/SceneViewDemo.xcodeproj` does NOT yet
// declare a unit-test target — adding one safely takes a dedicated PR that
// also wires the `SceneViewDemoTests` PBXNativeTarget, XCTest framework
// linkage, build configurations and a Test scheme. Until that lands, drop
// this file into Xcode → "Add files" → "Add to target: SceneViewDemoTests"
// to run locally, OR open the target via Xcode 16's project editor and let
// it generate the target boilerplate.
//
// The tests use a custom `URLProtocol` subclass so the iTunes lookup call
// returns canned JSON without hitting the network. No XCTestExpectation
// timeouts — every test uses `async/await`, which XCTest 14+ supports
// natively. Compatible with both `swift test` (via a Package.swift) and
// `xcodebuild test` (via an Xcode test target).

#if canImport(XCTest)

import XCTest
@testable import SceneViewDemo

@MainActor
final class AppStoreUpdaterTests: XCTestCase {

    // MARK: - URLProtocol stub

    /// Intercepts every `URLSession.data(from:)` call so the tests never
    /// hit `itunes.apple.com`. Each test pushes a `(status, body)` tuple
    /// onto the static queue and pops it on `startLoading()`.
    final class StubURLProtocol: URLProtocol {
        static var responses: [(Int, Data)] = []

        override class func canInit(with request: URLRequest) -> Bool { true }
        override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

        override func startLoading() {
            guard !Self.responses.isEmpty else {
                client?.urlProtocol(self, didFailWithError: URLError(.cancelled))
                return
            }
            let (status, body) = Self.responses.removeFirst()
            let response = HTTPURLResponse(
                url: request.url!,
                statusCode: status,
                httpVersion: "HTTP/1.1",
                headerFields: ["Content-Type": "application/json"]
            )!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }

        override func stopLoading() {}
    }

    private func makeSession() -> URLSession {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: config)
    }

    private func makeUpdater(
        now: Date = Date(),
        defaults: UserDefaults = makeDefaults(),
        currentVersion: String = "4.3.4"
    ) -> AppStoreUpdater {
        // `currentVersion` defaults to a known string because the XCTest
        // runner's `Bundle.main` is the test harness bundle, not
        // `SceneViewDemo` — `Bundle.main.infoDictionary["CFBundleShortVersionString"]`
        // returns nil in that context. Production code path uses
        // `AppStoreUpdater.bundleVersion` as default.
        AppStoreUpdater(
            session: makeSession(),
            defaults: defaults,
            now: { now },
            currentVersion: { currentVersion }
        )
    }

    private static func makeDefaults() -> UserDefaults {
        let suite = "AppStoreUpdaterTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defaults.removePersistentDomain(forName: suite)
        return defaults
    }

    override func tearDown() {
        StubURLProtocol.responses = []
        super.tearDown()
    }

    // MARK: - Version comparison

    func testIsNewer_majorBumpDetected() {
        XCTAssertTrue(AppStoreUpdater.isNewer(latest: "5.0.0", current: "4.3.1"))
    }

    func testIsNewer_patchBumpDetected() {
        XCTAssertTrue(AppStoreUpdater.isNewer(latest: "4.3.2", current: "4.3.1"))
    }

    func testIsNewer_equalReturnsFalse() {
        XCTAssertFalse(AppStoreUpdater.isNewer(latest: "4.3.1", current: "4.3.1"))
    }

    func testIsNewer_currentAheadReturnsFalse() {
        XCTAssertFalse(AppStoreUpdater.isNewer(latest: "4.3.0", current: "4.3.1"))
    }

    func testIsNewer_doubleDigitComponentsHandled() {
        // Lexicographic comparison would say "10" < "9" — guard that we use ints.
        XCTAssertTrue(AppStoreUpdater.isNewer(latest: "4.10.0", current: "4.9.99"))
    }

    func testIsNewer_trailingZerosTreatedAsEqual() {
        XCTAssertFalse(AppStoreUpdater.isNewer(latest: "4.3", current: "4.3.0"))
    }

    // MARK: - Network flow

    func testCheck_updateAvailableSetsPublishedState() async throws {
        let body = #"""
        {
          "results": [{
            "version": "9.9.9",
            "bundleId": "io.github.sceneview.demo",
            "releaseNotes": "Magic."
          }]
        }
        """#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)

        guard case let .updateAvailable(version, notes) = updater.state else {
            return XCTFail("Expected .updateAvailable, got \(updater.state)")
        }
        XCTAssertEqual(version, "9.9.9")
        XCTAssertEqual(notes, "Magic.")
    }

    func testCheck_sameVersionSetsUpToDate() async throws {
        let current = "4.3.4"
        let body = #"{"results":[{"version":"\#(current)","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater(currentVersion: current)
        await updater.checkForUpdate(force: true)

        XCTAssertEqual(updater.state, .upToDate)
    }

    func testCheck_networkFailureFallsBackToIdle() async throws {
        StubURLProtocol.responses = []   // protocol returns .cancelled
        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)
        XCTAssertEqual(updater.state, .idle)
    }

    func testCheck_wrongBundleIdRejected() async throws {
        // An attacker re-publishes an app with the same display name; we
        // must NOT mistake their version for ours.
        let body = #"""
        {"results":[{"version":"99.99.99","bundleId":"com.attacker.fake","releaseNotes":null}]}
        """#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)

        XCTAssertEqual(updater.state, .idle, "Mismatched bundleId must not surface as updateAvailable")
    }

    // MARK: - Throttle + snooze

    func testCheck_secondCallWithinThrottleSkipsNetwork() async throws {
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        // Only ONE response queued — second `checkForUpdate` would crash if it hit the network.
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let defaults = Self.makeDefaults()
        let now = Date()
        let updater = makeUpdater(now: now, defaults: defaults)
        await updater.checkForUpdate()
        // Second call within the 12h window should NOT touch the queue.
        await updater.checkForUpdate()

        XCTAssertEqual(StubURLProtocol.responses.count, 0, "Single lookup expected — got 2")
    }

    func testCheck_clockRollbackDoesNotHardLockChecks() async throws {
        // Regression for #1249: if the system clock rolls backward, a
        // `lastCheckAt` stamped in the (now-)future makes `now - last`
        // negative — which is always below the throttle, so `shouldCheck()`
        // would short-circuit every future check forever. The `min(last, now)`
        // clamp on read repairs this.
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let defaults = Self.makeDefaults()
        // First check happens "today".
        let today = Date()
        let firstPass = makeUpdater(now: today, defaults: defaults)
        await firstPass.checkForUpdate()
        // `lastCheckAt` is now stamped at `today`.

        // The clock rolls back one full day — `lastCheckAt` is now in the
        // future relative to the new "now".
        let yesterday = today.addingTimeInterval(-24 * 60 * 60)
        let rolledBack = makeUpdater(now: yesterday, defaults: defaults)
        await rolledBack.checkForUpdate()

        // The check must have run despite the future `lastCheckAt`: the queued
        // response was consumed.
        XCTAssertEqual(StubURLProtocol.responses.count, 0,
                       "Clock rollback must not hard-lock update checks")
        XCTAssertTrue(rolledBack.state.isUpdateAvailable)
    }

    func testSnooze_hidesBannerForOneWeek() async throws {
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)
        XCTAssertTrue(updater.state.isUpdateAvailable)

        updater.snooze()
        XCTAssertTrue(updater.isSnoozed)
        XCTAssertEqual(updater.state, .upToDate, "snooze must clear the banner state")
    }
}

private extension AppStoreUpdater.UpdateState {
    var isUpdateAvailable: Bool {
        if case .updateAvailable = self { return true }
        return false
    }
}

#endif
