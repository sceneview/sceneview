<!-- category: Fixed -->
- **In-app bug reports now name the screen they were filed from and carry a usable log
  window ([#3390](https://github.com/sceneview/sceneview/issues/3390)).** Reports arrived
  with neither: the demo id was matched against the literal route `demo/{id}` while the
  declared route had grown a `?model={model}` argument, so it always resolved to `null`,
  and the tab host published nothing at all — a report filed from the Explore gallery or
  a live AR session was indistinguishable from any other. Every report now opens with a
  `Screen` row (`Demo · model-viewer`, `Explore gallery`, `AR View tab · session active`,
  …), read from the navigation arguments rather than a route string that drifts, and the
  display resolution moves to its own `Display` row. The log window went from ~30 lines to
  ~1200 captured: the share path carries the whole capture, and the pre-filled GitHub
  issue binary-searches the largest tail that fits the URL budget instead of snapping down
  a coarse 60/30/10/0 ladder, with each `threadtime` line stripped of its date and pid/tid
  columns — the millisecond timestamps stay, so the period of a repeating warning is
  readable straight from the issue. The report sheet states what it is about to attach
  before it is sent.
