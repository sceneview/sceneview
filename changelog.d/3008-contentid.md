<!-- category: Added -->
- **`SceneView.contentID(_:)` on Apple platforms — swap a model without
  re-creating the renderer.** The content closure used to run only when the
  scene was created, so every demo that shows a different model re-keyed the
  whole view with SwiftUI's `.id(_:)`. That destroys the `RealityView` and
  builds a new one, and a re-created `RealityView` on iOS 26 Simulator
  intermittently renders *nothing at all* — no model, and no skybox either —
  permanently ([#3008](https://github.com/sceneview/sceneview/issues/3008)).
  `.contentID(_:)` keeps one renderer for the scene's lifetime: it removes the
  previous content (unregistering its gesture handlers first, so it deallocates
  instead of leaking), re-runs the closure, re-applies the render-quality
  preset, and re-arms the auto-framing pass so the new subject is fitted to the
  viewport instead of inheriting the previous one's camera distance. Additive
  and non-breaking — a scene without the modifier builds its content exactly
  once, as before. Android needs no equivalent: its DSL content is already
  re-read on recomposition.

<!-- category: Fixed -->
- **iOS demo: `Animation`, `Scene Gallery` and `Model Viewer` no longer go
  permanently black when you change the model.** All three now keep their
  `SceneView` mounted — spinner as an overlay rather than an `if let` that
  unmounts the scene — and swap subjects through `.contentID(_:)`. Measured on
  `QA-iPhone16-c` (iOS 26.3): a subject change used to build **two** fresh
  `RealityView` instances and now builds **zero**.
- **iOS demo: the asset-source pill no longer tears the scene down the first
  time it appears.** `assetSourcePill(_:)` branched between `overlay(…)` and
  `self`, which are structurally different views, so the first transition from
  no-pill to pill discarded the modified subtree — `RealityView` included. It
  now applies the overlay unconditionally and drops only the pill. This was
  measured re-creating `AnimationDemo`'s scene on exactly the first subject
  change and no other.
- **iOS demo: `Model Viewer`'s "Surprise me" no longer skips a model when two
  rolls share a title.** Its scene key was the model's display name, so two
  consecutive picks with the same title left the key unchanged and the swap
  silently did not happen. It is keyed on a monotonic load counter now. The
  same collision existed with the previous `.id(_:)`.
