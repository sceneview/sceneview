<!-- category: Changed -->
- **Build: the Gradle daemon heap is 3 GB instead of 4.** A cold recompile of `sceneview`, `arsceneview` and the Android demo took the same time on a 16 GB Mac (87 s at 4 GB, 88 s at 3 GB, two runs each). Jobs that need more still override it.
