- **CI:** the iOS App Store submit step now sources the required "What's New"
  (`whatsNew`) field from a user-facing
  `samples/ios-demo/distribution/app-store/en-US/release_notes.txt` instead of
  deriving it from the technical, cross-platform `CHANGELOG.md` (which left the
  field near-empty for Web/Flutter-heavy releases). An empty *required*
  `whatsNew` is rejected by App Store Connect with HTTP 409
  `ENTITY_STATE_INVALID` ("not in valid state") at review submission — the
  second blocker that stopped 4.25.0 even after the #2885 build-platform fix.
  Falls back to the previous `CHANGELOG.md` extraction when the file is absent.
  (#2893)

<!-- category: Fixed -->
