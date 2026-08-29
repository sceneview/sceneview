<!-- category: Fixed -->
<!-- breaking: false -->
iOS/visionOS: `CameraControls.fitRadius` now fits the subject's **projected extent on each
FOV axis** instead of inscribing its bounding box in a sphere. The old formula collapsed
the box to half its space diagonal and divided by `sin` of the *smaller* half-FOV — the
horizontal one on a portrait phone — so every subject paid for a diagonal it does not
occupy, and the vertical axis paid the horizontal axis's distance. A 3 m column in a
portrait viewport was pushed back to 5.90 m where 3.00 m frames it exactly: the subject
filled barely half the height available to it ([#3383](https://github.com/sceneview/sceneview/issues/3383)).

Framing stays invariant to `azimuth`, as an auto-rotating model must not clip when it turns
broadside. Rather than fitting the pose you happen to be at, the fit takes the box's *sweep*
about world Y — a cylinder — and fits that exactly through its support function, so the
result is the tightest azimuth-independent distance rather than an upper bound on one. The
new distance is never larger than the old one, so no scene is framed further away than
before; portrait gains are up to 49 % of the old distance for tall subjects and 10–17 % for
cubic ones, while wide subjects in portrait are unchanged because their horizontal reach
genuinely requires that distance.

Unlike the sphere fit, the result now depends on `elevation`, since a subject's projected
height changes as the camera rises. The default `defaultFitMargin` of 1.15 covers the worst
case measured (1.124, a 4 m panel in landscape), so orbiting after an auto-fit still does
not clip.

The Android (`sceneview/`) and web (`sceneview-web/`) framing helpers carry the same
bounding-sphere approximation and are **not** touched here; they are reported in the pull
request for a separate follow-up.
