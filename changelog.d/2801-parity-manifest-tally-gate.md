<!-- category: Tests -->
- **`check-demo-id-parity.sh` now gates the `parity-manifest.yml` section tallies.** The
  `# ─── working (N) ───` banners are comments, and every other check in that script reads
  the manifest through `yaml.safe_load`, which never sees a comment — so a stale `N` passed
  CI silently. It did, four separate times during the Wave-A iOS-port run, ending four rows
  off reality with the gate green throughout. The banners are now compared against the
  parsed row counts, and once any banner exists every non-empty status must carry one, so a
  stale tally cannot be "fixed" by deleting the banner
  ([#2801](https://github.com/sceneview/sceneview/issues/2801)).
- This is gated hard rather than WARN-only because it is exact counting, not a heuristic:
  it cannot false-positive, the same reason `gpt/knowledge-*.md` drift is a blocking check.
  Covered by three new `test-check-demo-id-parity.sh` cases (stale tally, accurate tally,
  banner-deletion dodge).
