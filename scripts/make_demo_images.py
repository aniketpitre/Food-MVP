#!/usr/bin/env python3
"""Generates the illustrated placeholder dish photos used by the demo seed.

They are deliberately drawn, not photographed, so there is no copyright question.
Replace them with your own photos before inviting real users (same file names in
backend/seed/images/, then re-seed a fresh database).

usage: python3 scripts/make_demo_images.py
"""
import math
import random
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "backend" / "seed" / "images"
SEED_DATA = ROOT / "backend" / "src" / "seed-data.ts"
W, H, S = 900, 1125, 2  # final size (4:5) and supersampling factor

PALETTES = {
    "misal": [("#7a1d0b", "#e2541b"), ("#c2410c", "#f59e0b"), ("#f97316", "#dc2626", "#fde68a")],
    "snacks": [("#78350f", "#d97706"), ("#b45309", "#facc15"), ("#ca8a04", "#a16207", "#16a34a")],
    "south-indian": [("#365314", "#84cc16"), ("#ca8a04", "#fde047"), ("#eab308", "#fef3c7", "#15803d")],
    "biryani": [("#431407", "#9a3412"), ("#fbbf24", "#fff7ed"), ("#f59e0b", "#7c2d12", "#16a34a")],
    "thali": [("#1e3a8a", "#475569"), ("#e2e8f0", "#f8fafc"), ("#eab308", "#dc2626", "#16a34a")],
    "chai-coffee": [("#292524", "#78716c"), ("#78350f", "#d6b38a"), ("#fef3c7", "#92400e", "#57534e")],
    "desserts": [("#831843", "#f472b6"), ("#f59e0b", "#fde68a"), ("#ea580c", "#facc15", "#fff7ed")],
    "street-food": [("#7f1d1d", "#ef4444"), ("#c2410c", "#fdba74"), ("#dc2626", "#fbbf24", "#22c55e")],
    "burgers": [("#1c1917", "#57534e"), ("#d97706", "#fde68a"), ("#92400e", "#22c55e", "#ef4444")],
    "pizza-pasta": [("#14532d", "#4ade80"), ("#f59e0b", "#fef3c7"), ("#dc2626", "#16a34a", "#fffbeb")],
    "chinese": [("#450a0a", "#b91c1c"), ("#fde68a", "#fbbf24"), ("#ea580c", "#16a34a", "#7f1d1d")],
    "breakfast": [("#065f46", "#34d399"), ("#fef9c3", "#fde68a"), ("#eab308", "#16a34a", "#b45309")],
}


def hexrgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    px = img.load()
    a, b = hexrgb(top), hexrgb(bottom)
    for y in range(h):
        c = lerp(a, b, y / (h - 1))
        for x in range(w):
            px[x, y] = c
    return img


def bokeh(img, rnd, colour):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for _ in range(14):
        r = rnd.randint(30, 140) * S
        x, y = rnd.randint(0, img.width), rnd.randint(0, img.height)
        d.ellipse((x - r, y - r, x + r, y + r), fill=(*hexrgb(colour), rnd.randint(25, 60)))
    return Image.alpha_composite(img.convert("RGBA"), layer.filter(ImageFilter.GaussianBlur(18 * S)))


def plate(d, cx, cy, r):
    d.ellipse((cx - r * 1.04, cy - r * 0.98, cx + r * 1.04, cy + r * 1.1), fill=(0, 0, 0, 70))  # shadow
    d.ellipse((cx - r, cy - r, cx + r, cy + r), fill=(250, 248, 244, 255))
    d.ellipse((cx - r * 0.8, cy - r * 0.8, cx + r * 0.8, cy + r * 0.8), fill=(238, 234, 226, 255))


