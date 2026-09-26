#if os(iOS) || os(macOS) || os(visionOS)
import CoreGraphics

/// Turns a drag's cumulative translation into per-tick deltas for the camera.
///
/// The first tick after ``reset()`` only takes the baseline and returns zero.
/// A `DragGesture` reports nothing until the finger has travelled its minimum
/// distance, so its first translation already holds that whole distance:
/// applied as a delta it turned the camera by 10 pt of travel in one frame, a
/// visible kick at the start of every orbit. The same rule makes a drag that
/// outlives a pinch resume from where the finger is rather than from where the
/// gesture began.
struct CameraDragBaseline: Equatable {
    private var last: CGSize?

    /// The travel since the previous tick — zero on the first one.
    mutating func delta(to translation: CGSize) -> CGSize {
        defer { last = translation }
        guard let last else { return .zero }
        return CGSize(
            width: translation.width - last.width,
            height: translation.height - last.height
        )
    }

    /// Forgets the baseline: the next tick starts a new measure.
    mutating func reset() {
        last = nil
    }
}
#endif
