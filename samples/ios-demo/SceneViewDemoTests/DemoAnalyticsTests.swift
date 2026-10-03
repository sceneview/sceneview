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
        case deleteUnsent
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
    func deleteUnsentReports() { record(.deleteUnsent) }
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

    /// A tester install that had turned the switch OFF before the consent existed.
    private func makeAnalyticsAfterLegacyOptOut(requiresConsent: Bool = false) -> DemoAnalytics {
        defaults.set(false, forKey: DemoAnalytics.usageStatsKey)
        let clock = self.clock!
        let store = ConsentStore(defaults: defaults, requiresConsent: requiresConsent, now: { clock.date })
        return DemoAnalytics(backend: NoopAnalyticsBackend(), defaults: defaults, consent: store, now: { clock.date })
    }

    func testInstallHonoursAPreviousOptOut() {
        let analytics = makeAnalyticsAfterLegacyOptOut()
        analytics.install(backend)
        XCTAssertEqual(backend.calls, [.collection(false)])
    }

    func testOptOutLogsFirstThenDisablesThenResetsThenDeletesReports() {
        analytics.install(backend)
        analytics.setUsageStatsEnabled(false)
        XCTAssertEqual(backend.calls, [
            .collection(true),
            .log("settings_changed", ["key": "analytics", "value": "false"]),
            .collection(false),
            .reset,
            .deleteUnsent,
        ])
        XCTAssertFalse(analytics.usageStatsEnabled)
        XCTAssertEqual(defaults.string(forKey: ConsentStore.stateKey), "denied")
    }

    /// Consent zone, nothing answered: collection off, nothing logged.
    func testConsentZoneCollectsNothingBeforeTheAnswer() {
        let store = ConsentStore(defaults: defaults, requiresConsent: true)
        let analytics = DemoAnalytics(backend: NoopAnalyticsBackend(), defaults: defaults, consent: store)
        analytics.install(backend)
        analytics.log(.pushPromptShown)
        analytics.sampleOpened("cosmos", category: "View 3D", source: .home)
        XCTAssertEqual(backend.calls, [.collection(false)])
    }

    /// Consent zone, "Share": the backend starts (strict mode) with collection off, the
    /// reports cached before are deleted, then collection goes on and the opt-in is logged.
    func testConsentZoneShareStartsTheBackendThenEnables() {
        let clock = self.clock!
        let store = ConsentStore(defaults: defaults, requiresConsent: true, now: { clock.date })
        let analytics = DemoAnalytics(backend: NoopAnalyticsBackend(), defaults: defaults, consent: store)
        analytics.setUsageStatsEnabled(true) { analytics.install(self.backend) }
        XCTAssertEqual(backend.calls, [
            .collection(false),
            .deleteUnsent,
            .collection(true),
            .log("settings_changed", ["key": "analytics", "value": "true"]),
        ])
        XCTAssertEqual(store.state, .granted)
        XCTAssertEqual(store.consentedAt, clock.date)
    }

    /// Consent zone, "Don't share" before anything was allowed: nothing is logged.
    func testConsentZoneRefusalLogsNothing() {
        let store = ConsentStore(defaults: defaults, requiresConsent: true)
        let analytics = DemoAnalytics(backend: NoopAnalyticsBackend(), defaults: defaults, consent: store)
        analytics.install(backend)
        analytics.setUsageStatsEnabled(false)
        XCTAssertEqual(backend.calls, [.collection(false), .collection(false), .reset, .deleteUnsent])
        XCTAssertEqual(store.state, .denied)
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
        let analytics = makeAnalyticsAfterLegacyOptOut()
        analytics.install(backend)
        analytics.setUsageStatsEnabled(true)
        XCTAssertEqual(backend.calls, [
            .collection(false),
            .deleteUnsent,
            .collection(true),
            .log("settings_changed", ["key": "analytics", "value": "true"]),
        ])
    }

    func testSampleOpenAndCloseWithDuration() {
        analytics.install(backend)
        analytics.sampleOpened("cosmos", category: "view_3d", source: .push)
        clock.date.addTimeInterval(42.7)
        analytics.sampleClosed("cosmos")
        XCTAssertEqual(backend.calls.dropFirst().map { $0 }, [
            .log("screen_view", ["screen_name": "cosmos", "screen_class": "Sample"]),
            .log("sample_open", [
                "sample_id": "cosmos", "entry_id": "cosmos", "category": "view_3d", "source": "push",
            ]),
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

    @MainActor
    func testCategoriesMatchAndroidSlugs() {
        let expected: [DemoSection: String] = [
            .view3d: "view_3d", .create: "create", .placeAR: "place_ar",
            .understand: "understand", .devTools: "dev_tools",
        ]
        for section in Set(GeneratedScenes.all().map(\.section)) {
            XCTAssertEqual(DemoAnalytics.category(for: section), expected[section])
            XCTAssertNotEqual(DemoAnalytics.category(for: section), "unknown")
        }
        XCTAssertEqual(DemoAnalytics.category(for: nil), "unknown")
    }

    func testSampleOpenEntryIdAndOptionalModeMatchAndroid() {
        XCTAssertEqual(
            AnalyticsEvent.sampleOpen(sampleId: "cosmos", entryId: "cosmos-old", category: "create",
                                      source: .deeplink, mode: "spacetime").params,
            ["sample_id": "cosmos", "entry_id": "cosmos-old", "category": "create",
             "source": "deeplink", "mode": "spacetime"]
        )
        let withoutMode = AnalyticsEvent.sampleOpen(sampleId: "geometry", category: "create", source: .home).params
        XCTAssertEqual(withoutMode["entry_id"], "geometry")
        XCTAssertNil(withoutMode["mode"])

        let longValue = String(repeating: "x", count: AnalyticsEvent.maxParamValueLength + 20)
        let capped = AnalyticsEvent.sampleOpen(
            sampleId: longValue, entryId: longValue, category: longValue,
            source: .other, mode: longValue
        )
        for (key, value) in capped.params {
            XCTAssertNotNil(key.range(of: "^[a-z][a-z0-9_]*$", options: .regularExpression))
            if case .string(let string) = value {
                XCTAssertLessThanOrEqual(string.count, AnalyticsEvent.maxParamValueLength)
            }
        }
    }

    @MainActor
    func testUmbrellaModesAndModeInteractionUseSharedValues() {
        XCTAssertEqual(DemoAnalytics.initialMode(for: "cosmos"), "starlight")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "cosmos", tab: "1"), "spacetime")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "cosmos", tab: "spacetime"), "spacetime")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "lighting", tab: "2"), "sun")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "ar-placement"), "place")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "ar-placement", tab: "1"), "wall")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "ar-placement", tab: "99"), "place")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "materials"), "gallery")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "materials", tab: "2"), "occlusion")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "model-viewer"), "single_model")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "model-viewer", tab: "1"), "multi_model")
        XCTAssertEqual(DemoAnalytics.initialMode(for: "rolling-balls", tab: "1"), "pendulum")
        XCTAssertNil(DemoAnalytics.initialMode(for: "geometry"))

        XCTAssertEqual(DemoAnalytics.modeControl("x"), "mode_x")
        XCTAssertEqual(
            AnalyticsEvent.sampleInteraction(sampleId: "cosmos", control: DemoAnalytics.modeControl("x")).params["control"],
            "mode_x"
        )
        let longControl = AnalyticsEvent.sampleInteraction(
            sampleId: "cosmos",
            control: DemoAnalytics.modeControl(String(repeating: "x", count: 200))
        ).params["control"]
        guard case .string(let value)? = longControl else { return XCTFail("control must be a string") }
        XCTAssertEqual(value.count, AnalyticsEvent.maxParamValueLength)
    }

    @MainActor
    func testModeCatalogueAndAliasedModesResolveToLiveCards() {
        for id in DemoAnalytics.modeSampleIds {
            XCTAssertTrue(GeneratedScenes.allowedIds.contains(id), id)
        }
        for (alias, mode) in DemoDeepLinkRegistry.aliasModes {
            guard let card = DemoDeepLinkRegistry.legacyAliases[alias] else {
                return XCTFail("\(alias) has a mode but no card")
            }
            XCTAssertTrue(GeneratedScenes.allowedIds.contains(card), "\(alias) -> \(card)")
            XCTAssertEqual(DemoAnalytics.initialMode(for: card, tab: mode), mode, alias)
        }
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
