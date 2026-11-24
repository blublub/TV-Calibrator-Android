# Mac lab tool

The original TV Calibrator for Mac: a small local web app around `tvcal.py`, working with a
Calibrite Display Plus HL through ArgyllCMS. In this project it's the **lab tool for making camera
profiles** for new iPhone models (see [../docs/ADDING-AN-IPHONE.md](../docs/ADDING-AN-IPHONE.md)).
It can also calibrate a TV directly with the meter.

- Requirements:
  - Python 3: `xcode-select --install`
  - ArgyllCMS: `brew install argyll-cms`
- Run it by opening `TV Calibrator.app`. It starts a server on `http://127.0.0.1:8765` and opens it
  in the browser. If macOS blocks the app, run `Start in Terminal.command` instead.
- Demo without a meter: `python3 "TV Calibrator.app/Contents/Resources/tvcal_app.py" --sim`.
- Data lives in `~/tvcal/`: history per TV, `camera_profiles.json`, and exported profiles in
  `Camera profiles/`.
- `Contents/Resources/clips/` holds 2-second HDR10 and Dolby Vision clips. They're used when an
  iPhone is the pattern source over HDMI.

`tvcal.py` also works on its own from the terminal. Its header lists the options.
