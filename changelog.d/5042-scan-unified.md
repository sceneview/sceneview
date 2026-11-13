<!-- category: Changed -->
- Android demo: unify 3D Scan around a full-screen camera, translucent captured surfaces and a growing colour model; keep Object, Room, Home and Outdoor capture, reject pose jumps, and keep the result still until a gesture or an explicit view change.

The capture now paints a uniform sky-blue coat and keeps the live model in a compact translucent corner. Small scans expose their mesh before texturing finishes and retain measured medium-confidence boundaries.

Room and home scans now use a bounded 5 cm screened Poisson reconstruction before photo texturing; object and outdoor scans keep fused detail. The capture coat uses uniform shader ink with the original opaque splats. Dense captures skip redundant replay-plane texture baking while keeping their original photos and measured polygons. The compact live model stays in place during saving, with animated progress and a camera photo covering the result's first GPU frames. Unobserved regions remain open.

The fifth device pass restores the opaque uniform capture ink and removes planar completion: ARCore plane extents do not establish observed colour or depth across a polygon.

The Pixel 4a comparison retains Poisson: the Live Room v2 depth-only trial produced noisier surfaces, more floor perforations and less readable furniture on the same replay. Its experimental finish is removed, and cached depth-trial models are rebuilt with the retained pipeline.

The fixed result camera retains the sampled preview boundary instead of trimming its sparse furniture as percentile outliers, so the model and plan fit the same measured extent from the start.
