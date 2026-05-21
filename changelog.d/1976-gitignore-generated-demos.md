<!-- category: Changed -->
- Build: `samples/android-demo`'s `GeneratedDemos.kt` is no longer committed — it is `.gitignore`d and regenerated before Kotlin compilation by the new `generateDemoRegistry` Gradle task, killing the per-PR merge-conflict class that hit every demo-adding PR (#1976).
