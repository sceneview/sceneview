<!-- category: Fixed -->
- **device-qa (CI)**: the pre-flight disk gate aborted `--platform=web` CI runs
  at random — it required 15 GB free (a threshold sized for a full local
  multi-platform pass) while GitHub ubuntu runners float between ~14-21 GB
  depending on the image, and the web leg is the BLOCKING release gate. The
  threshold now scales with the platform selection (web-only: 5 GB).
