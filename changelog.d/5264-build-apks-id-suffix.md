<!-- category: Tests -->
- **Device-proof demo APKs build in CI.** `build-apks.yml` takes an optional `demo_id_suffix` when dispatched (`gh workflow run build-apks.yml --ref <branch> -f demo_id_suffix=.floordrag`). It builds the demo of that branch as a separate app, installed next to the Play Store and `.qa` ones. Release tags still build the plain demo.
