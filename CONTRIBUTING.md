# Contributing

Thanks for helping. A few notes:

- **iPhone app:** open `TV Calibrator.xcodeproj` with Xcode 27. Keep the engine (`CalibrationKit`) free of
  UI code, and run its tests (`swift test` in `CalibrationKit`) before sending a pull
  request.
- **Engine changes:** the Swift engine mirrors the Python one in `mac-lab/`. If you change behaviour,
  change both and regenerate the test fixtures with `python3 tools/make_fixtures.py`.
- **TV menus:** brand menu paths and setup advice live in `TV Calibrator/Storage/Brands.swift`.
  Corrections for specific models are very welcome; say which model and firmware.
- **Camera profiles** need a colorimeter. See `docs/ADDING-AN-IPHONE.md`.
