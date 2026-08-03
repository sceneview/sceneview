<!-- category: Fixed -->
- **android-demo** — the AR Placement and Orbital AR asset-source pills now report the
  origin they actually rendered. Both inferred it from `SketchfabConfig.apiKey`, so a
  build with a key configured but a failed resolve — no network, aeroplane mode, a stale
  key, a 4xx, the WAF, a bounds-drifted asset, exhausted retries, all of which end at the
  bundled fallback — showed "Streamed (cached)" over the offline stand-in. Both now ask
  the resolved file via `SketchfabAssetResolver.isBundledFallback`, and consult the key
  only while nothing has resolved yet and there is no file to ask. Orbital AR takes the
  whole-scene pessimistic verdict Multi-Model uses: one fallen-back planet reads "Offline
  model" for the formation. The rule now lives in one testable place
  (`AssetSourceProbe`) instead of being re-derived per demo (#2953, follows #2936).
