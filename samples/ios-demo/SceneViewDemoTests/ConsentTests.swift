// ConsentTests.swift
//
// Unit tests for the usage-statistics consent (`SceneViewDemo/Services/Telemetry/`
// `ConsentRegion.swift` and `ConsentStore.swift`): who is asked, the migration of tester
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
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch, "outside the zone Firebase starts as before")
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

    /// An upgrade from a build without the consent: Firebase's storage still says
    /// collection ON (Crashlytics' own store beats the Info.plist NO), so configuring
    /// before the answer would upload cached crashes and log `app_update` /
    /// `session_start`. The push exception waits for the answer.
    func testLegacyPushSubscriberWaitsForTheAnswerWhileCollectionMayBeOn() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        XCTAssertTrue(store.pushNeedsFirebase)
        XCTAssertTrue(store.collectionMayBeOn)
        XCTAssertTrue(store.configureWouldLeak)
        XCTAssertFalse(store.shouldStartFirebaseAtLaunch)
        XCTAssertTrue(store.needsPrompt, "still asked for the usage statistics")
    }

    func testLegacyPushSubscriberStartsFirebaseOnceCollectionIsOff() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        // The backend applied OFF (Firebase was configured by a "Share" then a withdrawal).
        store.collectionMayBeOn = false
        store.record(.denied)
        XCTAssertFalse(store.configureWouldLeak)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch, "push keeps Firebase at launch")
    }

    func testAShareLiftsTheUpgradeGate() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        store.record(.granted)
        XCTAssertFalse(store.configureWouldLeak)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch)
    }

    func testARefusalKeepsTheUpgradeGate() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        store.record(.denied)
        XCTAssertTrue(store.configureWouldLeak, "Firebase was never configured to switch it off")
        XCTAssertFalse(store.shouldStartFirebaseAtLaunch)
    }

    func testAnyLegacyRunKeyMeansCollectionMayBeOn() {
        for key in ConsentStore.legacyRunKeys {
            defaults.removePersistentDomain(forName: suiteName)
            defaults.set(1, forKey: key)
            XCTAssertTrue(makeStore().collectionMayBeOn, key)
        }
    }

    func testLegacyExplicitOffDoesNotMarkCollectionOn() {
        defaults.set(false, forKey: ConsentStore.legacyUsageStatsKey)
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore()
        XCTAssertFalse(store.collectionMayBeOn, "that build persisted OFF")
        XCTAssertEqual(store.state, .denied)
        XCTAssertTrue(store.shouldStartFirebaseAtLaunch, "push keeps Firebase at launch")
    }

    func testFreshInstallDoesNotMarkCollectionOn() {
        let store = makeStore()
        XCTAssertFalse(store.collectionMayBeOn)
        XCTAssertFalse(store.configureWouldLeak)
    }

    func testOutsideTheZoneNothingWaits() {
        defaults.set(true, forKey: ConsentStore.legacyPushSubscribedKey)
        let store = makeStore(requiresConsent: false)
        XCTAssertFalse(store.configureWouldLeak)
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
