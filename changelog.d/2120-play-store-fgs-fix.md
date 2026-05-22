<!-- category: Fixed -->
- Fix Play Store CI deploys blocked by undeclared Foreground Service (FGS) permission (#2120).
  The production fallback now preserves the staged edit in Play Console (instead of deleting it
  on FGS failure), making the FGS declaration section visible under App content. A new
  `commit_edit_id` fast-path in `workflow_dispatch` lets you commit the preserved edit in ~2 min
  after declaring FGS — no 40-min rebuild needed.
