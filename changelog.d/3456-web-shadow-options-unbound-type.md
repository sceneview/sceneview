<!-- category: Fixed -->
- **Web: `LightManager.setShadowOptions()` and `LightManager$Builder.shadowOptions()` no
  longer throw `UnboundTypeError` with the bundled Filament.js
  ([#3456](https://github.com/sceneview/sceneview/issues/3456)).** The runtime vendored at
  `website-static/js/filament/` (and the copy the web demo serves) was Filament.js 1.70.1,
  whose Embind bindings never registered `quatf` — the type of
  `ShadowOptions::transform` — so both public shadow-option entry points failed with
  `Cannot call LightManager._setShadowOptions due to unbound types` and nothing on the web
  could set `mapSize`, `normalBias`, contact shadows or `stepCount`. The runtime is now
  Filament.js **1.72.1**, the first tagged release that carries the upstream fix
  (google/filament#10116), the `filamentWebsite` pin and `RUNTIME.json` move with it, and
  the three `website-static/materials/*.filamat` blobs are recompiled with the matching
  `matc` (MATERIAL_VERSION 70 → 72) in the same change, as the runtime/material ABI
  invariant requires. A Playwright spec (`samples/web-demo/tests/shadow-options.spec.ts`)
  now calls both entry points against the shipped runtime and proves a `mapSize` change
  visibly moves the rendered shadow. The npm `filament` pin used by the Kotlin/JS bundle
  (`filamentWeb`, 1.52.3) is unchanged: npm publishing of that package stopped at 1.53.4,
  so the fix is only reachable through the vendored runtime.
