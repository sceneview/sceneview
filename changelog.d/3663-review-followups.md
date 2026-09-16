<!-- category: Fixed -->
- Physics: restored the pre-`gravity` JVM descriptors of `PhysicsState`, `PhysicsBody` and
  the `PhysicsNode` composables as hidden compatibility overloads, so code compiled against
  4.36.0 keeps linking instead of failing with `NoSuchMethodError`.
- Demo (Android): Back no longer closes the hidden Explore gallery when another tab is on
  screen — the gallery handler is now scoped to the Showcase tab.
- Demo (iOS): deleting the Explore search field below two characters now clears the active
  search, instead of leaving the previous query's results under a field that can no longer
  produce them.
