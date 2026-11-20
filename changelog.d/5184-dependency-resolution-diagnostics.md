<!-- category: Changed -->
- **CI dependency resolution diagnostics ([#5184](https://github.com/sceneview/sceneview/pull/5184)).** Failed Gradle builds on GitHub-hosted runners now report live Maven HTTP responses, local module-cache metadata, and JVM network settings to distinguish cached misses from network or CDN failures without changing caching or resolution behavior.
