<!-- category: Fixed -->
<!-- breaking: false -->
App Store slot 1 shows the Cyberpunk Hovercar again instead of a second copy of slot 2's
Damaged Helmet. The issue reported the hovercar as "framed as plinth-sized, so it reads
small next to the fixed slot 2"; the plinth half of that had already been fixed (#3315
stripped the display plane from `cyberpunk_hovercar.usdz`, and the flattened USD confirms
no plane prim remains). Capturing the demo on the 6.9" simulator showed the real cause:
the `model-viewer` slot was not rendering the hovercar at all.

Two independent changes collided. #3003 switched `dynamic-sky` — slot 2 — to
`khronos_damaged_helmet`, and the showcase redesign (#3308) rewrote `ModelViewerDemo` to
default `selectedModel` to `bundledModels[0]`, which is that same helmet. The redesign kept
the "the hovercar is the iOS store hero" comment while making the code disagree with it, so
the store hero silently stopped being captured and the listing showed one subject twice.
Under `qa_mode` the view now selects `storeHeroAssetName` explicitly before the first load;
the interactive first-run subject is unchanged — a new user still lands on the Khronos
reference helmet.

`captureFramingMargin` stays at 0.62, and the constant now records why rather than reading
as a preference. Swept against live captures with the `-camera_distance` override (#2785):
0.75 leaves the car at roughly 45 % of the frame width and 0.5 clips its tail against the
right edge. It is a floor, not a choice.

One framing defect is documented and deliberately left open, because it is an SDK change
rather than a sample constant: the car renders right of the frame centre with the left
third of the frame empty, and its silhouette is markedly smaller than the bounds the
auto-fit pass is fitting. `CameraControls.fitRadius` inscribes the *space diagonal* of the
union AABB in a sphere and fits that sphere to the narrower FOV axis — width, in a portrait
store frame — so a wide, short subject whose authored bounds exceed its visible geometry
cannot fill the frame however tight the margin gets. Fitting the projected AABB instead
would close it.
