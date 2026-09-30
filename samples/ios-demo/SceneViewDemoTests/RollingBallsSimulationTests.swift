import XCTest
import simd

@testable import SceneViewDemo

/// Pins the Rolling Balls tray's rolling contact and tilt maths — the numbers Android's tray
/// loop uses too, so a tilted tray behaves the same on both platforms.
@MainActor
final class RollingBallsSimulationTests: XCTestCase {

    private let g: Float = 9.8
    private let dt = RollingBallsSimulation.stepSeconds

    // MARK: Rolling contact

    /// One step on a 10° slope: a solid rubber sphere picks up `5/7 · g·sin10°` minus its
    /// rolling resistance, not the full `g·sin10°` of a sliding block.
    func testTenDegreeSlopeAcceleratesAtFiveSeventhsOfGravityMinusResistance() {
        let gravity = RollingBallsSimulation.trayLocalGravity(pitchDegrees: 10, rollDegrees: 0)
        let mu = RollingBallKind.rubber.rollingResistance
        let integrated = SIMD3<Float>(0, 0, 0) + gravity * dt
        let v = RollingBallsSimulation.rolling(velocity: integrated, gravity: gravity,
                                               resistance: mu, dt: dt)
        let acceleration = (v.x * v.x + v.z * v.z).squareRoot() / dt
        let theta = Float(10) * .pi / 180
        let expected = 5.0 / 7.0 * g * sin(theta) - mu * g * cos(theta)
        XCTAssertEqual(acceleration, expected, accuracy: 0.01)
        XCTAssertGreaterThan(v.z, 0, "positive pitch lowers the near (+Z) edge")
    }

    /// Half a second of the full step loop on the same slope: speed matches the closed form
    /// `a/k · (1 − e^(−k t))` of a constant pull with the 0.3/s speed drag.
    func testBallOnTenDegreeTrayMatchesTheRollingClosedForm() {
        var simulation = RollingBallsSimulation()
        simulation.gravity = RollingBallsSimulation.trayLocalGravity(pitchDegrees: 10, rollDegrees: 0)
        let kind = RollingBallKind.rubber
        simulation.add(kind, at: SIMD3(0, RollingBallsSimulation.floor + kind.radius, -0.5),
                       velocity: .zero)
        for _ in 0..<60 { simulation.step() }
        let v = simulation.balls[0].velocity
        let theta = Float(10) * .pi / 180
        let pull = 5.0 / 7.0 * g * sin(theta) - kind.rollingResistance * g * cos(theta)
        let t = Float(60) * dt
        let expected = pull / 0.3 * (1 - exp(-0.3 * t))
        XCTAssertEqual((v.x * v.x + v.z * v.z).squareRoot(), expected, accuracy: expected * 0.05)
    }

    /// A 0.2° tilt pulls less than rubber's rolling resistance: a resting ball stays put.
    func testNearlyLevelTrayDoesNotStartARubberBall() {
        var simulation = RollingBallsSimulation()
        simulation.gravity = RollingBallsSimulation.trayLocalGravity(pitchDegrees: 0.2,
                                                                     rollDegrees: 0.2)
        let kind = RollingBallKind.rubber
        let start = SIMD3<Float>(0.1, RollingBallsSimulation.floor + kind.radius, 0.1)
        simulation.add(kind, at: start, velocity: .zero)
        for _ in 0..<240 { simulation.step() }
        let ball = simulation.balls[0]
        XCTAssertEqual(ball.velocity.x, 0)
        XCTAssertEqual(ball.velocity.z, 0)
        // The step integrates position before the contact zeroes the velocity (PhysicsBody.step
        // order), so the ball may creep by g·sinθ·dt² per step: under 1 mm in 2 s.
        XCTAssertEqual(ball.position.x, start.x, accuracy: 2e-3)
        XCTAssertEqual(ball.position.z, start.z, accuracy: 2e-3)
    }

    /// A ball launched across a level tray slows down and comes to a full stop — no endless
    /// creep, for each material.
    func testBallLaunchedOnALevelTrayComesToRest() {
        for kind in RollingBallKind.allCases {
            var simulation = RollingBallsSimulation()
            simulation.add(kind, at: SIMD3(0, RollingBallsSimulation.floor + kind.radius, 0),
                           velocity: SIMD3(1.2, 0, 0.4))
            for _ in 0..<(120 * 15) { simulation.step() }
            let v = simulation.balls[0].velocity
            XCTAssertEqual(v.x, 0, "\(kind) still rolling along x")
            XCTAssertEqual(v.z, 0, "\(kind) still rolling along z")
        }
    }

    // MARK: Tilt

    /// Whatever the orbit, a drag to the right lowers the tray's side that is on the right of
    /// the screen: gravity along the tray points to the camera's right.
    func testDragRightSendsTheBallsToTheScreenRightAtAnyAzimuth() {
        for azimuth in [Float(0), 0.7, .pi / 2, 2.5, -1.2, .pi] {
            let delta = RollingBallsCoordinator.tiltDelta(dx: 20, dy: 0, azimuth: azimuth)
            let gravity = RollingBallsSimulation.trayLocalGravity(pitchDegrees: delta.pitch,
                                                                  rollDegrees: delta.roll)
            let downhill = simd_normalize(SIMD2(gravity.x, gravity.z))
            let screenRight = SIMD2(cos(azimuth), -sin(azimuth))
            XCTAssertGreaterThan(simd_dot(downhill, screenRight), 0.99, "azimuth \(azimuth)")
        }
    }

    func testDragDownTipsTheNearEdgeDownFromTheDefaultCamera() {
        let delta = RollingBallsCoordinator.tiltDelta(dx: 0, dy: 10, azimuth: 0)
        XCTAssertEqual(delta.pitch, 10 * RollingBallsCoordinator.tiltPerPoint, accuracy: 1e-5)
        XCTAssertEqual(delta.roll, 0, accuracy: 1e-5)
    }

    /// The rendered tilt covers 63 % of the gap in one time constant, whatever the frame rate.
    func testSmoothingIsExponentialAndFrameRateIndependent() {
        let tau = RollingBallsCoordinator.dragTau
        let one = RollingBallsCoordinator.smoothed(0, toward: 10, dt: tau, tau: tau)
        XCTAssertEqual(one, 10 * (1 - exp(-1)), accuracy: 1e-4)
        let half = RollingBallsCoordinator.smoothed(0, toward: 10, dt: tau / 2, tau: tau)
        let two = RollingBallsCoordinator.smoothed(half, toward: 10, dt: tau / 2, tau: tau)
        XCTAssertEqual(two, one, accuracy: 1e-4)
        XCTAssertEqual(RollingBallsCoordinator.smoothed(9.9995, toward: 10, dt: 0.001, tau: tau), 10)
    }
}
