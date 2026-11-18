<!-- category: Changed -->
- **Demo app: raw depth dots are drawn in one batch per colour band ([#5153](https://github.com/sceneview/sceneview/pull/5153)).** The depth ramp is drawn in 24 steps, with at most 24 native calls per frame instead of one per point.
- **Note on `SceneView(disallowParentIntercept = …)` from 4.69.7 ([#5153](https://github.com/sceneview/sceneview/pull/5153)).** The new parameter sits before `content`. Trailing-lambda and named-argument calls are unaffected; a call passing every argument by position must name `content`.
