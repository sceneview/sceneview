// CosmosSystemTests.swift
//
// Pure-function tests for the Cosmos Star scene's ringed world (`CosmosSystem.swift`) — the
// iOS mirror of Android's `CosmosSystemTest` (#4192): the orbit, the camera poses that frame
// it, the eased flight between them and the tap hit test.

#if DEBUG

import XCTest
import simd
@testable import SceneViewDemo

final class CosmosSystemTests: XCTestCase {

    private let aspects: [Float] = [1206.0 / 2622.0, 1, 2622.0 / 1206.0]

    func testEaseExpressiveRunsFromZeroToOneAndNeverTurnsBack() {
        XCTAssertEqual(CosmosSystem.easeExpressive(0), 0)
        XCTAssertEqual(CosmosSystem.easeExpressive(1), 1)
        var last: Float = 0
        for i in 1...100 {
            let value = CosmosSystem.easeExpressive(Float(i) / 100)
            XCTAssertGreaterThanOrEqual(value, last - 1e-5)
            last = value
        }
        // A quick start: past halfway well before half the time.
        XCTAssertGreaterThan(CosmosSystem.easeExpressive(0.3), 0.5)
    }

    func testBlendStartsAndEndsOnItsPoses() {
        let system = CosmosSystem(aspect: aspects[0])
        let from = system.systemPose(2)
        let to = system.planetPose(2)
        let start = CosmosSystem.blend(from, to, 0)
        let end = CosmosSystem.blend(from, to, 1)
        XCTAssertLessThan(simd_distance(start.eye, from.eye), 1e-4)
        XCTAssertLessThan(simd_distance(end.eye, to.eye), 1e-4)
        XCTAssertLessThan(simd_distance(end.target, to.target), 1e-4)
    }

    func testAFlightNeverEntersTheStar() {
        for aspect in aspects {
            let system = CosmosSystem(aspect: aspect)
            for time: Float in [0, 3, 20, 45] {
                for (a, b) in [(CosmosFocus.system, CosmosFocus.planet), (.planet, .star), (.star, .planet)] {
                    let from = system.pose(a, time: time)
                    let to = system.pose(b, time: time)
                    for i in 0...50 {
                        let pose = CosmosSystem.blend(from, to, Float(i) / 50)
                        XCTAssertGreaterThan(simd_length(pose.eye), 1.2, "\(a) → \(b) at \(time) s")
                    }
                }
            }
        }
    }

    func testTheOverviewFramesTheWholeOrbitAtEveryAspect() {
        for aspect in aspects {
            let system = CosmosSystem(aspect: aspect)
            for time: Float in [0, 7, 30] {
                let pose = system.systemPose(time)
                for i in 0..<72 {
                    let p = system.orbitPoint(Float(i) * 2 * .pi / 72)
                    guard let s = CosmosSystem.project(pose, aspect: aspect, p) else {
                        return XCTFail("orbit point behind the camera")
                    }
                    XCTAssertLessThanOrEqual(abs(s.x), CosmosSystem.systemCoverX + 1e-3)
                    XCTAssertLessThanOrEqual(abs(s.y), CosmosSystem.systemCoverY + 1e-3)
                }
            }
        }
    }

    func testThePlanetStaysOnItsOrbit() {
        let system = CosmosSystem(aspect: aspects[0])
        for time: Float in [0, 1, 17, 90] {
            XCTAssertEqual(simd_length(system.planetPosition(time)), CosmosSystem.orbitRadius, accuracy: 1e-4)
            XCTAssertEqual(simd_dot(system.planetPosition(time), system.normal), 0, accuracy: 1e-4)
        }
    }

    func testATapFindsThePlanetThenTheStarElseNothing() {
        let width: Float = 402
        let height: Float = 874
        let system = CosmosSystem(aspect: width / height)
        let time: Float = 3
        let pose = system.systemPose(time)
        let planet = CosmosSystem.project(pose, aspect: width / height, system.planetPosition(time))!
        let px = (planet.x + 1) * 0.5 * width
        let py = (1 - planet.y) * 0.5 * height
        XCTAssertEqual(system.hit(pose: pose, time: time, width: width, height: height,
                                  x: px, y: py, minRadius: 32), .planet)
        XCTAssertEqual(system.hit(pose: pose, time: time, width: width, height: height,
                                  x: width / 2, y: height / 2, minRadius: 32), .star)
        XCTAssertNil(system.hit(pose: pose, time: time, width: width, height: height,
                                x: 4, y: 4, minRadius: 32))
    }

