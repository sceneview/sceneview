#!/usr/bin/env python3
"""Generate demo preview images with Gemini image models (image-to-image from real captures).

Usage:
  gen.py prompts.json out_dir [--refs DIR] [--only id,id] [--variants light,dark] [--model M]
  gen.py model-thumbs.json out_dir --kind thumb --refs DIR   # square model/environment tiles
  gen.py heroes.json out_dir --kind hero --refs DIR          # the wide home hero banner

prompts.json: {"style": "...shared suffix...", "items": {"<demo-id>": {"prompt": "...", "ref": "file.png", "ar": bool}}}
Output (--kind preview, the default):
  out_dir/raw/<id>_<variant>.png and out_dir/webp/preview_<id_>_<variant>.webp (800x640, q80).
Output (--kind thumb):
  out_dir/raw/<id>_dark.png and out_dir/webp/model_thumb_<id>.webp (320x320, q80) — the
  sheet-and-picker tile size `ModelThumbnails` maps by asset stem. Thumbs are dark-only:
  the sheets that show them sit on a scrim in both app themes, so a light variant would
  never be read (`--variants` is ignored for this kind).
Output (--kind hero):
  out_dir/raw/<id>_dark.png and out_dir/webp/preview_hero_<id_>.webp (1600x1000, 16:10, q80) —
  the `HomeHero` banner. Dark-only, and the item's prompt is used verbatim: the hero carries a
  Compose caption over its left third, so it needs its own framing rather than the shared
  field/style suffix the cards take.
The key is read from GEMINI_API_KEY or from the env file named by GEMINI_ENV_FILE (never printed).
See DESIGN.md "Preview Image Art Direction" for the rules these prompts must follow.
"""
import argparse, base64, json, os, re, subprocess, sys, time, urllib.request, urllib.error
from PIL import Image

FIELD = {"light": "Background field colour #EEF0F3, a clean light studio; exposure bright and airy.",
         "dark": "Background field colour #0E1218, a deep dark studio; exposure moody but the subject fully readable."}
AR_FIELD = {"light": "Keep the real camera photo background; bright daylight exposure.",
            "dark": "Keep the real camera photo background; dim evening exposure, subject still readable."}

def api_key():
    if os.environ.get("GEMINI_API_KEY"): return os.environ["GEMINI_API_KEY"]
    env = open(os.path.expanduser(os.environ["GEMINI_ENV_FILE"])).read()
    return re.search(r'^GEMINI_API_KEY=["\']?([^"\'\n]+)', env, re.M).group(1)

def generate(model, out, prompt, refs, aspect):
    parts = [{"text": prompt}]
    for r in refs:
        mime = "image/png" if r.endswith(".png") else "image/webp" if r.endswith(".webp") else "image/jpeg"
        parts.append({"inline_data": {"mime_type": mime, "data": base64.b64encode(open(r, "rb").read()).decode()}})
    body = {"contents": [{"parts": parts}],
            "generationConfig": {"responseModalities": ["IMAGE"], "imageConfig": {"aspectRatio": aspect}}}
    req = urllib.request.Request(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key()}",
        data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    for attempt in range(3):
        try:
            resp = json.load(urllib.request.urlopen(req, timeout=180))
            for p in resp.get("candidates", [{}])[0].get("content", {}).get("parts", []):
                if "inlineData" in p:
                    open(out, "wb").write(base64.b64decode(p["inlineData"]["data"])); return True
            print("  no image returned", file=sys.stderr)
        except urllib.error.HTTPError as e:
            print("  HTTP", e.code, file=sys.stderr)
            if e.code == 429: time.sleep(45 * (attempt + 1)); continue
        time.sleep(8)
    return False

def crop_save(png, out, tw, th, width, q=80):
    im = Image.open(png).convert("RGB"); w, h = im.size
    if w / h > tw / th: nw = int(h * tw / th); im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
    else: nh = int(w * th / tw); im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
    im.resize((width, width * th // tw), Image.LANCZOS).save(out, "WEBP", quality=q, method=6)

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("prompts"); ap.add_argument("out")
    ap.add_argument("--model", default="gemini-3.1-flash-image"); ap.add_argument("--refs")
    ap.add_argument("--only"); ap.add_argument("--variants", default="light,dark")
    ap.add_argument("--kind", default="preview", choices=("preview", "thumb", "hero"))
    a = ap.parse_args(); spec = json.load(open(a.prompts)); style = spec.get("style", "")
    os.makedirs(os.path.join(a.out, "raw"), exist_ok=True); os.makedirs(os.path.join(a.out, "webp"), exist_ok=True)
    only = set(a.only.split(",")) if a.only else None; failed = []
    thumb = a.kind == "thumb"
    hero = a.kind == "hero"
    # Thumbs and heroes are dark-only: the model sheets sit on a scrim in both app themes, and
    # `HomeHero` draws its caption over a fixed dark scrim, so a light variant is never read.
    variants = ["dark"] if (thumb or hero) else a.variants.split(",")
    for did, item in spec["items"].items():
        if only and did not in only: continue
        for variant in variants:
            png = os.path.join(a.out, "raw", f"{did}_{variant}.png")
            if not os.path.exists(png):
                field = (AR_FIELD if item.get("ar") else FIELD)[variant]
                refs = [os.path.join(a.refs, item["ref"])] if a.refs and item.get("ref") and os.path.exists(os.path.join(a.refs, item["ref"])) else []
                # `refUrl` fetches the reference instead of committing it. Model thumbs are
                # image-to-image from each asset's OWN upstream render (the Khronos
                # `screenshot/screenshot.jpg`), and those are themselves CC-BY works: caching
                # them under `refs/` would put third-party binaries in the repo that need
                # their own attribution line for no gain. Fetched to `out/raw`, never tracked.
                if not refs and item.get("refUrl"):
                    cached = os.path.join(a.out, "raw", f"ref_{did}{os.path.splitext(item['refUrl'])[1]}")
                    if not os.path.exists(cached):
                        urllib.request.urlretrieve(item["refUrl"], cached)
                    refs = [cached]
                print(f"[{did}/{variant}] ref={'yes' if refs else 'no'}")
                # The hero carries its own framing and lighting directions (it has a caption
                # overlaid on one side), so it takes neither the field sentence nor the shared
                # thumbnail style suffix. 16:10 is not an aspect the API offers — generate 16:9
                # and let crop_save take the ~5% a side.
                prompt = item["prompt"] if hero else (
                    item["prompt"].rstrip(". ") + ". " + field + " " + style)
                if not generate(a.model, png, prompt, refs,
                                "1:1" if thumb else "16:9" if hero else "4:3"):
                    failed.append(f"{did}_{variant}"); continue
            if thumb:
                crop_save(png, os.path.join(a.out, "webp", f"model_thumb_{did}.webp"), 1, 1, 320)
            elif hero:
                crop_save(png, os.path.join(a.out, "webp", f"preview_hero_{did.replace('-', '_')}.webp"), 16, 10, 1600)
            else:
                crop_save(png, os.path.join(a.out, "webp", f"preview_{did.replace('-', '_')}_{variant}.webp"), 5, 4, 800)
    print("failed:", failed)

if __name__ == "__main__": main()
