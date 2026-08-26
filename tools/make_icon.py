#!/usr/bin/env python3
"""Derive the shipped plugin icon from the source artwork.

The artwork in docs/icon-source/ is the record; this script is how it becomes the
PNG the plugin uses. Re-run it after replacing the source; do not hand-edit the
output.

Three things it fixes, all measured rather than assumed:

**The halo.** The supplied artwork has a soft glow and *no* fully opaque ink at all
-- 0% at alpha 255, 7% of the canvas sitting in the 1-63 haze band. Downscaled,
that averages into grey and the icon reads as a smudge rather than white lines. The
alpha is remapped so near-solid ink becomes solid and the halo goes, while genuine
antialiasing on the edges survives.

**Aspect.** The source is 1536x1024, not square. A non-square source scaled to a
square canvas loses width, so it is centred on a square first and nothing stretches.

**Colour.** ATAK tints toolbar icons, so whatever colour is in the file is discarded.
The output is pure white on full transparency; a filled background would render as a
solid block.

    ./make_icon.py
"""

import os
import sys

try:
    from PIL import Image
except ImportError:
    sys.exit("needs Pillow:  python3 -m pip install --user pillow")

HERE = os.path.dirname(os.path.abspath(__file__))
PLUGIN = os.path.dirname(HERE)
SRC = os.path.join(PLUGIN, "docs", "icon-source", "camdepot.png")
OUT = os.path.join(PLUGIN, "app", "src", "main", "res", "drawable", "ic_launcher.png")

SIZE = 256
# Below FLOOR is halo and is discarded; at or above SOLID is ink and is driven to
# opaque. Between the two is real edge antialiasing and is rescaled across the range.
FLOOR = 40
SOLID = 150
# Leave a little air so the icon is not flush to the canvas edge.
MARGIN = 0.04


def main():
    if not os.path.exists(SRC):
        sys.exit("no source artwork at " + SRC)

    im = Image.open(SRC).convert("RGBA")
    alpha = im.split()[3]

    alpha = alpha.point(lambda v: 0 if v < FLOOR else
                        (255 if v >= SOLID else
                         int(255 * (v - FLOOR) / float(SOLID - FLOOR))))

    # Crop to the ink so centring is based on the drawing, not the canvas.
    box = alpha.getbbox()
    if box:
        alpha = alpha.crop(box)

    # White ink, shaped only by alpha.
    white = Image.new("RGBA", alpha.size, (255, 255, 255, 255))
    white.putalpha(alpha)

    inner = int(SIZE * (1 - 2 * MARGIN))
    w, h = white.size
    scale = min(inner / float(w), inner / float(h))
    white = white.resize((max(1, int(w * scale)), max(1, int(h * scale))),
                         Image.LANCZOS)

    out = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    out.alpha_composite(white, ((SIZE - white.size[0]) // 2,
                                (SIZE - white.size[1]) // 2))
    out.save(OUT)

    hist = out.split()[3].histogram()
    ink = sum(hist[1:]) or 1
    print("wrote %s  %dx%d" % (OUT, SIZE, SIZE))
    print("  %.0f%% of ink fully opaque   %.0f%% of canvas is haze"
          % (100.0 * hist[255] / ink, 100.0 * sum(hist[1:64]) / sum(hist)))


if __name__ == "__main__":
    main()
