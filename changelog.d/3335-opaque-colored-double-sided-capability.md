<!-- category: Fixed -->
- **`MaterialInstance.setDoubleSided(true)` on a `createColorInstance` material now actually
  works ([#3335](https://github.com/sceneview/sceneview/issues/3335), reopens
  [#1426](https://github.com/sceneview/sceneview/issues/1426)).** `opaque_colored.mat` never
  declared the `doubleSided` key, so it compiled without Filament's double-sided
  *capability* — `setDoubleSided(true)` was a silent runtime no-op (`Parent material does
  not have double-sided capability`), and `GeometryDemo`'s spinning plane still disappeared
  for half of every rotation despite #1426's fix. The `.mat` now declares
  `"doubleSided" : false`: per Filament's `MaterialBuilder::doubleSided(bool)`, presence of
  the key — regardless of its value — is what turns the capability on, while `false` keeps
  every existing `createColorInstance` consumer single-sided and back-face culled by
  default, so there is no rendering or performance change for callers that never call
  `setDoubleSided`. `GeometryDemo`'s plane and `TwoDInThreeDDemo`'s chrome/floor materials
  (both silently affected by the same gap) now honour the runtime toggle.
