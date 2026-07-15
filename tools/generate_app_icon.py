#!/usr/bin/env python3
"""Regenerate the korTTY program icon (PNG + macOS .icns + Windows .ico).

The icon is a crisp, high-resolution re-render of the neon korTTY logo — the
cyan ``>`` prompt chevron with the ``_`` cursor bar, and the purple→green
"brain" network graph, on the dark grid background. The "korTTY" wordmark is
intentionally omitted: this is the program/dock/taskbar icon, not the in-app
logo (which lives in ``kortty_icon`` siblings and ``kortty_logo.png``).

Source of the artwork geometry
------------------------------
The node/edge graph, colors, stroke widths, chevron and cursor-bar geometry
below were extracted pixel-exactly from a clean, fully-lit frame of the
canonical logo animation ``korTTY_logo_ai.mp4`` (720p). That video is the
source of truth for the logo look — NOT the older bundled icon artwork, which
is a different, earlier brain graph. The extracted values are baked in as the
``NODES``/``EDGES``/``POLY_EDGES``/``CHEV``/``BAR`` literals, so this script is
self-contained and needs neither the video nor any network access to run.

To re-derive the geometry after a logo redesign: grab a glint-free frame
(``ffmpeg -i korTTY_logo_ai.mp4 -vf "select='not(mod(n,20))'" -vsync 0 f%02d.png``),
detect nodes as distance-transform local maxima of the lit mask (luminance>85
for topology; use max-RGB-channel>232 for stroke-core widths — luminance alone
fails on the magenta strokes), trace edges by sampling segments that lie
outside all node discs, sample node colors from the frame, then replace the
literals here.

Rendering notes
---------------
* Rendered at ``N*SS`` (3072px) and LANCZOS-downscaled to 1024 for anti-aliasing.
* Neon bloom is a multi-scale Gaussian sum, suppressed *on* the strokes
  (``bg*(1-a) + glow*(1-a) + emission``) so the stroke cores keep the exact
  colors sampled from the video.
* Deterministic (``random.seed(7)``): re-running reproduces the same PNG.

Requirements
------------
Pillow, numpy, scipy. This repo has them in the docs venv, so the simplest
invocation is::

    .venv-docs/bin/python tools/generate_app_icon.py

Outputs (default: ``src/main/resources/icon/`` next to build.gradle.kts, which
jpackage consumes directly — see ``getMacIcon``/``getWindowsIcon``):
``kortty_icon.png`` (1024²), ``kortty_icon.icns`` (16–1024px iconset), and
``kortty_icon.ico`` (16–256px).  The
macOS icon is inset into a transparent 1024px canvas and clipped to a squircle;
unmasked edge-to-edge artwork is rendered as an oversized square in the Dock.
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

# ---------------------------------------------------------------- render config
N = 1024          # final icon size (px)
SS = 3            # supersampling factor for anti-aliasing
C = N * SS
SCALE = 1.66      # how much the mark fills the square canvas
SEED = 7          # deterministic background sparkles
MACOS_TILE_SIZE = 824          # Apple icon-grid body inside the 1024px canvas
MACOS_SQUIRCLE_EXPONENT = 5.0  # continuous-corner rounded-square silhouette

# ============ geometry extracted from korTTY_logo_ai.mp4 (clean frame) =========
# (x, y, radius, (r,g,b)) in frame space; radii are stroke cores, glow re-added.
NODES = [
    (713, 116, 13.3, (67, 254, 163)),
    (572, 121, 13.0, (227, 54, 255)),
    (635, 171, 13.0, (166, 141, 253)),
    (822, 187, 13.4, (46, 254, 130)),
    (479, 196, 13.0, (240, 33, 255)),
    (746, 198, 4.4, (56, 254, 139)),
    (702, 205, 13.3, (72, 254, 176)),
    (562, 215, 12.7, (222, 52, 255)),
    (654, 246, 13.0, (141, 168, 245)),
    (528, 263, 12.5, (234, 31, 255)),
    (772, 265, 13.7, (48, 254, 142)),
    (846, 288, 13.2, (39, 254, 126)),
    (685, 293, 13.1, (91, 231, 205)),
    (748, 318, 4.5, (52, 255, 138)),
    (594, 340, 13.0, (217, 54, 255)),
    (812, 345, 13.2, (47, 254, 137)),
    (671, 358, 12.8, (150, 161, 253)),
    (724, 370, 13.0, (51, 254, 151)),
    (742, 436, 13.4, (49, 254, 139)),
]
WSC = 0.75  # scales measured (luminance>85) widths down to the stroke core
EDGES = [
    (0, 1, 6.0), (0, 2, 6.3), (0, 3, 6.3), (0, 5, 6.3), (0, 6, 7.2),
    (1, 2, 5.7), (1, 4, 4.5), (1, 7, 6.0), (2, 6, 6.3), (2, 7, 5.7),
    (2, 8, 6.3), (3, 5, 6.0), (3, 10, 4.0), (3, 11, 6.0), (4, 7, 6.0),
    (4, 9, 5.7), (5, 6, 6.0), (5, 10, 6.3), (6, 8, 5.7), (6, 10, 7.2),
    (7, 8, 6.0), (7, 9, 5.7), (7, 14, 6.0), (8, 9, 6.0), (8, 10, 6.0),
    (8, 12, 6.3), (8, 14, 6.3), (9, 14, 5.7), (10, 11, 5.8), (10, 12, 4.5),
    (10, 13, 5.7), (10, 15, 5.7), (11, 15, 5.7), (12, 13, 6.3), (12, 14, 6.3),
    (12, 16, 6.3), (12, 17, 6.3), (13, 15, 6.3), (13, 17, 6.3), (14, 16, 6.0),
    (16, 18, 7.2), (17, 18, 6.0),
]
POLY_EDGES = [(15, 17, 6.0, [(775, 383)])]   # bent edge with an elbow (no node there)

CHEV = [(415.0, 242.0), (512.0, 341.0), (415.0, 440.0)]   # ">" centerline
CHEV_W = 29.0
CYAN = (19 / 255, 255 / 255, 255 / 255)
BAR = (517.0, 424.0, 641.0, 450.0)   # "_" cursor: sharp rectangle
BARC_L = (19 / 255, 255 / 255, 255 / 255)
BARC_R = (166 / 255, 135 / 255, 253 / 255)

ICO_SIZES = [(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)]


def _lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def _col255(c):
    return tuple(int(max(0, min(1, v)) * 255) for v in c)


def render_master() -> Image.Image:
    """Render the 1024×1024 RGBA icon master (deterministic)."""
    from scipy.ndimage import gaussian_filter

    random.seed(SEED)

    pts = [(x, y) for (x, y, _, _) in NODES] + CHEV + [(BAR[0], BAR[1]), (BAR[2], BAR[3])]
    cx = (min(p[0] for p in pts) + max(p[0] for p in pts)) / 2
    cy = (min(p[1] for p in pts) + max(p[1] for p in pts)) / 2

    def T(x, y):
        return ((x - cx) * SCALE + 512) * SS, ((y - cy) * SCALE + 512) * SS

    def Tr(r):
        return r * SCALE * SS

    # ---- background: dark diagonal gradient, teal glow, faint grid, sparkles ----
    yy, xx = np.mgrid[0:C, 0:C].astype(np.float32)
    u = xx / C
    v = yy / C
    tl = np.array([0.008, 0.014, 0.018])
    br = np.array([0.020, 0.120, 0.104])
    g = np.clip(u * 0.5 + v * 0.6, 0, 1)
    bg = tl[None, None, :] * (1 - g[..., None]) + br[None, None, :] * g[..., None]

    def glow_blob(cxr, cyr, rad, col, amp):
        d2 = (xx - cxr * C) ** 2 + (yy - cyr * C) ** 2
        return amp * np.exp(-d2 / (2 * (rad * C) ** 2))[..., None] * np.array(col)[None, None, :]

    bg = bg + glow_blob(0.54, 0.62, 0.28, (0.015, 0.13, 0.11), 1.0)
    bg = bg + glow_blob(0.50, 0.48, 0.30, (0.05, 0.04, 0.12), 0.40)
    bg = np.clip(bg, 0, 1).astype(np.float32)
    grid_step = int(0.107 * C)
    lw = max(1, int(SS))
    gridmask = ((xx.astype(int) % grid_step) < lw) | ((yy.astype(int) % grid_step) < lw)
    bg[gridmask] += np.array([0.010, 0.035, 0.032], np.float32)
    stardraw = Image.new("L", (C, C), 0)
    sd = ImageDraw.Draw(stardraw)
    for _ in range(46):
        sx = random.randint(0, C - 1)
        sy = random.randint(0, C - 1)
        rr = random.choice([1, 1, 2]) * SS
        sd.ellipse([sx - rr, sy - rr, sx + rr, sy + rr], fill=random.randint(30, 90))
    star = np.asarray(stardraw).astype(np.float32) / 255.0
    bg = np.clip(bg + star[..., None] * np.array([0.10, 0.16, 0.16], np.float32), 0, 1)

    # ---- emission layer: neon strokes in their exact sampled colors ----
    em = Image.new("RGB", (C, C), (0, 0, 0))
    ed = ImageDraw.Draw(em)

    def ncol(i):
        r, g_, b = NODES[i][3]
        return (r / 255, g_ / 255, b / 255)

    def draw_gradient_path(path, c1, c2, w):
        lengths = [0.0]
        for (a1, b1), (a2, b2) in zip(path[:-1], path[1:]):
            lengths.append(lengths[-1] + ((a2 - a1) ** 2 + (b2 - b1) ** 2) ** 0.5)
        total = lengths[-1]
        ww = max(1, int(round(Tr(w))))
        for si, ((a1, b1), (a2, b2)) in enumerate(zip(path[:-1], path[1:])):
            seglen = lengths[si + 1] - lengths[si]
            steps = max(6, int(seglen / 4))
            for s in range(steps):
                t0, t1 = s / steps, (s + 1) / steps
                gt = (lengths[si] + seglen * (t0 + t1) / 2) / total
                p1 = T(a1 + (a2 - a1) * t0, b1 + (b2 - b1) * t0)
                p2 = T(a1 + (a2 - a1) * t1, b1 + (b2 - b1) * t1)
                ed.line([p1, p2], fill=_col255(_lerp(c1, c2, gt)), width=ww)
            if si < len(path) - 2:  # round the interior elbow so segments join cleanly
                jx, jy = T(*path[si + 1])
                hr = ww / 2
                ed.ellipse([jx - hr, jy - hr, jx + hr, jy + hr],
                           fill=_col255(_lerp(c1, c2, lengths[si + 1] / total)))

    for i, j, w in EDGES:
        x1, y1, _, _ = NODES[i]
        x2, y2, _, _ = NODES[j]
        draw_gradient_path([(x1, y1), (x2, y2)], ncol(i), ncol(j), w * WSC)
    for i, j, w, way in POLY_EDGES:
        x1, y1, _, _ = NODES[i]
        x2, y2, _, _ = NODES[j]
        draw_gradient_path([(x1, y1)] + way + [(x2, y2)], ncol(i), ncol(j), w * WSC)

    for (x, y, r, c) in NODES:
        cc = (c[0] / 255, c[1] / 255, c[2] / 255)
        px, py = T(x, y)
        rad = Tr(r)
        ed.ellipse([px - rad, py - rad, px + rad, py + rad], fill=_col255(cc))
        hr = rad * 0.40
        ed.ellipse([px - hr, py - hr, px + hr, py + hr], fill=_col255(_lerp(cc, (1, 1, 1), 0.12)))

    # chevron: butt ends + mitered apex
    A, B_, Cp = (np.array(p, float) for p in CHEV)
    h = CHEV_W / 2
    dA = (B_ - A) / np.linalg.norm(B_ - A)
    dB = (Cp - B_) / np.linalg.norm(Cp - B_)
    nA = np.array([dA[1], -dA[0]])
    nB = np.array([dB[1], -dB[0]])
    mid = nA + nB
    mid = mid / np.linalg.norm(mid)
    ext = h / float(np.dot(mid, nA))
    poly = [tuple(A + nA * h), tuple(B_ + mid * ext), tuple(Cp + nB * h),
            tuple(Cp - nB * h), tuple(B_ - mid * ext), tuple(A - nA * h)]
    ed.polygon([T(px, py) for (px, py) in poly], fill=_col255(CYAN))

    em_np = np.asarray(em).astype(np.float32) / 255.0

    # cursor bar: sharp rectangle with cyan→lavender horizontal gradient
    bx0, by0 = T(BAR[0], BAR[1])
    bx1, by1 = T(BAR[2], BAR[3])
    inside = (xx >= bx0) & (xx <= bx1) & (yy >= by0) & (yy <= by1)
    tg = np.clip((xx - bx0) / max(bx1 - bx0, 1), 0, 1)
    barcol = np.stack([BARC_L[i] + (BARC_R[i] - BARC_L[i]) * tg for i in range(3)], -1).astype(np.float32)
    em_np = np.where(inside[..., None], np.maximum(em_np, barcol), em_np)

    # ---- multi-scale bloom, suppressed on strokes so cores stay exact ----
    def blur(arr, sig):
        return np.stack([gaussian_filter(arr[..., c], sig) for c in range(3)], -1)

    glow = (0.52 * blur(em_np, 2.2 * SS)
            + 0.40 * blur(em_np, 7.0 * SS)
            + 0.26 * blur(em_np, 18.0 * SS)
            + 0.10 * blur(em_np, 40.0 * SS))
    alpha = em_np.max(axis=2, keepdims=True)
    result = np.clip(bg * (1 - alpha) + glow * 0.95 * (1 - alpha) + em_np, 0, 1)

    img = Image.fromarray((result * 255).astype(np.uint8), "RGB")
    return img.resize((N, N), Image.LANCZOS).convert("RGBA")


def render_macos_master(master: Image.Image) -> Image.Image:
    """Inset and mask the artwork to the standard macOS Dock-icon silhouette."""
    tile = master.resize((MACOS_TILE_SIZE, MACOS_TILE_SIZE), Image.LANCZOS)

    # A superellipse follows the continuous-corner macOS app-icon shape more closely
    # than Pillow's circular-corner rounded_rectangle primitive. Render the mask at
    # the artwork's supersampling factor so the transparent edge stays smooth.
    mask_size = MACOS_TILE_SIZE * SS
    coords = (np.arange(mask_size, dtype=np.float32) + 0.5) / mask_size * 2.0 - 1.0
    xx, yy = np.meshgrid(coords, coords)
    mask = ((np.abs(xx) ** MACOS_SQUIRCLE_EXPONENT
             + np.abs(yy) ** MACOS_SQUIRCLE_EXPONENT) <= 1.0).astype(np.uint8) * 255
    mask_image = Image.fromarray(mask, "L").resize((MACOS_TILE_SIZE, MACOS_TILE_SIZE), Image.LANCZOS)
    tile.putalpha(mask_image)

    canvas = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    offset = (N - MACOS_TILE_SIZE) // 2
    canvas.alpha_composite(tile, (offset, offset))
    return canvas


def validate_macos_master(master: Image.Image) -> None:
    """Guard the transparent padding that keeps the Dock icon optically balanced."""
    if master.size != (N, N) or master.mode != "RGBA":
        raise ValueError(f"macOS icon master must be {N}x{N} RGBA, got {master.size} {master.mode}")
    alpha = master.getchannel("A")
    offset = (N - MACOS_TILE_SIZE) // 2
    expected_bbox = (offset, offset, offset + MACOS_TILE_SIZE, offset + MACOS_TILE_SIZE)
    if alpha.getbbox() != expected_bbox:
        raise ValueError(f"macOS icon alpha bounds must be {expected_bbox}, got {alpha.getbbox()}")
    if any(alpha.getpixel(point) for point in ((0, 0), (N - 1, 0), (0, N - 1), (N - 1, N - 1))):
        raise ValueError("macOS icon canvas corners must be transparent")


def write_icns(master: Image.Image, out: Path) -> bool:
    """Build a multi-resolution .icns with Pillow's platform-independent writer."""
    macos_master = render_macos_master(master)
    validate_macos_master(macos_master)
    macos_master.save(out, format="ICNS")
    return True


