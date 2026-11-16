<!-- category: Fixed -->
- **Reflection probes restore a live environment after a caller light is destroyed.** Leaving a probe zone or removing the last probe falls back to the scene environment when a direct caller override is no longer alive, while an explicit null still disables indirect lighting.

<!-- category: Added -->
- **Demo app: compare sunset reflections with live room lighting.** The Pose Placement demo now has a reflection-probe toggle that restores the latest AR lighting estimate when switched off.
