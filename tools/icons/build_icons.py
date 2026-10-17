#!/usr/bin/env python3
"""Builds Waypointer's GUI icon atlas from the hand-drawn grids in icons.txt.

Every icon is a grid of up to 16x16 characters, centred in its 16x16 cell:

    .  transparent
    #  white          (the tint colour at full strength)
    +  light shade    (78% of the tint)
    =  mid shade      (55% of the tint)
    -  dark shade     (35% of the tint)

The screens tint icons when they draw them (text colour, accent on hover, muted when
disabled, a folder's own colour), so the atlas stays greyscale. The order of icons in
icons.txt is the order of Icons.Icon in the client code; tests check the two agree.

Run from the repository root:  python3 tools/icons/build_icons.py
"""
import pathlib
import struct
import sys
import zlib

ROOT = pathlib.Path(__file__).resolve().parents[2]
SOURCE = pathlib.Path(__file__).with_name("icons.txt")
OUTPUT = ROOT / "src/main/resources/assets/waypointer/textures/gui/icons.png"
CELL = 16
COLUMNS = 16
SHADES = {".": None, "#": 255, "+": 200, "=": 140, "-": 90}


def parse(text):
    icons = []
    name, rows = None, []
    for line in text.splitlines():
        line = line.rstrip()
        if not line or line.startswith(";"):
            continue
        if line.startswith("@"):
            if name is not None:
                icons.append((name, rows))
            name, rows = line[1:].strip(), []
            continue
        rows.append(line)
    if name is not None:
        icons.append((name, rows))
    for name, rows in icons:
        if not rows or len(rows) > CELL or any(len(r) > CELL for r in rows):
            sys.exit(f"{name}: icons must be at most {CELL}x{CELL}")
        bad = {c for r in rows for c in r} - SHADES.keys()
        if bad:
            sys.exit(f"{name}: unknown characters {sorted(bad)}")
    return icons


def png(width, height, pixels):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            raw.extend(pixels[y * width + x])

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def main():
    icons = parse(SOURCE.read_text(encoding="utf-8"))
    rows = (len(icons) + COLUMNS - 1) // COLUMNS
    width, height = COLUMNS * CELL, rows * CELL
    pixels = [(0, 0, 0, 0)] * (width * height)
    for index, (_, grid) in enumerate(icons):
        grid_width = max(len(row) for row in grid)
        ox = (index % COLUMNS) * CELL + (CELL - grid_width) // 2
        oy = (index // COLUMNS) * CELL + (CELL - len(grid)) // 2
        for y, row in enumerate(grid):
            for x, c in enumerate(row):
                shade = SHADES[c]
                if shade is not None:
                    pixels[(oy + y) * width + ox + x] = (shade, shade, shade, 255)
    OUTPUT.write_bytes(png(width, height, pixels))
    names = pathlib.Path(__file__).with_name("icon-names.txt")
    names.write_text("\n".join(name for name, _ in icons) + "\n", encoding="utf-8")
    print(f"{len(icons)} icons -> {OUTPUT.relative_to(ROOT)} ({width}x{height})")


if __name__ == "__main__":
    main()
