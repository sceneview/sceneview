#!/bin/bash
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$1"
out="$2"
mkdir -p "$work" "$out"
# Blender writes the graded HDRs first; never compile the ungraded source skies.
for mood in morning dusk; do
  deploy="$work/cmgen_$mood"
  mkdir -p "$deploy"
  cmgen --quiet --format=ktx --size=256 --deploy="$deploy" "$work/$mood.hdr"
  # cmgen versions use either the input basename or 'ibl' for deployed files.
  ibl=$(find "$deploy" -name '*_ibl.ktx' -type f | head -1)
  sky=$(find "$deploy" -name '*_skybox.ktx' -type f | head -1)
  test -n "$ibl" && test -n "$sky"
  cp "$ibl" "$out/pavilion_${mood}_ibl.ktx"
  cp "$sky" "$out/pavilion_${mood}_skybox.ktx"
done
