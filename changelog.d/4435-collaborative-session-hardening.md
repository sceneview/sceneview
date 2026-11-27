<!-- category: Fixed -->

- Collaborative sessions reject hostile counter jumps, keep evicted removals from resurrecting nodes, and synchronize late joiners through an active ready peer with fallback when it does not respond. Snapshot attempts and Cloud Anchor completions are bound to one runtime, so delayed work from before a restart cannot complete or change the new session. ([#4435](https://github.com/sceneview/sceneview/issues/4435))
