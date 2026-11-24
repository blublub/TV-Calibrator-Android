#!/usr/bin/env python3
"""Makes "SDR 00b - Point finder (play from the start).mp4" in the same style as the other SDR pattern
videos: 3840x2160 24p H.264, BT.709 video levels, a centred window covering 10% of the screen on black,
and a caption at the bottom left. Timeline (CalibrationKit Patterns.finderSequence(hdr: false)):
10 s black, sync 30% / 100% / 30% (6 s each), then WHITE and greys 10%..100% in 5% steps, 5 s each."""
import os, subprocess, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 3840, 2160
WIN_W, WIN_H, WIN_X, WIN_Y = 1214, 684, 1312, 738           # measured from 'SDR 00 - Quick check'
# DejaVu Sans, like the other pattern videos (on a Mac: brew install --cask font-dejavu, then set DEJAVU_SANS)
FONT = ImageFont.truetype(os.environ.get("DEJAVU_SANS", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"), 56)
TITLE = "SDR 00b - Point finder (play from the start)"
OUT = sys.argv[1] if len(sys.argv) > 1 else TITLE + ".mp4"

def code(p):            # signal level 0-1 -> 8-bit video-level code value
    return int(round(16 + 219 * p))

segments = [("starting...", None, 10)]
segments += [("SYNC", 0.30, 6), ("SYNC", 1.0, 6), ("SYNC", 0.30, 6)]
patches = [("WHITE", 1.0)] + [(f"{p}% GREY", p / 100) for p in range(10, 101, 5)]
for i, (name, lvl) in enumerate(patches):
    segments.append((f"{i + 1}/{len(patches)}  {name}", lvl, 5))

def frame(caption, level):
    y = np.full((H, W), 16, np.uint8)
    if level is not None:
        y[WIN_Y:WIN_Y + WIN_H, WIN_X:WIN_X + WIN_W] = code(level)
    im = Image.new("L", (W, 200), 0)
    ImageDraw.Draw(im).text((120, 80), f"{TITLE}  |  {caption}", font=FONT, fill=255)
    a = np.asarray(im).astype(float) / 255
    band = y[1900:2100].astype(float)
    y[1900:2100] = np.round(np.maximum(band, 16 + a * 56)).astype(np.uint8)
    uv = np.full((H // 2, W // 2), 128, np.uint8)
    return y.tobytes() + uv.tobytes() + uv.tobytes()

parts = []
for i, (cap, lvl, dur) in enumerate(segments):
    raw, mp4 = f"seg{i:02d}.yuv", f"seg{i:02d}.mp4"
    open(raw, "wb").write(frame(cap, lvl))
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-stream_loop", "-1", "-f", "rawvideo", "-pix_fmt", "yuv420p",
                    "-s", f"{W}x{H}", "-framerate", "24", "-i", raw, "-frames:v", str(dur * 24),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "12", "-tune", "stillimage", "-bf", "0",
                    "-profile:v", "high", "-level", "5.1", "-pix_fmt", "yuv420p",
                    "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
                    mp4], check=True)
    os.remove(raw)
    parts.append(mp4)
open("list.txt", "w").write("".join(f"file '{p}'\n" for p in parts))
subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy",
                "-movflags", "+faststart", OUT], check=True)
for p in parts: os.remove(p)
os.remove("list.txt")
print("wrote", OUT, sum(s[2] for s in segments), "s")
