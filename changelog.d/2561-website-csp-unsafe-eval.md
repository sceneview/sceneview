<!-- category: Fixed -->
- **Website**: 3D was dead site-wide — the pages' CSP `script-src` was missing `'unsafe-eval'`, which Filament's Emscripten WASM glue requires, so `Filament()` rejected and every viewer spun forever. Added `'unsafe-eval'` to all 26 HTML pages (#2561).
- **Website**: Filament engine init now has a 15s watchdog and a graceful "3D preview unavailable" placeholder — an init failure (blocked WASM, asset 404, OOM) degrades visibly instead of an infinite "Loading 3D engine…" spinner (#2563).
