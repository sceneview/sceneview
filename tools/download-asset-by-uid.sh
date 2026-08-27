#!/bin/bash
# Download a single Sketchfab model by UID
# Usage: bash tools/download-asset-by-uid.sh <uid> <filename_without_extension>
set -e

API_KEY="${SKETCHFAB_API_KEY:-}"
if [ -z "$API_KEY" ]; then
    echo "SKETCHFAB_API_KEY is not set. Get a token from https://sketchfab.com/settings/password and export it." >&2
    exit 1
fi
MODELS_DIR="samples/android-demo/src/main/assets/models"

uid=$1
name=$2

if [ -z "$uid" ] || [ -z "$name" ]; then
    echo "Usage: bash tools/download-asset-by-uid.sh <uid> <name>"
    echo "Example: bash tools/download-asset-by-uid.sh abc123 sports_car"
    exit 1
fi

mkdir -p "$MODELS_DIR"

echo "⬇️  Getting download URL for $name ($uid)..."
download_json=$(curl -sf -H "Authorization: Token $API_KEY" \
    "https://api.sketchfab.com/v3/models/$uid/download")

url=$(echo "$download_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['glb']['url'])")

echo "⬇️  Downloading GLB..."
curl -L -sf -o "$MODELS_DIR/${name}.glb" "$url"

size=$(du -h "$MODELS_DIR/${name}.glb" | cut -f1)
echo "✅ Saved: $MODELS_DIR/${name}.glb ($size)"
