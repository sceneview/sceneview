#!/bin/bash
# SceneView Demo — Download pro assets from Sketchfab + Poly Haven
# Usage: bash tools/download-assets.sh
set -e

API_KEY="${SKETCHFAB_API_KEY:-}"
if [ -z "$API_KEY" ]; then
    echo "SKETCHFAB_API_KEY is not set. Get a token from https://sketchfab.com/settings/password and export it." >&2
    exit 1
fi
MODELS_DIR="samples/android-demo/src/main/assets/models"
ENV_DIR="samples/android-demo/src/main/assets/environments"

mkdir -p "$MODELS_DIR" "$ENV_DIR"

download_sketchfab() {
    local uid=$1
    local name=$2
    echo "⬇️  Downloading $name ($uid) from Sketchfab..."

    local download_json
    download_json=$(curl -sf -H "Authorization: Token $API_KEY" \
        "https://api.sketchfab.com/v3/models/$uid/download")

    if [ $? -ne 0 ]; then
        echo "❌ Failed to get download URL for $name — skipping (may not be downloadable)"
        return 1
    fi

    local url
    url=$(echo "$download_json" | python3 -c "import json,sys; print(json.load(sys.stdin)['glb']['url'])")

    curl -L -sf -o "$MODELS_DIR/${name}.glb" "$url"
    local size=$(du -h "$MODELS_DIR/${name}.glb" | cut -f1)
    echo "✅ $name.glb ($size)"
}

search_sketchfab() {
    local query=$1
    local count=${2:-10}
    local animated=${3:-}

    local url="https://api.sketchfab.com/v3/search?type=models&downloadable=true&sort_by=-likeCount&file_format=glb&count=$count&q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$query'))")"

    if [ -n "$animated" ]; then
        url="${url}&animated=true"
    fi

    curl -sf -H "Authorization: Token $API_KEY" "$url" | python3 -c "
import json, sys
data = json.load(sys.stdin)
for m in data.get('results', []):
    anim = '🎬' if m.get('animationCount', 0) > 0 else '  '
    print(f\"{m['uid']} | {anim} | ♥{m.get('likeCount',0):>5} | {m['name'][:60]}\")
"
}

echo "============================================"
echo " SceneView Demo — Asset Downloader"
echo "============================================"
echo ""

# --- Step 1: Search and show realistic models ---
echo "🔍 Searching realistic showcase models..."
echo "-------------------------------------------"
search_sketchfab "realistic product showcase" 10
echo ""
echo "🔍 Searching realistic vehicles..."
echo "-------------------------------------------"
search_sketchfab "realistic car vehicle" 5
echo ""
echo "🔍 Searching realistic furniture..."
echo "-------------------------------------------"
search_sketchfab "realistic furniture chair" 5
echo ""

# --- Step 2: Search small animated models ---
echo "🔍 Searching small animated icon-like models..."
echo "-------------------------------------------"
search_sketchfab "animated low poly icon" 10 "animated"
echo ""
echo "🔍 Searching animated emoji/food models..."
echo "-------------------------------------------"
search_sketchfab "animated emoji food cute" 10 "animated"
echo ""

echo "============================================"
echo " Copy a UID from above and download:"
echo "   bash tools/download-asset-by-uid.sh <uid> <name>"
echo ""
echo " Or download all pre-selected models:"
echo "   bash tools/download-preselected.sh"
echo "============================================"
