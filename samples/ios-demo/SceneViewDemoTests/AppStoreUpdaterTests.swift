// AppStoreUpdaterTests.swift
//
// Unit tests for `samples/ios-demo/SceneViewDemo/Services/AppStoreUpdater.swift`.
//
// **Wiring status (2026-05-15).** This file is compiled by the
// `SceneViewDemoTests` unit-test target declared in
// `samples/ios-demo/SceneViewDemo.xcodeproj` and is run by CI via the shared
// `SceneViewDemo` scheme (`xcodebuild test`).
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
        // `nonisolated(unsafe)`: under Swift 6 strict concurrency a mutable
        // static is flagged. Access is serialized in practice — every test
        // assigns `responses` synchronously *before* awaiting the lookup, and
        // `startLoading()` pops exactly one entry per request. Each test also
        // uses a fresh ephemeral `URLSession`, so there is no cross-test race.
        nonisolated(unsafe) static var responses: [(Int, Data)] = []

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
        // Regression for #1249: a `lastCheckAt` stamped in the future (e.g. the
        // system clock was set ahead, then corrected) makes `now - last`
        // negative. A negative value is always below the throttle, so without
        // the `min(last, now)` clamp `shouldCheck()` would short-circuit every
        // future check *forever*. The clamp bounds the gap at 0, so the check
        // simply resumes once a normal throttle window has elapsed.
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        // One queued response — consumed only if the post-repair check runs.
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let defaults = Self.makeDefaults()
        let now = Date()
        // Simulate a `lastCheckAt` stamped 24h in the *future* (the bug input).
        defaults.set(now.addingTimeInterval(24 * 60 * 60).timeIntervalSince1970,
                     forKey: "sceneview.update.lastCheckAt")

        // A check "now" sees a future timestamp: clamped to `now`, gap is 0,
        // so it is (correctly) throttled — but NOT permanently negative-locked.
        let throttled = makeUpdater(now: now, defaults: defaults)
        await throttled.checkForUpdate()
        XCTAssertEqual(StubURLProtocol.responses.count, 1,
                       "Check within the throttle window must not hit the network")

        // Once wall-clock time surpasses the future timestamp by a throttle
        // window (24h ahead + 12h throttle → check 37h later), the check runs
        // again. Without the clamp `now - futureLast` would still be negative
        // — hence below the throttle — and the check would stay locked forever.
        let recovered = makeUpdater(now: now.addingTimeInterval(37 * 60 * 60),
                                    defaults: defaults)
        await recovered.checkForUpdate()

        XCTAssertEqual(StubURLProtocol.responses.count, 0,
                       "Clock rollback must not hard-lock update checks")
        XCTAssertTrue(recovered.state.isUpdateAvailable)
    }

    func testSnooze_clearsBannerStateForDismissedVersion() async throws {
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)
        XCTAssertTrue(updater.state.isUpdateAvailable)

        updater.snooze()
        XCTAssertEqual(updater.state, .upToDate, "snooze must clear the banner state")
    }

    /// Acceptance for #1231: a version-keyed snooze must NOT hide a *newer*
    /// release. Dismiss 4.3.1's banner → next check returns 4.4.0 → the banner
    /// re-surfaces (`isSnoozed == false`), because the snooze is keyed on the
    /// dismissed version string, not a time window.
    func testSnooze_newVersionInvalidatesSnoozeAndReSurfacesBanner() async throws {
        let defaults = Self.makeDefaults()

        // User is on 4.3.0; App Store advertises 4.3.1.
        let v431 = #"{"results":[{"version":"4.3.1","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(v431.utf8))]
        let firstCheck = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await firstCheck.checkForUpdate(force: true)
        XCTAssertTrue(firstCheck.state.isUpdateAvailable, "4.3.1 banner expected")

        // User taps "Later" — 4.3.1 is now snoozed.
        firstCheck.snooze()

        // App Store now advertises 4.4.0. A fresh updater (same defaults
        // suite, simulating a later app resume) re-checks.
        let v440 = #"{"results":[{"version":"4.4.0","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(v440.utf8))]
        let secondCheck = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await secondCheck.checkForUpdate(force: true)

        guard case let .updateAvailable(version, _) = secondCheck.state else {
            return XCTFail("Expected .updateAvailable for 4.4.0, got \(secondCheck.state)")
        }
        XCTAssertEqual(version, "4.4.0")
        XCTAssertFalse(secondCheck.isSnoozed,
                       "A newer version must invalidate the 4.3.1 snooze and re-surface the banner")
    }

    /// The snoozed version stays hidden if the App Store still advertises it.
    func testSnooze_sameVersionStaysSnoozedAcrossChecks() async throws {
        let defaults = Self.makeDefaults()
        let body = #"{"results":[{"version":"4.3.1","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#

        StubURLProtocol.responses = [(200, Data(body.utf8))]
        let firstCheck = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await firstCheck.checkForUpdate(force: true)
        firstCheck.snooze()

        StubURLProtocol.responses = [(200, Data(body.utf8))]
        let secondCheck = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await secondCheck.checkForUpdate(force: true)
        XCTAssertTrue(secondCheck.isSnoozed,
                      "Re-checking the same version must keep it snoozed")
    }

    // MARK: - Update toast visibility

    func testShowsUpdatePrompt_falseBeforeAnyCheck() {
        XCTAssertFalse(makeUpdater().showsUpdatePrompt)
    }

    func testShowsUpdatePrompt_trueWhenANewerVersionIsAvailable() async throws {
        let body = #"{"results":[{"version":"9.9.9","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater()
        await updater.checkForUpdate(force: true)

        XCTAssertTrue(updater.showsUpdatePrompt)
    }

    func testShowsUpdatePrompt_falseWhenUpToDate() async throws {
        let body = #"{"results":[{"version":"4.3.4","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#
        StubURLProtocol.responses = [(200, Data(body.utf8))]

        let updater = makeUpdater(currentVersion: "4.3.4")
        await updater.checkForUpdate(force: true)

        XCTAssertFalse(updater.showsUpdatePrompt)
    }

    /// Closing the toast hides it at once, and a later launch that sees the same
    /// version keeps it hidden — the toast never nags for a version already refused.
    func testShowsUpdatePrompt_falseAfterDismissal_andOnLaterLaunchesForThatVersion() async throws {
        let defaults = Self.makeDefaults()
        let body = #"{"results":[{"version":"4.3.1","bundleId":"io.github.sceneview.demo","releaseNotes":null}]}"#

        StubURLProtocol.responses = [(200, Data(body.utf8))]
        let first = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await first.checkForUpdate(force: true)
        XCTAssertTrue(first.showsUpdatePrompt)
        first.snooze()
        XCTAssertFalse(first.showsUpdatePrompt)

        StubURLProtocol.responses = [(200, Data(body.utf8))]
        let later = makeUpdater(defaults: defaults, currentVersion: "4.3.0")
        await later.checkForUpdate(force: true)
        XCTAssertFalse(later.showsUpdatePrompt)
    }

    /// The DEBUG `-update_qa available` path: a forced version is reported
    /// without a network call, so a simulator can show the toast.
    func testForcedVersion_surfacesTheToastWithoutTheNetwork() async throws {
        StubURLProtocol.responses = []   // any lookup would fail
        let updater = AppStoreUpdater(
            session: makeSession(),
            defaults: Self.makeDefaults(),
            now: { Date() },
            currentVersion: { "4.3.4" },
            forcedVersion: "99.0.0"
        )

        await updater.checkForUpdate()

        XCTAssertEqual(updater.state, .updateAvailable(version: "99.0.0", notes: nil))
        XCTAssertTrue(updater.showsUpdatePrompt)
    }

    func testLaunchArgForcedVersion_isNilWithoutTheQaArgument() {
        // The test runner is not launched with `-update_qa available`.
        XCTAssertNil(AppStoreUpdater.launchArgForcedVersion)
    }
}

private extension AppStoreUpdater.UpdateState {
    var isUpdateAvailable: Bool {
        if case .updateAvailable = self { return true }
        return false
    }
}

#endif
