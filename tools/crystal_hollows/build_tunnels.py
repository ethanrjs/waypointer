#!/usr/bin/env python3
"""Builds the Crystal Hollows tunnel map shipped with the mod from terrain scans.

Scans come from the developer command /wpch scan on (config/waypointer/crystal-hollows-scans,
one .wpch file per lobby visit). Every lobby shares the Hollows' natural tunnels but has its own
random structures and player-mined holes, so the lobbies vote: a block is open only when most of
the lobbies that saw it found it open (a tie counts as rock). Repeat visits to one lobby count
once. Needs numpy. Run from the repository root:

    python3 tools/crystal_hollows/build_tunnels.py <scan folder>

Output: src/main/resources/assets/waypointer/crystal_hollows/tunnels.bin.gz. For every column it
stores one bit per 10-block band of height that has somewhere to stand (rock below, two open
blocks above), plus how many lobbies covered each chunk.
"""
import gzip
import pathlib
import re
import struct
import sys

import numpy as np

ROOT = pathlib.Path(__file__).resolve().parents[2]
OUT = ROOT / "src/main/resources/assets/waypointer/crystal_hollows/tunnels.bin.gz"
SCAN_MAGIC = 0x57504348   # "WPCH"
MAP_MAGIC = 0x57504354    # "WPCT"
BAND = 10
CHUNKS = 40
MIN_CHUNK = 12


def load(path):
    data = gzip.open(path, "rb").read()
    magic, version, minx, miny, minz, size, sizey = struct.unpack_from(">7i", data, 0)
    if magic != SCAN_MAGIC or version != 1:
        raise ValueError(f"{path.name} is not a Crystal Hollows scan")
    off = 28
    (nseen,) = struct.unpack_from(">i", data, off); off += 4
    seen_words = np.frombuffer(data, ">u8", nseen, off).astype("<u8"); off += 8 * nseen
    (nsolid,) = struct.unpack_from(">i", data, off); off += 4
    solid_words = np.frombuffer(data, ">u8", nsolid, off).astype("<u8")
    solid = np.unpackbits(solid_words.view(np.uint8), bitorder="little")[: sizey * size * size]
    seen = np.zeros(CHUNKS * CHUNKS, bool)
    if nseen:
        bits = np.unpackbits(seen_words.view(np.uint8), bitorder="little")[: CHUNKS * CHUNKS]
        seen[: len(bits)] = bits.astype(bool)
    return dict(minx=minx, miny=miny, minz=minz, size=size, sizey=sizey,
                solid=solid.reshape(sizey, size, size).astype(bool), seen=seen.reshape(CHUNKS, CHUNKS))


def column_mask(seen, minx, size):
    """Chunk coverage spread over block columns, [z, x]."""
    cells = np.repeat(np.repeat(seen, 16, axis=0), 16, axis=1)
    start = minx - MIN_CHUNK * 16
    return cells[start:start + size, start:start + size]


def despeckle(layer, radius=3, keep=12):
    """Drops specks: spots with fewer than {keep} standable columns within {radius} blocks,
    mostly bits of structures or mined holes that survived the vote."""
    padded = np.pad(layer.astype(np.int32), radius + 1)
    total = padded.cumsum(0).cumsum(1)
    w = 2 * radius + 1
    window = (total[w:, w:] - total[:-w, w:] - total[w:, :-w] + total[:-w, :-w])
    return layer & (window[: layer.shape[0], : layer.shape[1]] >= keep)


def main():
    folder = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else None
    if folder is None or not folder.is_dir():
        sys.exit("Usage: build_tunnels.py <folder of .wpch scans>")
    by_lobby = {}
    for path in sorted(folder.glob("*.wpch")):
        lobby = re.split(r"-(?:day\d+-)?\d{8}-\d{6}", path.stem)[0]
        scan = load(path)
        known = by_lobby.get(lobby)
        if known is None:
            by_lobby[lobby] = scan
            continue
        # One vote per lobby: later visits only fill chunks the earlier ones missed, since the
        # earlier view has had less mining.
        new = scan["seen"] & ~known["seen"]
        columns = column_mask(new, scan["minx"], scan["size"])
        known["solid"][:, columns] = scan["solid"][:, columns]
        known["seen"] |= new
    if not by_lobby:
        sys.exit(f"No scans in {folder}")
    scans = list(by_lobby.values())
    first = scans[0]
    minx, miny, size, sizey = first["minx"], first["miny"], first["size"], first["sizey"]

    votes = np.zeros((sizey, size, size), np.uint8)
    lobbies = np.zeros((size, size), np.uint8)
    for scan in scans:
        covered = column_mask(scan["seen"], minx, size)
        lobbies += covered
        votes += (scan["solid"] & covered[None]).astype(np.uint8)
    solid = votes * 2 >= np.maximum(lobbies, 1)[None]
    solid[:, lobbies == 0] = True

    standable = np.zeros_like(solid)
    standable[1:-1] = solid[:-2] & ~solid[1:-1] & ~solid[2:]
    bands = (sizey + BAND - 1) // BAND
    masks = np.zeros((size, size), np.uint16)
    for band in range(bands):
        layer = standable[band * BAND:(band + 1) * BAND].any(axis=0)
        masks |= despeckle(layer).astype(np.uint16) << band

    chunk_lobbies = np.zeros((CHUNKS, CHUNKS), np.uint8)
    for scan in scans:
        chunk_lobbies += scan["seen"].astype(np.uint8)

    header = struct.pack(">8i", MAP_MAGIC, 1, minx, miny, first["minz"], size, BAND, bands)
    body = masks.astype(">u2").tobytes() + chunk_lobbies.tobytes()
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT, "wb", compresslevel=9) as out:
        out.write(header + body)
    histogram = ", ".join(f"{n} lobbies: {int((chunk_lobbies == n).sum())}"
                          for n in range(1, int(chunk_lobbies.max()) + 1))
    print(f"Chunks by lobbies covering them: {histogram}")
    print(f"{len(scans)} lobbies ({', '.join(by_lobby)}); "
          f"{int((chunk_lobbies >= 3).sum())} of {CHUNKS * CHUNKS} chunks in 3+ lobbies, "
          f"{int((chunk_lobbies >= 1).sum())} seen; {OUT.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
