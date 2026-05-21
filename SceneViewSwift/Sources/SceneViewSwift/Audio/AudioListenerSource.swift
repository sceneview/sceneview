#if os(iOS) || os(macOS) || os(visionOS)
import RealityKit

/// Where the virtual "ears" of a ``SpatialAudioNode`` sit in world space.
///
/// Mirrors Android's `AudioListenerSource`. Default ``camera`` tracks the active
/// camera entity — what almost every interactive app wants.
///
/// ``anchor(_:)`` is reserved for phase 2 of #1900 (AR-anchored audio); declared today
/// so the public API does not churn between phases. The phase-1 path logs a warning and
/// falls back to ``camera`` at runtime.
///
/// The `.anchor` case is iOS-only because ``AnchorNode`` is itself ARKit-only
/// (`#if os(iOS)`). On macOS / visionOS only ``camera`` is reachable in the public API
/// — the phase-1 listener engine treats both platforms identically.
public enum AudioListenerSource: Sendable {

    /// Tracks the camera pose each frame — the default for every app.
    case camera

    #if os(iOS)
    /// Anchors the listener to a node entity (typically an ``AnchorNode``).
    ///
    /// **Phase 1 (v4.12):** declared but not implemented — the engine logs and falls
    /// back to ``camera``. Wired up for real in phase 2.
    ///
    /// `AnchorNode` is itself a `Sendable` value type, so this enum can stay `Sendable`
    /// while carrying the payload. iOS-only because `AnchorNode` is ARKit-backed and
    /// ARKit ships on iOS only.
    case anchor(AnchorNode)
    #endif
}
#endif // os(iOS) || os(macOS) || os(visionOS)
