#if os(iOS) || os(macOS) || os(visionOS)
import Foundation

/// Distance-attenuation model applied to a ``SpatialAudioNode``.
///
/// Mirrors Android's `io.github.sceneview.audio.AudioFalloff` sealed hierarchy verbatim so
/// the same `(refDistance, maxDistance)` inputs produce identical gain curves on every
/// platform. See the Android tests
/// (`sceneview/src/test/java/io/github/sceneview/audio/AudioFalloffTest.kt`) for the
/// golden table that every implementation must match — Swift `Self.gain(for:distance:)`
/// is the verbatim port and the Swift unit tests pin the same numbers.
public enum AudioFalloff: Sendable {

    /// Inverse distance attenuation (WebAudio `"inverse"` model). Physically realistic;
    /// the default for ``SpatialAudioNode``.
    case inverse(refDistance: Float = 1, maxDistance: Float = 100, rolloffFactor: Float = 1)

    /// Linear attenuation — gain reaches `0` exactly at `maxDistance`.
    case linear(refDistance: Float = 1, maxDistance: Float = 100)

    /// No distance attenuation — gain is always `1.0`.
    case none

    /// Computes the linear gain (`0...1`) for `falloff` at the given source-listener
    /// `distance` (meters). Internal helper used by the iOS RealityKit playback path so
    /// the curves match Android `AudioFalloff.gainFor`.
    public static func gain(for falloff: AudioFalloff, distance: Float) -> Float {
        // NaN / negative distances become 0 — keep the gain in `[0, 1]` even if a
        // malformed frame produces garbage coordinates.
        let d = (distance.isFinite && distance >= 0) ? distance : 0
        switch falloff {
        case .none:
            return 1
        case .inverse(let ref, let max, let rolloff):
            let clamped = Swift.min(d, max)
            if clamped <= ref { return 1 }
            let safeRef = Swift.max(ref, 1e-4)
            let gain = safeRef / (safeRef + rolloff * (clamped - safeRef))
            return Swift.min(Swift.max(gain, 0), 1)
        case .linear(let ref, let max):
            if max <= ref {
                return d < ref ? 1 : 0
            }
            let clamped = Swift.min(Swift.max(d, ref), max)
            let gain = 1 - (clamped - ref) / (max - ref)
            return Swift.min(Swift.max(gain, 0), 1)
        }
    }
}
#endif // os(iOS) || os(macOS) || os(visionOS)
