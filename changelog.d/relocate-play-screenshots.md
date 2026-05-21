<!-- category: Fixed -->
- Relocated the tablet Play Store screenshots added by #2092 from the
  orphaned `samples/android-demo/play/listings/` tree (dead since #1710)
  into the canonical, CI-synced
  `samples/android-demo/distribution/play-store/en-GB/graphics/` directory,
  renamed to `tablet7-screenshot-*.png` / `tablet10-screenshot-*.png` so
  `play-store.yml`'s listing-sync actually uploads them. Removed the
  re-created dead `play/` tree, including the 3 Chromebook captures — the
  Play `edits.images` API has no Chromebook image type, so large-screen
  devices reuse the 10-inch tablet screenshots.
