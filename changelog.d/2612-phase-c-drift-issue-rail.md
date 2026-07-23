<!-- category: Changed -->
- Daily maintenance (`maintenance.yml`) now opens and refreshes a **de-duplicated
  tracking issue** — one per store — when the live Play Store or App Store listing
  has drifted from the repo. Both read-only drift jobs run their diff with
  `--fail-on-drift`, and the issue is filed only on a *measured* drift (exit 3),
  never on a credential-less skip or a mid-read crash. Advisory-only: a drifted
  listing surfaces as an actionable, self-closing issue instead of an unread step
  summary, and never fails CI (#2612 Phase C).
