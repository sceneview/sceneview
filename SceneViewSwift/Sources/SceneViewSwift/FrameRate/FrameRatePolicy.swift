#if os(iOS) || os(macOS) || os(visionOS)

/// How often a ``SceneView`` does its per-frame work, and what display cadence it asks for.
///
/// Mirrors SceneView Android's `FrameRatePolicy`
/// (`sceneview/src/main/java/io/github/sceneview/FrameRatePolicy.kt`), case for case and
/// name for name. Pass it with ``SceneView/frameRatePolicy(_:)``:
///
/// ```swift
/// SceneView { /* … */ }                                   // .onDemand() — the default
/// SceneView { /* … */ }.frameRatePolicy(.continuous())    // tick every vsync, never park
/// SceneView { /* … */ }.frameRatePolicy(.onDemand(maxFps: 30))
/// ```
///
/// ## Read this before you believe the name
///
/// On Android the render loop belongs to SceneView: it calls Filament's
/// `beginFrame` / `render` / `endFrame` itself, so `OnDemand` can decide not to draw and
/// **no GPU frame happens**.
///
/// On iOS it does not. `SceneView` is a SwiftUI `RealityView`, and RealityKit owns its own
/// render loop — there is no public gate, no `isPaused`, no `setNeedsDisplay`, and
/// `ARView.RenderOptions` carries no cadence option. **`.onDemand` therefore cannot stop
/// RealityKit from presenting a frame**, and this type does not pretend otherwise.
///
/// What it *does* control on iOS is real, and it is the whole of what the platform offers:
///
/// 1. **SceneViewSwift's own per-frame driver.** The camera-motion loop — the one writer for
///    motion no finger is driving (the coast a released drag leaves, the ``SceneView/autoRotate(speed:)``
///    turntable) — runs on a `CADisplayLink` under this policy. Under ``onDemand(maxFps:)``
///    it stops once nothing is moving and schedules no wake-up at all; under
///    ``continuous(maxFps:)`` it keeps ticking for the view's lifetime.
/// 2. **The display cadence request.** A running driver carries a
///    `CADisplayLink.preferredFrameRateRange`; a parked one is invalidated, so SceneViewSwift
///    asks the system for nothing and a variable-refresh-rate panel is free to idle down.
///    This is the iOS counterpart of Android's `Surface.setFrameRate` vote, and it is a
///    request in exactly the same way — the system may ignore it.
/// 3. **The cap.** ``maxFps`` bounds both the driver's tick rate and the cadence request.
///
/// ## iOS ↔ Android divergence
///
/// | | Android (Filament) | iOS / visionOS (RealityKit) |
/// |---|---|---|
/// | Skip the GPU submit when nothing changed | ✅ the loop owns `beginFrame`/`render`/`endFrame` | ❌ **not possible** — RealityKit presents every vsync |
/// | Park the library's own per-frame work | ✅ | ✅ the camera / coast / turntable driver stops |
/// | Cadence request | ✅ `Surface.setFrameRate` (API 30+) | ✅ `CADisplayLink.preferredFrameRateRange` (iOS 15+); ❌ nothing on macOS |
/// | `maxFps` cap | ✅ presents on whole vsync multiples | ⚠️ caps *our* driver and *our* request — **not** RealityKit's own presentation |
/// | Above 60 Hz on iPhone | (n/a) | ⚠️ needs `CADisableMinimumFrameDurationOnPhone` in the **host app's** `Info.plist`; a framework cannot set it for its host |
/// | `maxFps` ≤ 0 | throws (`require(fps > 0)`) | clamped to `1` — a Swift `enum` case has no failable construction point |
/// | `requestRender()` after mutating a raw object | **required** — the frame would not be drawn | not required for the pixel to appear (RealityKit draws it anyway); use ``SceneRenderInvalidator`` to re-arm the driver and the cadence request |
///
/// The honest one-line summary: on Android `.onDemand` saves the **GPU**; on iOS it saves
/// SceneViewSwift's **CPU** and lets the panel idle, and the GPU is Apple's business.
///
/// ## Choosing
///
/// - ``onDemand(maxFps:)`` — the default, and right for nearly everything. A product viewer
///   nobody is touching stops advancing a camera that is not moving and stops asking for
///   cadence it is not using.
/// - ``continuous(maxFps:)`` — for a scene driven from outside SceneViewSwift every frame
///   (an app-owned animation writing entity transforms on its own timer) where you would
///   rather hold the cadence than call ``SceneRenderInvalidator/requestRender()``.
public enum FrameRatePolicy: Sendable, Hashable {

    /// Do per-frame work only while something is actually happening, then park.
    ///
    /// "Something happening" is a gesture in flight, a drag still coasting, an active
    /// turntable, a framing pass that has not latched, or an explicit
    /// ``SceneRenderInvalidator/requestRender()``. After the last of those the driver still
    /// owes ``FrameRateGate/settleFrames`` ticks before it parks — see that property for why
    /// the iOS reason differs from Android's.
    ///
    /// Parked means parked: the `CADisplayLink` is invalidated, so there is no periodic
    /// wake-up and no cadence request. It does **not** mean the screen stops being redrawn —
    /// RealityKit keeps presenting.
    ///
    /// - Parameter maxFps: ceiling for the driver's tick rate and the cadence request.
    ///   `nil` (the default) asks for the platform maximum. Values below `1` are clamped to `1`.
    case onDemand(maxFps: Int? = nil)

    /// Do per-frame work every vsync for the view's lifetime, and hold the cadence request.
    ///
    /// The driver never parks, even on a scene where nothing moves. This is the closest
    /// analogue of Android's `FrameRatePolicy.Continuous`, and like it, it is the option you
    /// pick when something outside the library changes the scene every frame.
    ///
    /// - Parameter maxFps: ceiling for the driver's tick rate and the cadence request.
    ///   `nil` (the default) asks for the platform maximum. Values below `1` are clamped to `1`.
    case continuous(maxFps: Int? = nil)

    /// The ceiling this policy was constructed with, exactly as written — `nil` for "no cap".
    ///
    /// Mirrors the Kotlin `val maxFps: Int?` on the sealed interface. Use ``resolvedMaxFps``
    /// for the value the driver actually applies, which clamps out the nonsense.
    public var maxFps: Int? {
        switch self {
        case .onDemand(let maxFps), .continuous(let maxFps):
            return maxFps
        }
    }

    /// ``maxFps`` clamped to the range the platform can express: `nil` stays `nil`, anything
    /// below `1` becomes `1`.
    ///
    /// Android rejects `maxFps <= 0` at construction with `require(fps > 0)`. A Swift `enum`
    /// case has no body to throw from, and trapping inside a display-link callback would turn
    /// a caller's typo into a crash in a shipped app, so the Swift port clamps instead and
    /// says so here and in the divergence table on ``FrameRatePolicy``.
    public var resolvedMaxFps: Int? {
        maxFps.map { Swift.max(1, $0) }
    }

    /// Whether the driver keeps ticking with nothing happening.
    ///
    /// `true` only for ``continuous(maxFps:)``. This is the single behavioural bit that
    /// separates the two cases; everything else they share.
    public var ticksWhenIdle: Bool {
        switch self {
        case .onDemand:
            return false
        case .continuous:
            return true
        }
    }
}

#endif
