#!/usr/bin/env python3
"""compose.py — turn a raw capture into a captioned store screenshot.

The store listings need a caption on every slot (the whole category does it:
Polycam, Sketchfab, Reality Composer), and a raw `adb exec-out screencap`
carries none. This script is the *only* step between a capture and the PNG the
listing sync uploads, so a re-capture never needs a hand retouch:

    raw capture (1080x2400 on the Pixel_7a AVD)
      -> crop the status bar
      -> scale into a card under a caption band
      -> write the slot's exact pixel spec

Everything it draws comes from DESIGN.md tokens, dark values:
`surface` #0d1117, `surface-dim` #161B22, `on-surface` #f3f4f6,
`primary` #a4c1ff. No colour is invented here.

Usage (see `slots.json` for the committed set):

    python3 tools/store-screenshots/compose.py --manifest tools/store-screenshots/slots.json

or a single frame:

    python3 tools/store-screenshots/compose.py \
        --src /tmp/cap.png --out .../phone-screenshot-1.png \
        --caption "Open any 3D file" --size 1080x2304 --crop-top 96
"""

import argparse
import json
import os
import pathlib

from PIL import Image, ImageDraw, ImageFont

# DESIGN.md, dark column.
SURFACE = (13, 17, 23)
SURFACE_DIM = (22, 27, 34)
ON_SURFACE = (243, 244, 246)
PRIMARY = (164, 193, 255)

BOLD_CANDIDATES = [
    ("/System/Library/Fonts/SFNS.ttf", "Bold"),
    ("/System/Library/Fonts/Supplemental/Arial Bold.ttf", None),
    ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", None),
]
MONO_CANDIDATES = [
    "/System/Library/Fonts/SFNSMono.ttf",
    "/System/Library/Fonts/Menlo.ttc",
    "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
]


def bold_font(size):
    for path, variation in BOLD_CANDIDATES:
        if not os.path.exists(path):
            continue
        f = ImageFont.truetype(path, size)
        if variation:
            try:
                f.set_variation_by_name(variation)
            except Exception:
                pass
        return f
    raise RuntimeError("no bold font found")


def mono_font(size):
    for path in MONO_CANDIDATES:
        if os.path.exists(path):
            return ImageFont.truetype(path, size)
    raise RuntimeError("no mono font found")


def vertical_gradient(size, top, bottom):
    w, h = size
    base = Image.new("RGB", (1, h))
    px = base.load()
    for y in range(h):
        t = y / max(h - 1, 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return base.resize((w, h), Image.BILINEAR)


def rounded(im, radius):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1],
                                           radius=radius, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def text_width(draw, text, font):
    box = draw.textbbox((0, 0), text, font=font)
    return box[2] - box[0]


def fit_caption(draw, text, max_width, start=76, floor=44):
    """Largest size at which the caption still fits on one line."""
    size = start
    while size > floor:
        font = bold_font(size)
        if text_width(draw, text, font) <= max_width:
            return font
        size -= 2
    return bold_font(floor)


def code_panel(size, lines, pad=44):
    """A syntax-tinted Compose snippet on `code-bg-dark` (DESIGN.md #0d1117)."""
    w, h = size
    panel = Image.new("RGB", size, SURFACE)
    d = ImageDraw.Draw(panel)
    fsize = max(18, int((h - 2 * pad) / max(len(lines), 1) * 0.62))
    # Shrink until the longest line, indent included, still fits: a clipped
    # snippet reads as a bug in the listing.
    while fsize > 14:
        font = mono_font(fsize)
        widest = max(d.textlength(t, font=font) + i * fsize * 0.62 for i, t, _ in lines)
        if widest <= w - 2 * pad:
            break
        fsize -= 1
    font = mono_font(fsize)
    step = (h - 2 * pad) / max(len(lines), 1)
    for i, (indent, text, colour) in enumerate(lines):
        d.text((pad + indent * fsize * 0.62, pad + i * step), text, font=font, fill=colour)
    return panel


def _metrics(tw, band, margin, radius, bottom_margin):
    """Layout scales with the canvas: the same recipe at 1080 and at 1320 wide."""
    return (band or int(tw * 0.278), margin or int(tw * 0.074),
            radius or int(tw * 0.033), bottom_margin or int(tw * 0.052))


