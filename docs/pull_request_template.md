## What this changes

<!-- One or two sentences. For TV menu changes, say which model and firmware you checked. -->

## Checklist

- [ ] The engine tests pass (`cd CalibrationKit && swift test`), if the change touches `CalibrationKit`.
- [ ] If engine behaviour changed: the Python engine in `mac-lab/` matches and the fixtures were
      regenerated (`python3 tools/make_fixtures.py`).
- [ ] Tried on a real iPhone, or on the demo screen for UI-only changes.
