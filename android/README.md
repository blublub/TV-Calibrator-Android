# TV Calibrator Android (work in progress)

An Android port of **[TV Calibrator by Slidebean](https://github.com/slidebean/tv-calibrator)**, changed:
the iPhone app's engine is being rewritten in Kotlin and the app rebuilt for Android. Licensed like the
original under [CC BY-NC 4.0](../LICENSE): non-commercial use only. Slidebean's name, logo and app icon
are not part of this port.

This is not a replacement for a professional calibration. Without a camera profile trained against a
colorimeter, readings are approximate: good for spotting and fixing clear errors in white balance and
gamma, not for fine calibration.

## Modules

| Module | What it is |
|---|---|
| `engine` | The calibration engine (`CalibrationKit` in Kotlin, no Android dependencies). |
| `probe` | A diagnostic app: dumps the phone's Camera2 characteristics and takes manual RAW test frames. |

Not ported yet: `AutoCalibration` (not used by the iPhone app either), the camera meter and the UI.

## Building

You need JDK 17 and the Android SDK (`ANDROID_HOME` set, or a `local.properties` with `sdk.dir`;
that file is git-ignored because it holds a local path).

```sh
cd android
./gradlew :engine:test            # engine tests, on the PC, no phone needed
./gradlew :probe:assembleDebug    # the probe app
```

The engine tests read the same fixtures as the Swift tests, straight from
`CalibrationKit/Tests/CalibrationKitTests/Fixtures` (recorded from the original Python engine), and
check every number against them.
