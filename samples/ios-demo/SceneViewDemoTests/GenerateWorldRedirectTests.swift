// GenerateWorldRedirectTests.swift
//
// Unit tests for `samples/ios-demo/SceneViewDemo/Services/GenerateWorldRedirect.swift`.
//
// **Wiring status (2026-09-21).** This file is compiled by the
// `SceneViewDemoTests` unit-test target declared in
// `samples/ios-demo/SceneViewDemo.xcodeproj` and is run by CI via the shared
// `SceneViewDemo` scheme (`xcodebuild test`).
//
// Only the pure decision is exercised. `open()` touches `UIApplication` /
// `NSWorkspace` and would leave the test runner, so the branch it takes is
// isolated in `target(canOpenDeepLink:appStoreId:)` and tested here instead.

#if canImport(XCTest)

import XCTest
@testable import SceneViewDemo

final class GenerateWorldRedirectTests: XCTestCase {

    // MARK: - target(canOpenDeepLink:appStoreId:)

    func testInstalledAppWinsOverTheStore() {
        XCTAssertEqual(GenerateWorldRedirect.target(canOpenDeepLink: true, appStoreId: nil),
                       .deepLink)
        XCTAssertEqual(GenerateWorldRedirect.target(canOpenDeepLink: true, appStoreId: "1234567890"),
                       .deepLink,
                       "An installed AR Model Viewer must never be bounced through the App Store")
    }

    func testStoreIsUsedWhenTheAppIsNotInstalledButShipped() {
        XCTAssertEqual(GenerateWorldRedirect.target(canOpenDeepLink: false, appStoreId: "1234567890"),
                       .appStore(id: "1234567890"))
    }

    func testDocsAreTheFallbackWhileThereIsNoIOSApp() {
        XCTAssertEqual(GenerateWorldRedirect.target(canOpenDeepLink: false, appStoreId: nil),
                       .docs,
                       "Today's shipping configuration: no iOS app, so the card explains the feature")
    }

    func testEmptyAppStoreIdIsNotAnId() {
        XCTAssertEqual(GenerateWorldRedirect.target(canOpenDeepLink: false, appStoreId: ""),
                       .docs,
                       "`itms-apps://…/app/id` with nothing after it is a broken page, not a listing")
    }

    // MARK: - Contract

    func testDeepLinkMatchesTheSchemeWhitelistedInInfoPlist() {
        XCTAssertEqual(GenerateWorldRedirect.deepLink.absoluteString,
                       "armodelviewer://generate-world?source=sceneview-demo")
        XCTAssertEqual(GenerateWorldRedirect.deepLink.scheme,
                       "armodelviewer",
                       "`LSApplicationQueriesSchemes` in Info.plist must list exactly this scheme, "
                       + "or `canOpenURL` answers false whatever is installed")
    }

    func testDocsURLIsTheFeaturePage() {
        XCTAssertEqual(GenerateWorldRedirect.docsURL.absoluteString,
                       "https://sceneview.github.io/ai-development/")
    }
}

#endif
