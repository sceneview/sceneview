<!-- category: Fixed -->
- **`android-demo`'s Lines & Paths "Stroke Width" slider now visibly changes the on-screen
  stroke across its whole range ([#1432](https://github.com/sceneview/sceneview/issues/1432)).**
  Filament's `LINES` primitive is hardware-capped at 1 px, so the demo drives thickness by
  scaling per-point sphere "beads" laid along the `LineNode`/`PathNode` instead. That scale
  was correctly wired to a `DisposableEffect`, but it was mapped linearly from `0` to `4×`
  the base radius — roughly the bottom third of the drag produced a sub-pixel scale
  indistinguishable from no change at all, which read as "the slider does nothing," and the
  top end ballooned into a chain of oversized balls that dwarfed the line (device QA, Pixel
  9). The scale now lerps between a non-zero floor and a lower ceiling
  (`0.6×`–`2.4×` instead of `0×`–`4×`), the base bead radius is slightly smaller, and the
  line's bead count is higher so beads merge into a continuous stroke well before the slider
  tops out. Every position on the track now changes something visible, and the default and
  maximum settings both read as a stroke rather than a string of beads.
