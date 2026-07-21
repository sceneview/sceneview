<!-- category: Added -->
- App Store listing drift is now visible read-only, and the assumption the
  screenshot diff rests on is measured rather than assumed. A daily
  `asc-listing-drift` job runs `store-sync/asc_listing.py --dry-run` — the
  first CI caller of the ASC read-only path — and prints a
  `sourceFileChecksum` provenance verdict (`confirmed` / `unattested-match` /
  `md5-shaped` / `absent` / …) before the diff it justifies. It writes nothing
  to the store, skips honestly with no credential, and is never blocking. This
  is #2612 Phase C step 0: it turns the "is `sourceFileChecksum` really the
  source MD5?" question — which the upload path can never answer, since Apple
  only echoes what we send — into an observable measurement, and blocks the
  Phase C drift gate from being wired until the verdict is `confirmed`. A
  repo-MD5 match alone reads `unattested-match`, reported with the display
  type it was found in; promoting it to `confirmed` requires attesting console
  provenance (`--screenshots-are-console-sourced`), so uploading our own
  screenshots can never confirm the assumption by echo (#2612).