def main() -> int:
    repo_root = Path(__file__).resolve().parent.parent
    default_out = repo_root / "src" / "main" / "resources" / "icon"
    ap = argparse.ArgumentParser(description="Regenerate the korTTY program icon.")
    ap.add_argument("--out-dir", type=Path, default=default_out,
                    help=f"where to write kortty_icon.{{png,icns,ico}} (default: {default_out})")
    ap.add_argument("--png-only", action="store_true", help="write only the 1024px PNG master")
    ap.add_argument("--macos-only", action="store_true",
                    help="rebuild only the .icns from the existing 1024px PNG master")
    args = ap.parse_args()
    args.out_dir.mkdir(parents=True, exist_ok=True)

    png = args.out_dir / "kortty_icon.png"
    if args.macos_only:
        if not png.exists():
            ap.error(f"--macos-only requires the existing master: {png}")
        icns = args.out_dir / "kortty_icon.icns"
        if write_icns(Image.open(png).convert("RGBA"), icns):
            print(f"wrote {icns}  (iconset 16-1024px)")
            return 0
        print("SKIP .icns: Pillow ICNS writer unavailable", file=sys.stderr)
        return 1

    master = render_master()
    master.save(png)
    print(f"wrote {png}  ({master.size[0]}x{master.size[1]})")

    if args.png_only:
        return 0

    ico = args.out_dir / "kortty_icon.ico"
    master.save(ico, format="ICO", sizes=ICO_SIZES)
    print(f"wrote {ico}  (sizes {', '.join(f'{w}' for w, _ in ICO_SIZES)})")

    icns = args.out_dir / "kortty_icon.icns"
    if write_icns(master, icns):
        print(f"wrote {icns}  (iconset 16-1024px)")
    else:
        print("SKIP .icns: Pillow ICNS writer unavailable", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
