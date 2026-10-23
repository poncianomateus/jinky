"""Generates every Jinky logo asset from the brand sources in branding/source.

Outputs (paths relative to the repo root):
  tv/app/src/main/res/drawable-nodpi/jinky_tile.png, jinky_tile_dark.png, jinky_icon.png
  tv/app/src/main/res/drawable-nodpi/jinky_wordmark_white.png
  tv/app/src/main/res/drawable-xhdpi/banner_jinky.png   Android TV home banner (320x180 dp)
  host/app/Assets/tile-256.png, wordmark-white.png       PC app logo
  host/app/Assets/jinky.ico                              Windows icon (16-256 px), also the installer's

Usage: python tools/branding/make_logos.py <repo-root>
Requires Pillow.
"""
import os
import sys

from PIL import Image

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
SRC = os.path.join(ROOT, "branding", "source")
RES = os.path.join(ROOT, "tv", "app", "src", "main", "res")
PC = os.path.join(ROOT, "host", "app", "Assets")


def load(name):
    return Image.open(os.path.join(SRC, name)).convert("RGBA")


tile = load("logo_tile_blue.png")
dark = load("logo_tile_dark.png")
white = load("wordmark_white.png")
wordmark = white.crop(white.getbbox())

# TV
nodpi = os.path.join(RES, "drawable-nodpi")
tile.resize((256, 256), Image.LANCZOS).save(os.path.join(nodpi, "jinky_tile.png"), optimize=True)
dark.resize((256, 256), Image.LANCZOS).save(os.path.join(nodpi, "jinky_tile_dark.png"), optimize=True)
tile.resize((192, 192), Image.LANCZOS).save(os.path.join(nodpi, "jinky_icon.png"), optimize=True)
w = wordmark.copy()
w.thumbnail((480, 480), Image.LANCZOS)
w.save(os.path.join(nodpi, "jinky_wordmark_white.png"), optimize=True)

banner = Image.new("RGBA", (640, 360), (7, 9, 13, 255))
banner.alpha_composite(tile.resize((176, 176), Image.LANCZOS), (58, 92))
w = wordmark.resize((300, int(wordmark.height * 300 / wordmark.width)), Image.LANCZOS)
banner.alpha_composite(w, (270, 180 - w.height // 2 + 6))
banner.convert("RGB").save(os.path.join(RES, "drawable-xhdpi", "banner_jinky.png"), optimize=True)

# PC
tile.resize((256, 256), Image.LANCZOS).save(os.path.join(PC, "tile-256.png"), optimize=True)
wordmark.resize((int(wordmark.width * 120 / wordmark.height), 120), Image.LANCZOS).save(
    os.path.join(PC, "wordmark-white.png"), optimize=True)
tile.save(os.path.join(PC, "jinky.ico"), sizes=[(s, s) for s in (16, 20, 24, 32, 40, 48, 64, 128, 256)])
print("ok")

# PC fonts: WPF can't pick weights from a variable font, so cut static ones from the TV's.
try:
    from fontTools.ttLib import TTFont
    from fontTools.varLib import instancer
except ImportError:
    print("fonts skipped (pip install fonttools)")
    sys.exit(0)

NAMES = {400: "Regular", 500: "Medium", 600: "SemiBold", 700: "Bold", 800: "ExtraBold"}
FONTS = os.path.join(ROOT, "tv", "app", "src", "main", "assets", "fonts")
for family, file, weights in (("Montserrat", "Montserrat.ttf", (500, 600, 700, 800)),
                              ("JetBrains Mono", "JetBrainsMono.ttf", (500, 700))):
    for wght in weights:
        font = instancer.instantiateVariableFont(TTFont(os.path.join(FONTS, file)), {"wght": wght})
        style = NAMES[wght]
        name = font["name"]
        for nid in (1, 2, 3, 4, 6, 16, 17):
            name.removeNames(nameID=nid)
        ps = family.replace(" ", "") + "-" + style
        for nid, text in ((1, family), (2, style), (3, ps), (4, f"{family} {style}"), (6, ps), (16, family), (17, style)):
            name.setName(text, nid, 3, 1, 0x409)
        font["OS/2"].usWeightClass = wght
        font.save(os.path.join(PC, "Fonts", ps + ".ttf"))
for lic in ("OFL-Montserrat.txt", "OFL-JetBrainsMono.txt"):
    with open(os.path.join(FONTS, lic), "rb") as a, open(os.path.join(PC, "Fonts", lic), "wb") as b:
        b.write(a.read())
print("fonts ok")
