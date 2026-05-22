<!-- category: Fixed -->
- **CI (quality-gate):** `feedback-worker` `npm test` is now run as part of the quality gate — a future regression in the worker is caught on every PR that touches `feedback-worker/`. (#2032)
- **Feedback (Android demo):** lower the screen-recording size cap from 28 MB to 25 MB to give 5 MB of headroom for the AAC audio track + multipart envelope (vs the previous ~2 MB) before the worker's 30 MB 413 threshold. (#2032)
- **Feedback (FeedbackContextTest):** fix stale KDoc mentioning the removed `route` key; add `isEmulator()` reachability test. (#2032)
