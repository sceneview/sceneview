<!-- category: Tests -->
- `sceneview-web.d.ts` is now machine-guarded: `check-web-dts.sh` (quality-gate + repo-hygiene CI) fails on any bidirectional drift between the npm typings and the actual Kotlin/JS surface, with a 6-scenario mutation self-test (#2736)
