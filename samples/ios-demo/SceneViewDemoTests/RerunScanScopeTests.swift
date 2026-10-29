// RerunScanScopeTests.swift
//
// Pure tests for `RerunScanScope.swift`, the "What are you scanning?" step of 3D Scan: the
// scopes offered, the capture settings each one sets (Android's `ScanScope` presets), the
// object cutoff (Android's `ScanCutoff`) and the `scope` key the recorder writes.

#if DEBUG

import Foundation
import simd
import XCTest
@testable import SceneViewDemo

final class RerunScanScopeTests: XCTestCase {

    private struct Frame: RerunCaptureFrame {
        var timestamp: TimeInterval
        var cameraTransform: simd_float4x4
        var sensorLens = RerunPinhole(width: 1920, height: 1440, fx: 1400, fy: 1410, cx: 950, cy: 730)
        var isTrackingNormal = true
        var points: [SIMD3<Float>] = []
        var planeList: [RerunCapturePlane] = []

        func featurePoints() -> [SIMD3<Float>] { points }
        func planes() -> [RerunCapturePlane] { planeList }
        func withColorLookup<R>(_ body: ((SIMD2<Float>) -> SIMD3<UInt8>?) -> R) -> R { body { _ in SIMD3(1, 2, 3) } }
        func portraitJPEG(width: Int, height: Int, quality: Double) -> Data? { Data(repeating: 1, count: 10) }
    }

    /// An upright phone at `position` looking down world -Z.
    private func phone(at position: SIMD3<Float>) -> simd_float4x4 {
        var m = simd_float4x4(SIMD4(0, -1, 0, 0), SIMD4(1, 0, 0, 0), SIMD4(0, 0, 1, 0), SIMD4(0, 0, 0, 1))
        m.columns.3 = SIMD4(position, 1)
        return m
    }