    func testAnInstantFlightLands() {
        let system = CosmosSystem(aspect: aspects[0])
        var flight = CosmosFlight()
        flight.record(system.systemPose(3), time: 3)
        flight.start()
        let target = system.planetPose(3)
        let pose = flight.advance(now: 10, target: target, instant: true)
        XCTAssertEqual(pose, target)
        XCTAssertFalse(flight.flying)
    }

    func testALiveFlightLeavesFromThePoseOnScreenAndLandsAfterItsDuration() {
        let system = CosmosSystem(aspect: aspects[0])
        var flight = CosmosFlight()
        let from = system.systemPose(3)
        let target = system.planetPose(3)
        flight.record(from, time: 3)
        flight.start()
        let first = flight.advance(now: 10, target: target, instant: false)
        XCTAssertLessThan(simd_distance(first.eye, from.eye), 1e-4)
        var now = 10.0
        var pose = first
        while flight.flying && now < 13 {
            now += 1.0 / 60
            pose = flight.advance(now: now, target: target, instant: false)
        }
        XCTAssertEqual(pose, target)
        XCTAssertEqual(now - 10, Double(CosmosSystem.flySeconds), accuracy: 0.05)
    }

    func testTheRingDensityHasItsGapAndFadesAtItsEdges() {
        XCTAssertEqual(CosmosSystem.ringDensity(0), 0, accuracy: 1e-4)
        XCTAssertEqual(CosmosSystem.ringDensity(1), 0, accuracy: 1e-4)
        XCTAssertLessThan(CosmosSystem.ringDensity(0.62), 0.05)
        let body = stride(from: Float(0.35), through: 0.55, by: 0.01).map(CosmosSystem.ringDensity).max()!
        XCTAssertGreaterThan(body, 0.6)
    }

    /// The overview shows the star's glow whole: it must die out before its quad's edge
    /// fade starts (0.8 of a 5-radius extent), or the quad shows as a hard-edged disc.
    func testTheStarGlowFadesOutInsideItsQuad() {
        XCTAssertGreaterThan(CosmosSceneLayers.starCorona(1.5).z, 0.1)
        XCTAssertLessThan(CosmosSceneLayers.starCorona(4).z, 0.002)
        XCTAssertEqual(CosmosSceneLayers.starCorona(4.8).z, 0)
        var last = CosmosSceneLayers.starCorona(1).z
        for r in stride(from: Float(1.05), through: 5, by: 0.05) {
            let blue = CosmosSceneLayers.starCorona(r).z
            XCTAssertLessThanOrEqual(blue, last + 1e-6, "brightens again at \(r)")
            last = blue
        }
    }

    /// Left alone, the camera tours every look and comes back, holding each for `hold`
    /// seconds from its landing; a touch holds it for `resume` instead.
    func testTheAutopilotToursEveryLookAndYieldsToATouch() {
        var pilot = CosmosAutopilot()
        var focus = CosmosFocus.system
        var seen: [CosmosFocus] = []
        for _ in 0..<3 {
            XCTAssertNil(pilot.advance(1, flying: true, focus: focus), "flies on mid-flight")
            var waited: Float = 0
            while true {
                waited += 0.1
                if let next = pilot.advance(0.1, flying: false, focus: focus) {
                    focus = next
                    break
                }
            }
            XCTAssertEqual(waited, CosmosAutopilot.hold, accuracy: 0.11)
            seen.append(focus)
        }
        XCTAssertEqual(seen, [.planet, .star, .system])

        pilot.touched()
        XCTAssertNil(pilot.advance(CosmosAutopilot.hold + 0.5, flying: false, focus: focus))
        XCTAssertEqual(pilot.advance(CosmosAutopilot.resume - CosmosAutopilot.hold, flying: false, focus: focus),
                       .planet)
    }
}

#endif
