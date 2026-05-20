<!-- category: Fixed -->
- **`sceneview-web` `SCENEVIEW_VERSION` constant lagged 2 releases (`4.9.0` while shipping `4.11.1`).** Bumped to `4.11.1` and promoted the `sync-versions.sh` check for this code-resident constant from WARN-only to a hard MISMATCH so it can never silently drift again. The regression-pin jsTest (#1357) was bumped in lockstep.
