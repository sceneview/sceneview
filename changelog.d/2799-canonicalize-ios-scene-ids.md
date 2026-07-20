<!-- category: Fixed -->
- iOS demo ids that diverged from Android's canonical `DemoRegistry` slugs are
  now aligned: `ar-cloud-anchors` → `ar-cloud-anchor`, `ar-rooftop-anchors` →
  `ar-rooftop`, `ar-terrain-anchors` → `ar-terrain`, `ar-recording` →
  `ar-record-playback`. The 4 old ids are kept as documented deep-link
  aliases in `DemoDeepLinkRegistry.allowedIds` so existing QR codes and
  bookmarks keep resolving. First lot (#2799) of the iOS/Android catalog-ISO
  effort (#2798) — required before the generated-registry union (#2800) can
  land without silently duplicating ids.
