<!-- category: Changed -->
- **Releases no longer use an npm token ([#PR](https://github.com/sceneview/sceneview/pull/PR)).** `sceneview-web` and `sceneview-mcp` publish only through npm trusted publishing (GitHub OIDC); the `sceneview-web` build runs before `setup-node`, so it no longer needs `NPM_TOKEN` either.
