<!-- category: Fixed -->
- **Website:** fixed the garbled ("forky") GitHub icon in the site header. The nav + dev-tools GitHub mark in `index.html` carried a malformed SVG `d` path (`…24.18.0-6.63…`) whose elliptical-arc segment was truncated, so the octocat rendered distorted on every browser. Restored the canonical path used by all other pages. (#2546)
