<!-- category: Fixed -->
- Play Store: both tablet classes are now on screenshot **set v2**
  (`model-viewer · dynamic-sky`). The three retired slots per class — `materials`
  (#2874), `geometry` (#2873) and `double-pendulum`, all shot from a 4.23.0 build —
  are removed, so the next listing sync stops uploading them: `play_listing.py`
  selects screenshots by glob, not by count.
- `capture-play-store-screenshots.sh` now prunes higher-numbered leftover slots
  after a completed run, so a shrinking set can no longer leave stale frames in
  the Play mirror where neither the mosaic nor the run summary can show them.
