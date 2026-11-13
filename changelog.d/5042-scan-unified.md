<!-- category: Changed -->
- Android demo: unify 3D Scan around a full-screen camera, translucent captured surfaces and a growing colour model; keep Object, Room, Home and Outdoor capture, reject pose jumps, and keep the result still until a gesture or an explicit view change.

The capture now paints a uniform sky-blue coat and keeps the live model in a compact translucent corner. Small scans expose their mesh before texturing finishes and retain measured medium-confidence boundaries.

Live Room/Home results now retain the measured 2 cm depth surface and vertex colours from the Live Room v2 fusion, with neutral uncoloured regions and only sub-15 cm holes closed. Older surfel-only recordings use bounded 5 cm Poisson; object and outdoor scans keep their existing finish. The capture coat uses uniform shader ink with the original opaque splats. Dense captures skip redundant replay-plane texture baking while keeping their original photos and measured polygons. The compact live model stays in place during saving, with animated progress and a camera photo covering the result's first GPU frames. Unobserved regions remain open.

The fifth device pass restores the opaque uniform capture ink and removes planar completion: ARCore plane extents do not establish observed colour or depth across a polygon.
