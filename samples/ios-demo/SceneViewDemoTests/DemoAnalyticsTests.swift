// DemoAnalyticsTests.swift
//
// Unit tests for the demo app's analytics facade
// (`SceneViewDemo/Services/Telemetry/DemoAnalytics.swift`): the opt-out switch,
// its ordering (log the opt-out, disable, reset), gating, sample durations and the
// taxonomy helpers shared with the Android demo.

import XCTest
import SceneViewSwift
@testable import SceneViewDemo

private final class RecordingBackend: AnalyticsBackend, @unchecked Sendable {
    enum Call: Equatable {
        case log(String, [String: AnalyticsParam])
        case userProperty(String?, AnalyticsUserProperty)
        case collection(Bool)
        case reset
    }

    private let lock = NSLock()
    private var storage: [Call] = []

    var calls: [Call] {
        lock.lock(); defer { lock.unlock() }
        return storage
    }

    var eventNames: [String] {
        calls.compactMap { if case .log(let name, _) = $0 { return name } else { return nil } }
    }

    private func record(_ call: Call) {
        lock.lock(); storage.append(call); lock.unlock()
    }

    func log(_ event: AnalyticsEvent) { record(.log(event.name, event.params)) }
    func setUserProperty(_ value: String?, for property: AnalyticsUserProperty) { record(.userProperty(value, property)) }
    func setCollectionEnabled(_ enabled: Bool) { record(.collection(enabled)) }
    func resetAnalyticsData() { record(.reset) }
}

private final class Clock: @unchecked Sendable {
    var date = Date(timeIntervalSince1970: 1_000_000)
}

