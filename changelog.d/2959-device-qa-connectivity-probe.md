<!-- category: Fixed -->
`device-qa.sh`'s `sketchfab` / `arcore-cloud` sub-legs only checked
`airplane_mode_on` before trusting a streamed-asset run — a radio that was ON
but routeless (captive portal, dead DNS, a dropped VPN) still passed the gate,
so every streamed Sketchfab slug could silently resolve to its bundled
fallback while the report said the path was exercised (measured closing
#2942). A new `lib/qa-connectivity.sh` layers a real probe — airplane mode,
Android's own captive-portal-validated `dumpsys connectivity` signal, and an
actual `ping` to the streamed-asset host — and fails closed to an honest
`skipped` when none of them prove a route. `ios-device-qa.sh` gets the
symmetric host-side probe (the Simulator shares the Mac's network). Both
scripts record the probe detail in their reports and accept `--allow-offline`
to downgrade the loud connectivity banner to a quiet, explicit skip list for a
deliberately offline run.
