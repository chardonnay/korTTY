#!/usr/bin/env python3
"""
Remove N rows from the top of a PNG, in place.

Exists for web/sync-screenshots.sh, which has to strip the native window title
bar from the screenshots it copies out of app-docs/screenshots. macOS `sips` can
only crop centered (`-c` takes height/width but its `--cropOffset` is ignored in
practice), which would shave half the rows off the bottom as well, and the repo
deliberately has no ImageMagick or Pillow dependency — so this does the crop
with the standard library alone.

Handles non-interlaced 8-bit PNGs of every color type, which covers both a raw
screen capture (RGBA) and a pngquant-optimized one (palette). Anything else is
refused loudly rather than written back wrong.

Usage:
  scripts/crop-png-top.py <rows> <file.png> [more.png ...]
"""
from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
# Bytes per pixel at bit depth 8, by PNG color type.
BYTES_PER_PIXEL = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


def read_chunks(data: bytes) -> list[tuple[bytes, bytes]]:
    if not data.startswith(PNG_MAGIC):
        raise ValueError("not a PNG (bad magic)")
    chunks = []
    offset = len(PNG_MAGIC)
    while offset < len(data):
        (length,) = struct.unpack(">I", data[offset:offset + 4])
        kind = data[offset + 4:offset + 8]
        payload = data[offset + 8:offset + 8 + length]
        chunks.append((kind, payload))
        offset += 12 + length  # length + type + payload + CRC
        if kind == b"IEND":
            break
    return chunks


def write_png(path: Path, chunks: list[tuple[bytes, bytes]]) -> None:
    out = bytearray(PNG_MAGIC)
    for kind, payload in chunks:
        out += struct.pack(">I", len(payload))
        out += kind
        out += payload
        out += struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
    path.write_bytes(bytes(out))


def paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def unfilter(raw: bytes, height: int, stride: int, bpp: int) -> list[bytearray]:
    """Reverses the per-scanline PNG filters, returning raw pixel rows."""
    rows: list[bytearray] = []
    previous = bytearray(stride)
    offset = 0
    for y in range(height):
        filter_type = raw[offset]
        offset += 1
        line = bytearray(raw[offset:offset + stride])
        offset += stride
        if filter_type == 0:
            pass
        elif filter_type == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 0xFF
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 0xFF
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                upper_left = previous[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + paeth(left, previous[i], upper_left)) & 0xFF
        else:
            raise ValueError(f"unknown PNG filter type {filter_type} on row {y}")
        rows.append(line)
        previous = line
    return rows


def crop_top(path: Path, rows_to_remove: int) -> tuple[int, int]:
    chunks = read_chunks(path.read_bytes())
    header = next(payload for kind, payload in chunks if kind == b"IHDR")
    width, height, depth, color_type, _compression, _filter, interlace = \
        struct.unpack(">IIBBBBB", header)

    if depth != 8:
        raise ValueError(f"{path}: only bit depth 8 is supported, found {depth}")
    if interlace != 0:
        raise ValueError(f"{path}: interlaced PNGs are not supported")
    if color_type not in BYTES_PER_PIXEL:
        raise ValueError(f"{path}: unsupported color type {color_type}")
    if not 0 < rows_to_remove < height:
        raise ValueError(
            f"{path}: cannot remove {rows_to_remove} of {height} rows")

    bpp = BYTES_PER_PIXEL[color_type]
    stride = width * bpp
    raw = zlib.decompress(b"".join(p for k, p in chunks if k == b"IDAT"))
    expected = height * (stride + 1)
    if len(raw) != expected:
        raise ValueError(
            f"{path}: decompressed {len(raw)} bytes, expected {expected}")

    kept = unfilter(raw, height, stride, bpp)[rows_to_remove:]
    # Re-emit every row with filter 0. pngquant/oxipng re-encode afterwards and
    # pick their own filters, so there is nothing to gain from filtering here.
    body = bytearray()
    for line in kept:
        body.append(0)
        body += line

    new_height = height - rows_to_remove
    new_header = struct.pack(
        ">IIBBBBB", width, new_height, depth, color_type, 0, 0, 0)

    rebuilt: list[tuple[bytes, bytes]] = []
    for kind, payload in chunks:
        if kind == b"IHDR":
            rebuilt.append((kind, new_header))
        elif kind == b"IDAT":
            continue  # replaced in one piece below
        elif kind == b"IEND":
            rebuilt.append((b"IDAT", zlib.compress(bytes(body), 9)))
            rebuilt.append((kind, payload))
        else:
            rebuilt.append((kind, payload))

    write_png(path, rebuilt)
    return width, new_height


def main(argv: list[str]) -> int:
    if len(argv) < 3:
        print(__doc__.strip(), file=sys.stderr)
        return 2
    try:
        rows = int(argv[1])
    except ValueError:
        print(f"crop-png-top: '{argv[1]}' is not a row count", file=sys.stderr)
        return 2

    for name in argv[2:]:
        path = Path(name)
        try:
            width, height = crop_top(path, rows)
        except (OSError, ValueError, zlib.error, StopIteration) as error:
            print(f"crop-png-top: {error}", file=sys.stderr)
            return 1
        print(f"cropped {rows}px off the top of {path} -> {width}x{height}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
