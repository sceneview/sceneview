<!-- category: Changed -->

- The pre-release checklist (`release-checklist.sh`) now surfaces **Play Store and App Store
  listing drift** before tagging: section 17 runs the store-as-code read-only diff
  (`play_listing.py` / `asc_listing.py --dry-run --fail-on-drift`) and WARNs when the live
  store listing has diverged from the repo, so a silently-drifted listing is caught at
  release time rather than after the next blind sync overwrites it. Advisory-first — a
  drifted (or, without credentials, unmeasured) listing is a warning, never a release
  blocker (#2612 Phase C).
