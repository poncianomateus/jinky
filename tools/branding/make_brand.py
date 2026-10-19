"""Generates every Jinky brand asset from one geometry definition.

Outputs (paths relative to the repo root):
  branding/jinky-mark.svg, branding/jinky-logo.svg      vector masters
  branding/png/mark-<size>.png                           app icon renders
  host/app/Assets/jinky.ico                              Windows icon (16-256 px)
  host/app/Assets/mark-256.png                           in-app logo
  tv/app/src/main/res/drawable/ic_jinky.xml              Android launcher icon (vector)
  tv/app/src/main/res/drawable/banner.xml                Android TV home banner (vector, 320x180)

Usage: python -I tools/branding/make_brand.py <repo-root>
Requires Pillow (PNG/ICO only).
"""
import math
import os
import sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."

BLUE = "#4C93FF"       # accent / icon tile
INK = "#06101F"        # glyph on the blue tile
GROUND = "#0A0E14"     # app background
TEXT = "#E8EDF5"       # wordmark on dark


# --- geometry -----------------------------------------------------------------------------
# A shape is ("stroke", [points...]) for a round-capped monoline, or ("dot", (x, y)) — both use
# the drawing's stroke width.

def arc(cx, cy, r, a0, a1, n=24):
    """Points on a circle, angles in degrees (0 = +x, 90 = +y, i.e. downwards on screen)."""
    return [(cx + r * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
             cy + r * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]


def quad(p0, p1, p2, n=16):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * p1[0] + t * t * p2[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * p1[1] + t * t * p2[1])
            for t in (i / n for i in range(n + 1))]


# The mark, in a 100x100 box: a lowercase "j" (same letterform as the wordmark), optically
# centred: stem at x 67, hook sweeping left around (50, 60), the tittle above the stem.
MARK_STROKE = 13.0
MARK = [
    ("stroke", [(67, 41), (67, 60)] + arc(50, 60, 17, 0, 180, n=32)[1:]),
    ("dot", (67, 24)),
]

# Wordmark "jinky": x-height 40 (y 20..60), ascender to 0, descender to 80, stroke 8.
WORD_STROKE = 10.0
WORD = [
    # j
    ("dot", (16, 6)),
    ("stroke", [(16, 20), (16, 66)] + quad((16, 66), (16, 80), (4, 80))[1:]),
    # i
    ("dot", (34, 6)),
    ("stroke", [(34, 20), (34, 60)]),
    # n
    ("stroke", [(52, 20), (52, 60)]),
    ("stroke", [(52, 34)] + arc(66, 34, 14, 180, 360)[1:] + [(80, 60)]),
    # k
    ("stroke", [(98, 0), (98, 60)]),
    ("stroke", [(122, 20), (99, 43)]),
    ("stroke", [(107, 36), (124, 60)]),
    # y
    ("stroke", [(138, 20), (153, 55)]),
    ("stroke", [(168, 20), (149, 70)] + quad((149, 70), (145, 80), (134, 80))[1:]),
]
WORD_BOX = (-5, -5, 177, 89)  # x0, y0, x1, y1 including stroke


# --- SVG / Android vector -----------------------------------------------------------------

def svg_paths(shapes, stroke, color, dx=0.0, dy=0.0, s=1.0):
    out = []
    for kind, data in shapes:
        if kind == "stroke":
            d = "M" + " L".join(f"{dx + x * s:.2f},{dy + y * s:.2f}" for x, y in data)
            out.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{stroke * s:.2f}" '
                       f'stroke-linecap="round" stroke-linejoin="round"/>')
        else:
            x, y = data
            out.append(f'<circle cx="{dx + x * s:.2f}" cy="{dy + y * s:.2f}" r="{stroke * s / 2:.2f}" fill="{color}"/>')
    return out


def vector_paths(shapes, stroke, color, dx=0.0, dy=0.0, s=1.0):
    out = []
    for kind, data in shapes:
        if kind == "stroke":
            d = "M" + " L".join(f"{dx + x * s:.2f},{dy + y * s:.2f}" for x, y in data)
            out.append(f'    <path android:pathData="{d}" android:strokeColor="{color}" '
                       f'android:strokeWidth="{stroke * s:.2f}" android:strokeLineCap="round" '
                       f'android:strokeLineJoin="round"/>')
        else:
            x, y = data
            r = stroke * s / 2
            cx, cy = dx + x * s, dy + y * s
            d = f"M{cx - r:.2f},{cy:.2f} a{r:.2f},{r:.2f} 0 1,0 {2 * r:.2f},0 a{r:.2f},{r:.2f} 0 1,0 {-2 * r:.2f},0"
            out.append(f'    <path android:pathData="{d}" android:fillColor="{color}"/>')
    return out


def rounded_rect_path(x, y, w, h, r):
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    return (f"M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} V{f(y + h - r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} H{f(x + r)} A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} "
            f"V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z")


