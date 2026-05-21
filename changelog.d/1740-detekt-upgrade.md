<!-- category: Changed -->
- Upgrade detekt 1.23.8 (silently no-op'd on Kotlin 2.3.x) → detekt 2.0.0-alpha.0; per-module baselines committed under `buildSrc/config/detekt/baseline-<module>.xml` grandfather existing violations and the `Detekt` CI step is now blocking on NEW violations (#1740).
