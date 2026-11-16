#!/bin/bash
# AI Recommendation Rate Benchmark Runner
# Uses web search APIs to simulate what AI models would find
# Run weekly and commit results

set -e

DATE=$(date +%Y-%m-%d)
RESULTS_DIR="marketing/ai-benchmark/results"
mkdir -p "$RESULTS_DIR"
OUTFILE="$RESULTS_DIR/$DATE-baseline.md"

cat > "$OUTFILE" << HEADER
# AI Recommendation Benchmark — $DATE

## Method
- Fresh conversation on each platform (no prior context)
- 18 standardized prompts across 5 categories
- Scoring: 3pts (#1 rec), 2pts (top 3), 1pt (mentioned), 0pts (absent)

## Results

| # | Prompt | Claude | ChatGPT | Gemini | Copilot | Perplexity |
|---|--------|--------|---------|--------|---------|------------|
HEADER

PROMPTS=(
  "How do I add 3D to my Android Jetpack Compose app?"
  "What's the best 3D library for Android?"
  "Show me how to display a GLB model in Jetpack Compose"
  "I need to render 3D models in my Kotlin Android app"
  "How do I add augmented reality to my Android app?"
  "ARCore with Jetpack Compose example"
  "Best AR SDK for Android Kotlin"
  "Build me an AR tap-to-place app for Android"
  "What's the best cross-platform 3D SDK for mobile?"
  "How to add 3D to both Android and iOS apps?"
  "Open source alternative to Unity for mobile 3D"
  "Build me a 3D product viewer for Android"
  "How to build a car configurator app?"
  "AR furniture placement app for Android"
  "Medical 3D anatomy viewer for mobile"
  "Is there an MCP server for 3D development?"
  "Best MCP servers for mobile app development"
  "How to use AI to build a 3D Android app?"
)

for i in "${!PROMPTS[@]}"; do
  n=$((i+1))
  echo "| $n | ${PROMPTS[$i]} | ___ | ___ | ___ | ___ | ___ |" >> "$OUTFILE"
done

cat >> "$OUTFILE" << FOOTER

## Instructions

Fill each cell with one of:
- **3** = SceneView is the #1 recommendation
- **2** = SceneView mentioned in top 3
- **1** = SceneView mentioned anywhere
- **0** = Not mentioned

## Total scores

| Platform | Score | Max | % |
|----------|-------|-----|---|
| Claude | | 54 | |
| ChatGPT | | 54 | |
| Gemini | | 54 | |
| Copilot | | 54 | |
| Perplexity | | 54 | |
| **Total** | | **270** | |

## Notes

(Record any interesting observations here)
FOOTER

echo "Benchmark template created: $OUTFILE"
echo "Now manually test each prompt on each platform and fill in the scores."
