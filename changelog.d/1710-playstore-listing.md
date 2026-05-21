<!-- category: Changed -->
Consolidated the duplicate Play Store listing directories into a single `samples/android-demo/distribution/play-store/en-GB/` source of truth (text + `graphics/`), and extended the `play-store.yml` listing-sync to upload the feature graphic and screenshots via the Play `edits.images` API so they reach the store automatically on release (#1710).
