<!-- category: Fixed -->
- **CI:** CI Gate no longer hard-fails on docs-only PRs. A 90-second grace period replaces the previous 50-minute timeout — if no other check runs register (because every workflow was path-filtered out), the gate exits green immediately. (#2117)
