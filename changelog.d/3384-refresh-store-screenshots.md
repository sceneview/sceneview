<!-- category: Fixed -->
- **The committed App Store screenshots match what the app renders again ([#3384](https://github.com/sceneview/sceneview/issues/3384)).**
  The four PNGs in `samples/ios-demo/appstore-screenshots/` dated from 4 August and showed
  an app that no longer exists: the pre-redesign chrome (#3308) over every frame, and the
  white display plinth (#3315) under the hovercar. `app-store.yml` reads this directory
  inline at every release, so those frames were what Apple received. All four re-captured on
  the documented `qa_mode` pipeline, at the dimensions App Store Connect accepted
  (`iphone-6.9` 1320 × 2868, `ipad-13` 2064 × 2752). The subjects are unchanged — hovercar
  in slot 1, Damaged Helmet under a live sky in slot 2 — which for slot 1 is only true
  because of the `qa_mode` hero override (#3382) and the stage fix below.

- **The iOS store hero is staged on a lit backdrop again, not on black.** The model
  viewer opens with `showSkybox = false`, so nothing is drawn behind the model — the
  right look for a viewer you are about to orbit, the wrong one for a store frame: the
  hovercar's dark bodywork read as a grey silhouette on near-black, which is the "dim,
  dark-on-black" capture #2896 was filed about. The pre-redesign code pinned a
  `studio_warm` hero environment for exactly that reason and #3308 dropped it. Under
  `qa_mode` the demo now also selects `storeHeroEnvironmentName` and draws its skybox,
  alongside the existing hero-model override (#3382). The interactive first-run look is
  unchanged.

- **QA-mode chrome no longer leaks into published store frames.** `qa_mode` paints a
  "QA ×" chip so a human who enabled it can switch it back off; a scripted capture pass has
  no human and its output ships to the App Store. The chip arrived with the redesign (#3308),
  after the last capture, so it never shipped — but the pipeline launches with `-qa_mode 1`,
  so this refresh would have baked it in. `DemoSheet` now suppresses it when
  `DeepLinkRouter.isScriptedCapture` is true — keyed on the `-demo <id>` launch argument
  that only the capture and XCUITest passes carry. The determinism scripted passes rely on
  (frozen pose, framing, hero selection) is untouched; only chrome that exists to serve a
  human is hidden.

<!-- RELEASE NOTE (maintainer-only):
     No upload was performed. These files only reach App Store Connect on the next
     release, or via a manual dispatch of app-store-screenshots.yml.
     One framing defect is knowingly left in the shipped frames: the hovercar sits
     right of centre with the left third of the frame empty, worse on ipad-13 than
     on iphone-6.9. Cause is #3383 (CameraControls.fitRadius fits the union AABB's
     space diagonal to the narrower FOV axis) — an SDK fix, out of scope here.
     captureFramingMargin stays 0.62; -camera_distance only scales and would make
     the offset worse. -->
