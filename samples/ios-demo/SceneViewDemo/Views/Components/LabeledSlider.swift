import SwiftUI

/// A slider with its name and current value on one line above the track.
///
/// Every demo needs this control and, before this existed, every demo re-typed it — in five
/// mutually incompatible shapes across nine files:
///
/// - `HStack { Text(label); Slider }` with no value shown at all,
/// - `HStack { Text(label); Spacer(); Text(value) }` with the track on the line below,
/// - `VStack { Text("Label: %.2f m"); Slider }` with the value fused into the label,
/// - `HStack { Image; Slider; Image }` with icons standing in for the label,
/// - a `offsetSlider(label:value:range:)` helper local to one file — someone already felt the
///   need and solved it where only one screen could benefit.
///
/// They disagreed on typography (`.caption` here, `.subheadline.weight(.semibold)` there), on
/// whether the readout was `.monospacedDigit()`, on the fixed width reserved for it (44 pt, 36
/// pt, none), on tint (`.blue`, `.orange`, `.yellow`, picked per demo), and on whether the
/// control was accessible at all.
///
/// The value sits on the trailing edge rather than inside the label so that a column of these
/// reads as a table: names align left, values align right, and a changing value never reflows
/// the name. That is the difference between a settings panel and a stack of sentences. It is
/// the same contract as `LabeledSlider` in `samples/common` on Android, deliberately — the two
/// demo apps should not look like two products.
///
/// Tint is not a parameter. `.tint(_:)` propagates through the environment, so a demo that
/// genuinely needs one applies it to this view; the default is the app accent, which is what
/// the per-demo colours were silently overriding.
struct LabeledSlider<V>: View where V: BinaryFloatingPoint, V.Stride: BinaryFloatingPoint {
    /// The control's name, e.g. `"Density"`. Rendered on the leading edge.
    let label: String
    /// Current value. Must lie within `range`.
    @Binding var value: V
    /// Inclusive bounds of the track.
    let range: ClosedRange<V>
    /// Distance between discrete stops. `nil` is continuous.
    var step: V.Stride?
    /// Digits after the decimal point in the rendered value. Ignored when `valueText` is set.
    var decimals: Int = 2
    /// Appended to the rendered value after a thin space, e.g. `"m"` or `"m/s²"`. Ignored when
    /// `valueText` is set.
    var unit: String?
    /// Fully-formatted value, for what `decimals`/`unit` cannot express — a percentage derived
    /// from a 0…1 float, an enum-like readout, a value plus a word.
    var valueText: String?

    private var rendered: String {
        valueText ?? Self.format(Double(value), decimals: decimals, unit: unit)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Text(label)
                    .font(.subheadline)
                Spacer(minLength: 8)
                Text(rendered)
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
            // Hiding the row rather than wrapping the whole control keeps the track's
            // "adjustable" trait: VoiceOver must still be able to swipe the value up and down.
            // Labelling the track instead means the number is announced once, not twice.
            .accessibilityHidden(true)

            slider
                .accessibilityLabel(label)
                .accessibilityValue(rendered)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder
    private var slider: some View {
        if let step {
            Slider(value: $value, in: range, step: step)
        } else {
            Slider(value: $value, in: range)
        }
    }

    /// Thin space: keeps `12.5 m` from breaking across the value/unit boundary.
    private static var thinSpace: String { "\u{2009}" }

    /// Formats a slider readout.
    ///
    /// A `nil` locale means POSIX, not the device locale, and that is on purpose: these are
    /// engineering readouts sitting next to API values a reader is meant to copy into code, and
    /// a decimal comma would not round-trip through `Float(_:)`.
    static func format(_ value: Double, decimals: Int, unit: String?) -> String {
        let number = String(format: "%.\(max(decimals, 0))f", locale: nil, value)
        guard let unit, !unit.isEmpty else { return number }
        return number + thinSpace + unit
    }
}

#Preview("LabeledSlider") {
    @Previewable @State var density: Float = 0.42
    @Previewable @State var start: Float = 3.5
    @Previewable @State var intensity: Double = 120_000

    VStack(alignment: .leading, spacing: 14) {
        LabeledSlider(label: "Density", value: $density, range: 0...1)
        LabeledSlider(label: "Start", value: $start, range: 0...20, decimals: 1, unit: "m")
        LabeledSlider(
            label: "Intensity", value: $intensity, range: 10_000...500_000, decimals: 0
        )
        LabeledSlider(
            label: "Time of Day", value: $start, range: 0...24, valueText: "6.0 h · Morning"
        )
    }
    .padding()
}
