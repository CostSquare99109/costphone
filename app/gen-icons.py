#!/usr/bin/env python3
"""Genera los mipmaps del icono CostPhone desde /sdcard/Download/costphone-logo.png."""
import os
from PIL import Image

PROJ = os.path.dirname(os.path.abspath(__file__))
SRC = "/sdcard/Download/costphone-logo.png"

src = Image.open(SRC).convert("RGBA")
w, h = src.size
s = max(w, h)
sq = Image.new("RGBA", (s, s), (0, 0, 0, 0))
sq.paste(src, ((s - w) // 2, (s - h) // 2))

DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

for name, px in DENS.items():
    out = os.path.join(PROJ, "res", "mipmap-" + name)
    os.makedirs(out, exist_ok=True)
    icon = sq.resize((px, px), Image.LANCZOS)
    icon.save(os.path.join(out, "ic_launcher.png"))
    icon.save(os.path.join(out, "ic_launcher_round.png"))
    c = round(px * 108 / 48)
    canvas = Image.new("RGBA", (c, c), (0, 0, 0, 0))
    art = sq.resize((round(c * 0.70), round(c * 0.70)), Image.LANCZOS)
    canvas.paste(art, ((c - art.width) // 2, (c - art.height) // 2), art)
    canvas.save(os.path.join(out, "ic_launcher_foreground.png"))
    print(f"  ✓ mipmap-{name}: {px}px legacy + {c}px foreground")

print("✅ Iconos generados")
