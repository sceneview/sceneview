// ConsentTests.swift
//
// Unit tests for the usage-statistics consent (`SceneViewDemo/Services/Telemetry/`
// `ConsentRegion.swift`, `ConsentStore.swift`, `FirebaseLeftovers.swift`): who is asked, the migration of tester
// installs from before the consent, `consent_version`, strict mode at launch and the
// `-telemetry_consent` launch argument. Same cases as the Android demo's tests.

import XCTest
@testable import SceneViewDemo

final class ConsentRegionTests: XCTestCase {
    private func asks(_ region: String?, _ timeZone: String?) -> Bool {
        ConsentRegion.requiresConsent(regionCode: region, timeZoneIdentifier: timeZone)
    }

    func testFrenchRegionWithAUSTimeZoneAsks() {
        XCTAssertTrue(asks("FR", "America/New_York"))
    }

    func testUSTimeZoneWithAFrenchRegionAsks() {
        XCTAssertTrue(asks("US", "Europe/Paris"))
    }

    func testEverythingUSDoesNotAsk() {
        XCTAssertFalse(asks("US", "America/New_York"))
        XCTAssertFalse(asks("JP", "Asia/Tokyo"))
        XCTAssertFalse(asks("CA", "America/Toronto"))
    }

    func testNoReadableSignalAsks() {
        XCTAssertTrue(asks(nil, nil))
        XCTAssertTrue(asks("", ""))
        XCTAssertTrue(asks("001", "UTC"))
        XCTAssertTrue(asks("ZZ", "GMT"))
        XCTAssertTrue(asks(nil, "Etc/GMT+2"))
    }

    func testOneReadableSignalOutsideTheZoneIsEnough() {
        XCTAssertFalse(asks("US", nil))
        XCTAssertFalse(asks(nil, "America/Chicago"))
        XCTAssertFalse(asks("BR", "UTC"))
    }

    func testCanariesAsk() {
        XCTAssertTrue(asks("IC", "Atlantic/Canary"))
        XCTAssertTrue(asks("US", "Atlantic/Canary"))
    }

    func testUnitedKingdomAsks() {
        XCTAssertTrue(asks("GB", "Europe/London"))
        XCTAssertTrue(asks("GB", "America/New_York"))
    }

    func testSwitzerlandAsks() {
        XCTAssertTrue(asks("CH", "Europe/Zurich"))
        XCTAssertTrue(asks("CH", "Asia/Tokyo"))
    }

    func testEEAAndOutermostRegionsAsk() {
        for region in ["DE", "IT", "ES", "PL", "IE", "NO", "IS", "LI", "GP", "RE", "GF"] {
            XCTAssertTrue(asks(region, "America/New_York"), region)
        }
        for zone in ["Atlantic/Madeira", "Atlantic/Azores", "Indian/Reunion", "America/Martinique",
                     "America/Cayenne", "Africa/Ceuta", "Atlantic/Reykjavik"] {
            XCTAssertTrue(asks("US", zone), zone)
        }
    }

    func testCyprusAndTerritoriesAsk() {
        for zone in ["Asia/Nicosia", "Asia/Famagusta", "America/Marigot", "Arctic/Longyearbyen",
                     "America/Guadeloupe", "Indian/Mayotte"] {
            XCTAssertTrue(asks("US", zone), zone)
        }
        for region in ["SJ", "EL", "MF", "AX", "GI", "IM", "JE", "GG", "YT", "MQ", "CY"] {
            XCTAssertTrue(asks(region, "America/New_York"), region)
        }
    }

    func testLegacyTimeZoneAliasesAsk() {
        for zone in ["CET", "WET", "MET", "EET", "Eire", "GB", "GB-Eire", "Iceland", "Poland", "Portugal"] {
            XCTAssertTrue(asks("US", zone), zone)
        }
    }

    func testPlacelessTimeZonesAreNoSignal() {
        for zone in ["UTC", "UCT", "GMT", "GMT0", "GMT+0", "GMT-0", "Greenwich", "Universal", "Zulu", "Etc/UTC"] {
            XCTAssertFalse(asks("US", zone), zone)
            XCTAssertTrue(asks(nil, zone), zone)
        }
    }

    func testOtherAsianZonesDoNotAsk() {
        XCTAssertFalse(asks("US", "Asia/Istanbul"))
        XCTAssertFalse(asks("US", "Asia/Tbilisi"))
    }

    func testRegionCaseDoesNotMatter() {
        XCTAssertTrue(asks("fr", "America/New_York"))
    }
}

final class ConsentStoreTests: XCTestCase {
    private var defaults: UserDefaults!
    private var suiteName: String!
    private let date = Date(timeIntervalSince1970: 1_800_000_000)

    override func setUp() {
        super.setUp()
        suiteName = "ConsentStoreTests-\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func makeStore(requiresConsent: Bool = true) -> ConsentStore {
        let date = self.date
        return ConsentStore(defaults: defaults, requiresConsent: requiresConsent, now: { date })
    }

    // MARK: Fresh install

