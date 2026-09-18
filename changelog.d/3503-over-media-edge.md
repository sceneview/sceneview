<!-- category: Fixed -->
<!-- breaking: false -->
<!-- RELEASE NOTE (maintainer-only):
     `SceneViewTokens` lives in samples/android-demo and is not part of any published
     artifact, so deleting `Glass.border` / `ArOverlay.borderLight` / `…borderDark` breaks
     no consumer. The migration sentence is there for anyone lifting the demo's chrome
     into their own app, which is what the demo is for — not a semver signal. -->
- **Android demo — every surface floating over the camera now has an edge you can actually see ([#3503](https://github.com/sceneview/sceneview/issues/3503)).** The 1 dp 24 % white border on the glass chrome measured **1.03:1** against the surface it was supposed to bound, because `Modifier.border` strokes *inside* the bounds — on top of the panel's own 14 % white fill — so no opacity could have saved it. It is replaced by `Modifier.overMediaEdge(shape)`, a new `over-media-edge` token: a 1 dp 36 % white ring straddling the boundary plus a 1 dp 75 % black halo 1 dp further out, both drawn **outside** the fill, on the media. WCAG 1.4.11 asks 3:1 for the line that identifies a control, and no single colour can hold that over a camera frame — a white wall kills the white ring, a night room kills the black halo — so the two bands carry each other. The floating dock, which had never drawn an edge at all, gets one too. To port the change: replace `Modifier.border(width, color, shape)` on chrome that sits over the camera or the 3D viewport with `Modifier.overMediaEdge(shape)`, placed **before** the `clip`/`background` in the chain.
