#!/bin/bash
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
cd "$(dirname "$0")/TV Calibrator.app/Contents/Resources" && python3 tvcal_app.py "$@"