def write(path, text):
    full = os.path.join(ROOT, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def make_svgs():
    mark = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100" width="512" height="512">',
            f'<rect width="100" height="100" rx="24" fill="{BLUE}"/>']
    mark += svg_paths(MARK, MARK_STROKE, INK)
    mark.append("</svg>\n")
    write("branding/jinky-mark.svg", "\n".join(mark))

    # Horizontal lockup on dark: tile 100 + gap 28 + wordmark scaled to the tile's height.
    s = 64 / 80  # wordmark ascender..descender (80) -> 64 units
    w = 100 + 28 + (WORD_BOX[2] - WORD_BOX[0]) * s
    logo = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="-20 -20 {w + 40:.0f} 140" width="{(w + 40) * 3:.0f}" height="420">',
            f'<rect x="-20" y="-20" width="{w + 40:.0f}" height="140" fill="{GROUND}"/>',
            f'<rect width="100" height="100" rx="24" fill="{BLUE}"/>']
    logo += svg_paths(MARK, MARK_STROKE, INK)
    logo += svg_paths(WORD, WORD_STROKE, TEXT, dx=128 - WORD_BOX[0] * s, dy=18, s=s)
    logo.append("</svg>\n")
    write("branding/jinky-logo.svg", "\n".join(logo))


def make_android():
    icon = ['<?xml version="1.0" encoding="utf-8"?>',
            '<!-- Generated by tools/branding/make_brand.py - edit the generator, not this file. -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
            f'    <path android:pathData="{rounded_rect_path(4, 4, 100, 100, 24)}" android:fillColor="{BLUE}"/>']
    icon += vector_paths(MARK, MARK_STROKE, INK, dx=4, dy=4)
    icon.append("</vector>\n")
    write("tv/app/src/main/res/drawable/ic_jinky.xml", "\n".join(icon))

    # TV banner 320x180: dark ground, tile on the left, wordmark to its right, optically centred.
    tile, gap = 84, 22
    s = 70 / 80
    word_w = (WORD_BOX[2] - WORD_BOX[0]) * s
    total = tile + gap + word_w
    x0 = (320 - total) / 2
    ty = (180 - tile) / 2
    banner = ['<?xml version="1.0" encoding="utf-8"?>',
              '<!-- Generated by tools/branding/make_brand.py - edit the generator, not this file. -->',
              '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
              '    android:width="320dp" android:height="180dp" android:viewportWidth="320" android:viewportHeight="180">',
              f'    <path android:pathData="M0,0 H320 V180 H0 Z" android:fillColor="{GROUND}"/>',
              f'    <path android:pathData="{rounded_rect_path(round(x0, 2), round(ty, 2), tile, tile, 20)}" android:fillColor="{BLUE}"/>']
    banner += vector_paths(MARK, MARK_STROKE, INK, dx=x0, dy=ty, s=tile / 100)
    # Wordmark box (0..80 tall) centred on the tile; +4*s nudges it to the x-height's optical centre.
    banner += vector_paths(WORD, WORD_STROKE, TEXT, dx=x0 + tile + gap - WORD_BOX[0] * s, dy=ty + (tile - 80 * s) / 2 + 4 * s, s=s)
    banner.append("</vector>\n")
    write("tv/app/src/main/res/drawable/banner.xml", "\n".join(banner))

    # Wordmark alone (light, for dark screens); viewport = its stroke box.
    w, h = WORD_BOX[2] - WORD_BOX[0], WORD_BOX[3] - WORD_BOX[1]
    word = ['<?xml version="1.0" encoding="utf-8"?>',
            '<!-- Generated by tools/branding/make_brand.py - edit the generator, not this file. -->',
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
            f'    android:width="{w}dp" android:height="{h}dp" android:viewportWidth="{w}" android:viewportHeight="{h}">']
    word += vector_paths(WORD, WORD_STROKE, TEXT, dx=-WORD_BOX[0], dy=-WORD_BOX[1])
    word.append("</vector>\n")
    write("tv/app/src/main/res/drawable/jinky_wordmark.xml", "\n".join(word))


# --- raster (Pillow) ----------------------------------------------------------------------

def render_mark(size):
    from PIL import Image, ImageDraw
    ss = 4  # supersampling for smooth edges
    big = size * ss
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    k = big / 100
    d.rounded_rectangle([0, 0, big - 1, big - 1], radius=24 * k, fill=BLUE)
    r = MARK_STROKE * k / 2
    for kind, data in MARK:
        pts = data if kind == "stroke" else [data]
        # Stamp the round brush along the path, densely enough to look continuous.
        for (x0, y0), (x1, y1) in zip(pts, pts[1:] or pts):
            dist = math.hypot(x1 - x0, y1 - y0) * k
            steps = max(1, int(dist / (r / 4)))
            for i in range(steps + 1):
                t = i / steps
                cx, cy = (x0 + (x1 - x0) * t) * k, (y0 + (y1 - y0) * t) * k
                d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=INK)
    return img.resize((size, size), Image.LANCZOS)


def make_raster():
    try:
        from PIL import Image  # noqa: F401
    except ImportError:
        print("Pillow not installed: skipping PNG/ICO")
        return
    os.makedirs(os.path.join(ROOT, "branding/png"), exist_ok=True)
    os.makedirs(os.path.join(ROOT, "host/app/Assets"), exist_ok=True)
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256, 512, 1024]
    renders = {s: render_mark(s) for s in sizes}
    for s, im in renders.items():
        im.save(os.path.join(ROOT, f"branding/png/mark-{s}.png"))
    renders[256].save(os.path.join(ROOT, "host/app/Assets/mark-256.png"))
    ico_sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    renders[256].save(os.path.join(ROOT, "host/app/Assets/jinky.ico"),
                      sizes=[(s, s) for s in ico_sizes],
                      append_images=[renders[s] for s in ico_sizes if s != 256])


if __name__ == "__main__":
    make_svgs()
    make_android()
    make_raster()
    print("brand assets written")
