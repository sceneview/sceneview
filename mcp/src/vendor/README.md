# WASM-free widget fallback

`widget-fallback.ts` is a generated JavaScript string embedded in the MCP resource.
Its source is `../../browser/widget-fallback.js`. It bundles **three.js 0.181.2**
(MIT, license included in the string), GLTFLoader, OrbitControls and RoomEnvironment.
Filament remains the primary renderer. The bundle is executed only after the WASM
probe or Filament initialization fails. No additional website deployment, CDN
allowlist, eval permission, workers or telemetry are required.

The cost is approximately 625 kB of additional uncompressed resource HTML. This
avoids making a fallback depend on a not-yet-deployed website file. The fallback
supports ordinary glTF/GLB and converted 3MF; Draco, meshopt and KTX2 decoders are
not installed because they can require WASM. Unsupported compressed assets show
an error and retain the phone link.

To regenerate from the repository root, install the build tools in a temporary
directory, then bundle the source and encode it as a TypeScript string. Do not edit
the generated string. The commands below do not change package versions or publish:

```sh
WIDGET_BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/widget-bundle.XXXXXX")"
npm install --prefix "$WIDGET_BUILD_DIR" three@0.181.2 esbuild@0.28.1
WIDGET_BUILD_DIR="$WIDGET_BUILD_DIR" node --input-type=module <<'JS'
import { readFile, writeFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';
const modules = process.env.WIDGET_BUILD_DIR + '/node_modules';
const { build } = await import(pathToFileURL(modules + '/esbuild/lib/main.js'));
const license = await readFile(modules + '/three/LICENSE', 'utf8');
const result = await build({
  entryPoints: ['mcp/browser/widget-fallback.js'],
  bundle: true, minify: true, format: 'iife', target: 'es2020', write: false,
  nodePaths: [modules], legalComments: 'inline',
  banner: { js: '/* three.js 0.181.2 — ' + license.replaceAll('*/', '* /') + ' */' },
});
const source = result.outputFiles[0].text.replace(/<\/script/gi, '<\\/script');
await writeFile('mcp/src/vendor/widget-fallback.ts',
  '// Generated from mcp/browser/widget-fallback.js; see README.md. Do not edit.\n' +
  '// biome-ignore-all lint/suspicious/noTemplateCurlyInString: Serialized vendor JavaScript contains template literals.\n' +
  'export const WIDGET_FALLBACK_JS =\n  ' + JSON.stringify(source) + ';\n');
JS
mcp/node_modules/.bin/biome format --write mcp/src/vendor/widget-fallback.ts
```

The build includes the generated module through the normal TypeScript import graph;
`dist/**/*.js` already includes it in the npm package. The Worker also bundles it.
