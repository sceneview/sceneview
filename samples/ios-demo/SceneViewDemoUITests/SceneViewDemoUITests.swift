// SceneViewDemoUITests.swift
//
// UI-testing smoke suite for the SceneView iOS demo (#2803, part of the
// iOS-parity tracker #2798). This target is what makes `render-tests.yml`'s
// "iOS screenshot tests" job REAL: before it, that job ran the logic-only
// `SceneViewDemoTests` unit target, which emits ZERO `XCTAttachment` images —
// so the job captured nothing despite its name.
//
// The suite launches the app in a simulator and captures a screenshot of:
//   1. the launch screen (Explore tab) and every tab in the tab bar, and
//   2. a representative subset of WORKING 3D demos, each reached headlessly via
//      the app's `-demo <id>` launch argument — the same deep-link path the App
//      Store screenshot pipeline uses (see
//      `.claude/scripts/capture-appstore-screenshots.sh`) — with `-qa_mode 1`
//      to freeze auto-rotation for a deterministic frame.
//
// Every attachment sets `lifetime = .keepAlways` so the PNGs survive a GREEN
// run and land in the `.xcresult`, from which `render-tests.yml` exports them
// as real PNG artifacts.
//
// Scope is a deliberately honest smoke, not the full ~42-demo catalog (#2803):
//   * AR demos are NOT screenshotted — the simulator has no camera, so ARKit
//     demos never reach a rendered frame.
//   * The tab-bar pass is anchored on the tab bar itself (not a demo id), so it
//     always yields ≥4 PNGs even if every demo id were later renamed.
//   * The demo-id pass is TOLERANT: it asserts only that the app stays alive,
//     so a single renamed id degrades coverage but never red-fails this
//     advisory job.

import Foundation
import XCTest
import UIKit

final class SceneViewDemoUITests: XCTestCase {

    override func setUpWithError() throws {
        // A single unreachable demo must never abort the remaining captures.
        continueAfterFailure = true
    }

