// The model viewer's entrance flight and the Toy Car's material repair,
// checked against the Android values they port.
//
// `ViewerEntranceFlight` is pure math, so the poses are checked without a
// scene; the constants are also read from the Android sources, so a retune on
// one side fails here until the other follows.

import XCTest
import RealityKit
import SceneViewSwift
@testable import SceneViewDemo

final class ViewerEntranceTests: XCTestCase {

    private let rest = SceneCameraPose(azimuth: 0, elevation: 12 * .pi / 180,
                                       distance: 2, target: [0, 0.1, 0])

    private func assertClose(_ a: SIMD3<Float>, _ b: SIMD3<Float>, _ tolerance: Float = 1e-4,
                             file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertLessThan(simd_length(a - b), tolerance, "\(a) vs \(b)", file: file, line: line)
    }

    func testArrivalStartsWhereAndroidsManipulatorDoes() {
        let start = ViewerEntranceFlight.pose(rest: rest, start: .arrival, progress: 0)
        let d = rest.cameraPosition() - rest.target
        let radius = simd_length(d)
        let yaw: Float = 24 * .pi / 180
        let expected = rest.target + SIMD3(
            d.x * cos(yaw) + d.z * sin(yaw),
            d.y + radius * 0.22,
            d.z * cos(yaw) - d.x * sin(yaw)
        ) * 1.55
        assertClose(start.cameraPosition(), expected)
        XCTAssertEqual(start.target, rest.target)
        // Swung 24° round +Y, from azimuth 0: the eye moved towards +X.
        XCTAssertGreaterThan(start.cameraPosition().x, 0)
    }

    func testFlightLandsExactlyOnRest() {
        var flight = ViewerEntranceFlight(rest: rest, start: .arrival)
        XCTAssertNotEqual(flight.pose, rest)
        for _ in 0..<200 { flight.advance(by: 1.0 / 60) }
        XCTAssertEqual(flight.pose, rest)
        XCTAssertEqual(flight.settleDrop, 0)
        XCTAssertTrue(flight.isFinished)
    }

    func testRecenterFliesStraightFromTheEyeOnScreen() {
        let eye: SIMD3<Float> = [1.5, 0.8, -0.4]
        let start = ViewerEntranceFlight.pose(rest: rest, start: .eye(eye), progress: 0)
        assertClose(start.cameraPosition(), eye)
        let half = ViewerEntranceFlight.pose(rest: rest, start: .eye(eye), progress: 0.5)
        assertClose(half.cameraPosition(), (eye + rest.cameraPosition()) / 2)
    }

    func testAStepAdvancesAtMostOneTwentiethOfASecond() {
        var flight = ViewerEntranceFlight(rest: rest, start: .arrival)
        flight.advance(by: 2)
        XCTAssertEqual(flight.elapsed, 1.0 / 20, accuracy: 1e-9)
        flight.advance(by: -1)
        XCTAssertEqual(flight.elapsed, 1.0 / 20, accuracy: 1e-9)
    }

    func testCurveIsTheExpressiveBezier() {
        // cubic-bezier(0.2, 0, 0, 1) front-loads the motion: nearly 90 % of the way
        // at half time, and exact at the ends.
        let curve = ViewerEntranceFlight.curve
        XCTAssertEqual(curve.value(at: 0), 0, accuracy: 1e-6)
        XCTAssertEqual(curve.value(at: 1), 1, accuracy: 1e-6)
        XCTAssertGreaterThan(curve.value(at: 0.5), 0.85)
        XCTAssertLessThan(curve.value(at: 0.1), 0.25)
    }

    func testModelSettlesFromSixPercentOfTheCameraDistanceBelow() {
        let flight = ViewerEntranceFlight(rest: rest, start: .arrival)
        XCTAssertEqual(flight.settleDrop, rest.distance * 0.06, accuracy: 1e-5)
        var later = flight
        for _ in 0..<3 { later.advance(by: 1.0 / 20) }
        XCTAssertLessThan(later.settleDrop, flight.settleDrop)
    }

    @MainActor
    // MARK: - A sheet moving under the flight

    private func fit(distance: Float) -> SceneCameraPose {
        SceneCameraPose(azimuth: 0, elevation: 0, distance: distance, target: .zero)
    }

    /// An entity that counts as "in the scene" for the driver.
    private func stagedEntity() -> (stage: Entity, model: Entity) {
        let stage = Entity()
        let model = Entity()
        stage.addChild(model)
        return (stage, model)
    }

    @MainActor
    func testAModelArrivingWhileASheetMovesWaitsForTheSettledFit() {
        let driver = ViewerEntranceDriver()
        let (stage, model) = stagedEntity()
        let move = driver.viewportWillMove()
        driver.arrive(model, azimuth: 0.4, elevation: 0.2)
        // Fits read mid-move frame a rectangle that is still changing: the
        // first one is twice too far for the rectangle the sheet leaves.
        driver.cameraChanged(fit(distance: 4), entityInScene: true)
        XCTAssertNil(driver.pose)
        driver.cameraChanged(fit(distance: 2), entityInScene: true)
        XCTAssertNil(driver.pose)
        XCTAssertNotNil(model.components[OpacityComponent.self], "still hidden")

        driver.viewportDidSettle(move)
        XCTAssertEqual(driver.rest?.distance, 2)
        XCTAssertEqual(driver.rest?.azimuth, 0.4)
        XCTAssertNotNil(driver.pose, "the flight starts from the settled fit")
        driver.stop(settle: true)
        _ = stage
    }

