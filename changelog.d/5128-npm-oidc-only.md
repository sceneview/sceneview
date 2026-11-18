<!-- category: Changed -->
- **Releases no longer use an npm token ([#5128](https://github.com/sceneview/sceneview/pull/5128)).** `sceneview-web` and `sceneview-mcp` publish only through npm trusted publishing (GitHub OIDC); the `sceneview-web` build runs before `setup-node`, so it no longer needs `NPM_TOKEN` either.
