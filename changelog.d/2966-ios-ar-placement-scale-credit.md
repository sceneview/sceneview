<!-- category: Fixed -->
iOS demo: the AR placement demos now place each streamed slug at its own `scaleToUnits`
(a coffee mug at 0.10 m, a floor lamp at 1.55 m) instead of a hardcoded 0.3 m, and the
attribution caption under every streamed-slug picker credits the model actually on screen —
the bundled fallback's own name, author and licence on a keyless build (including CC-BY-NC
fallbacks), the Sketchfab author only when the stream really loaded (#2966).
