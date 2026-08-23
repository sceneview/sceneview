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
