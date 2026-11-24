#!/usr/bin/env python3
"""
Makes the TV Calibrator pattern videos for YouTube: 3840x2160, 24p, H.264, BT.709 video levels, no audio.

Each video is:
  1. A branded intro (teal, Slidebean logo, a QR code to the app, three short instructions).
  2. 10 s of black (time to tap Measure), with a small countdown at the bottom.
  3. The sync flashes the app listens for: 30% grey, 100% white, 30% grey (6 s each).
  4. The patches, each in a centred window (10% of the screen) on black, exactly as the app's
     timeline expects (CalibrationKit Patterns.swift). A caption names each patch for 3 s, then fades
     out, so nothing stays on screen long enough to mark an OLED.
  5. A short outro.

The app finds the video by its sync flashes (bright, a 6 s neutral grey, bright again). The intro is
teal, not neutral grey, so it can't be mistaken for them.

Usage (from the repo folder):
    python3 tools/make_pattern_videos.py sdr quick            # SDR 00 - Quick check
    python3 tools/make_pattern_videos.py sdr quick --qr-url https://apps.apple.com/app/id...
    python3 tools/make_pattern_videos.py sdr quick --preview  # stills of key moments, no video

Fonts: Inter (https://rsms.me/inter). Set INTER_DIR if it isn't in /usr/share/fonts/opentype/inter.
"""
import argparse
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

W, H, FPS = 3840, 2160, 24
WIN_W, WIN_H, WIN_X, WIN_Y = 1214, 684, 1312, 738      # the measured window, as in every pattern video
LEAD, SYNC_HOLD, HOLD = 10, 6, 7                        # Patterns.lead / syncHold / hold
HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.join(HERE, "..")
INTER = os.environ.get("INTER_DIR", "/usr/share/fonts/opentype/inter")
LOGO = os.path.join(REPO, "TV Calibrator", "Assets.xcassets", "SlidebeanLogo.imageset", "SlidebeanLogo.png")

# Brand colours (the app's design system).
BG = (0x05, 0x17, 0x1A)
SURFACE = (0x0D, 0x3B, 0x42)
HERO = (0x13, 0x4D, 0x54)
TEAL = (0x40, 0xB8, 0xB0)
YELLOW = (0xFF, 0xD1, 0x33)
WHITE = (255, 255, 255)
GREY = (153, 162, 164)
MODE_TINT = {"sdr": TEAL, "hdr10": YELLOW}


def font(name, size):
    return ImageFont.truetype(os.path.join(INTER, name), size)


# ---------------------------------------------------------------------------------------------- signal

def rgb_to_ycbcr(r, g, b):
    """Non-linear R'G'B' 0-1 -> BT.709 limited-range 8-bit codes (floats)."""
    y = 0.2126 * r + 0.7152 * g + 0.0722 * b
    return 16 + 219 * y, 128 + 224 * (b - y) / 1.8556, 128 + 224 * (r - y) / 1.5748