def compose(src, out, caption, size, crop_top=0, band=None, margin=None, radius=None,
            bottom_margin=None):
    tw, th = size
    band, margin, radius, bottom_margin = _metrics(tw, band, margin, radius, bottom_margin)
    canvas = vertical_gradient((tw, th), SURFACE, SURFACE_DIM)
    draw = ImageDraw.Draw(canvas)

    font = fit_caption(draw, caption, tw - 2 * margin,
                       start=int(tw * 0.070), floor=int(tw * 0.040))
    box = draw.textbbox((0, 0), caption, font=font)
    cw, ch = box[2] - box[0], box[3] - box[1]
    cx = (tw - cw) // 2 - box[0]
    cy = (band - ch) // 2 - box[1] - int(band * 0.06)
    draw.text((cx, cy), caption, font=font, fill=ON_SURFACE)
    # `primary` accent rule under the caption — the one non-text mark in the band.
    rule_w = max(int(cw * 0.28), 90)
    rule_y = cy + box[3] + int(band * 0.12)
    draw.rounded_rectangle([(tw - rule_w) // 2, rule_y, (tw + rule_w) // 2, rule_y + 8],
                           radius=4, fill=PRIMARY)

    shot = Image.open(src).convert("RGB")
    if crop_top:
        shot = shot.crop((0, crop_top, shot.width, shot.height))
    avail_h = th - band - bottom_margin
    avail_w = tw - 2 * margin
    scale = min(avail_w / shot.width, avail_h / shot.height)
    shot = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)
    card = rounded(shot, radius)
    x = (tw - card.width) // 2
    y = band + (avail_h - card.height) // 2
    canvas.paste(card, (x, y), card)
    ImageDraw.Draw(canvas).rounded_rectangle(
        [x, y, x + card.width - 1, y + card.height - 1], radius=radius,
        outline=(255, 255, 255, 40), width=2)

    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "PNG", optimize=True)
    return canvas.size


