<!-- category: Changed -->
- **android-demo** — the Scene Gallery and Multi-Model asset-source pills now route through
  the same `AssetSourceProbe` the two AR demos use, finishing the de-duplication started in
  #2953. All four call sites had held their own copy of the rule in three different shapes,
  and it had been fixed once per site (#2934, #2938, #2953) because each re-derived it. No
  behaviour change — the two remaining copies were already correct, and both directions were
  re-verified on the emulator (#2989).