def to_yuv420(img):
    """An RGB uint8 image (H, W, 3) -> yuv420p bytes, BT.709 limited range."""
    a = img.astype(np.float32) / 255
    y, cb, cr = rgb_to_ycbcr(a[..., 0], a[..., 1], a[..., 2])
    sub = lambda c: c.reshape(H // 2, 2, W // 2, 2).mean(axis=(1, 3))
    q = lambda c: np.clip(np.round(c), 0, 255).astype(np.uint8)
    return q(y).tobytes() + q(sub(cb)).tobytes() + q(sub(cr)).tobytes()


class Plane:
    """A pattern frame kept as Y, Cb, Cr planes, so the measured window gets exact codes."""

    def __init__(self, rgb=None):
        self.y = np.full((H, W), 16, np.uint8)
        self.cb = np.full((H // 2, W // 2), 128, np.uint8)
        self.cr = np.full((H // 2, W // 2), 128, np.uint8)
        if rgb is not None:
            y, cb, cr = (int(round(v)) for v in rgb_to_ycbcr(*rgb))
            self.y[WIN_Y:WIN_Y + WIN_H, WIN_X:WIN_X + WIN_W] = y
            self.cb[WIN_Y // 2:(WIN_Y + WIN_H) // 2, WIN_X // 2:(WIN_X + WIN_W) // 2] = cb
            self.cr[WIN_Y // 2:(WIN_Y + WIN_H) // 2, WIN_X // 2:(WIN_X + WIN_W) // 2] = cr

    def with_text(self, mask, peak):
        """Light grey text (mask 0-1, H x W) over the black, at most `peak` (8-bit code)."""
        y = self.y.astype(np.float32)
        y = np.maximum(y, 16 + mask * (peak - 16))
        return np.round(y).astype(np.uint8).tobytes() + self.cb.tobytes() + self.cr.tobytes()

    def bytes(self):
        return self.y.tobytes() + self.cb.tobytes() + self.cr.tobytes()


def text_mask(lines, size=56, x=120, y=1980, weight="Inter-Medium.otf"):
    im = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(im)
    f = font(weight, size)
    for i, t in enumerate(lines):
        d.text((x, y + i * int(size * 1.4)), t, font=f, fill=255)
    return np.asarray(im).astype(np.float32) / 255


# ---------------------------------------------------------------------------------------------- timelines

def sdr_quick():
    """Patterns.quickSequence(hdr: false): white, greys 20-100%, the six 75% colours, 7 s each."""
    pats = [("W0", "100% white", (1, 1, 1))]
    pats += [(f"G{p}", f"{p}% grey", (p / 100,) * 3) for p in (20, 40, 60, 80, 100)]
    for name, c in [("Red", (1, 0, 0)), ("Green", (0, 1, 0)), ("Blue", (0, 0, 1)),
                    ("Cyan", (0, 1, 1)), ("Magenta", (1, 0, 1)), ("Yellow", (1, 1, 0))]:
        pats.append((name.upper(), f"{name} 75%", tuple(0.75 * v for v in c)))
    return [(pid, name, rgb, HOLD) for pid, name, rgb in pats]


VIDEOS = {
    ("sdr", "quick"): dict(title="SDR 00 - Quick check (play from the start)", label="Quick check",
                           patches=sdr_quick, sync=(0.30, 1.0),
                           used_for="Use it at the Before, Check and After steps."),
}


# ---------------------------------------------------------------------------------------------- intro art

def gradient_bg():
    """Teal glow in the middle, fading to the app's near-black."""
    yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
    d = np.sqrt(((xx - W / 2) / (W * 0.62)) ** 2 + ((yy - H * 0.45) / (H * 0.75)) ** 2)
    t = np.clip(d, 0, 1)[..., None]
    a, b = np.array(SURFACE, np.float32), np.array(BG, np.float32)
    return (a * (1 - t) + b * t).astype(np.uint8)


def paste_logo(im, cx, cy, width):
    logo = Image.open(LOGO).convert("RGBA")
    logo = logo.crop(logo.getbbox())
    h = int(logo.height * width / logo.width)
    logo = logo.resize((width, h), Image.LANCZOS)
    im.alpha_composite(logo, (int(cx - width / 2), int(cy - h / 2)))


def pill(d, xy, text, fnt, fill, ink):
    x, y = xy
    tw = d.textlength(text, font=fnt)
    h = fnt.size * 1.5
    d.rounded_rectangle((x, y, x + tw + fnt.size * 1.4, y + h), radius=h / 2, fill=fill)
    d.text((x + fnt.size * 0.7, y + h / 2), text, font=fnt, fill=ink, anchor="lm")
    return x + tw + fnt.size * 1.4


def qr_image(url, size):
    if url:
        import segno
        q = segno.make(url, error="m")
        m = np.array(q.matrix, dtype=bool)
        n = m.shape[0] + 8                                  # 4-module quiet zone
        canvas = np.ones((n, n), bool)
        canvas[4:-4, 4:-4] = ~m
        img = Image.fromarray((canvas * 255).astype(np.uint8)).resize((size, size), Image.NEAREST)
        return img.convert("RGBA")
    # No link yet: an honest placeholder.
    img = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((24, 24, size - 24, size - 24), radius=24, outline=(180, 186, 188), width=6)
    f = font("Inter-Medium.otf", 54)
    d.text((size / 2, size / 2 - 40), "App Store", font=f, fill=(90, 98, 100), anchor="mm")
    d.text((size / 2, size / 2 + 40), "QR code", font=f, fill=(90, 98, 100), anchor="mm")
    return img


def scene_title(spec, mode):
    im = Image.fromarray(gradient_bg()).convert("RGBA")
    d = ImageDraw.Draw(im)
    paste_logo(im, W / 2, 640, 300)
    d.text((W / 2, 1000), "TV Calibrator", font=font("InterDisplay-SemiBold.otf", 210), fill=WHITE, anchor="mm")
    d.text((W / 2, 1170), "by Slidebean", font=font("Inter-Medium.otf", 84), fill=YELLOW, anchor="mm")
    # format badge + video name
    fb = font("Inter-Bold.otf", 64)
    fn = font("Inter-Regular.otf", 76)
    badge = mode.upper()
    bw = d.textlength(badge, font=fb) + fb.size * 1.4
    nw = d.textlength(spec["label"], font=fn)
    x0 = W / 2 - (bw + 36 + nw) / 2
    pill(d, (x0, 1400), badge, fb, MODE_TINT[mode], (0, 0, 0))
    d.text((x0 + bw + 36, 1400 + fb.size * 0.75), spec["label"], font=fn, fill=WHITE, anchor="lm")
    d.text((W / 2, 1600), "Test patterns for calibrating your TV with an iPhone", font=font("Inter-Regular.otf", 64),
           fill=GREY, anchor="mm")
    return np.asarray(im.convert("RGB"))


def scene_steps(spec, mode, qr_url):
    im = Image.fromarray(gradient_bg()).convert("RGBA")
    d = ImageDraw.Draw(im)
    # left: the app + QR
    lx = 330
    card = (lx - 60, 420, lx + 1160, 1740)
    d.rounded_rectangle(card, radius=64, fill=(*HERO, 255), outline=(*TEAL, 90), width=4)
    d.text((lx + 50, 540), "Get the free app", font=font("InterDisplay-SemiBold.otf", 104), fill=WHITE)
    d.text((lx + 50, 690), "TV Calibrator for iPhone", font=font("Inter-Regular.otf", 64), fill=GREY)
    q = qr_image(qr_url, 640)
    im.alpha_composite(q, (int(lx + 50), 840))
    d.text((lx + 50 + 640 + 50, 1000), "Scan with\nyour iPhone\ncamera", font=font("Inter-Medium.otf", 60),
           fill=TEAL, spacing=24)
    # right: three steps
    rx = 1760
    d.text((rx, 450), "How it works", font=font("Inter-SemiBold.otf", 64), fill=TEAL)
    steps = [
        ("Open the app, pick your screen and " + mode.upper() + ",", "then tap Measure."),
        ("Put your iPhone on a stand, a hand's length", "(15–30 cm) from the TV, camera facing the centre."),
        ("Keep this video playing.", "Measuring starts after the grey and white flashes."),
    ]
    fnum = font("InterDisplay-SemiBold.otf", 84)
    f1 = font("Inter-SemiBold.otf", 72)
    f2 = font("Inter-Regular.otf", 60)
    y = 600
    for i, (a, b) in enumerate(steps):
        d.ellipse((rx, y, rx + 130, y + 130), fill=YELLOW)
        d.text((rx + 65, y + 66), str(i + 1), font=fnum, fill=(0, 0, 0), anchor="mm")
        d.text((rx + 180, y + 4), a, font=f1, fill=WHITE)
        d.text((rx + 180, y + 102), b, font=f2, fill=GREY)
        y += 360
    d.text((rx, 1780), spec["used_for"], font=font("Inter-Regular.otf", 56), fill=GREY)
    return np.asarray(im.convert("RGB"))


def scene_outro(mode):
    im = Image.fromarray(gradient_bg()).convert("RGBA")
    d = ImageDraw.Draw(im)
    d.ellipse((W / 2 - 110, 640, W / 2 + 110, 860), outline=TEAL, width=14)
    d.line((W / 2 - 50, 755, W / 2 - 10, 800, W / 2 + 60, 705), fill=TEAL, width=16, joint="curve")
    d.text((W / 2, 1060), "That's it.", font=font("InterDisplay-SemiBold.otf", 180), fill=WHITE, anchor="mm")
    d.text((W / 2, 1240), "Your results are in the app.", font=font("Inter-Regular.otf", 84), fill=GREY, anchor="mm")
    paste_logo(im, W / 2 - 330, 1560, 130)
    d.text((W / 2 - 230, 1560), "TV Calibrator by Slidebean", font=font("Inter-Medium.otf", 64), fill=WHITE, anchor="lm")
    return np.asarray(im.convert("RGB"))


# ---------------------------------------------------------------------------------------------- assembly

def ease(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


def build(mode, kind, qr_url, out, preview_dir=None, crf=16):
    spec = VIDEOS[(mode, kind)]
    patches = spec["patches"]()
    black = np.zeros((H, W, 3), np.uint8) + np.array([0, 0, 0], np.uint8)   # full-range black -> code 16
    title, steps, outro = scene_title(spec, mode), scene_steps(spec, mode, qr_url), scene_outro(mode)
    title_f, steps_f, outro_f = (s.astype(np.float32) for s in (title, steps, outro))
    black_f = black.astype(np.float32)

    timeline = []          # (name, seconds, frame function of t in [0, seconds))
    keyframes = []

    def blend(a, b, k):
        return (a * (1 - k) + b * k).astype(np.uint8)

    # 1. intro: title (5 s), steps (11 s), with 0.6 s fades
    timeline.append(("intro title", 5, lambda t: to_yuv420(blend(black_f, title_f, ease(t / 0.8)) if t < 0.8
                                                            else blend(title_f, steps_f, ease((t - 4.4) / 0.6)) if t > 4.4
                                                            else title)))
    timeline.append(("intro steps", 11, lambda t: to_yuv420(steps if t < 10.2
                                                             else blend(steps_f, black_f, ease((t - 10.2) / 0.8)))))
    # 2. lead: black with a small countdown
    base = Plane()
    for s in range(LEAD):
        m = text_mask([f"{spec['title']}  |  Patterns start in {LEAD - s}"])
        frame_bytes = base.with_text(m, 72)
        timeline.append((f"lead {s}", 1, (lambda fb: (lambda t: fb))(frame_bytes)))
    # 3. sync
    lo, hi = spec["sync"]
    for i, lvl in enumerate((lo, hi, lo)):
        p = Plane((lvl,) * 3)
        m = text_mask([f"{spec['title']}  |  Sync"])
        timeline.append((f"sync {i}", SYNC_HOLD, (lambda p, m: (lambda t: p.with_text(m, 72) if t < 3
                                                               else p.with_text(m * (1 - ease(t - 3)), 72) if t < 4
                                                               else p.bytes()))(p, m)))
    # 4. patches: caption for 3 s, fading out over the next second
    for i, (pid, name, rgb, hold) in enumerate(patches):
        p = Plane(rgb)
        m = text_mask([f"{spec['title']}  |  {i + 1}/{len(patches)}  {name}"])
        timeline.append((f"patch {pid}", hold, (lambda p, m: (lambda t: p.with_text(m, 72) if t < 3
                                                             else p.with_text(m * (1 - ease(t - 3)), 72) if t < 4
                                                             else p.bytes()))(p, m)))
    # 5. outro
    timeline.append(("outro", 6, lambda t: to_yuv420(blend(black_f, outro_f, ease(t / 0.8)) if t < 0.8
                                                     else blend(outro_f, black_f, ease((t - 5.2) / 0.8)) if t > 5.2
                                                     else outro)))

    total = sum(s for _, s, _ in timeline)
    t0 = 0
    marks = {}
    for name, secs, _ in timeline:
        marks[name] = t0
        keyframes.append(t0)
        t0 += secs

    if preview_dir:
        os.makedirs(preview_dir, exist_ok=True)
        for name, at in [("intro title", 2.5), ("intro steps", 5), ("lead 0", 0.5), ("sync 1", 1), ("patch G40", 1),
                         ("patch RED", 5), ("outro", 3)]:
            fn = dict((n, f) for n, _, f in timeline)[name]
            raw = fn(at)
            yv = np.frombuffer(raw[:W * H], np.uint8).reshape(H, W)
            cb = np.frombuffer(raw[W * H:W * H * 5 // 4], np.uint8).reshape(H // 2, W // 2).repeat(2, 0).repeat(2, 1)
            cr = np.frombuffer(raw[W * H * 5 // 4:], np.uint8).reshape(H // 2, W // 2).repeat(2, 0).repeat(2, 1)
            yy = (yv.astype(np.float32) - 16) / 219
            pb, pr = (cb.astype(np.float32) - 128) / 224, (cr.astype(np.float32) - 128) / 224
            r = yy + 1.5748 * pr
            b = yy + 1.8556 * pb
            g = (yy - 0.2126 * r - 0.0722 * b) / 0.7152
            rgb = np.clip(np.stack([r, g, b], -1) * 255, 0, 255).astype(np.uint8)
            Image.fromarray(rgb).resize((1280, 720), Image.LANCZOS).save(
                os.path.join(preview_dir, f"{name.replace(' ', '_')}.png"))
        print("previews in", preview_dir)
        return marks, total

    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", f"{W}x{H}",
           "-framerate", str(FPS), "-i", "-",
           "-c:v", "libx264", "-preset", "medium", "-crf", str(crf), "-bf", "2", "-g", str(FPS * 2),
           "-force_key_frames", ",".join(f"{k:.3f}" for k in keyframes),
           "-profile:v", "high", "-level", "5.1", "-pix_fmt", "yuv420p",
           "-color_range", "tv", "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
           "-movflags", "+faststart", out]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    for name, secs, fn in timeline:
        for f in range(secs * FPS):
            proc.stdin.write(fn(f / FPS))
        print(f"  {name:<14} {secs:>3} s", flush=True)
    proc.stdin.close()
    if proc.wait() != 0:
        sys.exit("ffmpeg failed")
    print("wrote", out, f"{total} s")
    return marks, total


def main():
    ap = argparse.ArgumentParser(description="Make a branded TV Calibrator pattern video.")
    ap.add_argument("mode", choices=["sdr"])
    ap.add_argument("kind", choices=["quick"])
    ap.add_argument("--qr-url", help="App Store link for the QR code (a placeholder is drawn without it)")
    ap.add_argument("--out", help="output file (default: the video's title .mp4)")
    ap.add_argument("--preview", metavar="DIR", help="write stills of key moments to DIR instead of a video")
    ap.add_argument("--crf", type=int, default=16)
    a = ap.parse_args()
    spec = VIDEOS[(a.mode, a.kind)]
    marks, total = build(a.mode, a.kind, a.qr_url, a.out or spec["title"] + ".mp4", a.preview, a.crf)
    print("sync starts at", marks["sync 0"], "s; first patch at", marks["sync 2"] + SYNC_HOLD, "s; length", total, "s")


if __name__ == "__main__":
    main()
