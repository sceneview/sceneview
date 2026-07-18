<!-- category: Fixed -->
- Explore gallery (Android): the model footer showed a misleading "Rendered by SceneView · 0 polys" for sources that expose no face count (e.g. Poly Haven). The "· N polys" suffix is now hidden when `faceCount == 0`, mirroring the StatsRow poly chip. (The face-count recompute itself is a separate SDK-side follow-up.)
