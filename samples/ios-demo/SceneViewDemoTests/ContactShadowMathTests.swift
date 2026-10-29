// ContactShadowMathTests.swift
//
// Pure-function tests for the Contact Shadow Preview port (`ContactShadowPreviewDemo.swift`):
// the hop, the pool that follows it and the label fade — the iOS mirror of Android's `DemoMath`
// grounding tests. The shadow mask itself is tested in the SDK (`ContactShadowNodeTests`).

#if DEBUG

import XCTest
import simd
import SceneViewSwift
@testable import SceneViewDemo

final class ContactShadowMathTests: XCTestCase {

    func testTheBoxRestsOnTheFloorAtTimeZeroAndLandsEveryPeriod() {
        XCTAssertEqual(ContactShadowMath.bounceHeight(0), 0)
        XCTAssertEqual(ContactShadowMath.bounceHeight(-1), 0)
        XCTAssertEqual(ContactShadowMath.bounceHeight(ContactShadowMath.hopPeriod), 0, accuracy: 1e-4)
        XCTAssertEqual(ContactShadowMath.bounceHeight(ContactShadowMath.hopPeriod / 2),
                       ContactShadowMath.maxHop, accuracy: 1e-4)
    }

    func testThePoolIsFullAndTightAtContactAndFadesWideAtTheTop() {
        XCTAssertEqual(ContactShadowMath.intensityFactor(0), 1)
        XCTAssertEqual(ContactShadowMath.spread(0), 1)
        XCTAssertEqual(ContactShadowMath.shadowOffset(0), .zero)
        let top = ContactShadowMath.maxHop
        XCTAssertEqual(ContactShadowMath.intensityFactor(top), 0.45, accuracy: 1e-5)
        XCTAssertEqual(ContactShadowMath.spread(top), 1.5, accuracy: 1e-5)
        // The light comes from +x/+z above, so the pool slides toward -x/-z as the box lifts.
        let slide = ContactShadowMath.shadowOffset(top)
        XCTAssertEqual(slide.x, -0.35 * top, accuracy: 1e-5)
        XCTAssertEqual(slide.y, -0.4 * top, accuracy: 1e-5)
    }

    func testTheFloatingTwinNeverLands() {
        for step in 0...100 {
            let y = ContactShadowMath.floatHoverY(Double(step) * 0.05)
            XCTAssertGreaterThan(y - ContactShadowMath.boxEdge / 2, 0.3)
        }
    }

    func testLabelsHoldFrontOnAndAreGoneSideOn() {
        XCTAssertEqual(ContactShadowMath.labelAlpha(azimuth: 0), 1)
        XCTAssertEqual(ContactShadowMath.labelAlpha(azimuth: 20 * .pi / 180), 1)
        XCTAssertEqual(ContactShadowMath.labelAlpha(azimuth: -50 * .pi / 180), 0)
        XCTAssertEqual(ContactShadowMath.labelAlpha(azimuth: 2 * .pi), 1, accuracy: 1e-4)
        let mid = ContactShadowMath.labelAlpha(azimuth: 35 * .pi / 180)
        XCTAssertGreaterThan(mid, 0)
        XCTAssertLessThan(mid, 1)
    }

    func testHomePoseIsAndroidsCamera() {
        let pose = ContactShadowMath.homePose
        let eye = pose.cameraPosition()
        XCTAssertLessThan(simd_distance(eye, [0, 1.35, 3.3]), 1e-3)
        XCTAssertEqual(pose.target, ContactShadowMath.cameraTarget)
    }

    func testEveryPresetHasAChipAndAVerdict() {
        XCTAssertEqual(ContactShadowContext.allCases.map(\.chipLabel), ["Floor", "Wall", "Table"])
        XCTAssertEqual(Set(ContactShadowContext.allCases.map(\.wallVerdict)).count, 3)
    }
}

#endif
