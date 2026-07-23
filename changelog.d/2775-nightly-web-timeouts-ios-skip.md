<!-- category: Fixed -->
- Nightly CI health (#2775): the two web Playwright legs no longer time out at
  night — their job budgets were outgrown by the suite itself (measured green
  wall-clocks 13–17 min vs a 20-min cap in `render-tests.yml`, 18–24 min vs a
  25-min cap in `device-qa.yml`); both caps raised (+10 min) while Playwright's
  per-test timeout keeps bounding real hangs.
- Device-QA ios leg — two macOS bash 3.2 empty-array crashes (`set -u` rejects
  expanding an empty array before bash 4.4): `device-qa.sh` died with
  `LEGS[@]: unbound variable` whenever the disk gate skipped every leg
  (turning the honest advisory skip into a bogus exit 1 on the self-hosted
  Mac), and `lib/maestro.sh` died on `device_args[@]` on the iOS path — worse,
  that abort exited 0 (bash 3.2 `||`-guarded abort with an EXIT trap set), so
  the leg graded **PASSED with zero Maestro steps run**. Both expansions are
  now guarded, and `run_ios` additionally requires the positive
  `[ios-qa] PASS` marker — an exit-0 harness abort can never grade green again.
