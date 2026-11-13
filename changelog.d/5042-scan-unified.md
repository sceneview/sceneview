<!-- category: Changed -->
- Android demo: 3D Scan now captures on the live model. The growing model, in the colours the camera saw, fills the screen on a dark stage with a floor grid and its live width and depth; the camera slides into a small viewfinder beside a recorder (red Finish button, Pause / Resume) and one readable guidance line. Object, Room, Home and Outdoor capture are kept, pose jumps are rejected, and the result stays still until a gesture or an explicit view change.

The saved model also keeps surfaces a single depth frame saw, so walls seen once are no longer dropped from the result.

Room and home scans now use a bounded 5 cm screened Poisson reconstruction before photo texturing; object and outdoor scans keep fused detail. The capture coat uses uniform shader ink with the original opaque splats. Dense captures skip redundant replay-plane texture baking while keeping their original photos and measured polygons. The compact live model stays in place during saving, with animated progress and a camera photo covering the result's first GPU frames. Unobserved regions remain open.

The fifth device pass restores the opaque uniform capture ink and removes planar completion: ARCore plane extents do not establish observed colour or depth across a polygon.

The Pixel 4a comparison retains Poisson: the Live Room v2 depth-only trial produced noisier surfaces, more floor perforations and less readable furniture on the same replay. Its experimental finish is removed, and cached depth-trial models are rebuilt with the retained pipeline.

The fixed result camera retains the sampled preview boundary instead of trimming its sparse furniture as percentile outliers, so the model and plan fit the same measured extent from the start.

While capturing, the phone stands in the live model as its view cone, with the path it walked. The view follows it from behind and above, eased so that a turn swings it round over a couple of seconds, and steps back as the room grows. The model's points are drawn finer (2 cm, coarse ones capped at 4 cm), so a wall seen well reads as a surface and one seen thinly as scattered points. Gaps in the floor inside what the model spans get a hairline hatching; only the gap the guidance names is hatched brightly, and the line names its side ("Turn left, nothing scanned there yet"), held for 4.5 s at least or until that gap is filled. The result's progress names its two steps.
