#if os(iOS) || os(macOS) || os(visionOS)
import SwiftUI

/// Delivers ``SceneView/contentInsets(_:)`` to the camera frame by frame.
///
/// `Animatable`: when the insets change inside an animated transaction,
/// SwiftUI re-evaluates this modifier — and only this modifier — with every
/// interpolated value, and `onFrame` receives each of them. The projection
/// therefore follows a panel on the panel's own curve, with no timer of its
/// own and without the scene view's body running per frame.
struct ContentInsetsDriver: ViewModifier, Animatable {
    /// The insets of the current frame, physical edges.
    var insets: EdgeInsets
    /// Called with the first value, then with every value that differs.
    let onFrame: (EdgeInsets) -> Void

    var animatableData: EdgeInsets.AnimatableData {
        get { insets.animatableData }
        set { insets.animatableData = newValue }
    }

    func body(content: Content) -> some View {
        content.onChange(of: insets, initial: true) { _, current in
            onFrame(current)
        }
    }
}
#endif
