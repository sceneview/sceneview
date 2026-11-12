#!/bin/bash
# build.sh <workDir> <outDir>; output tree mirrors Android assets.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
work="$1"
out="$2"
mkdir -p "$work" "$out/models" "$out/environments"
work="$(cd "$work" && pwd)"
out="$(cd "$out" && pwd)"
df -h /
"${BLENDER:-/Applications/Blender.app/Contents/MacOS/Blender}" -b --factory-startup --python-exit-code 1 -P "$here/build_pavilion.py" -- "$work" "$out/models/pavilion.glb"
"$here/build_ibl.sh" "$work" "$out/environments"
cp "$work/pavilion_points.bin" "$out/models/pavilion_points.bin"
python3 - "$out" <<'PY2'
from pathlib import Path
import sys
files=sorted(p for p in Path(sys.argv[1]).rglob('*') if p.is_file())
for p in files: print(f'{p.stat().st_size:10,d}  {p}')
print(f'APK asset payload (uncompressed): {sum(p.stat().st_size for p in files)/1024**2:.2f} MiB')
PY2
