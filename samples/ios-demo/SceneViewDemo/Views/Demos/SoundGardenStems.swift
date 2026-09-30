import Foundation
import AVFoundation

/// The Sound Garden's four parts and what the eye reads from them — the iOS twin of
/// Android's `SoundGardenStems` (`samples/android-demo/.../demos/soundgarden/SoundGardenStems.kt`),
/// number for number, so an orb pulses and fires its shells on the same notes on both
/// platforms.
///
/// The stems are written by `tools/generate-sound-garden-stems.py`; `loopFrames` must equal
/// `LOOP` there and `SoundGardenStems.LOOP_FRAMES` on Android.
enum SoundGardenStems {

    /// Rate the stems are written at.
    static let sampleRate = 48_000

    /// Frames in one loop: 4 bars of 4/4 at 90 BPM = 16 beats × 32 000 frames = 10.667 s.
    static let loopFrames = 512_000

    /// RMS window of the visual envelope: 21 ms, 500 windows per loop.
    static let envelopeWindow = 1_024

    /// Per-window decay of the envelope's peak hold: a hit lights its orb at once and lets go
    /// over ≈ 150 ms, so a 60 Hz frame loop never misses a 50 ms drum hit.
    static let envelopeRelease: Float = 0.87

    /// Rise of the envelope within one window that counts as a new note — the moment a shell
    /// leaves the orb.
    static let onsetRise: Float = 0.18

    /// Level a rise has to reach to count: quiet hats and pad shimmer do not fire a shell.
    static let onsetFloor: Float = 0.35

    /// Closest two onsets can be: 8 windows = 171 ms, a sixteenth note at 90 BPM.
    static let onsetMinGap = 8

    /// Reads a bundled stem as mono floats. Core Audio honours the CAF packet table, so the
    /// AAC priming is dropped and the result is exactly one loop long.
    static func decodeMono(url: URL) throws -> [Float] {
        let file = try AVAudioFile(forReading: url, commonFormat: .pcmFormatFloat32, interleaved: false)
        let capacity = AVAudioFrameCount(file.length)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: capacity),
              capacity > 0 else { return [] }
        try file.read(into: buffer)
        guard let channel = buffer.floatChannelData?[0] else { return [] }
        return Array(UnsafeBufferPointer(start: channel, count: Int(buffer.frameLength)))
    }

    /// The loudness contour that drives an orb's pulse: RMS per `window` frames, held with a
    /// fast attack and an ``envelopeRelease`` decay, then normalised so each part's loudest
    /// moment is 1. The hold wraps around the loop, like the audio does.
    static func envelope(_ stem: [Float], window: Int = envelopeWindow) -> [Float] {
        precondition(window > 0, "window must be positive")
        let count = (stem.count + window - 1) / window
        guard count > 0 else { return [] }
        var rms = [Float](repeating: 0, count: count)
        stem.withUnsafeBufferPointer { samples in
            for w in 0..<count {
                let from = w * window
                let to = min(from + window, samples.count)
                var sum = 0.0
                for i in from..<to { sum += Double(samples[i]) * Double(samples[i]) }
                rms[w] = Float((sum / Double(to - from)).squareRoot())
            }
        }
        // Two passes so the hold carried over the loop point is settled when the second starts.
        var held = [Float](repeating: 0, count: count)
        var level: Float = 0
        for _ in 0..<2 {
            for w in 0..<count {
                level = max(rms[w], level * envelopeRelease)
                held[w] = level
            }
        }
        if let peak = held.max(), peak > 0 {
            for w in held.indices { held[w] /= peak }
        }
        return held
    }

    /// The envelope value heard at loop position `frame`.
    static func envelope(_ envelope: [Float], at frame: Int, window: Int = envelopeWindow) -> Float {
        guard !envelope.isEmpty else { return 0 }
        let loopFrame = floorMod(frame, loopFrames)
        return envelope[min(loopFrame / window, envelope.count - 1)]
    }

    /// For every window of `envelope`, the window of the latest note that started at or before
    /// it — a jump of at least ``onsetRise`` up to at least ``onsetFloor`` — looking back across
    /// the loop point like the audio does. All `-1` when the part has no note at all.
    static func lastOnsets(_ envelope: [Float]) -> [Int] {
        let count = envelope.count
        var result = [Int](repeating: -1, count: count)
        guard count > 0 else { return result }
        var onset = [Bool](repeating: false, count: count)
        var last = Int.min / 2
        for w in 0..<count {
            let rise = envelope[w] - envelope[(w - 1 + count) % count]
            if rise >= onsetRise, envelope[w] >= onsetFloor, w - last >= onsetMinGap {
                onset[w] = true
                last = w
            }
        }
        // Before the loop's first note, the latest one is the last note of the previous pass.
        guard var current = onset.lastIndex(of: true) else { return result }
        for w in 0..<count {
            if onset[w] { current = w }
            result[w] = current
        }
        return result
    }

    /// Seconds since the latest note heard at loop position `frame`, from ``lastOnsets(_:)``'s
    /// table; `nil` when the part has no note. Wraps: just after the loop point, the last note of
    /// the previous pass is a few hundred milliseconds old, not ten seconds in the future.
    static func secondsSinceOnset(_ lastOnsets: [Int], at frame: Int, window: Int = envelopeWindow) -> Float? {
        guard !lastOnsets.isEmpty else { return nil }
        let loop = lastOnsets.count * window
        let loopFrame = floorMod(frame, loop)
        let onset = lastOnsets[loopFrame / window]
        guard onset >= 0 else { return nil }
        let delta = floorMod(loopFrame - onset * window, loop)
        return Float(delta) / Float(sampleRate)
    }

    private static func floorMod(_ value: Int, _ modulus: Int) -> Int {
        let r = value % modulus
        return r < 0 ? r + modulus : r
    }
}
