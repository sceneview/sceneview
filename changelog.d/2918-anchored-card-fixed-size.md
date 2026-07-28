<!-- category: Fixed -->
- **Point & Ask**: anchored answer cards no longer drift and jump. All panels share one
  `ViewNode` `WindowManager`, whose single wrap-content host sizes itself to its *largest*
  child and re-measures every sibling to that size — so a long streaming answer silently
  resized and shifted every other pinned card, continuously, while it typed. Each card now
  has a fixed width **and** height, with the answer scrolling inside it.
- Docs: the `ViewNode` KDoc (mirrored in `llms.txt`) claimed there is "no parent to measure
  against", so `fillMaxWidth()` has "nothing to fill". The window is `WRAP_CONTENT`, so the
  content is measured `AT_MOST(display)` — `fillMaxWidth()` resolves to the full display
  width and puts a metres-wide quad in the scene. The advice (give an explicit size) was
  right; the stated reason and failure mode were not. The shared-`WindowManager` sizing rule
  is now documented alongside it.
