<!-- category: Fixed -->

- **`contact-shadow-preview`: the legend chip is no longer drawn across the grounded box at
  its landing pose** (#2957). Device QA measured the chip row crossing the hero box by
  170 × 58 px — 51 % of the box's width — in 3 of 6 sampled frames, precisely on the
  contact-shadow moment the screen exists to demonstrate. The row used to float over the
  viewport, lifted clear of the Settings FAB by a vertical gutter; no gutter constant can fix
  this, because where a 3D object lands on screen is a projection and any value is tuned to
  one viewport. `DemoScaffold` gains an opt-in `bottomOverlayReservesScene` that insets the
  scene by the **measured** height of the `bottomOverlay` band, so the viewport and the
  overlay are disjoint by layout at any screen size, density, font scale or locale. The
  legend now clears the FAB sideways instead of being lifted over it, keeping the reserved
  band to the chip's own height.
- **`contact-shadow-preview`: the Wall preset's verdict line now describes what actually
  renders** (#2957). It promised "a faint, wide halo below the panel"; the measured pool is
  18.6/255 darker in the first 70 px under a 314 px-tall panel and has fully decayed by
  70 px. The caption names the visible cue instead — a thin band of shade against the
  panel's lower edge — and states what it buys.
