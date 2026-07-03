<!-- category: Fixed -->
- **Website**: hero layout no longer collapses at ≥769px — `minmax(0,1fr)` grid tracks + `min-width:0` children, and the fallback visual's conflicting fixed `height:500px` (which imposed an ~889px intrinsic width via `aspect-ratio:16/9`) now derives from the aspect ratio (#2560).
- **Website**: repaired 5 SVG icon paths in `index.html` corrupted by a historical version find-replace (`4.18.0` injected into arc commands), and added a `sync-versions.sh` guard that fails when a version string appears inside any `d="…"` path data (#2562).
- **Website**: aligned stale version strings on 4.18.0 (iOS snippet 4.3.4, web JSON-LD 4.4.0, playground prompt 4.3.1, `?v=3.6.2`/`4.4.0` cache-busters) and pinned each surface in `sync-versions.sh` so they can't drift again (#2564).
