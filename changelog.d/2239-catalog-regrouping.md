<!-- category: Changed -->
- **The Android demo catalogue is now nine named sections instead of one flat run of cards
  ([#2239](https://github.com/sceneview/sceneview/issues/2239)).** 53 demos rendered as a
  single uninterrupted grid, 33 of them behind one "Augmented Reality" chip — so nothing
  told a scrolling thumb where one subject ended and the next began, and demos that belong
  together read as scattered even when they were adjacent. The grid now draws a full-span
  header per section and the chips filter down to one: **Viewer · Geometry & Materials ·
  Rendering · Interaction · AR Placement · AR Tracking · AR Understanding · AR Anchors ·
  Platform**. AR is four sections because AR is four ARCore API families — placing content,
  tracking a subject, reading the room, and anchors that outlive the frame — not one.
  Three merges land with it, each folding demos that were the same SDK surface cut in half:
  `fog` became **Lighting Lab**'s fifth mode (both are per-Filament-`View` option objects
  reached through the same `rememberView` handle), `gesture-feedback-preview` became
  **Camera & Gestures**' third mode (the Gestures mode flipped `isEditable` and drew no
  affordance; the preview drew the affordance with no controls), and `ar-terrain` +
  `ar-rooftop` became one **Geospatial Anchors** card with Terrain and Rooftop modes — they
  were 303 identical lines out of ~485, differing only in whether the resolve call names
  `altitudeAboveTerrain` or `altitudeAboveRooftop`. 53 cards → 50. Every retired id keeps
  deep-linking: `sceneview://demo/fog` opens Lighting Lab **on the Fog mode**, and the same
  for the other three, so no QR code, doc link or Maestro leg lost its target or its
  coverage.
<!-- RELEASE NOTE (maintainer-only):
     Two generator bugs surfaced while doing this and are fixed here rather than left for
     the next person. (1) `collate-demos.sh` carried a third hardcoded copy of the category
     list; a category absent from it was silently SKIPPED, so the first regeneration after
     the regroup dropped 48 of 50 demos from llms.txt without failing. It now reads the
     order from DEMO_CATEGORIES and the labels from the same string resources the app
     draws, so there is no category vocabulary left in that script. (2) `ar-measure` was a
     registered demo driven by no Maestro flow at all — a pre-existing coverage gap, now
     closed. `isArDemo` replaces the old single-category equality test; it unions the four
     AR sections with an explicit two-id list (`ar-record-playback`, `ar-rerun`) that live
     under Platform because their subject is capture tooling but still open an ARCore
     session. -->