    func testFreshInstallInTheZoneIsAskedAndCollectsNothing() {
        let store = makeStore()
        XCTAssertEqual(store.state, .unknown)
        XCTAssertTrue(store.needsPrompt)
        XCTAssertFalse(store.collectionAllowed)
        XCTAssertFalse(store.shouldStartFirebaseAtLaunch, "strict mode: no configure() before the yes")
    }

    func testFreshInstallOutsideTheZoneIsNotAskedAndCollects() {
        let store = makeStore(requiresConsent: false)
        XCTAssertFalse(store.needsPrompt)
        XCTAssertTrue(store.collectionAllowed)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch)
    }

    func testOptOutOutsideTheZone() {
        let store = makeStore(requiresConsent: false)
        store.record(.denied)
        XCTAssertFalse(store.collectionAllowed)
        XCTAssertFalse(store.shouldStartFirebaseAtLaunch, "nothing allows it: no collection, no push")
    }

    // MARK: Answers

    func testGrantIsRecordedWithTimeAndVersion() {
        let store = makeStore()
        store.record(.granted)
        XCTAssertEqual(store.state, .granted)
        XCTAssertEqual(store.consentedAt, date)
        XCTAssertEqual(defaults.integer(forKey: ConsentStore.versionKey), ConsentStore.currentVersion)
        XCTAssertFalse(store.needsPrompt)
        XCTAssertTrue(store.collectionAllowed)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch)
    }

    func testRefusalIsNeverAskedAgain() {
        makeStore().record(.denied)
        let relaunched = makeStore()
        XCTAssertEqual(relaunched.state, .denied)
        XCTAssertFalse(relaunched.needsPrompt)
        XCTAssertFalse(relaunched.collectionAllowed)
        XCTAssertFalse(relaunched.shouldStartFirebaseAtLaunch)
    }

    func testAnOlderConsentVersionAsksAgain() {
        let store = makeStore()
        store.record(.denied)
        defaults.set(ConsentStore.currentVersion - 1, forKey: ConsentStore.versionKey)
        XCTAssertEqual(store.storedState, .denied)
        XCTAssertEqual(store.state, .unknown)
        XCTAssertTrue(store.needsPrompt)
    }

    func testPushKeepsFirebaseAtLaunchWithoutTheUsageConsent() {
        let store = makeStore()
        store.record(.denied)
        store.pushNeedsFirebase = true
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch)
        XCTAssertFalse(store.collectionAllowed)
    }

    // MARK: Migration

    func testLegacyExplicitOffBecomesDenied() {
        defaults.set(false, forKey: ConsentStore.legacyUsageStatsKey)
        let store = makeStore()
        XCTAssertEqual(store.state, .denied)
        XCTAssertFalse(store.needsPrompt)
        XCTAssertEqual(store.consentedAt, date)
    }

    func testLegacyDefaultOnIsAskedOnce() {
        defaults.set(true, forKey: ConsentStore.legacyUsageStatsKey)
        let store = makeStore()
        XCTAssertEqual(store.state, .unknown)
        XCTAssertTrue(store.needsPrompt)
    }

    /// An upgrade from a build without the consent, push on: Firebase starts at launch for
    /// push, with collection off (`FirebaseTelemetry.configure` clears what that build saved
    /// first), and the usage statistics are still asked.
    func testLegacyPushSubscriberStartsFirebaseForPushBeforeTheAnswer() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        XCTAssertTrue(store.pushNeedsFirebase)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch, "push the user already turned on")
        XCTAssertFalse(store.collectionAllowed, "collection off until the answer")
        XCTAssertTrue(store.needsPrompt, "still asked for the usage statistics")
    }

    func testLegacyExplicitOffKeepsPushAndRefuses() {
        defaults.set(false, forKey: ConsentStore.legacyUsageStatsKey)
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        XCTAssertEqual(store.state, .denied)
        XCTAssertFalse(store.collectionAllowed)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch, "push keeps Firebase at launch")
    }

    func testOutsideTheZonePushAloneStartsFirebase() {
        let store = makeStore(requiresConsent: false)
        store.record(.denied)
        store.pushNeedsFirebase = true
        XCTAssertFalse(store.collectionAllowed)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch)
    }

    func testMigrationRunsOnlyBeforeTheFirstAnswer() {
        makeStore().record(.granted)
        defaults.set(false, forKey: ConsentStore.legacyUsageStatsKey)
        XCTAssertEqual(makeStore().state, .granted)
    }

    // MARK: Launch argument

    func testLaunchArgumentForms() {
        typealias Override = ConsentStore.LaunchOverride
        XCTAssertEqual(Override.parse(["app", "-telemetry_consent", "denied"], defaults: defaults), .denied)
        XCTAssertEqual(Override.parse(["app", "-telemetry_consent", "GRANTED"], defaults: defaults), .granted)
        XCTAssertEqual(Override.parse(["app", "telemetry_consent=ask"], defaults: defaults), .ask)
        XCTAssertNil(Override.parse(["app", "-push_preprompt", "off"], defaults: defaults))
        XCTAssertNil(Override.parse(["app", "-telemetry_consent", "maybe"], defaults: defaults))
        defaults.set("denied", forKey: "telemetry_consent")
        XCTAssertEqual(Override.parse(["app"], defaults: defaults), .denied)
    }

    func testAskForgetsTheAnswer() {
        let store = makeStore()
        store.record(.denied)
        store.apply(.ask)
        XCTAssertEqual(store.state, .unknown)
        XCTAssertNil(store.consentedAt)
        XCTAssertTrue(store.needsPrompt)
    }

    func testAskForcesTheZone() {
        XCTAssertTrue(ConsentStore.requiresConsent(override: .ask, inRegion: false))
        XCTAssertFalse(ConsentStore.requiresConsent(override: .denied, inRegion: false))
        XCTAssertFalse(ConsentStore.requiresConsent(override: nil, inRegion: false))
        XCTAssertTrue(ConsentStore.requiresConsent(override: .granted, inRegion: true))
    }
}

