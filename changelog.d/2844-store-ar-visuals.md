<!-- category: Changed -->
- **The store listings open on an AR visual ([#2844](https://github.com/sceneview/sceneview/issues/2844)).**
  Slot 1 of every Play Store screenshot class, the Play feature graphic and a new `00-ar`
  slot-1 frame per App Store class are now generated AR visuals: the reference helmet
  (`tools/demo-previews/refs/hero.webp`) composited photoreal-AR into a real living room
  with Gemini `gemini-3.1-flash-image` — not captures, since the demo has no AR screen
  that photographs this well. The v3 Android captures keep slots 2–4 and the iOS captured
  frames follow in filename order. Each visual exists in a dark (committed) and a light
  variant; the light set lives in `alternates-light/` directories next to each upload
  set, deliberately outside the synced paths. Provenance is documented in
  `samples/android-demo/distribution/play-store/en-GB/graphics/README.md`
  ("The #2844 AR set").

<!-- RELEASE NOTE (maintainer-only):
     Nothing was uploaded. The Play listing picks these up on the next listing sync,
     the App Store set on the next asc_listing.py --apply-screenshots run — both are
     manual, maintainer-triggered steps. -->
