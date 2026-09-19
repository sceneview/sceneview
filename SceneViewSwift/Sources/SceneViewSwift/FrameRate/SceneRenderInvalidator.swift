#if os(iOS) || os(macOS) || os(visionOS)
import Combine

/// Wakes a ``SceneView`` whose ``FrameRatePolicy/onDemand(maxFps:)`` driver has parked.
///
/// The Swift counterpart of Android's `rememberRenderInvalidator()` +
/// `SceneView(renderInvalidator = …)`. Hold one, hand it to the view, call
/// ``requestRender()`` when you change something the library cannot see:
///
/// ```swift
/// @StateObject private var invalidator = SceneRenderInvalidator()
///
/// var body: some View {
///     SceneView { root in root.addChild(model) }
///         .renderInvalidator(invalidator)
///         .onReceive(myAnimationTimer) { _ in
///             model.position.y = bounce()
///             invalidator.requestRender()   // hold the cadence while I drive this
///         }
/// }
/// ```
///
/// ## When you need it on iOS — which is less often than on Android, and not for the same reason
///
/// On Android this is how a pixel gets drawn at all. A `MaterialInstance.setParameter` or a
/// light intensity written straight onto the Filament component is invisible to the gate, the
/// loop stays parked, and **the change never appears** until something else asks for a frame.
///
/// On iOS nothing is ever invisible in that sense: RealityKit owns the render loop and
/// presents your entity edit on the next vsync whether or not SceneViewSwift ticked. Writing
/// `entity.position` from your own timer works with no invalidator at all, and adding one
/// changes nothing about whether the change is seen.
///
/// What it changes is the **cadence**. A parked driver has invalidated its `CADisplayLink`, so
/// SceneViewSwift is asking the system for no particular refresh rate; a variable-refresh-rate
/// panel is then free to idle down, and an animation you are driving yourself can end up
/// presented at the panel's idle rate instead of its maximum. ``requestRender()`` re-arms the
/// driver and the cadence request for ``FrameRateGate/settleFrames`` ticks — so calling it once
/// per frame of your own animation holds the rate for its duration, and calling it once gives a
/// short tail.
///
/// That is the whole of it. If you are not driving motion from outside the library, you do not
/// need this type.
///
/// ## Ordering
///
/// A request made before the view has attached is not lost: the generation counter is part of
/// the driver's task identity, so a bump that lands early simply means the first loop starts
/// on the newer identity. There is no attach-order hazard to design around — the Android
/// invalidator's explicit replay buffer has no Swift equivalent because it needs none.
public final class SceneRenderInvalidator: ObservableObject {

    /// Monotonic count of requests. Read by ``SceneView`` as part of its driver's task
    /// identity; a change restarts the driver.
    ///
    /// `&+` on overflow rather than a trap: this is a wake-up counter, and an app calling
    /// ``requestRender()`` every frame for a year should not crash on the wrap.
    @Published public private(set) var generation: Int = 0

    public init() {}

    /// Ask the scene to resume per-frame work and re-raise its cadence request.
    ///
    /// Cheap and idempotent within a frame: several calls between two ticks re-arm the same
    /// budget once. Main-actor bound because it feeds SwiftUI state.
    @MainActor
    public func requestRender() {
        generation &+= 1
    }
}

#endif
