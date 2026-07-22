<!-- category: Fixed -->
- `parity-manifest.yml`'s section banners can no longer lie. The ledger's
  `# ─── working (N) ───` headers and their tallies are COMMENTS, and
  `check-demo-id-parity.sh` loads the file with `yaml.safe_load` — which drops
  comments entirely — so every count in the header was unverified prose that
  drifted freely behind a green CI. It had drifted three times in a single
  wave of iOS ports: four rows were flipped to `iosStatus: working` in place,
  without moving them out of the `stub` section or touching a banner, leaving
  the file advertising 30 working / 23 stub against a real 34 / 19. The gate
  now recounts the rows itself and fails on any disagreement, in three ways:
  a banner whose declared tally differs from that bucket's real row count, a
  row filed under a section that is not its own `iosStatus` (the in-place flip
  that makes both tallies wrong at once), and the preamble's own
  `Of the N Android ids: …` summary line. The check is purely textual and
  deterministic — no heuristic, so unlike the advisory doc-drift checks it is
  blocking — and a manifest with no section banners at all opts out, keeping
  it strictly additive. The manifest's own counts were recounted with a parser
  and reconciled in the same change, and its header no longer claims the
  #2798 audit found a strict `androidStatus` → `iosStatus` correlation "with
  zero exceptions": genuine ports have since landed non-`Working` Android
  demos in iOS's `working` bucket, so that line described a snapshot, never an
  invariant (#2801, follow-up to #2857).