def compose_split(code_src, render_src, out, caption, size, crop_top=0, band=None,
                  margin=None, radius=None, bottom_margin=None, gap=None, zoom=1.0,
                  focus=0.32):
    """Slot 6: the snippet above, the frame it renders below."""
    tw, th = size
    band, margin, radius, bottom_margin = _metrics(tw, band, margin, radius, bottom_margin)
    gap = gap or max(20, int(tw * 0.026))
    canvas = vertical_gradient((tw, th), SURFACE, SURFACE_DIM)
    draw = ImageDraw.Draw(canvas)
    font = fit_caption(draw, caption, tw - 2 * margin,
                       start=int(tw * 0.070), floor=int(tw * 0.040))
    box = draw.textbbox((0, 0), caption, font=font)
    cw = box[2] - box[0]
    cx = (tw - cw) // 2 - box[0]
    cy = (band - (box[3] - box[1])) // 2 - box[1] - int(band * 0.06)
    draw.text((cx, cy), caption, font=font, fill=ON_SURFACE)
    rule_w = max(int(cw * 0.28), 90)
    rule_y = cy + box[3] + int(band * 0.12)
    draw.rounded_rectangle([(tw - rule_w) // 2, rule_y, (tw + rule_w) // 2, rule_y + 8],
                           radius=4, fill=PRIMARY)

    avail_h = th - band - bottom_margin
    avail_w = tw - 2 * margin
    code_h = int(avail_h * 0.42) - gap // 2
    render_h = avail_h - code_h - gap

    code = code_panel((avail_w, code_h), code_src)
    canvas.paste(rounded(code, radius), (margin, band), rounded(code, radius))

    shot = Image.open(render_src).convert("RGB")
    if crop_top:
        shot = shot.crop((0, crop_top, shot.width, shot.height))
    # Centre-crop the capture to the render band's aspect rather than letterboxing it.
    target = avail_w / render_h
    if shot.width / shot.height > target:
        nw = int(shot.height * target)
        shot = shot.crop(((shot.width - nw) // 2, 0, (shot.width - nw) // 2 + nw, shot.height))
    else:
        nh = int(shot.width / target)
        top = int((shot.height - nh) * focus)
        shot = shot.crop((0, top, shot.width, top + nh))
    if zoom > 1.0:
        # A subject that sits small in the capture would read as an empty card
        # at this size; crop in rather than re-shoot with a camera override.
        cw, ch = int(shot.width / zoom), int(shot.height / zoom)
        cx, cy = (shot.width - cw) // 2, (shot.height - ch) // 2
        shot = shot.crop((cx, cy, cx + cw, cy + ch))
    shot = shot.resize((avail_w, render_h), Image.LANCZOS)
    card = rounded(shot, radius)
    canvas.paste(card, (margin, band + code_h + gap), card)

    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out, "PNG", optimize=True)
    return canvas.size


def banner(src, out, headline, subline, size, margin=64):
    """Feature graphic: `gradient-hero` (DESIGN.md), promise left, the art right.

    A full-bleed crop of a portrait photo loses the subject and leaves the
    headline sitting on whatever colour happens to be under it; the split keeps
    both readable at the 1024x500 the Play grid actually renders.
    """
    tw, th = size
    # DESIGN.md `gradient-hero`: #005bc1 -> #6446cd, drawn horizontally here.
    im = vertical_gradient((th, tw), (0, 91, 193), (100, 70, 205)).rotate(-90, expand=True)

    art_w = int(tw * 0.42)
    art = Image.open(src).convert("RGB")
    scale = max(art_w / art.width, th / art.height)
    art = art.resize((int(art.width * scale) + 1, int(art.height * scale) + 1), Image.LANCZOS)
    ox, oy = (art.width - art_w) // 2, int((art.height - th) * 0.42)
    art = art.crop((ox, oy, ox + art_w, oy + th))
    im.paste(art, (tw - art_w, 0))
    # Feather the seam into the gradient so the art reads as part of the banner.
    fade = Image.new("RGBA", (140, th), (0, 0, 0, 0))
    fd = ImageDraw.Draw(fade)
    for x in range(140):
        fd.line([(x, 0), (x, th)], fill=(0, 91, 193, int(255 * (1 - x / 140) ** 1.2)))
    im = im.convert("RGBA")
    im.alpha_composite(fade, (tw - art_w, 0))
    im = im.convert("RGB")

    d = ImageDraw.Draw(im)
    hfont = fit_caption(d, headline, tw - art_w - 2 * margin, start=84, floor=34)
    hbox = d.textbbox((0, 0), headline, font=hfont)
    sfont = fit_caption(d, subline, tw - art_w - 2 * margin,
                        start=int(hfont.size * 0.44), floor=18)
    sbox = d.textbbox((0, 0), subline, font=sfont)
    total = (hbox[3] - hbox[1]) + 26 + (sbox[3] - sbox[1])
    y = (th - total) // 2
    d.text((margin - hbox[0], y - hbox[1]), headline, font=hfont, fill=ON_SURFACE)
    d.text((margin - sbox[0], y + (hbox[3] - hbox[1]) + 26 - sbox[1]), subline,
           font=sfont, fill=ON_SURFACE)

    pathlib.Path(out).parent.mkdir(parents=True, exist_ok=True)
    im.save(out, "PNG", optimize=True)
    return im.size


def parse_size(s):
    w, h = s.lower().split("x")
    return int(w), int(h)


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--manifest")
    ap.add_argument("--src")
    ap.add_argument("--out")
    ap.add_argument("--caption")
    ap.add_argument("--size", default="1080x2304")
    ap.add_argument("--crop-top", type=int, default=0)
    a = ap.parse_args()

    if a.manifest:
        root = pathlib.Path(a.manifest).resolve().parents[2]
        spec = json.load(open(a.manifest))
        for item in spec["slots"]:
            size = parse_size(item["size"])
            out = root / item["out"]
            kind = item.get("kind", "shot")
            if kind == "split":
                lines = [(l[0], l[1], tuple(l[2])) for l in spec["snippet"]]
                compose_split(lines, root / item["src"], out, item["caption"], size,
                              crop_top=item.get("crop_top", 0),
                              band=item.get("band"), margin=item.get("margin"),
                              zoom=item.get("zoom", 1.0),
                              focus=item.get("focus", 0.32))
            elif kind == "banner":
                banner(root / item["src"], out, item["headline"], item["subline"], size)
            else:
                compose(root / item["src"], out, item["caption"], size,
                        crop_top=item.get("crop_top", 0),
                        band=item.get("band"), margin=item.get("margin"))
            print(f"{item['out']} <- {item['src']} [{item['size']}]")
        return

    compose(a.src, a.out, a.caption, parse_size(a.size), crop_top=a.crop_top)
    print(a.out)


if __name__ == "__main__":
    main()