    private func manifest(_ data: Data) throws -> [String: Any] {
        try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    // MARK: - Scopes

    func testOffersObjectRoomAndAreaWithRoomFirstByDefault() {
        XCTAssertEqual(RerunScanScope.offered, [.object, .room, .area])
        XCTAssertEqual(RerunScanScope.default, .room)
    }

    func testKeysRoundTripAndUnknownOrHomeFallBackToRoom() {
        XCTAssertEqual(RerunScanScope.from(key: "object"), .object)
        XCTAssertEqual(RerunScanScope.from(key: "area"), .area)
        XCTAssertEqual(RerunScanScope.from(key: "home"), .room)
        XCTAssertEqual(RerunScanScope.from(key: "nope"), .room)
        XCTAssertEqual(RerunScanScope.from(key: nil), .room)
    }

    func testManifestScopeIsReadAndAMissingOneIsARoom() {
        XCTAssertEqual(RerunScanScope.of(manifest: Data(#"{"scope":"object","x":1}"#.utf8)), .object)
        XCTAssertEqual(RerunScanScope.of(manifest: Data(#"{"scope":"area"}"#.utf8)), .area)
        XCTAssertEqual(RerunScanScope.of(manifest: Data(#"{"scope":"home"}"#.utf8)), .room)
        XCTAssertEqual(RerunScanScope.of(manifest: Data(#"{"x":1}"#.utf8)), .room)
        XCTAssertEqual(RerunScanScope.of(manifest: Data("not json".utf8)), .room)
    }

    func testCaptureSettingsMatchAndroidPresets() {
        let object = RerunScanScope.object.configuration
        XCTAssertEqual(object.farM, 1.5)
        XCTAssertEqual(object.keyframeTravel, 0.08, accuracy: 1e-6)
        XCTAssertEqual(object.keyframeTurnDegrees, 6, accuracy: 1e-6)
        XCTAssertEqual(object.scopeKey, "object")

        let room = RerunScanScope.room.configuration
        let base = RerunCaptureRecorder.Configuration()
        XCTAssertNil(room.farM)
        XCTAssertNil(room.scopeKey, "a room writes no scope key, like Android")
        XCTAssertEqual(room.keyframeTravel, base.keyframeTravel)
        XCTAssertEqual(room.keyframeTurnDegrees, base.keyframeTurnDegrees)

        let area = RerunScanScope.area.configuration
        XCTAssertNil(area.farM)
        XCTAssertEqual(area.scopeKey, "area")

        XCTAssertFalse(RerunScanScope.object.mapsGround)
        XCTAssertTrue(RerunScanScope.room.mapsGround)
        XCTAssertTrue(RerunScanScope.area.mapsGround)
    }

    func testCopyIsAndroids() {
        XCTAssertEqual(RerunScanScopeCopy.chooserTitle, "What are you scanning?")
        XCTAssertEqual(RerunScanScopeCopy.title(.object), "An object")
        XCTAssertEqual(RerunScanScopeCopy.title(.area), "An outdoor area")
        XCTAssertEqual(RerunScanScopeCopy.start(.room), "Scan a room")
        XCTAssertEqual(RerunScanScopeCopy.how(.object),
                       "Walk around it at arm's length. Anything further than 1.5 m is left out.")
        XCTAssertFalse(RerunScanScopeCopy.how(.room).contains("plan"), "iOS draws no floor plan")
    }

    func testRememberedScopeRoundTrips() throws {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: "RerunScanScopeTests"))
        defaults.removePersistentDomain(forName: "RerunScanScopeTests")
        XCTAssertEqual(RerunScanScopeStore.last(defaults), .room)
        RerunScanScopeStore.remember(.area, defaults)
        XCTAssertEqual(RerunScanScopeStore.last(defaults), .area)
        defaults.removePersistentDomain(forName: "RerunScanScopeTests")
    }

    // MARK: - Cutoff

    func testCutoffKeepsEverythingBeforeTheFirstPose() {
        let cutoff = RerunScanCutoff(farM: 1.5)
        XCTAssertTrue(cutoff.keeps(SIMD3(100, 0, 0)))
        let square: [SIMD3<Float>] = [SIMD3(-9, 0, -9), SIMD3(9, 0, -9), SIMD3(9, 0, 9), SIMD3(-9, 0, 9)]
        XCTAssertEqual(cutoff.clip(square), square)
    }

    func testCutoffDropsFarPointsFromThePhone() {
        var cutoff = RerunScanCutoff(farM: 1.5)
        cutoff.follow(SIMD3(0, 1.5, 0))
        XCTAssertTrue(cutoff.keeps(SIMD3(0, 1.5, -1.4)))
        XCTAssertFalse(cutoff.keeps(SIMD3(0, 1.5, -1.6)))
    }

    func testCutoffCutsPlanesToTheSquareRoundThePathMiddle() throws {
        var cutoff = RerunScanCutoff(farM: 1.5)
        cutoff.follow(SIMD3(-1, 1.5, 0))
        cutoff.follow(SIMD3(1, 1.5, 0))
        let centre = try XCTUnwrap(cutoff.centre)
        XCTAssertEqual(centre.x, 0, accuracy: 1e-6)
        XCTAssertEqual(centre.y, 0, accuracy: 1e-6)

        let floor: [SIMD3<Float>] = [SIMD3(-5, 0, -5), SIMD3(5, 0, -5), SIMD3(5, 0, 5), SIMD3(-5, 0, 5)]
        let cut = cutoff.clip(floor)
        XCTAssertEqual(cut.count, 4)
        for p in cut {
            XCTAssertLessThanOrEqual(abs(p.x), 1.5 + 1e-5)
            XCTAssertLessThanOrEqual(abs(p.z), 1.5 + 1e-5)
        }
        XCTAssertEqual(RerunCaptureMath.area(of: cut), 9, accuracy: 1e-3)

        let farWall: [SIMD3<Float>] = [SIMD3(-1, 0, -4), SIMD3(1, 0, -4), SIMD3(1, 2, -4), SIMD3(-1, 2, -4)]
        XCTAssertEqual(cutoff.clip(farWall), [], "a wall across the room goes")
    }

    // MARK: - Recorder

    func testObjectRecordingWritesItsScopeAndLeavesFarPointsOut() throws {
        var recorder = RerunCaptureRecorder(configuration: RerunScanScope.object.configuration)
        recorder.add(Frame(timestamp: 0, cameraTransform: phone(at: SIMD3(0, 1.5, 0)),
                           points: [SIMD3(0, 1.5, -1), SIMD3(0.5, 1.5, -3)]))
        XCTAssertEqual(recorder.stats.points, 1)
        let pack = recorder.finish()
        XCTAssertEqual(try manifest(pack.manifest)["scope"] as? String, "object")
        XCTAssertEqual(RerunScanScope.of(manifest: pack.manifest), .object)
    }

    func testRoomRecordingKeepsFarPointsAndWritesNoScope() throws {
        var recorder = RerunCaptureRecorder(configuration: RerunScanScope.room.configuration)
        recorder.add(Frame(timestamp: 0, cameraTransform: phone(at: SIMD3(0, 1.5, 0)),
                           points: [SIMD3(0, 1.5, -1), SIMD3(0.5, 1.5, -3)]))
        XCTAssertEqual(recorder.stats.points, 2)
        let pack = recorder.finish()
        XCTAssertNil(try manifest(pack.manifest)["scope"])
        XCTAssertEqual(RerunScanScope.of(manifest: pack.manifest), .room)
    }
}

#endif
