<!-- category: Fixed -->
- Flutter (Android): the AR plane-discovery bridge now dedupes detected planes by reference identity (`IdentityHashMap`-backed set) instead of `System.identityHashCode`, which is not collision-free — a new plane whose hash collided with an already-reported one could silently drop its `onPlaneDetected` callback (#2488).
