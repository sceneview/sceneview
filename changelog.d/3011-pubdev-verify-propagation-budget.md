<!-- category: Fixed -->
<!-- RELEASE NOTE (maintainer-only):
     v4.33.0's "Publish flutter_sceneview to pub.dev" job went red with
     "NOT ON REGISTRY: flutter_sceneview 4.33.0 is absent after a green
     publish" even though the publish had actually succeeded — pub.dev's own
     upload response says "it may take up-to 10 minutes before the new
     version is available," but the post-publish verification (#3013,
     #3021) only retried for 100s (5 x 20s) before failing the job. v4.32.0
     nearly hit the same false red, landing on attempt 3/5. The package was
     never at risk — this only fixed the CI signal, not pub.dev credentials
     or trust. -->
- **pub.dev publish verification no longer false-reds on its own documented propagation delay.** `.claude/scripts/verify-published-version.sh`'s `pub` budget grew from 5x20s (100s) to 20x30s (10 min), matching the window pub.dev's own upload response states; `release.yml`'s `pub-publish` job timeout grew from 15 to 20 minutes to give that budget room.
