import XCTest

@testable import SceneViewDemo

/// Pins the readout contract `LabeledSlider` shares with its Android twin in `samples/common`.
///
/// These assertions are cheap and they are the half of the control a screenshot cannot check:
/// a thin space and a normal space render identically, and a locale-dependent decimal separator
/// only shows up on a device that is not the one the goldens were recorded on.
final class LabeledSliderTests: XCTestCase {

    func testRendersTheRequestedPrecision() {
        XCTAssertEqual(LabeledSlider<Double>.format(0.5, decimals: 2, unit: nil), "0.50")
        XCTAssertEqual(LabeledSlider<Double>.format(0.5, decimals: 0, unit: nil), "0")
        XCTAssertEqual(LabeledSlider<Double>.format(1.239, decimals: 1, unit: nil), "1.2")
    }

    func testAppendsTheUnitAfterAThinSpace() {
        // Written as an escape on purpose: U+2009 and U+0020 are indistinguishable in a diff,
        // and a test that pastes the literal character silently stops testing anything.
        XCTAssertEqual(LabeledSlider<Double>.format(3.5, decimals: 1, unit: "m"), "3.5\u{2009}m")
        XCTAssertEqual(
            LabeledSlider<Double>.format(9.8, decimals: 1, unit: "m/s²"), "9.8\u{2009}m/s²"
        )
    }

    func testOmitsTheSeparatorWhenThereIsNoUnit() {
        XCTAssertEqual(LabeledSlider<Double>.format(2.0, decimals: 1, unit: nil), "2.0")
        XCTAssertEqual(LabeledSlider<Double>.format(2.0, decimals: 1, unit: ""), "2.0")
    }

    func testUsesADotRegardlessOfTheDeviceLocale() {
        // The readouts sit next to API values a reader copies into code. A decimal comma would
        // not round-trip through `Float(_:)`.
        XCTAssertTrue(LabeledSlider<Double>.format(1.5, decimals: 1, unit: nil).contains("."))
        XCTAssertFalse(LabeledSlider<Double>.format(1.5, decimals: 1, unit: nil).contains(","))
    }

    func testClampsANegativeDecimalCount() {
        XCTAssertEqual(LabeledSlider<Double>.format(1.6, decimals: -2, unit: nil), "2")
    }
}
