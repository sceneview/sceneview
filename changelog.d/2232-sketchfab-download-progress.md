<!-- category: Fixed -->
- **Sketchfab viewer** — the loading sheet now shows a determinate
  `LinearProgressIndicator` + `X.X / Y.Y MB` counter while a GLB is
  streaming from Sketchfab, replacing the silent indeterminate spinner
  that gave no feedback during 20+ second downloads of heavy models.
  An advisory label ("Heavy model — may take a moment") appears for
  models ≥ 500k polys (#2232).