final class DemoAnalyticsTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var backend: RecordingBackend!
    private var clock: Clock!
    private var analytics: DemoAnalytics!

    override func setUp() {
        super.setUp()
        suiteName = "DemoAnalyticsTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
        backend = RecordingBackend()
        clock = Clock()
        let clock = self.clock!
        analytics = DemoAnalytics(backend: NoopAnalyticsBackend(), defaults: defaults, now: { clock.date })
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    func testUsageStatsDefaultsToOn() {
        XCTAssertTrue(analytics.usageStatsEnabled)
        analytics.install(backend)
        XCTAssertEqual(backend.calls, [.collection(true)])
    }

    func testInstallHonoursAPreviousOptOut() {
        defaults.set(false, forKey: DemoAnalytics.usageStatsKey)
        analytics.install(backend)
        XCTAssertEqual(backend.calls, [.collection(false)])
    }

    func testOptOutLogsFirstThenDisablesThenResets() {
        analytics.install(backend)
        analytics.setUsageStatsEnabled(false)
        XCTAssertEqual(backend.calls, [
            .collection(true),
            .log("settings_changed", ["key": "analytics", "value": "false"]),
            .collection(false),
            .reset,
        ])
        XCTAssertFalse(analytics.usageStatsEnabled)
        XCTAssertEqual(defaults.object(forKey: DemoAnalytics.usageStatsKey) as? Bool, false)
    }

    func testNothingIsSentAfterOptOut() {
        analytics.install(backend)
        analytics.setUsageStatsEnabled(false)
        let before = backend.calls.count
        analytics.log(.pushPromptShown)
        analytics.setUserProperty("dark", for: .appTheme)
        analytics.sampleOpened("cosmos", category: "View 3D", source: .home)
        analytics.interaction("cosmos", "galaxy")
        XCTAssertEqual(backend.calls.count, before)
    }

    func testOptInEnablesThenLogs() {
        defaults.set(false, forKey: DemoAnalytics.usageStatsKey)
        analytics.install(backend)
        analytics.setUsageStatsEnabled(true)
        XCTAssertEqual(backend.calls, [
            .collection(false),
            .collection(true),
            .log("settings_changed", ["key": "analytics", "value": "true"]),
        ])
    }

    func testSampleOpenAndCloseWithDuration() {
        analytics.install(backend)
        analytics.sampleOpened("cosmos", category: "View 3D", source: .push)
        clock.date.addTimeInterval(42.7)
        analytics.sampleClosed("cosmos")
        XCTAssertEqual(backend.calls.dropFirst().map { $0 }, [
            .log("screen_view", ["screen_name": "cosmos", "screen_class": "Sample"]),
            .log("sample_open", ["sample_id": "cosmos", "category": "View 3D", "source": "push"]),
            .log("sample_close", ["sample_id": "cosmos", "duration_s": .int(42)]),
        ])
    }

    func testDoubleCloseIsCountedOnce() {
        analytics.install(backend)
        analytics.sampleOpened("cosmos", category: "View 3D", source: .home)
        analytics.sampleClosed("cosmos")
        analytics.sampleClosed("cosmos")
        analytics.sampleClosed("never-opened")
        XCTAssertEqual(backend.eventNames.filter { $0 == "sample_close" }.count, 1)
    }

    func testTaxonomyNames() {
        XCTAssertEqual(AnalyticsEvent.sampleInteraction(sampleId: "cosmos", control: "burst").name, "sample_interaction")
        XCTAssertEqual(AnalyticsEvent.arSessionCreated(sampleId: "ar").name, "ar_session_created")
        XCTAssertEqual(AnalyticsEvent.arTrackingReady(sampleId: "ar").name, "ar_tracking_ready")
        XCTAssertEqual(AnalyticsEvent.arFirstPlacement(sampleId: "ar").name, "ar_first_placement")
        XCTAssertEqual(AnalyticsEvent.arTrackingLost(sampleId: "ar", reason: "excessive_motion").params["reason"], "excessive_motion")
        XCTAssertEqual(AnalyticsEvent.arSessionFailed(sampleId: "ar", reason: "x").name, "ar_session_failed")
        XCTAssertEqual(AnalyticsEvent.modelLoadFailed(sampleId: "m", reason: "r").name, "model_load_failed")
        XCTAssertEqual(AnalyticsEvent.pushPromptShown.name, "push_prompt_shown")
        XCTAssertEqual(AnalyticsEvent.pushPromptResult(.notNow).params, ["result": "not_now"])
        XCTAssertEqual(AnalyticsEvent.pushPromptResult(.granted).params, ["result": "granted"])
        XCTAssertEqual(AnalyticsEvent.pushOpened(campaign: "launch", sampleId: "cosmos").params,
                       ["campaign": "launch", "sample_id": "cosmos"])
        XCTAssertEqual(AnalyticsEvent.outboundLink(target: .github).params, ["target": "github"])
        XCTAssertEqual(AnalyticsEvent.outboundLink(target: .docs, sampleId: "cosmos").params,
                       ["target": "docs", "sample_id": "cosmos"])
    }

    func testOutboundTargets() {
        XCTAssertEqual(OutboundTarget.classify(URL(string: "https://github.com/sceneview/sceneview")!), .github)
        XCTAssertEqual(OutboundTarget.classify(URL(string: "https://apps.apple.com/app/id6475390374")!), .store)
        XCTAssertEqual(OutboundTarget.classify(URL(string: "itms-apps://itunes.apple.com/app/id1")!), .store)
        XCTAssertEqual(OutboundTarget.classify(URL(string: "https://sceneview.github.io/docs")!), .docs)
        XCTAssertEqual(OutboundTarget.classify(URL(string: "https://sketchfab.com/3d-models/x")!), .other)
    }

    func testCategoriesMatchAndroidKeys() {
        XCTAssertEqual(DemoAnalytics.category(for: .view3d), "View 3D")
        XCTAssertEqual(DemoAnalytics.category(for: .create), "Create & Record")
        XCTAssertEqual(DemoAnalytics.category(for: .placeAR), "Place in AR")
        XCTAssertEqual(DemoAnalytics.category(for: .understand), "Understand the World")
        XCTAssertEqual(DemoAnalytics.category(for: .devTools), "Developer Tools")
        XCTAssertEqual(DemoAnalytics.category(for: nil), "unknown")
    }

    func testErrorReasonCarriesNoMessage() {
        let error = NSError(domain: "SceneView", code: 7,
                            userInfo: [NSLocalizedDescriptionKey: "/Users/someone/private/model.usdz"])
        XCTAssertEqual(DemoAnalytics.reason(for: error), "SceneView:7")
    }

    func testModelLoadReasonsMatchAndroidKeys() {
        let file = URL(fileURLWithPath: "/tmp/m.usdz")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: ModelLoadingError.unreadableFile(file)), "asset_missing")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: ModelLoadingError.emptyMesh), "no_bounds")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: ModelLoadingError.malformed(reason: "x")), "decode_failed")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: ModelLoadingError.unsupportedFormat(fileExtension: "fbx")),
                       "decode_failed")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: URLError(.notConnectedToInternet)), "asset_missing")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: SketchfabError.downloadFailed), "asset_missing")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: CocoaError(.fileNoSuchFile)), "asset_missing")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: CocoaError(.fileReadCorruptFile)), "decode_failed")
        XCTAssertEqual(DemoAnalytics.modelLoadReason(for: NSError(domain: "Elsewhere", code: 1)), "unknown")
    }
}