    /// Attach the app's current screen as a keep-always PNG named `name`.
    /// `.keepAlways` is required — a passing test discards its attachments by
    /// default, which would leave the screenshot job with an empty artifact.
    /// Every launch turns the push pre-prompt off: a sheet that may appear after the
    /// second sample closed must not cover the screen a test is about to tap. The
    /// usage-statistics consent is pre-answered for the same reason: in the EEA, UK and
    /// Switzerland its sheet comes up over Home on first launch.
    private static func makeApp() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-push_preprompt", "off", "-telemetry_consent", "denied"]
        return app
    }

    private func snapshot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    /// Filesystem-safe token for an attachment name derived from a UI label.
    private func slug(_ raw: String) -> String {
        let lowered = raw.lowercased()
        let mapped = lowered.map { ch -> Character in
            (ch.isLetter || ch.isNumber) ? ch : "-"
        }
        return String(mapped)
    }

    /// Launch, then walk the tab bar. Anchored on the tab bar (not any demo
    /// id), so it always produces launch + one PNG per tab (≥4 total on iOS).
    func testLaunchAndTabScreenshots() {
        let app = Self.makeApp()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30),
                      "app never reached the foreground")
        snapshot(app, "01-launch")

        let tabBar = app.tabBars.firstMatch
        guard tabBar.waitForExistence(timeout: 15) else {
            XCTFail("no tab bar found after launch")
            return
        }
        // Iterate by index so the pass never depends on a tab's title/label —
        // the tab set (Explore, AR View, Samples, About) can change without
        // breaking the smoke. The button's `label` still names the PNG.
        let count = tabBar.buttons.count
        for i in 0..<count {
            let button = tabBar.buttons.element(boundBy: i)
            guard button.exists else { continue }
            let name = String(format: "%02d-tab-%@", i + 2, slug(button.label))
            button.tap()
            _ = app.wait(for: .runningForeground, timeout: 3)
            snapshot(app, name)
        }
    }

    /// A representative subset of WORKING 3D demos, each launched headlessly
    /// via `-demo <id>`. Kept in sync (by intent) with the App Store screenshot
    /// pipeline's `DEMOS` array — pure-3D demos proven to render on a CI
    /// simulator without a camera.
    func testWorkingDemoScreenshots() {
        let demos = [
            "model-viewer",
            "dynamic-sky",
            "multi-model",
            "lighting",
        ]
        for (index, id) in demos.enumerated() {
            let app = Self.makeApp()
            // `-demo <id>` routes straight to the demo on the first frame;
            // `-qa_mode 1` freezes auto-rotation for a deterministic capture.
            app.launchArguments += ["-demo", id, "-qa_mode", "1"]
            app.launch()
            XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30),
                          "app never reached the foreground for -demo \(id)")
            // Let the scene load its model and settle its first frames. A fixed
            // settle mirrors the App Store pipeline; the exact value is not
            // load-bearing for a smoke — we only need a plausibly-rendered frame.
            Thread.sleep(forTimeInterval: 5)
            snapshot(app, String(format: "%02d-demo-%@", index + 6, slug(id)))
            app.terminate()
        }
    }

    /// Exercise the real renderer: advancing animation must change the character pixels,
    /// while pausing must hold them. The crop excludes all controls and the status bar.
    func testFoxAnimationTransport() throws {
        let app = Self.makeApp()
        app.launchArguments += ["-demo", "animation"]
        app.launch()
        let pause = app.buttons["Pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 30))
        let ready = NSPredicate(format: "enabled == true")
        let loaded = XCTNSPredicateExpectation(predicate: ready, object: pause)
        XCTAssertEqual(XCTWaiter.wait(for: [loaded], timeout: 30), .completed)
        Thread.sleep(forTimeInterval: 2)

        func stagePixels() throws -> [UInt8] {
            let image = try XCTUnwrap(app.screenshot().image.cgImage)
            let crop = try XCTUnwrap(image.cropping(to: CGRect(
                x: Double(image.width) * 0.1, y: Double(image.height) * 0.2,
                width: Double(image.width) * 0.8, height: Double(image.height) * 0.4
            )))
            var pixels = [UInt8](repeating: 0, count: 96 * 96 * 4)
            try pixels.withUnsafeMutableBytes { storage in
                let context = try XCTUnwrap(CGContext(data: storage.baseAddress, width: 96, height: 96,
                    bitsPerComponent: 8, bytesPerRow: 96 * 4,
                    space: CGColorSpaceCreateDeviceRGB(),
                    bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
                context.draw(crop, in: CGRect(x: 0, y: 0, width: 96, height: 96))
            }
            return pixels
        }
        func difference(_ first: [UInt8], _ second: [UInt8]) -> Double {
            zip(first, second).reduce(0.0) { $0 + abs(Double($1.0) - Double($1.1)) }
                / Double(first.count)
        }

        let surveyStart = try stagePixels()
        Thread.sleep(forTimeInterval: 0.7)
        let surveyMotion = difference(surveyStart, try stagePixels())
        XCTAssertGreaterThan(surveyMotion, 0.1, "Survey must animate on first open")

        for clip in ["Walk", "Run"] {
            app.buttons[clip].tap()
            XCTAssertTrue(app.buttons[clip].isSelected)
            Thread.sleep(forTimeInterval: 0.5)
            let first = try stagePixels()
            Thread.sleep(forTimeInterval: 0.3)
            XCTAssertGreaterThan(difference(first, try stagePixels()), 0.1,
                                 "\(clip) must visibly animate")
        }
        pause.tap()
        XCTAssertTrue(app.buttons["Play"].exists)
        Thread.sleep(forTimeInterval: 1)
        let held = try stagePixels()
        Thread.sleep(forTimeInterval: 0.7)
        XCTAssertLessThan(difference(held, try stagePixels()), 0.1,
                          "Pause must hold the actual rendered pose")
        snapshot(app, "fox-paused")
        let orbitStart = app.coordinate(withNormalizedOffset: CGVector(dx: 0.45, dy: 0.42))
        let orbitEnd = app.coordinate(withNormalizedOffset: CGVector(dx: 0.72, dy: 0.47))
        orbitStart.press(forDuration: 0.1, thenDragTo: orbitEnd)
        Thread.sleep(forTimeInterval: 1)
        XCTAssertGreaterThan(difference(held, try stagePixels()), 1,
                             "Orbit must change the view of the paused character")
        snapshot(app, "fox-orbit")

        let speed = app.sliders["animation-speed"]
        XCTAssertTrue(speed.exists)
        speed.adjust(toNormalizedSliderPosition: 1)
        XCTAssertTrue(app.staticTexts["2×"].exists)
        app.buttons["Survey"].tap()
        XCTAssertTrue(pause.exists, "Selecting a clip from pause starts playback")
        app.buttons["Loop"].tap()
        let finished = app.buttons["Play"]
        XCTAssertTrue(finished.waitForExistence(timeout: 10), "Once must finish and show Play")
        finished.tap()
        XCTAssertTrue(pause.exists, "A completed clip can be replayed")
    }

    // MARK: - Black-viewport probe (#3008)

    /// Opt-in probe for the intermittent black viewport of #3008: a `SceneView`
    /// re-created by `.id()` sometimes renders nothing at all — no model, no
    /// skybox — permanently.
    ///
    /// **Opt-in on purpose.** It takes minutes and it is a measurement rig, not
    /// a smoke test, so it skips unless `SV_BLACK_PROBE=1` reaches the *runner*
    /// process. Neither exporting it in the shell nor passing
    /// `TEST_RUNNER_SV_BLACK_PROBE=1` to `xcodebuild test` does that — measured:
    /// the test then sees no `SV_*` key at all. Put it in the `.xctestrun`
    /// instead, which also makes repeated passes cheap (no rebuild between
    /// runs, which is what an interleaved baseline/fixed window needs):
    ///
    /// ```
    /// xcodebuild build-for-testing -scheme SceneViewDemoUITests \
    ///   -destination '…' -derivedDataPath DD
    /// # add SV_BLACK_PROBE / SV_PROBE_SWITCHES / SV_PROBE_LABEL to every
    /// # target's EnvironmentVariables in DD/Build/Products/*.xctestrun, then:
    /// xcodebuild test-without-building -xctestrun <patched> -destination '…' \
    ///   -only-testing:SceneViewDemoUITests/SceneViewDemoUITests/testBlackViewportProbe
    /// ```
    ///
    /// Read the counts off the exported attachments: a viewport is black when
    /// the brightest subpixel over the viewport crop is `0`.
    ///
    /// Two things here are load-bearing and must not be "tidied up":
    ///
    /// - **No `-qa_mode 1`.** Every other capture in this file freezes
    ///   auto-rotation for determinism, but `qa_mode` passes
    ///   `autoRotate(speed: 0)`, and a zero speed makes `SceneView`'s
    ///   auto-rotate task return immediately instead of driving `applyCamera()`
    ///   at 60 Hz (#2896). That is a different render path from the one the
    ///   defect was measured on, so the probe runs the shipping path.
    /// - **Two samples per switch.** A viewport is only counted black when it
    ///   is still black on the *second* sample — a frame that has not rendered
    ///   yet is not a black viewport, and conflating the two is how a "black
    ///   screen" verdict gets manufactured.
    ///
    /// The subject row alternates between the two *streamed* chips that stay
    /// fully on-screen. Slot 0 ("Soldier") is deliberately not in the rotation:
    /// it is bundled and renders on the process's first `RealityView`, which
    /// has never been observed to fail, so including it would dilute the rate
    /// with switches that cannot reproduce.
    func testBlackViewportProbe() throws {
        guard ProcessInfo.processInfo.environment["SV_BLACK_PROBE"] == "1" else {
            throw XCTSkip("Set SV_BLACK_PROBE=1 to run the #3008 measurement rig.")
        }
        let switches = Int(
            ProcessInfo.processInfo.environment["SV_PROBE_SWITCHES"] ?? "8"
        ) ?? 8
        let label = ProcessInfo.processInfo.environment["SV_PROBE_LABEL"] ?? "run"

        let app = Self.makeApp()
        app.launchArguments += ["-demo", "animation"]
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30),
                      "app never reached the foreground")

        let fab = app.buttons["demo-settings-fab"]
        XCTAssertTrue(fab.waitForExistence(timeout: 20), "settings FAB never appeared")
        // Let slot 0 settle first: it is the control that proves the rig is
        // driving a live scene rather than a stalled app.
        Thread.sleep(forTimeInterval: 12)
        snapshot(app, "\(label)-00-control-soldier")
        fab.tap()

        let chips = ["Retro TV Robot", "Catfish Mech"]
        for i in 0..<switches {
            let name = chips[i % chips.count]
            let chip = app.buttons[name]
            guard chip.waitForExistence(timeout: 10) else {
                XCTFail("subject chip '\(name)' never appeared on switch \(i + 1)")
                return
            }
            chip.tap()
            Thread.sleep(forTimeInterval: 12)
            snapshot(app, String(format: "%@-%02d-a-%@", label, i + 1, slug(name)))
            Thread.sleep(forTimeInterval: 8)
            snapshot(app, String(format: "%@-%02d-b-%@", label, i + 1, slug(name)))
        }
    }

    /// A pinch on a demo's 3D stage drives the camera; it must never dismiss
    /// the demo (#4008). The catalogue opens demos with a zoom transition,
    /// whose system pinch-to-dismiss used to take the stage's zoom-out pinch
    /// and shrink the whole demo back into its card. Opened from the Showcase
    /// hero on purpose: the `-demo` launch path has no zoom transition, so it
    /// cannot reproduce this.
    func testPinchOnDemoStageDoesNotDismissTheDemo() {
        let app = Self.makeApp()
        app.launch()
        let hero = app.descendants(matching: .any)["home-hero"]
        XCTAssertTrue(hero.waitForExistence(timeout: 30), "Showcase hero never appeared")
        hero.tap()

        let close = app.descendants(matching: .any)["demo-close"]
        XCTAssertTrue(close.waitForExistence(timeout: 20), "the demo never opened")
        // Let the model land and the framing settle.
        Thread.sleep(forTimeInterval: 6)
        snapshot(app, "4008-01-before-pinch")

        let stage = app.windows.firstMatch
        for _ in 0..<3 {
            stage.pinch(withScale: 0.4, velocity: -1.5)
            Thread.sleep(forTimeInterval: 1.5)
        }
        snapshot(app, "4008-02-after-pinch-out")
        XCTAssertTrue(close.exists && close.isHittable, "a pinch on the stage dismissed the demo")
    }

    /// #4015: on iPad, Explore's scrolled content was drawn over the top bar,
    /// between the floating tab bar and the navigation bar. Opens Explore from
    /// the Showcase card, scrolls, and keeps a frame of the top edge.
    func testExploreScrolledContentStaysUnderTheTopBar() {
        // Portrait on purpose: #4015 was reported in landscape, but the bug is
        // the same in portrait, and forcing landscape from XCUITest on the
        // iPad simulator leaves the hit-test frames rotated (taps miss).
        let app = Self.makeApp()
        app.launch()
        XCTAssertTrue(app.wait(for: .runningForeground, timeout: 30),
                      "app never reached the foreground")

        let browse = app.buttons["Browse online models"]
        for _ in 0..<12 where !(browse.exists && browse.isHittable) {
            app.swipeUp()
        }
        XCTAssertTrue(browse.waitForExistence(timeout: 10), "Browse online models card not found")
        // Let the last swipe's momentum settle, or the tap lands mid-scroll and
        // is swallowed; retry once for the same reason.
        Thread.sleep(forTimeInterval: 1.5)
        let search = app.textFields["explore-search-field"]
        for _ in 0..<2 where !search.exists {
            browse.tap()
            _ = search.waitForExistence(timeout: 8)
        }
        XCTAssertTrue(search.waitForExistence(timeout: 7), "Explore never opened")
        Thread.sleep(forTimeInterval: 2)
        snapshot(app, "4015-01-explore-top")

        app.swipeUp(velocity: .slow)
        app.swipeUp(velocity: .slow)
        Thread.sleep(forTimeInterval: 2)
        snapshot(app, "4015-02-explore-scrolled")

        let back = app.navigationBars.buttons.firstMatch
        XCTAssertTrue(back.exists && back.isHittable, "the top bar's back button is gone")
    }

    /// "The tab closes when you go into Featured" (02/10): scrolling from the
    /// hero into Featured folded the tab bar down to its selected item, and it
    /// stayed folded through a demo and back. Scrolls to each Featured card,
    /// opens it, closes it, and checks every tab is still there each time, and
    /// that the home came back where it was left.
    /// Frames are also written to `$SV_CAPTURE_DIR` when the runner sets it
    /// (`TEST_RUNNER_SV_CAPTURE_DIR=… xcodebuild test …`).
    func testFeaturedRoundTripKeepsTheHome() {
        let app = Self.makeApp()
        app.launch()
        let header = app.descendants(matching: .any)["home-section-featured"]
        XCTAssertTrue(header.waitForExistence(timeout: 30), "the Featured group never appeared")
        Thread.sleep(forTimeInterval: 2)
        capture(app, "featured-01-home")

        let tabs = ["Showcase", "AR View", "About"].map { app.tabBars.buttons[$0] }
        func assertTabsOnScreen(_ moment: String) {
            for tab in tabs {
                XCTAssertTrue(tab.exists && tab.isHittable, "a tab is folded away \(moment)")
            }
        }
        assertTabsOnScreen("on launch")
        for (step, id) in ["cosmos", "splat-preview"].enumerated() {
            let card = app.descendants(matching: .any)["home-featured-\(id)"]
            for _ in 0..<6 where !(card.exists && card.isHittable) {
                app.swipeUp(velocity: .slow)
            }
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertTrue(card.isHittable, "Featured \(id) never came on screen")
            let before = card.frame
            capture(app, "featured-\(step + 2)a-\(id)-before")
            assertTabsOnScreen("after scrolling to \(id)")
            card.tap()

            let close = app.descendants(matching: .any)["demo-close"]
            XCTAssertTrue(close.waitForExistence(timeout: 20), "\(id) never opened")
            Thread.sleep(forTimeInterval: 4)
            capture(app, "featured-\(step + 2)b-\(id)-open")
            close.tap()
            Thread.sleep(forTimeInterval: 2.5)
            capture(app, "featured-\(step + 2)c-\(id)-back")

            assertTabsOnScreen("after closing \(id)")
            XCTAssertTrue(card.exists && card.isHittable,
                          "the home did not come back where it was left after \(id)")
            XCTAssertEqual(card.frame.minY, before.minY, accuracy: 4,
                           "the home scrolled away while \(id) was open")
        }
    }

    /// "I still struggle to see where the new things are" (02/10): the What's
    /// new row under the hero selects the What's new chip and brings the chip
    /// row up under the header, with only the New / Updated demos below it.
    func testWhatsNewRowOpensTheFilter() {
        let app = Self.makeApp()
        app.launch()
        let row = app.descendants(matching: .any)["home-whats-new-row"]
        XCTAssertTrue(row.waitForExistence(timeout: 30), "the What's new row never appeared")
        Thread.sleep(forTimeInterval: 2)
        capture(app, "whats-new-01-home")
        row.tap()
        Thread.sleep(forTimeInterval: 2)
        capture(app, "whats-new-02-filtered")

        let chip = app.buttons["home-chip-whats-new"]
        XCTAssertTrue(chip.exists && chip.isHittable, "the What's new chip is not on screen")
        XCTAssertTrue(chip.isSelected, "the What's new chip is not selected")
        XCTAssertFalse(app.descendants(matching: .any)["home-row-lines-paths"].exists,
                       "a demo with nothing new is still listed")
        XCTAssertTrue(app.descendants(matching: .any)["home-row-lighting-lab"].waitForExistence(timeout: 5),
                      "the new Lighting Lab is not listed")
    }

    private func capture(_ app: XCUIApplication, _ name: String) {
        snapshot(app, name)
        guard let dir = ProcessInfo.processInfo.environment["SV_CAPTURE_DIR"] else { return }
        let png = app.screenshot().pngRepresentation
        try? png.write(to: URL(fileURLWithPath: dir).appendingPathComponent("\(name).png"))
        let about = app.tabBars.buttons["About"]
        let line = "\(name)\tAbout tab hittable=\(about.exists && about.isHittable)\n"
        let log = URL(fileURLWithPath: dir).appendingPathComponent("log.txt")
        if let h = try? FileHandle(forWritingTo: log) {
            h.seekToEndOfFile(); h.write(Data(line.utf8)); try? h.close()
        } else {
            try? Data(line.utf8).write(to: log)
        }
    }
}
