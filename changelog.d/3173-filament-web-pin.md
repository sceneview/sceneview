<!-- category: Fixed -->
<!-- breaking: false -->
The MCP server no longer names Filament.js 1.70.2 anywhere. That version was a
hand-copied string in the web rendering guide and in the artifact generator, and it was
wrong on both counts: it never matched the runtime vendored for `sceneview.js`
(`filamentWebsite`, 1.70.1) and it was never published on npm, so the jsDelivr URL the
generated 3D artifacts loaded (`filament@1.70.2/filament.js`) did not resolve.
`generate-version.js` now reads `filamentWeb` and `filamentWebsite` from
`gradle/libs.versions.toml` at build time; the artifact CDN URL uses the npm pin and the
guide names the website runtime, so the next Filament bump propagates without a manual
edit.
