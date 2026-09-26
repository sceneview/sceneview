<!-- category: Fixed -->
- **Release**: the npm publish check now waits up to 5 minutes for the registry to list a new version, instead of 100 seconds. With npm Trusted Publishing, `sceneview-mcp` and `@sceneview-sdk/react-native` 4.41.0 took about two minutes to appear, so the release showed a failure even though both versions were published.
