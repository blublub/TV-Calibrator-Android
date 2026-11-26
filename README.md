# TV Calibrator Android

An **unofficial Android port** of [TV Calibrator by Slidebean](https://github.com/slidebean/tv-calibrator),
changed: the iPhone app's engine was rewritten in Kotlin and the app rebuilt for Android (English and German).
Not affiliated with Slidebean. Licensed like the original under [CC BY-NC 4.0](LICENSE): non-commercial use only.
The Slidebean name, logo and app icon are Slidebean's and are not part of this port.

> **Status: early, not yet validated on a real TV.** The calibration engine passes the original's test suite,
> the camera meter works on an OPPO Find X8, and the full guided flow runs with the built-in demo TV. Readings
> have **not** been compared with a colorimeter yet. The Android code is in [`android/`](android).
>
> **Download:** test version (APK, Android 10+) on the [Releases page](https://github.com/blublub/TV-Calibrator-Android/releases). Read the limitations below first.

## ⚠️ Read this first: Android is less accurate than the iPhone app

The iPhone app is accurate because Slidebean measured each supported iPhone model side by side with a
professional colorimeter (Calibrite Display Plus HL) and built a **camera profile** per model and per panel
type. **No such profiles exist for Android phones.** This port builds an *approximate* profile from the colour
matrices the phone itself reports (the same ones its DNG photos carry), which the phone maker tuned for photos
in daylight, not for the narrow-band light of TV panels.

| | iPhone app (Slidebean) | This Android port |
|---|---|---|
| Camera profile | Trained per iPhone model against a colorimeter, with separate matrices for WOLED, QD-LCD and RGB-LED panels | Approximate, from the phone's own colour metadata; one matrix for all panels |
| Profile accuracy vs. colorimeter | about **0.85–0.95 dE2000** on the training TVs (Slidebean's built-in profiles) | **not measured yet**; estimated **≈2–6 dE2000 for whites/greys** and **≈3–10+ dE2000 for saturated colours** |
| Absolute brightness (nits) | From the trained profile | Rough estimate from ISO/aperture/shutter (±50 % possible); matters for HDR, not for SDR greyscale and gamma |
| RAW data | 14-bit | Depends on the phone (OPPO Find X8: 10-bit) |
| Supported phones | Profiled iPhone models (others: "standard profile", approximate) | Phones whose camera gives apps RAW + manual exposure (Camera2); tested only on OPPO Find X8 |

**What this means in practice**

- **Relative measurements are robust:** whether greys get bluer or greener from dark to bright, gamma, and
  whether a change on the remote moves things the right way. Repeat readings on the X8 agree within ~0.3 %.
- **The absolute white point is the weak spot:** if the phone's matrix reads slightly warm, the app will steer
  the TV slightly cool, and that error ends up in the calibration.
- So: good for **finding and fixing clear errors** (a noticeably blue, green or pink white, a wrong gamma, a badly
  set picture mode), **not** for fine-tuning a TV that is already close.

## Is it worth it for your TV? Check RTINGS first

[RTINGS](https://www.rtings.com/tv/tests/picture-quality/color-accuracy) measures every TV it reviews before
calibration, in its most accurate preset (usually Filmmaker or Movie mode), and reports **SDR "White Balance dE"
and "Color dE" in dE2000** — the same measure this app uses. Its scale: below 1 not noticeable, 1–3 noticeable
to professionals, 3–5 to enthusiasts, above 5 to everyone; a white balance dE below 3 counts as good.

| Your TV's pre-calibration white balance dE (RTINGS) | Example | Recommendation |
|---|---|---|
| **below ~3** | LG C4 ≈1.6, Samsung S90D ≈2.2, Hisense A6N ≈2.9 in their best presets | Leave it in Filmmaker/Movie mode. The phone's own error (≈2–6) is larger than the TV's: calibrating with this app can make it **worse**. Use the app only to check that nothing is badly off. |
| **~3–5** | e.g. TCL S350G ≈4.7 (Movie mode) | Borderline. The app can help with clear tints and gamma, but double-check by eye; stop when the app says "close". |
| **above ~5**, or a preset that is visibly off | many budget TVs, or "Standard"/"Vivid" modes | This is where the app is most useful: it can bring a clearly wrong picture into a range where nobody notices. |

Keep in mind: RTINGS measures one sample; your unit can differ, and TVs drift with age and firmware updates.
The app's own "Before" check also shows your TV's starting point — but with the approximate profile, treat its
numbers as a guide, not a lab result.

Examples from RTINGS comparisons ([LG C4 vs Samsung S90D](https://www.rtings.com/tv/tools/compare/lg-c4-oled-samsung-s90d-oled/49541/49703),
[TCL S350G vs Hisense A6N](https://www.rtings.com/tv/tools/compare/tcl-s350g-hisense-a6n/40236/61156)); values change
when RTINGS retests, check the current review for your model.

### Kurzfassung (Deutsch)

Inoffizielle Android-Version von „TV Calibrator“ von Slidebean, nicht mit Slidebean verbunden. Auf Android gibt
es **keine mit einem Messgerät eingemessenen Kameraprofile** – die App rechnet mit den Farbdaten, die das Handy
selbst liefert. Erwartete Messabweichung: **ca. 2–6 dE bei Weiß/Grau, 3–10+ dE bei satten Farben** (iPhone mit
Slidebean-Profil: unter 1 dE). Daher: Schau zuerst bei **RTINGS** nach dem „White Balance dE“ deines Fernsehers
vor der Kalibrierung. **Unter 3** → Filmmaker-/Film-Modus lassen, die App kann es eher verschlechtern.
**Über 5** oder sichtbar verfärbt → hier hilft die App am meisten. Gut geeignet, um **grobe Ausreißer** zu
finden und zu beheben, nicht als Ersatz für eine Profi-Kalibrierung.

## Building the Android app

See [`android/README.md`](android/README.md): JDK 17 + Android SDK, then `cd android && ./gradlew :app:assembleDebug`.
No Mac needed. The engine tests run on the PC: `./gradlew :engine:test`.

---

# Original README: TV Calibrator by Slidebean

*Everything below is the original project's README (for the iPhone app), kept for reference.*

Calibrate your TV's picture with your iPhone. The phone's camera is the meter, and the test
patterns are videos you play on the TV. The app walks you through the TV's own menus, showing
which control to move, which way, and when it's on target.

By [Slidebean](https://www.youtube.com/@Slidebean). Open source under [CC BY-NC 4.0](LICENSE) (attribution, non-commercial).

**Get the app:** coming soon to the App Store. · [Support](mailto:calibrator@slidebean.com) ·
[Privacy policy](https://slidebean.com/tv-calibrator-privacy-policy)

## How it works

1. **Pattern videos on the TV.**
   - **SDR and HDR10** patterns are in the TV Calibrator playlists on the Slidebean YouTube channel.
     Play them in the TV's own YouTube app, or cast them from your phone.
   - **HDR10+ and Dolby Vision** patterns aren't supported by YouTube, so they're a download. Play
     them from a USB stick or a media app.
2. **The iPhone as the meter.** The phone sits on a stand facing the screen and reads the centre of
   each pattern from the camera's RAW sensor data. Lens, focus, ISO and, on the iPhone 18 Pro,
   aperture are all locked.
3. **Matched to a professional colorimeter.** Each iPhone model's camera has been measured
   side-by-side with a Calibrite Display Plus HL on several TVs. The result is a small **camera
   profile** that turns the sensor's numbers into real colour, so readings mean the same thing on
   every supported iPhone. Profiles are built into the app; newer ones (new models, retrained
   versions) download from the profile server. An iPhone without its own profile uses the
   **standard profile** (the iPhone 18 Pro's) and its readings are marked approximate.
4. **Guided steps.** Set up → Before → Bright end (Gain) → Dark end (Offset) → Check → Colours →
   After. There's also optional 10/20-point fine-tuning, with a "point finder" that works out which
   grey each of the TV's multipoint controls really moves. The app has menu paths for LG, Samsung,
   Sony, TCL, Hisense, Panasonic, Philips, Vizio, Toshiba, Sharp and Roku TVs. It works for SDR and
   HDR10 first (patterns on YouTube), then HDR10+ and Dolby Vision as advanced steps (pattern files).

Users don't need a Mac, a meter or an account. A **demo TV** (simulated readings and an on-screen
"remote") lets anyone try the app without a TV.

## What's in this repo

| Folder | What it is |
|---|---|
| `TV Calibrator.xcodeproj`, [`TV Calibrator/`](TV%20Calibrator) | The iPhone app (SwiftUI, iOS 18+). |
| [`CalibrationKit/`](CalibrationKit) | The calibration engine. The app builds its sources as a framework; `Package.swift` makes it a Swift package too, for the tests. |
| [`Config/`](Config) | The app's Info.plist additions (App Store build and Lab build). |
| [`server/`](server) | What the app downloads: `index.json` (supported iPhones), `profiles/` (one camera profile per model) and `patterns.json` (links to the pattern videos), Published by the push worker (`push-worker/`) on Cloudflare. |
| [`mac-lab/`](mac-lab) | The Mac lab tool, used with a Display Plus HL to create camera profiles for new iPhone models. Not needed by users. |
| [`tools/`](tools) | `build_catalog.py` publishes new camera profiles into `server/`; `make_fixtures.py` regenerates the engine's test data. |
| [`docs/`](docs) | How-tos: [adding an iPhone model](docs/ADDING-AN-IPHONE.md), [hosting on Cloudflare](docs/HOSTING.md), [the pattern videos](docs/PATTERN-VIDEOS.md), [how the engine works](docs/HOW-IT-WORKS.md), [testing and releasing](docs/RELEASE.md), [the launch checklist](docs/LAUNCH-CHECKLIST.md), [the App Store listing](docs/APP-STORE-LISTING.md), [publishing on GitHub](docs/PUBLISHING-ON-GITHUB.md). |

## Building the app

You need a Mac with **Xcode 27** (the iPhone 18 Pro's aperture control uses the iOS 27 SDK).

1. Open `TV Calibrator.xcodeproj`.
2. Pick the **TV Calibrator** scheme and your iPhone. In the target's **Signing & Capabilities**,
   choose your team.
3. Run.

The camera only works on a real iPhone. In the Simulator, use the demo TV.

The **TV Calibrator Lab** scheme builds a separate developer app ("TV Cal Lab") that can pair with
the Mac lab tool to train new camera profiles. That code isn't compiled into the App Store build.
See [docs/ADDING-AN-IPHONE.md](docs/ADDING-AN-IPHONE.md).

The server address isn't in the repo. To use downloads and notifications, copy
`Config/ServerConfig.example.plist` to `TV Calibrator/Resources/ServerConfig.plist` and put your own
worker's address in it (see [docs/HOSTING.md](docs/HOSTING.md)). Without it the app runs on its
built-in profiles.

## Tests

The engine is a Swift port of the Mac lab tool's Python engine. The tests feed both the same inputs
and check that every answer matches: colour maths, pattern timelines, sync detection, analysis,
guided hints and the point finder.

Open `CalibrationKit/Package.swift` in Xcode and press ⌘U, or from the terminal:

```sh
cd CalibrationKit
swift test
```

After changing either engine, regenerate the expected answers with
`python3 tools/make_fixtures.py`.

## Contributing

Issues and pull requests are welcome, especially:

- Menu paths for more TV brands: `TV Calibrator/Storage/Brands.swift`.
- Camera profiles for more iPhone models. You need a colorimeter; see
  [docs/ADDING-AN-IPHONE.md](docs/ADDING-AN-IPHONE.md).

## Reporting problems

Use [GitHub Issues](https://github.com/slidebean/tv-calibrator/issues). Include your iPhone model and your TV's brand and
model.

## License

TV Calibrator is licensed under [Creative Commons Attribution-NonCommercial 4.0](LICENSE)
([summary](https://creativecommons.org/licenses/by-nc/4.0/)). You're free to use, study, change and
share it, as long as you:

- **Give credit:** "TV Calibrator by Slidebean", with a link to https://github.com/slidebean/tv-calibrator, and say if you changed it.
- **Don't use it commercially:** no selling it, charging for it, or using it in a paid product or
  service. For commercial use, email calibrator@slidebean.com.

The Slidebean name and logo, and the TV Calibrator app icon, are Slidebean's trademarks and aren't
covered by the license: if you publish your own version of the app, give it your own name and icon.