def dots(d, rnd, cx, cy, r, colours, n, size):
    for _ in range(n):
        a, rr = rnd.random() * math.tau, r * math.sqrt(rnd.random())
        x, y = cx + rr * math.cos(a), cy + rr * math.sin(a)
        s = rnd.uniform(0.5, 1.0) * size
        d.ellipse((x - s, y - s, x + s, y + s), fill=(*hexrgb(rnd.choice(colours)), 255))


def food(d, rnd, cat, cx, cy, r, c):
    c1, c2, c3 = c
    if cat in ("misal", "street-food", "chinese"):
        d.ellipse((cx - r * .72, cy - r * .72, cx + r * .72, cy + r * .72), fill=(*hexrgb(c1), 255))
        d.ellipse((cx - r * .6, cy - r * .6, cx + r * .6, cy + r * .6), fill=(*hexrgb(c2), 255))
        if cat == "chinese":
            for i in range(26):
                a = rnd.random() * math.tau
                pts = [(cx + r * .5 * math.cos(a + t / 8) * rnd.uniform(.3, 1), cy + r * .5 * math.sin(a + t / 6) * rnd.uniform(.3, 1)) for t in range(6)]
                d.line(pts, fill=(*hexrgb(c3 if i % 3 else "#fde68a"), 255), width=7 * S, joint="curve")
        dots(d, rnd, cx, cy, r * .55, [c3, "#fde68a", "#16a34a"], 90, 9 * S)
        if cat == "street-food":  # pav on the side
            for k in (-1, 1):
                x = cx + k * r * .78
                d.rounded_rectangle((x - r * .2, cy + r * .45, x + r * .2, cy + r * .78), r * .08, fill=(*hexrgb("#e9b872"), 255))
    elif cat == "south-indian":
        d.polygon([(cx - r * .95, cy + r * .2), (cx + r * .95, cy - r * .35), (cx + r * .7, cy + r * .45)], fill=(*hexrgb(c1), 255))
        d.polygon([(cx - r * .9, cy + r * .22), (cx + r * .9, cy - r * .3), (cx + r * .68, cy + r * .35)], fill=(*hexrgb("#f4c95d"), 255))
        for k, col in ((-.45, "#fef3c7"), (0.05, "#dc2626"), (.5, "#15803d")):
            x = cx + k * r
            d.ellipse((x - r * .17, cy + r * .45, x + r * .17, cy + r * .75), fill=(*hexrgb(col), 255))
    elif cat == "biryani":
        d.ellipse((cx - r * .7, cy - r * .55, cx + r * .7, cy + r * .65), fill=(*hexrgb(c2), 255))
        dots(d, rnd, cx, cy, r * .6, [c1, "#fff7ed", "#fde68a"], 420, 5 * S)
        dots(d, rnd, cx, cy, r * .45, [c2, "#7c2d12"], 14, 22 * S)
        dots(d, rnd, cx, cy, r * .5, [c3], 25, 6 * S)
    elif cat == "thali":
        for k in range(6):
            a = k / 6 * math.tau
            x, y = cx + r * .55 * math.cos(a), cy + r * .55 * math.sin(a)
            d.ellipse((x - r * .22, y - r * .22, x + r * .22, y + r * .22), fill=(*hexrgb("#cbd5e1"), 255))
            d.ellipse((x - r * .17, y - r * .17, x + r * .17, y + r * .17), fill=(*hexrgb(rnd.choice([c1, c2, c3, "#f97316", "#fde68a"])), 255))
        d.ellipse((cx - r * .25, cy - r * .25, cx + r * .25, cy + r * .25), fill=(*hexrgb("#e7c48a"), 255))
    elif cat == "chai-coffee":
        d.ellipse((cx - r * .55, cy - r * .55, cx + r * .55, cy + r * .55), fill=(*hexrgb("#fafaf9"), 255))
        d.ellipse((cx - r * .45, cy - r * .45, cx + r * .45, cy + r * .45), fill=(*hexrgb(c2), 255))
        d.ellipse((cx - r * .28, cy - r * .3, cx + r * .1, cy - r * .05), fill=(*hexrgb(c1), 160))
        d.ellipse((cx + r * .5, cy - r * .15, cx + r * .82, cy + r * .15), outline=(*hexrgb("#fafaf9"), 255), width=16 * S)
    elif cat == "desserts":
        for k in range(5):
            a = k / 5 * math.tau
            x, y = cx + r * .4 * math.cos(a), cy + r * .4 * math.sin(a)
            for j in range(3):
                rr = r * (.24 - j * .07)
                d.ellipse((x - rr, y - rr, x + rr, y + rr), outline=(*hexrgb(c1 if j % 2 == 0 else c2), 255), width=12 * S)
    elif cat == "burgers":
        for i, (col, h) in enumerate([("#d97706", .3), ("#22c55e", .08), ("#7c2d12", .16), ("#ef4444", .07), ("#fbbf24", .26)]):
            y = cy + r * (.4 - i * .18)
            d.rounded_rectangle((cx - r * .62, y - r * h / 2, cx + r * .62, y + r * h / 2), r * .14, fill=(*hexrgb(col), 255))
        dots(d, rnd, cx, cy - r * .32, r * .35, ["#fef3c7"], 30, 4 * S)
    elif cat == "pizza-pasta":
        d.ellipse((cx - r * .78, cy - r * .78, cx + r * .78, cy + r * .78), fill=(*hexrgb("#d6a35c"), 255))
        d.ellipse((cx - r * .68, cy - r * .68, cx + r * .68, cy + r * .68), fill=(*hexrgb("#dc2626"), 255))
        dots(d, rnd, cx, cy, r * .6, ["#fffbeb", "#fef3c7"], 14, 30 * S)
        dots(d, rnd, cx, cy, r * .6, ["#16a34a"], 18, 10 * S)
        for k in range(4):
            a = k / 4 * math.pi
            d.line((cx - r * .78 * math.cos(a), cy - r * .78 * math.sin(a), cx + r * .78 * math.cos(a), cy + r * .78 * math.sin(a)), fill=(120, 60, 20, 180), width=4 * S)
    else:  # breakfast / snacks
        d.ellipse((cx - r * .66, cy - r * .5, cx + r * .66, cy + r * .6), fill=(*hexrgb(c1), 255))
        dots(d, rnd, cx, cy, r * .55, [c1, "#fef9c3", "#fde68a"], 300, 7 * S)
        dots(d, rnd, cx, cy, r * .5, ["#b45309", "#92400e"], 40, 9 * S)
        dots(d, rnd, cx, cy, r * .5, [c2], 30, 6 * S)


