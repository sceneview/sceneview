// WhatsNewChangelogTests.swift
//
// The What's new sheet's parser (`WhatsNewChangelog.swift`) — the iOS mirror
// of Android's `WhatsNewChangelogTest`: header shapes, categories, headline
// extraction, the iOS demo-app scope, and the bundled CHANGELOG.md itself.

#if DEBUG

import XCTest
@testable import SceneViewDemo

final class WhatsNewChangelogTests: XCTestCase {

    private let sample = """
    # Changelog

    ## Unreleased

    ### Added
    - **iOS demo: something not shipped yet.**

    ## v4.51.0 — 2026-09-30 — Glass and gravity

    ### Added
    - **iOS demo: Cosmos gains a ringed world.** Long prose follows. ([#4100](https://x/4100))
    - **Android demo: a thing only Android got.**
    - **SceneViewSwift**: an SDK-only change.

    ### Fixed
    - **iOS demo — Model Viewer**: models fly in like Android. More text.
    - **The iOS demo's About tab no longer shows donation links.**
    - **Demo apps:** the catalogue opens on its most striking demos.

    ### Tests
    - **iOS demo: a test-only bullet.**

    ## v4.50.0 — 2026-09-29

    ### Changed
    - **Website: nothing for the app.**

    ## v4.49.0 — Old title (2026-07-18)

    ### Removed
    - **iOS / macOS demo**: the *legacy* `Foo` screen. Gone.
    """

    func testReleasesAreScopedToTheIOSDemoAndEmptyOnesDropped() {
        let releases = WhatsNewChangelog.releases(fromChangelog: sample)
        XCTAssertEqual(releases.map(\.version), ["4.51.0", "4.49.0"])

        let latest = releases[0]
        XCTAssertEqual(latest.date, "2026-09-30")
        XCTAssertEqual(latest.title, "Glass and gravity")
        XCTAssertEqual(latest.highlights.map(\.headline), [
            "Cosmos gains a ringed world",
            "Model Viewer: models fly in like Android",
            "The iOS demo's About tab no longer shows donation links",
            "The catalogue opens on its most striking demos",
        ])
        XCTAssertEqual(latest.highlights.map(\.category), [.added, .fixed, .fixed, .fixed])
        XCTAssertEqual(latest.highlights.first?.issueNumber, 4100)
    }

    func testPreTitleDateFormAndEmphasisAndCodeSpans() {
        let old = WhatsNewChangelog.releases(fromChangelog: sample)[1]
        XCTAssertEqual(old.title, "Old title")
        XCTAssertEqual(old.date, "2026-07-18")
        XCTAssertEqual(old.highlights.map(\.headline), ["The legacy Foo screen"])
        XCTAssertEqual(old.highlights.map(\.category), [.removed])
    }

    func testUnreleasedAndTestsSectionsNeverReachTheSheet() {
        let headlines = WhatsNewChangelog.releases(fromChangelog: sample).flatMap(\.highlights).map(\.headline)
        XCTAssertFalse(headlines.contains { $0.contains("not shipped") })
        XCTAssertFalse(headlines.contains { $0.contains("test-only") })
    }

    func testDemoAppScope() {
        XCTAssertEqual(WhatsNewChangelog.demoAppHeadline("iOS demo: closing Body Tracking stops the camera"),
                       "Closing Body Tracking stops the camera")
        XCTAssertEqual(WhatsNewChangelog.demoAppHeadline("iOS demo app home: cards are pictures first"),
                       "iOS demo app home: cards are pictures first")
        XCTAssertEqual(WhatsNewChangelog.demoAppHeadline("Demo (iOS), sharper previews"), "Sharper previews")
        XCTAssertNil(WhatsNewChangelog.demoAppHeadline("Android demo: a fix"))
        XCTAssertNil(WhatsNewChangelog.demoAppHeadline("The demo app's home opens on what's new"))
        XCTAssertNil(WhatsNewChangelog.demoAppHeadline("iOSdemo: no word break"))
        XCTAssertNil(WhatsNewChangelog.demoAppHeadline("iOS: VideoNode shows its video again"))
    }

    func testMaxReleasesCapsTheList() {
        XCTAssertEqual(WhatsNewChangelog.releases(fromChangelog: sample, maxReleases: 1).map(\.version), ["4.51.0"])
    }

    /// The real file, as the build phase bundles it: the sheet has something to
    /// show, every release is versioned and dated, and no headline is a bare
    /// scope label.
    func testBundledChangelogFeedsTheSheet() throws {
        let releases = WhatsNewChangelog.load()
        XCTAssertFalse(releases.isEmpty, "CHANGELOG.md missing from the app bundle (Bundle changelog phase)")
        XCTAssertLessThanOrEqual(releases.count, WhatsNewChangelog.maxReleases)
        for release in releases {
            XCTAssertNotNil(release.date, "v\(release.version) has no date")
            XCTAssertFalse(release.highlights.isEmpty)
            for highlight in release.highlights {
                XCTAssertFalse(highlight.headline.isEmpty)
                XCTAssertFalse(highlight.headline.lowercased().hasPrefix("ios demo:"), highlight.headline)
            }
        }
    }

    func testSheetPhaseReopensOnlyAfterASampleVisit() {
        var phase = WhatsNewSheetPhase.closed
        phase.hostReturned()
        XCTAssertEqual(phase, .closed)
        phase.open()
        phase.leaveForSample()
        XCTAssertEqual(phase, .reopenOnReturn)
        phase.dismiss() // the sheet's own dismissal on the way out keeps the trip
        XCTAssertEqual(phase, .reopenOnReturn)
        phase.hostReturned()
        XCTAssertEqual(phase, .open)
        phase.dismiss()
        XCTAssertEqual(phase, .closed)
    }
}

#endif
