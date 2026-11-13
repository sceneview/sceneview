<!-- category: Changed -->
- Android demo: unify 3D Scan around a full-screen camera, translucent captured surfaces and a growing colour model; keep Object, Room, Home and Outdoor capture, reject pose jumps, and keep the result still until a gesture or an explicit view change.

The capture now paints a uniform sky-blue coat and keeps the live model in a compact translucent corner. Small scans expose their mesh before texturing finishes and retain measured medium-confidence boundaries.

Room and home scans now use a bounded 5 cm screened Poisson reconstruction before photo texturing; object and outdoor scans keep fused detail. The capture coat uses uniform shader ink with softened edges. Dense captures skip redundant replay-plane texture baking while keeping their original photos and measured polygons. The compact live model stays in place during saving, with animated progress and a camera photo covering the result's first GPU frames. Unobserved regions remain open.
