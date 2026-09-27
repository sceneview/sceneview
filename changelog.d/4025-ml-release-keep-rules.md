<!-- category: Fixed -->
- **Demo — release builds:** R8 no longer strips what ML Kit and MediaPipe load by reflection.
  ML Kit Object Labels no longer crashes on open (#4025), AR Body Tracker's pose model loads
  again (#4027), and Point & Ask reaches the real Gemini Nano status check instead of failing
  before it (#4028). If a detector still cannot start, the demo says so and the AR scene keeps
  running.
- **Demo — Point & Ask:** when Gemini Nano is unavailable the question field is hidden, the card
  explains why, and the only action offered is AICore's Google Play listing, shown only when
  AICore is installed — no more "Open system settings" dead end (#4028).
