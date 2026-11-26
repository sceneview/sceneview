# WASM-free widget fallback

`widget-fallback.js` bundles **three.js 0.181.2** (MIT, license header included),
GLTFLoader, OrbitControls and RoomEnvironment. Source: `mcp/browser/widget-fallback.js`.
Filament remains the primary renderer. The widget downloads this asset from
`https://sceneview.github.io/js/widget-fallback.js` only when its WASM probe or
Filament initialization fails. It uses the existing website CSP origin; no eval,
workers or telemetry are required. Successful Filament previews pay no bundle cost.

The fallback supports ordinary glTF/GLB and converted 3MF; Draco, meshopt and KTX2 decoders are
not installed because they can require WASM. Unsupported compressed assets show
an error and retain the phone link.

To regenerate from the repository root, install the build tools in a temporary
directory, then bundle the source into the website asset. Do not edit
the generated bundle. The commands below do not change package versions or publish:

```sh
WIDGET_BUILD_DIR="${TMPDIR:-/tmp}/codex-widget2/bundle"
mkdir -p "$WIDGET_BUILD_DIR"
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
await writeFile('website-static/js/widget-fallback.js', result.outputFiles[0].text);
JS
```

Deploy `website-static/` before shipping a widget that references this asset. The widget
adds the current SceneView release as a cache-busting query parameter. No npm package
or Worker needs to carry this bundle.
