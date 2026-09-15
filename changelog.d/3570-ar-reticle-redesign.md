<!-- category: Changed -->
- **The AR placement reticle now has three documented states and spends colour only on the last
  one ([#3570](https://github.com/sceneview/sceneview/issues/3570)).** `ReticlePhase` gains
  `LOCKED`: the white hairline ring stays achromatic while searching (35 % opacity, no dot) and
  on an estimated hit (60 %, small white dot), and the DESIGN.md `primary` dark-value accent
  appears only on the smaller centre dot once the hit is on a tracked ARCore plane (90 %) — so
  the cursor never competes with the model about to be placed.
