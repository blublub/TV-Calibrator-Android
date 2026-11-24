#!/usr/bin/env python3
"""
Builds the camera-profile server for the TV Calibrator iPhone app.

Each iPhone model is trained on the Mac with the lab tool (mac-lab/, with the Display Plus HL, on as
many TVs as you can) and the iPhone's Lab build, then exported (Camera profiles > Export). This script
adds those exported files to the repo's server/ folder, which the push worker publishes:

    server/
      index.json                      which models are supported, with checksums
      profiles/iPhone18,1-v1.json     one file per model and version
      patterns.json                   where the pattern videos are (edited by hand)

It also writes every published profile into the app itself (TV Calibrator/Resources/
builtin-profiles.json), so the next App Store build works offline and only downloads profiles that
are newer than it. That file also names the standard profile: the one iPhones without their own
profile fall back on.

Usage (from the repo folder):
    python3 tools/build_catalog.py ~/tvcal/Camera\\ profiles/*.json
    python3 tools/build_catalog.py ~/tvcal/camera_profiles.json --standard iPhone18,1 --name "iPhone18,1=iPhone 18 Pro"

Inputs are files exported from the Mac app (Camera profiles > Export) or the Mac app's whole
~/tvcal/camera_profiles.json. A model whose matrix changed gets a new version number, and phones
download it the next time they check. Models not passed in are kept. Then publish it with
`npx wrangler deploy` in push-worker/ and commit. See docs/ADDING-AN-IPHONE.md.
"""
import argparse
import hashlib
import json
import os
import sys
from datetime import datetime, timezone

FORMAT = 1
MIN_APP_FORMAT = 1


def load_exports(path):
    """One exported profile, or every trained model in the Mac app's camera_profiles.json."""
    with open(path, encoding="utf-8") as f:
        d = json.load(f)
    if isinstance(d, dict) and d.get("tvcal_camera_profile"):
        return [convert(path, d)]
    if isinstance(d, dict) and d and all(isinstance(v, dict) and "sessions" in v for v in d.values()):
        trained = [convert(path, {"model": m, **v}) for m, v in d.items() if v.get("M")]
        skipped = [m for m, v in d.items() if not v.get("M")]
        if skipped:
            print(f"! {path}: skipping untrained model(s): {', '.join(skipped)}")
        return trained
    sys.exit(f"{path}: not a TV Calibrator camera profile export or camera_profiles.json")


def convert(path, d):
    if not d.get("model"):
        sys.exit(f"{path}: profile has no model identifier")
    M = d.get("M")
    if not (isinstance(M, list) and len(M) == 3 and all(isinstance(r, list) and len(r) == 3 for r in M)):
        sys.exit(f"{path}: no trained matrix - train this iPhone on at least one TV first")
    cam = d.get("camera") or {}
    if not cam:
        sys.exit(f"{path}: the profile has no locked camera settings (iso/format/lens); retrain it")
    st = d.get("stats") or {}
    tvs = sorted((st.get("tvs") or {}).keys())
    name = (d.get("name") or "").strip()
    panels = {}
    for pn, v in (d.get("panels") or {}).items():
        if isinstance(v.get("M"), list) and len(v["M"]) == 3:
            panels[pn] = {"matrix": v["M"], "deAvg": v.get("de_avg")}
            if isinstance(v.get("Mn"), list) and len(v["Mn"]) == 3:
                panels[pn]["neutral"] = v["Mn"]
            if len(v.get("tvs", [])) < 2:
                print(f"! {d['model']}: {pn} trained on {len(v.get('tvs', []))} TV - a second {pn} TV would "
                      "estimate accuracy on unseen ones")
    if not panels:
        print(f"! {d['model']}: no conversion per kind of screen - set each TV's screen type on the Mac's "
              "Train page")
    return {
        "format": FORMAT,
        "model": d["model"],
        "name": name,
        "version": 0,
        "matrix": M,
        "camera": {k: cam[k] for k in ("iso", "format", "lens", "aperture") if cam.get(k) is not None},
        "stats": {"deAvg": st.get("de_avg"), "unseen": st.get("unseen"), "tvs": tvs},
        "trained": max((s.get("date") or "" for s in d.get("sessions", [])), default="") or None,
        "panels": panels,
    }


def inv3(M):
    a, b, c = M
    det = a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])
    cof = [[(b[1] * c[2] - b[2] * c[1]), -(a[1] * c[2] - a[2] * c[1]), (a[1] * b[2] - a[2] * b[1])],
           [-(b[0] * c[2] - b[2] * c[0]), (a[0] * c[2] - a[2] * c[0]), -(a[0] * b[2] - a[2] * b[0])],
           [(b[0] * c[1] - b[1] * c[0]), -(a[0] * c[1] - a[1] * c[0]), (a[0] * b[1] - a[1] * b[0])]]
    return [[v / det for v in row] for row in cof]


def mul3(A, B):
    return [[sum(A[i][k] * B[k][j] for k in range(3)) for j in range(3)] for i in range(3)]


DERIVED_PENALTY = 2.0   # measured: a conversion carried over from another phone was about 2 dE worse


