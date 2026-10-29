#!/bin/bash
# Rebuilds the night-circuit scene: textures, lettering, GLB, environment map.
#   build.sh <CarConcept.glb> <workDir> <out.glb> <out.ktx>
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; car="$1"; work="$2"; glb="$3"; ktx="$4"
mkdir -p "$work"
node "$here/build-textures.mjs" "$work" >/dev/null
node "$here/wordmark.mjs" "$work"
node "$here/build-scene.mjs" "$car" "$work" "$glb" --meshopt 2>&1 | grep -v 'Skipping\|prune:' || true
node "$here/build-ibl.mjs" "$work/circuit.hdr" 1024
rm -rf "$work/ibl"; cmgen --quiet --format=ktx --size=256 --deploy="$work/ibl" "$work/circuit.hdr"
cp "$work/ibl/ibl_ibl.ktx" "$ktx"
ls -l "$glb" "$ktx"
