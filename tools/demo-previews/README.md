# Demo preview images

`gen.py` generates the light/dark 5:4 preview WebPs in
`samples/android-demo/src/main/res/drawable-nodpi/` with Gemini image-to-image,
following the art direction in `DESIGN.md` ("Preview Image Art Direction").

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/prompts.json /tmp/previews --refs tools/demo-previews/refs --only lighting,fog
cp /tmp/previews/webp/*.webp samples/android-demo/src/main/res/drawable-nodpi/
```

`prompts.json` holds one prompt per demo id (`ref` = a real capture or the hero render
used as the image-to-image reference). Every generated image must be reviewed next to
its source capture before merge; never invent models or reshape assets.

## Model thumbnails

`model-thumbs.json` + `--kind thumb` generates the square 320×320 tiles the model picker
and the Model Viewer's model sheet show — `model_thumb_<asset-stem>.webp`, the exact key
`ModelThumbnails.resourceFor()` looks up, so **the id in the JSON is the bundled GLB's
file stem**. Dark-only: both sheets sit on a scrim in both app themes.

```bash
GEMINI_ENV_FILE=~/path/to/env-with-GEMINI_API_KEY python3 tools/demo-previews/gen.py \
  tools/demo-previews/model-thumbs.json /tmp/thumbs --kind thumb --only khronos_sheen_chair
cp /tmp/thumbs/webp/*.webp samples/android-demo/src/main/res/drawable-nodpi/
```

Entries use `refUrl` rather than a committed `ref`: the reference is each asset's own
upstream render (the Khronos `screenshot/screenshot.jpg`), itself a CC-BY work, and
caching it under `refs/` would add third-party binaries the repo would then owe an
attribution line for. It is fetched into the out dir instead, which is not tracked.