def derive_panels(profiles):
    """Kinds of screen a model wasn't trained on, but another model was: carry that model's conversion over
    through a kind of screen both were trained on (the phones' cameras relate the same way on any screen).
    Marked derived, with a higher expected error."""
    own = {p["model"]: {k: v for k, v in p["panels"].items() if not v.get("derived")} for p in profiles}
    for p in profiles:
        for q in profiles:
            if q is p:
                continue
            common = [k for k in own[p["model"]] if k in own[q["model"]]]
            if not common:
                continue
            c = min(common, key=lambda k: (own[p["model"]][k].get("deAvg") or 9) + (own[q["model"]][k].get("deAvg") or 9))
            Q = mul3(inv3(own[q["model"]][c]["matrix"]), own[p["model"]][c]["matrix"])   # p's camera -> q's
            for k, v in own[q["model"]].items():
                if k in p["panels"]:
                    continue
                p["panels"][k] = {"matrix": mul3(v["matrix"], Q),
                                  "deAvg": (v.get("deAvg") or 1.0) + DERIVED_PENALTY, "derived": True}
                if v.get("neutral"):
                    p["panels"][k]["neutral"] = mul3(v["neutral"], Q)
                print(f"  {p['model']}: {k} carried over from {q['model']} (via {c})")


def main():
    ap = argparse.ArgumentParser(description="Build the TV Calibrator camera-profile server folder.")
    ap.add_argument("exports", nargs="+", help="camera profile files exported from the Mac app")
    default_out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "server")
    ap.add_argument("--out", default=default_out, help="server folder to update (default: the repo's server/)")
    default_builtin = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "TV Calibrator", "Resources",
                                   "builtin-profiles.json")
    ap.add_argument("--builtin", default=default_builtin,
                    help="the app's built-in profiles file to rewrite (default: TV Calibrator/Resources/builtin-profiles.json)")
    ap.add_argument("--standard", help="model whose profile iPhones without their own use (default: keep the current one)")
    ap.add_argument("--name", action="append", default=[], metavar="MODEL=NAME",
                    help='marketing name for a model whose profile has none, e.g. "iPhone18,1=iPhone 18 Pro"')
    ap.add_argument("--max-unseen-de", type=float, default=3.0,
                    help="refuse profiles whose estimated dE on an unseen TV is above this (default 3)")
    a = ap.parse_args()

    os.makedirs(os.path.join(a.out, "profiles"), exist_ok=True)
    index_path = os.path.join(a.out, "index.json")
    old = {}
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            for e in json.load(f).get("profiles", []):
                old[e["model"]] = e

    names = dict(n.split("=", 1) for n in a.name)
    exports = [p for path in a.exports for p in load_exports(path)]
    for p in exports:
        if p["model"] in names:
            p["name"] = names[p["model"]].strip()
        if not p["name"]:
            print(f"! {p['model']}: no marketing name - pass --name \"{p['model']}=iPhone 18 Pro\" or rename it on the Mac")

    derive_panels(exports)
    entries = dict(old)
    for p in exports:
        unseen = p["stats"]["unseen"]
        if unseen is not None and unseen > a.max_unseen_de:
            print(f"! {p['model']}: estimated dE {unseen:.2f} on unseen TVs is above {a.max_unseen_de} - not published")
            continue
        prev = old.get(p["model"])
        version = 1
        if prev:
            version = prev["version"]
            prev_path = os.path.join(a.out, prev["file"])
            same = False
            if os.path.exists(prev_path):
                with open(prev_path, encoding="utf-8") as f:
                    q = json.load(f)
                same = q.get("matrix") == p["matrix"] and q.get("camera") == p["camera"] and q.get("name") == p["name"] \
                    and q.get("panels") == p["panels"]
            if not same:
                version += 1
        p["version"] = version
        rel = f"profiles/{p['model']}-v{version}.json"
        data = json.dumps(p, indent=1, sort_keys=True).encode("utf-8")
        with open(os.path.join(a.out, rel), "wb") as f:
            f.write(data)
        entries[p["model"]] = {
            "model": p["model"], "name": p["name"], "version": version, "file": rel,
            "sha256": hashlib.sha256(data).hexdigest(),
            "deAvg": p["stats"]["deAvg"], "unseen": unseen, "tvs": len(p["stats"]["tvs"]),
        }
        print(f"{p['model']:<14} {p['name'] or '(no name)':<22} v{version}  "
              f"unseen dE {unseen if unseen is not None else '-'}  {len(p['stats']['tvs'])} TV(s)")

    index = {"format": FORMAT, "minAppFormat": MIN_APP_FORMAT,
             "updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
             "profiles": sorted(entries.values(), key=lambda e: e["model"])}
    with open(index_path, "w", encoding="utf-8") as f:
        json.dump(index, f, indent=1)
    print(f"\nWrote {index_path} with {len(entries)} model(s).")

    # The app's built-in copy: every published profile, plus which one is the standard.
    builtin = {"format": 1, "standard": None, "profiles": []}
    if os.path.exists(a.builtin):
        with open(a.builtin, encoding="utf-8") as f:
            builtin["standard"] = json.load(f).get("standard")
    if a.standard:
        builtin["standard"] = a.standard
    for e in sorted(entries.values(), key=lambda e: e["model"]):
        with open(os.path.join(a.out, e["file"]), encoding="utf-8") as f:
            builtin["profiles"].append(json.load(f))
    models = [q["model"] for q in builtin["profiles"]]
    if builtin["standard"] not in models:
        if builtin["standard"]:
            print(f"! standard profile {builtin['standard']} isn't published; pass --standard")
        builtin["standard"] = models[0] if len(models) == 1 else None
    with open(a.builtin, "w", encoding="utf-8") as f:
        json.dump(builtin, f, indent=1, sort_keys=True)
    print(f"Wrote {a.builtin}: {len(models)} built-in profile(s), standard: {builtin['standard'] or 'none'}.")
    print("Commit and push to publish. Ship a new app build to update the built-in profiles.")


if __name__ == "__main__":
    main()
