<!-- category: Fixed -->
- **Android recording disposal (#4864).** Mirror and scene surface teardown share one one-second wait budget. Recorder surface release still follows the mirror wait; a later window detach submits destruction with only the remaining timeout.