/// `FirebaseTelemetry.configure` and `FirebaseLeftovers`: a start that must not collect
/// first clears the collection settings Firebase saved earlier, as on Android (#4257).
final class FirebaseLeftoversTests: XCTestCase {
    private var root: URL!
    private var suiteName: String!
    private var analytics: UserDefaults!

    override func setUp() {
        super.setUp()
        root = FileManager.default.temporaryDirectory
            .appendingPathComponent("FirebaseLeftoversTests-\(UUID().uuidString)")
        suiteName = "FirebaseLeftoversTests.\(UUID().uuidString)"
        analytics = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: root)
        analytics.removePersistentDomain(forName: suiteName)
        super.tearDown()
    }

    private func writePlist(_ dictionary: [String: Any], at path: String) throws {
        let url = root.appendingPathComponent(path)
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let data = try PropertyListSerialization.data(fromPropertyList: dictionary, format: .binary, options: 0)
        try data.write(to: url)
    }

    private func readPlist(at path: String) -> [String: Any]? {
        guard let data = try? Data(contentsOf: root.appendingPathComponent(path)) else { return nil }
        return try? PropertyListSerialization.propertyList(from: data, options: [], format: nil) as? [String: Any]
    }

    private func clear() -> Int {
        FirebaseLeftovers.clear(applicationSupport: root, bundleIdentifier: "io.github.sceneview.demo",
                                analyticsDefaults: analytics)
    }

    /// What a build that collected leaves, as read on a simulator install.
    func testClearsWhatACollectingBuildSaved() throws {
        try writePlist([FirebaseLeftovers.crashlyticsKey: 1, "com.crashlytics.iuuid": "kept"],
                       at: FirebaseLeftovers.crashlyticsFile)
        try writePlist([FirebaseLeftovers.measurementKey: 1, "/google/measurement/app_instance_id": "kept"],
                       at: FirebaseLeftovers.measurementFile)
        analytics.set(["analytics_storage": "granted"], forKey: "consent_settings_3p")
        analytics.set(10, forKey: "consent_source")
        analytics.set(0, forKey: "deferred_analytics_collection")

        XCTAssertEqual(clear(), 4)

        let crashlytics = try XCTUnwrap(readPlist(at: FirebaseLeftovers.crashlyticsFile))
        XCTAssertNil(crashlytics[FirebaseLeftovers.crashlyticsKey])
        XCTAssertEqual(crashlytics["com.crashlytics.iuuid"] as? String, "kept")
        let measurement = try XCTUnwrap(readPlist(at: FirebaseLeftovers.measurementFile))
        XCTAssertNil(measurement[FirebaseLeftovers.measurementKey])
        XCTAssertEqual(measurement["/google/measurement/app_instance_id"] as? String, "kept")
        XCTAssertNil(analytics.object(forKey: "consent_settings_3p"))
        XCTAssertNil(analytics.object(forKey: "consent_source"))
        XCTAssertEqual(analytics.object(forKey: "deferred_analytics_collection") as? Int, 0, "not a collection setting")
    }

    func testMacStoreUnderTheBundleIdentifierIsClearedToo() throws {
        let path = "io.github.sceneview.demo/" + FirebaseLeftovers.crashlyticsFile
        try writePlist([FirebaseLeftovers.crashlyticsKey: 1], at: path)
        XCTAssertEqual(clear(), 1)
        XCTAssertNil(readPlist(at: path)?[FirebaseLeftovers.crashlyticsKey])
    }

    func testNothingSavedIsANoOp() {
        XCTAssertEqual(clear(), 0)
        XCTAssertFalse(FileManager.default.fileExists(atPath: root.path), "creates nothing")
    }

    func testAStartWithoutCollectionClearsBeforeConfigure() {
        var calls: [String] = []
        FirebaseTelemetry.configure(collecting: false, clearLeftovers: { calls.append("clear") }) {
            calls.append("configure")
        }
        XCTAssertEqual(calls, ["clear", "configure"])
    }

    func testAStartThatCollectsKeepsTheSettings() {
        var calls: [String] = []
        FirebaseTelemetry.configure(collecting: true, clearLeftovers: { calls.append("clear") }) {
            calls.append("configure")
        }
        XCTAssertEqual(calls, ["configure"])
    }
}
