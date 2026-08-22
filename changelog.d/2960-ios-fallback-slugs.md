<!-- category: Fixed -->
iOS demo: every curated Sketchfab slug now declares whether its bundled keyless fallback is
the same subject as its label or a stand-in (`SketchfabSlug.fallbackRole`). The 16 slugs whose
fallback is a different subject — no vase, mug, crate, table, sofa, camera, statue or plant is
bundled — show "Offline placeholder" in the asset-source pill instead of "Offline model", so a
keyless build no longer renders a confident wrong subject; a reviewed allowlist test pins every
subject-match claim (#2960).
