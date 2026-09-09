<!-- category: Changed -->
- **The Play Store and App Store listings for the demo app now say what the app opens, and
  show it.** Both descriptions were missing four of the six formats the Android manifest
  already declares — the listings sold GLB and glTF while the app has been opening 3MF, STL,
  OBJ and PLY from any file manager — and the Play short description promised "Every demo is
  a screen you can actually use", which describes nothing a visitor gets. The six formats are
  now named on both stores, the promise is replaced by the real one (open any 3D file, at real
  size, in your room), and the App Store text only claims the formats the iOS app was measured
  opening. Store titles are unchanged.
- **The two listings carry a new captioned set of screenshots.** The Play phone class goes from
  five uncaptioned frames to six captured on the `Pixel_7a` emulator, and the App Store from
  three to six per device class captured on the iOS simulators; every frame now sits under an
  English caption card, so the carousel reads before any pixel of app UI does. The Play feature
  graphic is a composed banner with a text hook instead of a full-bleed crop. Captions, cards
  and code panels are composited by a new versioned tool, `tools/store-screenshots/compose.py`,
  driven by a manifest that names every output — no PNG is retouched by hand, and DESIGN.md
  tokens are the only colours it draws with.
- **The AR screenshot now shows the app, not just a room.** The "Real size, your room" slot was a
  generated photo with no app UI in it at all — the same defect the audit filed against the slot it
  replaced, and a screenshot that shows no app is one Google can refuse. It is now a composite: the
  generated room carries the AR screen's **real** chrome, captured on `emulator-5554` and keyed off
  the flat-black AR surface by `compose.py` (`kind: ar`) — the back arrow, the `Tap to Place`
  identity pill, the `Model · Sheen Chair` placement bar, the reset control and Settings, drawn as
  the app draws them. The subject is the chair the captured chrome has armed, so the bar names the
  model the frame shows.
