# How it works

## The camera as a meter

`TV Calibrator/Camera/CameraMeter.swift`:

- The app takes **Bayer RAW** photos from the back main camera, so there's no tone curve, white
  balance or processing.
- It averages the red, green and blue photosites in a box covering the centre 10% of the frame.
- Focus is locked at its closest setting. At TV distance this blurs the pixel grid into an even
  colour.
- ISO is locked at the minimum. On iPhones with a variable aperture (iPhone 18 Pro) the aperture is
  locked too.
- Only the shutter time changes, so each reading is divided by it. The sensor's black level,
  measured once with the lens covered, is subtracted.

A **camera profile** is a 3×3 matrix from those numbers to CIE XYZ, fitted against a Display Plus HL
on several TVs. The fit happens in `mac-lab/`. A profile also records the locked settings. If they
ever differ (for example an iOS update changes the RAW format), the app refuses to measure rather
than give wrong numbers.

## The engine

`CalibrationKit` is plain Swift with no UI, a port of the Mac lab tool's Python (`tvcal.py`,
`tvcal_app.py`):

| File | What it does |
|---|---|
| `ColourMath.swift` | xy / u′v′, Lab, CIEDE2000, PQ (ST 2084), gamut matrices. |
| `Patterns.swift` | The videos' timelines and file names. This is the single source of truth. |
| `Measuring.swift` | Finds a video's sync flashes and measures each patch on schedule. |
| `Analysis.swift` | Greyscale balance and dE, gamma, PQ tracking, colour errors. |
| `Guided.swift` | Live readings: a settled average that copes with TVs whose light wobbles, plain-language hints, "is the right pattern playing?" checks. |
| `PointFinder.swift` | Measures fine greys before and after nudging four multipoint controls, to find where each control really acts. |
| `Simulation.swift` | A simulated TV and meter for the demo and the tests. |
| `AutoCalibration.swift` | The Mac tool's measure-then-prescribe rounds. Kept and tested, but not used by the app. |

The tests in `CalibrationKit/Tests` compare the Swift engine with fixtures recorded from the
Python engine (`tools/make_fixtures.py`).

## Profiles and the server

The app picks its camera profile in this order (`TV Calibrator/Profiles/ProfileService.swift`):

1. A profile imported by hand (Lab build only).
2. This model's own profile: the one built into the app (`TV Calibrator/Resources/builtin-profiles.json`),
   or a newer version downloaded from the server.
3. The **standard profile**, named in `builtin-profiles.json`, for models with no profile of their
   own. Its locked camera settings can't match another model's, so that check is skipped and
   the app says the readings are approximate.

On launch the app fetches `index.json`. Only if the server has a profile for this model that's newer
than what the phone has does it download `profiles/<model>-v<n>.json`, check its SHA-256 against
the index and keep it. Offline, the app simply carries on with what it has. It also fetches
`patterns.json` for the video links. Nothing is uploaded: measurements stay on the phone.

## The Lab build

The **TV Calibrator Lab** scheme compiles with `LAB_MODE` and uses its own bundle ID and Info.plist
(local network access). It adds:

- **Lab mode:** pairs with the Mac lab tool over Wi-Fi and sends it raw camera readings on request.
- **Profile import:** loads a profile exported from the Mac, to test it before publishing.

The App Store build (TV Calibrator scheme) contains none of this.
