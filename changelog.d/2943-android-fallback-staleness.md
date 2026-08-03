<!-- category: Fixed -->
- **Demo (Android): a corrected bundled model now reaches installs that already
  ran the app.** `SketchfabAssetResolver` staged the offline fallback under a
  path keyed on `uid` alone and trusted any complete GLB found there, so the
  app's data dir — which survives a Play Store update — kept serving the
  previous version's bytes forever. An APK shipping a fixed asset stayed inert
  on every existing install. The staged copy is now compared against the byte
  length of the asset currently in the APK and re-staged when they diverge,
  mirroring the iOS fix from #2929. This closes the parity half of #2943 that
  #2947 left open; the KDoc contract "keep both in sync when adding behaviour"
  was pointing at exactly this gap.
- **Demo: a bundled asset that has gone missing degrades instead of throwing.**
  When the bundled resource is unreadable — renamed or pruned from the app
  while the registry still points at the old path — both resolvers now serve an
  existing staged copy as a last resort. On iOS the freshness check had moved
  the bundle lookup ahead of the staged-copy early return, turning "renders the
  previous model" into a throw across all eight `fallbackBundle` call sites;
  `Bundle` caches resource lookups, so the guard stats the file rather than
  trusting the URL it hands back.