    @MainActor
    func testAFitOfThePreviousModelDoesNotStartTheFlightOnSettle() {
        let driver = ViewerEntranceDriver()
        let (stage, model) = stagedEntity()
        let move = driver.viewportWillMove()
        driver.arrive(model, azimuth: 0.4, elevation: 0.2)
        // Reported before the new model was in the scene.
        driver.cameraChanged(fit(distance: 4), entityInScene: false)
        driver.viewportDidSettle(move)
        XCTAssertNil(driver.pose)
        // The next report is the new model's fit, the rectangle is still.
        driver.cameraChanged(fit(distance: 2), entityInScene: true)
        XCTAssertEqual(driver.rest?.distance, 2)
        XCTAssertNotNil(driver.pose)
        driver.stop(settle: true)
        _ = stage
    }

    @MainActor
    func testASheetMovingUnderAFlightLandsItAtOnce() throws {
        let driver = ViewerEntranceDriver()
        let (stage, model) = stagedEntity()
        driver.arrive(model, azimuth: 0.4, elevation: 0.2)
        driver.cameraChanged(fit(distance: 2), entityInScene: true)
        let start = try XCTUnwrap(driver.pose)
        XCTAssertGreaterThan(start.distance, 2, "the flight starts further out")

        _ = driver.viewportWillMove()
        XCTAssertEqual(driver.pose, driver.rest)
        XCTAssertNil(model.components[OpacityComponent.self], "revealed")
        // What SceneView reports next is its own rescale, not a flight to end.
        driver.cameraChanged(fit(distance: 3), entityInScene: true)
        XCTAssertEqual(driver.pose, driver.rest)
        _ = stage
    }

    @MainActor
    func testOnlyTheLastMoveSettles() {
        let driver = ViewerEntranceDriver()
        let (stage, model) = stagedEntity()
        let first = driver.viewportWillMove()
        let second = driver.viewportWillMove()
        driver.arrive(model, azimuth: 0.4, elevation: 0.2)
        driver.cameraChanged(fit(distance: 2), entityInScene: true)
        driver.viewportDidSettle(first)
        XCTAssertNil(driver.pose, "the sheet is still moving")
        driver.viewportDidSettle(second)
        XCTAssertNotNil(driver.pose)
        driver.stop(settle: true)
        _ = stage
    }

    @MainActor
    func testConstantsMatchAndroid() throws {
        let sources = ViewerAssetTests.androidDemoSources(#filePath)
        let viewer = try String(contentsOf: sources.appendingPathComponent("ModelViewerDemo.kt"), encoding: .utf8)
        let helpers = try String(contentsOf: sources.deletingLastPathComponent()
            .appendingPathComponent("DemoHelpers.kt"), encoding: .utf8)
        XCTAssertTrue(viewer.contains("CAMERA_ENTRANCE_MILLIS = \(Int(ViewerEntranceFlight.duration * 1000))"))
        XCTAssertTrue(viewer.contains("fitRadius * 0.06f"))
        XCTAssertTrue(helpers.contains("startDistanceScale: Float = 1.55f"))
        XCTAssertTrue(helpers.contains("startYawDegrees: Float = 24f"))
        XCTAssertTrue(helpers.contains("startLiftFraction: Float = 0.22f"))
        XCTAssertEqual(ViewerEntranceFlight.startDistanceScale, 1.55)
        XCTAssertEqual(ViewerEntranceFlight.startYawDegrees, 24)
        XCTAssertEqual(ViewerEntranceFlight.startLiftFraction, 0.22)
        XCTAssertEqual(ViewerEntranceFlight.settleDropFraction, 0.06)
    }

    // MARK: - Toy Car sheen

    @MainActor
    func testToyCarFabricGetsItsSheenBack() async throws {
        let url = try XCTUnwrap(Bundle.main.url(forResource: ViewerMaterialRepairs.toyCarAsset,
                                                withExtension: "usdz"))
        let car = try await Entity(contentsOf: url)
        ViewerMaterialRepairs.apply(to: car, asset: ViewerMaterialRepairs.toyCarAsset)
        var repaired = 0
        var stack = [car]
        while let entity = stack.popLast() {
            stack.append(contentsOf: entity.children)
            guard entity.name == ViewerMaterialRepairs.toyCarFabric,
                  let model = entity.components[ModelComponent.self] else { continue }
            for case let pbr as PhysicallyBasedMaterial in model.materials {
                // RealityKit keeps tints as linear sRGB: the values set, verbatim.
                let sheen = try linear(XCTUnwrap(pbr.sheen?.tint.cgColor))
                XCTAssertEqual(sheen[0], 1, accuracy: 1e-3)
                XCTAssertEqual(sheen[1], 0.3, accuracy: 1e-3)
                XCTAssertEqual(sheen[2], 0.08, accuracy: 1e-3)
                let tint = try linear(pbr.baseColor.tint.cgColor)
                XCTAssertEqual(tint[0], 0.15, accuracy: 2e-3)
                XCTAssertEqual(pbr.roughness.scale, 0.5, accuracy: 1e-6)
                repaired += 1
            }
        }
        XCTAssertGreaterThan(repaired, 0, "no Fabric material found in the Toy Car USDZ")
    }

    private func linear(_ color: CGColor) throws -> [CGFloat] {
        let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.extendedLinearSRGB))
        let converted = try XCTUnwrap(color.converted(to: space, intent: .defaultIntent, options: nil))
        return try XCTUnwrap(converted.components)
    }

    @MainActor
    func testOtherAssetsAreLeftAlone() {
        let box = ModelEntity(mesh: .generateBox(size: 0.1), materials: [PhysicallyBasedMaterial()])
        box.name = ViewerMaterialRepairs.toyCarFabric
        ViewerMaterialRepairs.apply(to: box, asset: "khronos_damaged_helmet")
        let pbr = box.model?.materials.first as? PhysicallyBasedMaterial
        XCTAssertNil(pbr?.sheen)
    }
}
