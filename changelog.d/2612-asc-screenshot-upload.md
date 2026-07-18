<!-- category: Added -->
- App Store screenshots can now be published from the repo instead of by hand:
  `.claude/scripts/store-sync/asc_listing.py --apply-screenshots` uploads
  `samples/ios-demo/appstore-screenshots/` to the editable App Store version
  (reserve → chunked upload → commit). It skips display types whose live set
  already matches, replaces the others, and skips honestly when no version is
  editable — it never creates one. CI entry point is its own dispatch-only
  workflow, `app-store-screenshots.yml`, which runs on ubuntu and starts no
  build. Closes the loop left open by the capture script, whose output had
  never actually reached the store (#2612, #2384).

<!-- category: Fixed -->
- Store-sync scripts no longer accept abbreviated flags: `--apply` or
  `--appl` used to resolve to `--apply-screenshots` via argparse prefix
  matching, so a near-miss could publish assets to a store. Both scripts now
  require the exact flag name (#2612).
