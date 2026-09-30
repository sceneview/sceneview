import XCTest
import simd

@testable import SceneViewDemo

/// The Sound Garden's audio maths on iOS — the same cases as Android's `SoundGardenStemsTest`,
/// so both apps pulse and fire their shells on the same notes — plus the two iOS-only
/// contracts: the bundled AAC stems decode to exactly one loop, and the garden is planted where
/// the user faces at any phone pitch.
final class SoundGardenStemsTests: XCTestCase {

    func testTheLoopIs16BeatsAt90BPM() {
        let beatFrames = SoundGardenStems.sampleRate * 60 / 90
        XCTAssertEqual(SoundGardenStems.loopFrames, 16 * beatFrames)
    }

    /// Core Audio honours the CAF packet table: AAC priming and the last packet's padding are
    /// dropped, so each part is exactly one loop long and the four stay on the same beat.
    func testEveryBundledStemDecodesToExactlyOneLoop() throws {
        let bundle = Bundle.main // tests are hosted by the app
        for part in ["beat", "bass", "pad", "bells"] {
            let url = try XCTUnwrap(bundle.url(forResource: "garden_\(part)", withExtension: "caf"),
                                    "garden_\(part).caf is not in the app bundle")
            let samples = try SoundGardenStems.decodeMono(url: url)
            XCTAssertEqual(samples.count, SoundGardenStems.loopFrames, part)
            XCTAssertGreaterThan(samples.map(abs).max() ?? 0, 0.05, "\(part) decodes to silence")
        }
    }

    func testTheEnvelopePeaksAt1WhereTheStemIsLoudest() {
        let window = SoundGardenStems.envelopeWindow
        // Silence, one loud window, silence.
        var stem = [Float](repeating: 0, count: window * 60)
        for i in (window * 5)..<(window * 6) { stem[i] = i % 2 == 0 ? 0.8 : -0.8 }
        let envelope = SoundGardenStems.envelope(stem)
        XCTAssertEqual(envelope.count, 60)
        XCTAssertEqual(envelope[5], 1, accuracy: 1e-6)
        // The hold wraps around the loop, so the window before the hit only sees its tail from
        // almost a whole loop ago.
        XCTAssertLessThan(envelope[4], 0.01)
        // Released, not cut.
        XCTAssertTrue((0.5...0.99).contains(envelope[6]))
        XCTAssertLessThan(envelope[7], envelope[6])
        XCTAssertLessThan(envelope[40], 0.1)
    }

    func testEnvelopeLookupFollowsThePlayedPositionAndWraps() {
        let window = SoundGardenStems.envelopeWindow
        let envelope = (0..<(SoundGardenStems.loopFrames / window)).map(Float.init)
        XCTAssertEqual(SoundGardenStems.envelope(envelope, at: 0), 0)
        XCTAssertEqual(SoundGardenStems.envelope(envelope, at: 3 * window + 10), 3)
        XCTAssertEqual(SoundGardenStems.envelope(envelope, at: SoundGardenStems.loopFrames + 3 * window), 3)
        // Just before the parts start (output latency subtracted): the end of the loop.
        XCTAssertEqual(SoundGardenStems.envelope(envelope, at: -1), Float(envelope.count - 1))
    }

    func testAJumpIsANoteAndTheNextWindowsPointBackToIt() {
        var envelope = [Float](repeating: 0.05, count: 40)
        envelope[10] = 1
        envelope[11] = 0.87 // the release after the hit is not a new note
        envelope[30] = 0.6
        let last = SoundGardenStems.lastOnsets(envelope)
        XCTAssertEqual(last[10], 10)
        XCTAssertEqual(last[11], 10)
        XCTAssertEqual(last[29], 10)
        XCTAssertEqual(last[30], 30)
        // Before the loop's first note, the latest note is the last one of the previous pass.
        XCTAssertEqual(last[3], 30)
    }

    func testQuietRisesAndNotesCloserThanASixteenthDoNotFire() {
        var envelope = [Float](repeating: 0.05, count: 40)
        envelope[5] = 0.3 // under the floor
        envelope[12] = 1
        envelope[14] = 1 // 2 windows after the previous note
        let last = SoundGardenStems.lastOnsets(envelope)
        XCTAssertEqual(last[12], 12)
        XCTAssertEqual(last[14], 12)
        XCTAssertEqual(last[20], 12)
        XCTAssertEqual(last[5], 12)
    }

    func testAPartWithoutNotesHasNoOnset() {
        let flat = SoundGardenStems.lastOnsets([Float](repeating: 0.5, count: 20))
        XCTAssertTrue(flat.allSatisfy { $0 == -1 })
        XCTAssertNil(SoundGardenStems.secondsSinceOnset(flat, at: 1_234))
        XCTAssertTrue(SoundGardenStems.lastOnsets([]).isEmpty)
    }

    func testTimeSinceTheLastNoteWrapsAcrossTheLoopPoint() throws {
        let window = SoundGardenStems.envelopeWindow
        var envelope = [Float](repeating: 0.05, count: 500)
        envelope[100] = 1
        envelope[490] = 1
        let last = SoundGardenStems.lastOnsets(envelope)
        let rate = Float(SoundGardenStems.sampleRate)
        XCTAssertEqual(try XCTUnwrap(SoundGardenStems.secondsSinceOnset(last, at: 100 * window + 1_000)),
                       1_000 / rate, accuracy: 1e-6)
        // Window 2 of the next pass: the note at window 490 is 12 windows old.
        XCTAssertEqual(try XCTUnwrap(SoundGardenStems.secondsSinceOnset(last, at: 500 * window + 2 * window)),
                       Float(12 * window) / rate, accuracy: 1e-6)
    }

    // MARK: - Planting

    /// Android's `SpatialVoiceMath.listenerFrame` case for case: level, pitched down 60°, and
    /// straight down (where only the top edge of the screen says where the user faces).
    @MainActor
    func testTheGardenIsPlantedWhereTheUserFacesAtAnyPitch() {
        let level = SoundGardenController.facing(forward: [0, 0, -1], up: [0, 1, 0])
        XCTAssertEqual(level.x, 0, accuracy: 1e-5)
        XCTAssertEqual(level.y, -1, accuracy: 1e-5)

        // Facing +X, tilted 60° down: forward dips, up leans forward.
        let p = Float.pi / 3
        let tilted = SoundGardenController.facing(forward: [cos(p), -sin(p), 0], up: [sin(p), cos(p), 0])
        XCTAssertEqual(tilted.x, 1, accuracy: 1e-5)
        XCTAssertEqual(tilted.y, 0, accuracy: 1e-5)

        // Straight down, top of the screen towards −X.
        let down = SoundGardenController.facing(forward: [0, -1, 0], up: [-1, 0, 0])
        XCTAssertEqual(down.x, -1, accuracy: 1e-5)
        XCTAssertEqual(down.y, 0, accuracy: 1e-5)

        // Degenerate (forward and up both vertical): a defined direction, not NaN.
        let degenerate = SoundGardenController.facing(forward: [0, -1, 0], up: [0, 1, 0])
        XCTAssertEqual(simd_length(degenerate), 1, accuracy: 1e-5)
    }
}
