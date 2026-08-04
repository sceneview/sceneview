<!-- category: Fixed -->
- The Android demo's staged-fallback guard now enforces the same 12-byte floor iOS does.
  `stagedLooksComplete` gated on `length() > 0` plus the `glTF` magic, so a 4-byte file whose
  entire content *is* that magic counted as a complete GLB and was served once the bundled
  asset vanished from the APK. iOS gates the same last-resort path on `boundsAreSane`, which
  carries the floor — the two platforms disagreed about the same file while both comments
  claimed parity. Reaching it needs a racy truncated write, and it degrades to the wrong model
  rather than crashing; the reason to fix it is the false parity claim, which is the shape this
  repo keeps paying for (#2961, #2943).
- The new test is mutation-tested: restoring `length() > 0L` makes it fail with the 4-byte file
  served instead of refused.
