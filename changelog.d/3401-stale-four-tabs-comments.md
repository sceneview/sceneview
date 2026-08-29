<!-- category: Fixed -->
- **The Android demo's "four tabs" comments now describe the real three-entry
  `RootTab` ([#3401](https://github.com/sceneview/sceneview/issues/3401)).** Three
  comments still described a four-tab bottom bar while `RootTab` has had three
  entries (`Showcase`, `ArView`, `About`) for a while: `MainActivity.kt`'s "list"
  route claimed a "4-tab root (Explore / AR View / Samples / About)" whose
  "Samples" tab hosted a `DemoListScreen` that no longer exists (demo deep links
  actually navigate straight to `demo/<id>`), `RootScreen.kt` said "the four tabs
  get their 168 dp of dead bottom gutter back" — a figure that survives only in
  that comment, the gutter having been reclaimed when the feedback FAB became a
  sibling card — and `FeedbackReport.kt` quoted #1930 as requiring the button "on
  the 4 tabs". All three now match the code, and the obsolete 168 dp figure is
  gone so nobody reintroduces it as a real constant. Comment-only — no behaviour
  change.
