<!-- category: Fixed -->
- **The iOS demo's dark mode has surfaces again, and four buried or broken things in the
  Model Viewer and Explore work
  ([#3582](https://github.com/sceneview/sceneview/issues/3582),
  [#3583](https://github.com/sceneview/sceneview/issues/3583),
  [#3584](https://github.com/sceneview/sceneview/issues/3584),
  [#3585](https://github.com/sceneview/sceneview/issues/3585),
  [#3586](https://github.com/sceneview/sceneview/issues/3586)).** Dark mode drew every card
  fill with the same `#0D1117` as the page it sat on, because DESIGN.md's `surface-container`
  (`#161C2C`) had never been ported to Swift — cards, the hero ground and the header chips
  were all literally invisible against their own background, which is what "flat and inky"
  meant. That token now exists, together with `outline`, `on-surface-faint`, `primary` and a
  `primary-container` for tinted actions; cards and the hero scrim land on it, hairlines use
  `outline`, and the selected filter chip trades the shouting white pill for a solid `primary`
  one. Light is untouched — every value is a light/dark pair whose light half is what shipped.
  With it: the **Model Viewer opens on an environment you can see** (`outdoor_cloudy`, backdrop
  drawn) instead of a studio rig whose backdrop is four softbox panels in a void, while the
  "Show environment" switch still wins and its answer is remembered across launches and
  environments; **Cyberpunk Hovercar and Butterfly have thumbnails** instead of silently
  falling back to an anonymous `cube.transparent` (`thumbnailName` probes `UIImage(named:)` and
  returns `nil` when an imageset is missing, so nothing failed — a unit test now walks the whole
  catalogue for tiles, USDZs and backdrop defaults); **"Surprise me" is promoted to the top of
  the Models sheet** and re-rollable from a pill in the viewer itself, instead of being the
  second-to-last row of a list nobody scrolls; and **Explore has a search field again** — it
  had one all along, but `.searchable` renders into a navigation bar and the embedded Explore
  is pushed onto a screen that hides its bar, so the field silently did not exist. Embedded
  Explore now draws its own inline field, with distinct empty, no-results and error states,
  and sits on `surface` rather than the system black it had been falling through to. The
  Android demo's model picker gets the same "Surprise me" promotion, so the two apps agree
  on where the feature lives.
