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

final class SceneViewDemoUITests: XCTestCase {

    override func setUpWithError() throws {
        // A single unreachable demo must never abort the remaining captures.
        continueAfterFailure = true
    }

    /// Attach the app's current screen as a keep-always PNG named `name`.
    /// `.keepAlways` is required — a passing test discards its attachments by
    /// default, which would leave the screenshot job with an empty artifact.
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
        let app = XCUIApplication()
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
            let app = XCUIApplication()
            // `-demo <id>` routes straight to the demo on the first frame;
            // `-qa_mode 1` freezes auto-rotation for a deterministic capture.
            app.launchArguments = ["-demo", id, "-qa_mode", "1"]
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
}
