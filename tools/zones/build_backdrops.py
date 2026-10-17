#!/usr/bin/env python3
"""Builds the zone backdrops shown behind community routes from raw in-game screenshots.

Put one screenshot per zone in tools/zones/raw/, named after the zone id (see zones.txt), e.g.
raw/dwarven_mines.png. Any size works; F1 (no HUD) at a wide view looks best. Then run from the
repository root (needs Pillow):

    python3 tools/zones/build_backdrops.py

Each picture is cropped to 16:9, blurred, darkened and reduced to 320x180 with a small palette,
so it costs about 10-25 KB in the jar. A .mcmeta beside it turns on smooth filtering, which hides
the low resolution when the picture is stretched behind the details pane.
"""
import json
import pathlib
import re
import sys

try:
    from PIL import Image, ImageEnhance, ImageFilter
except ImportError:
    sys.exit("Pillow is needed: pip install Pillow")

ROOT = pathlib.Path(__file__).resolve().parents[2]
RAW = pathlib.Path(__file__).with_name("raw")
OUT = ROOT / "src/main/resources/assets/waypointer/textures/gui/zones"
WIDTH, HEIGHT = 320, 180
ID = re.compile(r"[a-z0-9_]+")
MCMETA = {"texture": {"blur": True, "clamp": True}}


def build(source: pathlib.Path, target: pathlib.Path) -> int:
    image = Image.open(source).convert("RGB")
    w, h = image.size
    want = WIDTH / HEIGHT
    if w / h > want:
        crop = int(h * want)
        image = image.crop(((w - crop) // 2, 0, (w - crop) // 2 + crop, h))
    else:
        crop = int(w / want)
        image = image.crop((0, (h - crop) // 2, w, (h - crop) // 2 + crop))
    image = image.resize((WIDTH * 2, HEIGHT * 2), Image.LANCZOS)
    image = image.filter(ImageFilter.GaussianBlur(6))
    image = image.resize((WIDTH, HEIGHT), Image.LANCZOS)
    image = ImageEnhance.Color(image).enhance(0.85)
    image = ImageEnhance.Brightness(image).enhance(0.62)
    image = image.quantize(colors=64, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.FLOYDSTEINBERG)
    image.save(target, optimize=True)
    target.with_name(target.name + ".mcmeta").write_text(json.dumps(MCMETA) + "\n")
    return target.stat().st_size


def main() -> None:
    raw = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else RAW
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else OUT
    sources = sorted(p for p in raw.glob("*") if p.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"})
    if not sources:
        sys.exit(f"No screenshots in {raw}. Name them after zone ids, e.g. dwarven_mines.png")
    out.mkdir(parents=True, exist_ok=True)
    total = 0
    for source in sources:
        zone = source.stem.lower()
        if not ID.fullmatch(zone):
            print(f"skip {source.name}: name it after a zone id")
            continue
        size = build(source, out / f"{zone}.png")
        total += size
        print(f"{zone:45} {size / 1024:6.1f} KB")
    print(f"{len(sources)} backdrops, {total / 1024:.0f} KB")


if __name__ == "__main__":
    main()
