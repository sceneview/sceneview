<!-- category: Fixed -->
- **Android demo**: Cosmos's voyage frame-pacing log line and the Rerun `.rrd` frame paths are formatted with `Locale.ROOT`, so they read `10.0 fps` and `frames/001` whatever the device locale. `VoyageStateTest` no longer fails on a French-locale machine.
