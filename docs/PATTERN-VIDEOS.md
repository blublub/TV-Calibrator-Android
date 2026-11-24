# The pattern videos

The app measures a centred window (10% of the screen) of greys, whites and colours.

The video files themselves aren't in this repo. SDR and HDR10 are published on YouTube, and HDR10+ and
Dolby Vision are downloads (see [HOSTING.md](HOSTING.md)). The app tells users which video to play
by its **exact title**, so the YouTube titles and file names must match the list below.

## The files, per format

Each format has its own folder:

| Format | Folder | Where users get it |
|---|---|---|
| SDR | `SDR` | YouTube playlist |
| HDR10 | `HDR10` | YouTube playlist |
| HDR10+ | `HDR10+` | download |
| Dolby Vision | `Dolby Vision` | download |

**Timed videos.** The app follows these from their sync flashes, so they must play from the start.

| Video | Used by | Length |
|---|---|---|
| `<prefix> 00 - Quick check (play from the start)` | Before, Check, After | 1:52 SDR / 2:22 HDR |
| `<prefix> 00b - Point finder (play from the start)` | Fine-tune › point finder | 2:08 SDR / 2:38 HDR |

**Single patterns.** Each one runs 10 minutes, for the live steps.

| SDR | HDR (HDR10 / HDR10+ / DV) |
|---|---|
| 01 - White (bright end) | 01 - Grey 65 (bright end) |
| 02 - Grey 30 (dark end) | 02 - Grey 35 (dark end) |
| 03 - Grey 50 (middle) | 03 - Grey 50 (middle) |
| 04 - Grey 20 | 04 - Grey 40 |
| 05 - Grey 40 | 05 - Grey 60 |
| 06 - Grey 60 | 06 - Grey 70 |
| 07 - Grey 80 | 07 - Red |
| 08 - Red | 08 - Green |
| 09 - Green | 09 - Blue |
| 10 - Blue | 10 - Cyan |
| 11 - Cyan | 11 - Magenta |
| 12 - Magenta | 12 - Yellow |
| 13 - Yellow | |

The prefixes are `SDR`, `HDR10`, `HDR10+` and `DV`, e.g. `DV 03 - Grey 50 (middle).mp4`.

`server/patterns.json` already lists every SDR and HDR10 title with an empty link. Fill in each
video's YouTube URL after uploading.

## Timelines

The timed videos must follow the timelines in `CalibrationKit/Sources/CalibrationKit/Patterns.swift`
exactly:

1. 10 s of black.
2. Sync flashes: grey, white, grey, 6 s each.
   - SDR: 30% / 100% signal.
   - HDR: 25 / 200 nits.
3. The patches:
   - **Quick check:** 7 s each. HDR colours are 12 s each.
   - **Point finder:** 5 s each. It starts with white, then greys:
     - SDR: 10–100% in 5% steps.
     - HDR: PQ 20–80% in 2.5% steps.

SDR colours are 75% Rec.709. HDR colours are 100-nit P3 in a BT.2020 container.

## YouTube notes

- Upload HDR10 files with their HDR metadata intact. YouTube then serves HDR to TVs that support it,
  and the TV shows its HDR badge.
- Ads are fine. The app ignores everything until the video's sync flashes.
- Tell users to play the videos in the TV's YouTube app (or cast to it) at the highest quality.
  Playing them through a phone's screen mirroring would change the signal.

## Point finder videos

All four formats have one. The SDR one was made with `tools/make_sdr_point_finder.py`. That script
uses the same layout as the other SDR files:

- 3840×2160, 24p, H.264;
- BT.709 video levels (grey code = 16 + 219 × level);
- a 1214×684 window centred on black;
- a caption in DejaVu Sans 56.

Every pattern change is a keyframe.
