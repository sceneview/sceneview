<!-- category: Fixed -->
`StreetscapeGeometryNode` now derives per-vertex normals (as tangent quaternions) and a UV0 slot for the ARCore mesh, so lit materials such as `rememberMaterialInstance(color = …)` shade buildings and terrain by their real surface instead of a fallback normal, and Filament no longer logs `missing required attributes (0xb), declared=0x1` for every geometry (#3215).
