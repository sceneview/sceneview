<!-- category: Added -->
- **iOS QA: `lib/ios-axe.sh`** — helper script wrapping AXe (accessibility-driven iOS Simulator automation) for label-based taps, JSON UI-tree dumps, and screenshots. Mirrors `lib/android-cli.sh`'s pattern; falls back gracefully to `xcrun simctl` when AXe is not installed. Implements slice 1 of the iOS device-QA parity plan. (#1673)
