<!-- category: Fixed -->
- **android-demo** — the Scene Gallery asset-source pill now reports the origin it
  actually rendered. It was inferred from `SketchfabConfig.apiKey`, so a build with a
  key configured but a failed download — no network, a stale key, a bounds-drifted
  asset, exhausted retries, all of which end at the bundled fallback — showed
  "Streamed (cached)" over the offline stand-in. The pill now asks the resolved file
  via `SketchfabAssetResolver.isBundledFallback`, and only falls back to the
  key-based guess while nothing has resolved yet (#2936).