def make(key, cat):
    rnd = random.Random(key)
    bg, _, food_cols = PALETTES[cat]
    w, h = W * S, H * S
    img = gradient(w // 8, h // 8, *bg).resize((w, h), Image.BICUBIC)
    img = bokeh(img, rnd, food_cols[0]).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    cx, cy, r = w / 2 + rnd.randint(-30, 30) * S, h * .5, w * .36
    plate(d, cx, cy, r)
    food(d, rnd, cat, cx, cy, r, food_cols)
    img = img.filter(ImageFilter.GaussianBlur(0.6 * S)).resize((W, H), Image.LANCZOS).convert("RGB")
    d = ImageDraw.Draw(img, "RGBA")
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 22)
    d.rounded_rectangle((24, 24, 214, 62), 12, fill=(0, 0, 0, 110))
    d.text((40, 31), "DEMO IMAGE", font=font, fill=(255, 255, 255, 230))
    img.save(OUT / f"{key}.jpg", quality=86, optimize=True, progressive=True)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    src = SEED_DATA.read_text()
    dishes = re.findall(r"key: '([a-z0-9_]+)', name: '[^']+', category: '([a-z-]+)'", src)
    for key, cat in dishes:
        make(key, cat)
    print(f"wrote {len(dishes)} images to {OUT}")


if __name__ == "__main__":
    main()
