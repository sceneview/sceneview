// SecondaryCameraMathTests.swift
//
// The geometry behind the Secondary Camera (PiP) demo: the preset poses sit at
// Android's eye positions, a tap ray leaves the camera it was made through, and
// the floor / helmet tests resolve the edit the way Android's do.

import XCTest
import CoreGraphics
import simd
import SceneViewSwift
@testable import SceneViewDemo

final class SecondaryCameraMathTests: XCTestCase {
    private let accuracy: Float = 1e-4

    func testPresetPosesPutTheCameraAtAndroidsEyePositions() {
        // Top sits within SceneView's 85° elevation clamp, so it is checked apart.
        for preset in SecondaryCameraPreset.allCases where preset != .top {
            let eye = preset.pose.cameraPosition()
            XCTAssertEqual(eye.x, preset.eye.x, accuracy: accuracy, "\(preset)")
            XCTAssertEqual(eye.y, preset.eye.y, accuracy: accuracy, "\(preset)")
            XCTAssertEqual(eye.z, preset.eye.z, accuracy: accuracy, "\(preset)")
            XCTAssertEqual(preset.pose.target, SecondaryCameraMath.stageCentre)
        }
    }

    func testTopPresetLooksStraightDown() {
        let pose = SecondaryCameraPreset.top.pose
        XCTAssertGreaterThan(pose.elevation, 89 * .pi / 180)
        XCTAssertEqual(pose.distance, 1.7, accuracy: 1e-3)
    }

    func testOrbitTurnsOncePerTwelveSeconds() {
        XCTAssertEqual(SecondaryCameraPreset.orbitSpeed * 12, 2 * .pi, accuracy: accuracy)
    }

    func testCentreTapRayAimsAtTheTarget() throws {
        let pose = SecondaryCameraPreset.corner.pose
        let size = CGSize(width: 192, height: 128)
        let ray = try XCTUnwrap(SecondaryCameraMath.ray(
            through: CGPoint(x: size.width / 2, y: size.height / 2), in: size, pose: pose))
        let expected = simd_normalize(pose.target - pose.cameraPosition())
        XCTAssertEqual(simd_distance(ray.origin, pose.cameraPosition()), 0, accuracy: accuracy)
        XCTAssertEqual(simd_distance(ray.direction, expected), 0, accuracy: accuracy)
    }

    func testCornerRayAnglesMatchTheSixtyDegreeVerticalFieldOfView() throws {
        // From the front, the top-centre pixel's ray leans 30° above the view axis.
        let pose = SecondaryCameraMath.pose(eye: SIMD3(0, 0.2, 2), target: SIMD3(0, 0.2, 0))
        let ray = try XCTUnwrap(SecondaryCameraMath.ray(
            through: CGPoint(x: 100, y: 0), in: CGSize(width: 200, height: 400), pose: pose))
        let forward = SIMD3<Float>(0, 0, -1)
        let angle = acos(simd_dot(ray.direction, forward))
        XCTAssertEqual(angle, 30 * .pi / 180, accuracy: 1e-3)
        XCTAssertGreaterThan(ray.direction.y, 0)
    }

    func testFloorHitAndSkyMiss() throws {
        let down = SecondaryCameraMath.Ray(origin: SIMD3(1, 2, 0), direction: simd_normalize(SIMD3(0, -1, -1)))
        let hit = try XCTUnwrap(SecondaryCameraMath.floorHit(down))
        XCTAssertEqual(hit.y, 0, accuracy: accuracy)
        XCTAssertEqual(hit.z, -2, accuracy: accuracy)
        let up = SecondaryCameraMath.Ray(origin: SIMD3(0, 1, 0), direction: SIMD3(0, 1, 0))
        XCTAssertNil(SecondaryCameraMath.floorHit(up))
    }

    func testHelmetPickNeedsTheRayWithinItsRadiusAndInFront() {
        let centre = SIMD3<Float>(0, 0.25, 0)
        let through = SecondaryCameraMath.Ray(origin: SIMD3(0, 0.25, 2), direction: SIMD3(0, 0, -1))
        XCTAssertTrue(SecondaryCameraMath.passes(through, within: 0.2, of: centre))
        let beside = SecondaryCameraMath.Ray(origin: SIMD3(0.3, 0.25, 2), direction: SIMD3(0, 0, -1))
        XCTAssertFalse(SecondaryCameraMath.passes(beside, within: 0.2, of: centre))
        let behind = SecondaryCameraMath.Ray(origin: SIMD3(0, 0.25, 2), direction: SIMD3(0, 0, 1))
        XCTAssertFalse(SecondaryCameraMath.passes(behind, within: 0.2, of: centre))
    }

    func testMainFramingLooksDownTwentyFourDegreesAndKeepsTheStageInTheBand() throws {
        let size = CGSize(width: 402, height: 874)
        let pose = SecondaryCameraMath.mainFraming(size: size, bandTop: 300, bandBottom: 268)
        XCTAssertEqual(pose.elevation, 24 * .pi / 180, accuracy: accuracy)
        XCTAssertEqual(pose.azimuth, 0, accuracy: accuracy)
        // The stage's front corners land inside the band and the screen.
        let half = SecondaryCameraMath.stageHalfExtent + SecondaryCameraMath.helmetSize / 2
        for corner in [SIMD3<Float>(-half, 0, half), SIMD3(half, 0, half),
                       SIMD3(-half, SecondaryCameraMath.helmetSize, -half)] {
            let screen = try XCTUnwrap(project(corner, pose: pose, size: size))
            XCTAssertGreaterThanOrEqual(screen.x, 0)
            XCTAssertLessThanOrEqual(screen.x, size.width)
            XCTAssertGreaterThanOrEqual(screen.y, 300 - 1)
            XCTAssertLessThanOrEqual(screen.y, size.height - 268 + 1)
        }
    }

    /// Inverse of `SecondaryCameraMath.ray`: where `point` lands on screen.
    private func project(_ point: SIMD3<Float>, pose: SceneCameraPose, size: CGSize) -> CGPoint? {
        let eye = pose.cameraPosition()
        let forward = simd_normalize(pose.target - eye)
        let right = simd_normalize(simd_cross(forward, SIMD3(0, 1, 0)))
        let up = simd_cross(right, forward)
        let offset = point - eye
        let depth = simd_dot(offset, forward)
        guard depth > 0 else { return nil }
        let tanV = tan(SecondaryCameraMath.verticalFov / 2)
        let tanH = tanV * Float(size.width / size.height)
        let ndcX = simd_dot(offset, right) / depth / tanH
        let ndcY = simd_dot(offset, up) / depth / tanV
        return CGPoint(x: CGFloat((ndcX + 1) / 2) * size.width,
                       y: CGFloat((1 - ndcY) / 2) * size.height)
    }
}
