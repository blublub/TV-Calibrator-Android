#!/usr/bin/env python3
"""Runs the Mac lab tool's Python engine (tvcal.py / tvcal_app.py) and writes its exact outputs as JSON
fixtures. The Swift tests in CalibrationKit load these and check the Swift port gives the same answers.
Run it after changing the engine on either side, from the repo folder:

    python3 tools/make_fixtures.py
"""
import copy
import json
import math
import os
import random
import sys

REPO = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "mac-lab", "TV Calibrator.app", "Contents", "Resources")
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(REPO, "CalibrationKit", "Tests", "CalibrationKitTests", "Fixtures")
sys.path.insert(0, SRC)
os.environ["HOME"] = os.path.join(OUT, "..", ".fixture-home")      # keep the engine's ~/tvcal out of the way
import tvcal as T          # noqa: E402
import tvcal_app as A      # noqa: E402

os.makedirs(OUT, exist_ok=True)


def dump(name, data):
    def clean(o):
        if isinstance(o, float):
            return None if (math.isnan(o) or math.isinf(o)) else o
        if isinstance(o, dict):
            return {str(k): clean(v) for k, v in o.items()}
        if isinstance(o, (list, tuple)):
            return [clean(v) for v in o]
        return o
    with open(os.path.join(OUT, name), "w") as f:
        json.dump(clean(data), f, indent=0, separators=(",", ":"))
    print("wrote", name)


rnd = random.Random(42)

# ------------------------------------------------------------------ maths
maths = {"xyz": [], "de2000": [], "pq": [], "m709": T.M709, "m709inv": T.M709_INV,
         "p3": T.rgb_to_xyz_matrix(T.GAMUTS["p3"])}
for _ in range(40):
    XYZ = [rnd.uniform(0.01, 120), rnd.uniform(0.01, 120), rnd.uniform(0.01, 140)]
    x, y = T.xyz_to_xy(*XYZ)
    W = T.xyY_to_XYZ(*T.D65, 100.0)
    maths["xyz"].append({"XYZ": XYZ, "xy": [x, y], "lab": list(T.xyz_to_lab(XYZ, W)), "white_de": T.white_de(XYZ),
                         "upvp": list(T.upvp(x, y)), "polar": list(T.polar((x, y))), "rgb709": T.xyz_to_rgb709(XYZ)})
for _ in range(60):
    l1 = [rnd.uniform(0, 100), rnd.uniform(-90, 90), rnd.uniform(-90, 90)]
    l2 = [v + rnd.uniform(-8, 8) for v in l1] if rnd.random() < 0.6 else [rnd.uniform(0, 100), rnd.uniform(-90, 90), rnd.uniform(-90, 90)]
    maths["de2000"].append({"a": l1, "b": l2, "de": T.de2000(l1, l2)})
for n in (0.0, 0.005, 0.1, 1, 18.4, 100, 203, 1000, 4000, 10000):
    maths["pq"].append({"nits": n, "enc": T.pq_encode(n)})
for e in (0.0, 0.1, 0.25, 0.5, 0.58, 0.65, 0.75, 1.0):
    maths["pq"].append({"enc_in": e, "nits_out": T.pq_decode(e)})
maths["wrap"] = [[a, T.wrap(a)] for a in (-400, -181, -180, -179, 0, 179, 180, 181, 359, 725)]
dump("maths.json", maths)

# ------------------------------------------------------------------ sequences
seqs = {}
for hdr in (False, True):
    s = T.build_sequence(hdr)
    f = T.finder_sequence(hdr)
    seqs["hdr" if hdr else "sdr"] = {
        "sync": s["sync"], "patches": s["patches"], "offsets": T.patch_offsets(s), "video_length": T.video_length(hdr),
        "finder": f["patches"], "finder_offsets": T.patch_offsets(f),
        "quick_ids": T.QUICK_IDS[hdr], "quick_offsets": T.patch_offsets(T.quick_sequence(hdr, T.QUICK_IDS[hdr])),
        "targets": {n: {"xy": list(v["xy"]), "Yrel": v["Yrel"]} for n, v in T.colour_targets(hdr).items()},
    }
seqs["pack_files"] = {m: {pid: T.pack_file(m, pid) for pid, _ in T.PACK_FILES[m != "sdr"]} for m in ("sdr", "hdr10", "hdr10p", "dv")}
dump("sequences.json", seqs)


# ------------------------------------------------------------------ simulated rounds -> analyse + plan_round
def report_view(rep, hdr):
    return {"greys": [{k: g[k] for k in ("id", "level", "name", "Y", "targetY", "bal", "de", "clipped")} for g in rep["greys"]],
            "colours": [{k: c[k] for k in ("name", "sat", "hue", "lum", "de", "xy", "txy")} | {"target": list(c["target"])} for c in rep["colours"]],
            "warnings": rep["warnings"], "drift": rep.get("drift"), "peak": rep.get("peak"),
            "grey_avg": T.grey_avg(rep, hdr) if rep["greys"] else None,
            "grey_max": T.grey_max(rep, hdr) if rep["greys"] else None,
            "colour_avg": T.colour_avg(rep)}


analyse_cases, plan_cases = [], []


def strip_state(st):
    s = {k: v for k, v in st.items() if k not in ("sim",)}
    return copy.deepcopy(s)


def sim_run(label, hdr, points, cms, rounds, seed, perturb=None):
    random.seed(seed)
    tv = T.SimTV(hdr, None, seed=seed)
    st = {"version": T.STATE_VERSION, "rounds": [], "sens": {}}
    for r in range(rounds):
        meter = T.SimMeter(tv, hdr)
        meas = T.sweep(meter, hdr, verbose=False)
        if perturb and r == perturb[0]:
            meas = perturb[1](meas)
        st["rounds"].append({"time": f"2026-10-29T20:{r:02d}:00", "meas": meas, "applied": None, "kind": None,
                             "setup": {"source": "video", "meter": "iphone", "phone": None}})
        before = strip_state(st)
        plan = T.plan_round(st, hdr, points, cms)
        rep = plan["rep"]
        analyse_cases.append({"label": f"{label} r{r + 1}", "hdr": hdr, "meas": meas, "report": report_view(rep, hdr)})
        plan_cases.append({"label": f"{label} r{r + 1}", "hdr": hdr, "points": points, "cms": cms,
                           "state_before": before, "state_after": strip_state(st),
                           "plan": {k: plan.get(k) for k in ("changes", "kind", "title", "done", "message", "banner",
                                                             "notes", "discard", "stage")}})
        if plan.get("discard"):
            st["rounds"].pop()
            continue
        T.record_round(st, plan, True, tv)
        if plan["done"]:
            break


sim_run("sdr samsung 20pt", False, 20, "samsung", 8, 7)
sim_run("sdr hsl 20pt", False, 20, "hsl", 7, 11)
sim_run("sdr samsung 11pt", False, 11, "samsung", 7, 5)
sim_run("sdr off 2pt-only", False, 0, "off", 6, 3)
sim_run("hdr samsung 20pt", True, 20, "samsung", 8, 9)
sim_run("hdr hsl 11pt", True, 11, "hsl", 6, 13)


def brighter(meas):          # someone changed the TV's brightness: the round must be discarded
    out = copy.deepcopy(meas)
    for k, v in out.items():
        if isinstance(v, list):
            out[k] = [x * 1.5 for x in v]
    return out


sim_run("sdr discard", False, 20, "samsung", 4, 21, perturb=(3, brighter))


def worse(meas):             # a correction that went badly wrong: the app must ask to undo it
    out = copy.deepcopy(meas)
    for k, v in out.items():
        if isinstance(v, list) and k.startswith("G"):
            out[k] = [v[0] * 1.12, v[1], v[2] * 0.9]
    return out


sim_run("sdr undo", False, 20, "samsung", 5, 23, perturb=(4, worse))
dump("analyse.json", analyse_cases)
dump("plan.json", plan_cases)

# ------------------------------------------------------------------ guided: settled average, live reading, mismatch
settled = []
for case in range(30):
    n = rnd.randint(1, 14)
    base = [rnd.uniform(5, 150)]
    rx, rz = rnd.uniform(0.9, 1.1), rnd.uniform(0.9, 1.2)
    noise = rnd.choice([0.0005, 0.002, 0.01])
    jump_at = rnd.randint(0, n) if rnd.random() < 0.6 else None
    rs = []
    for i in range(n):
        if jump_at is not None and i == jump_at:
            rx *= 1 + rnd.uniform(-0.05, 0.05)
            rz *= 1 + rnd.uniform(-0.05, 0.05)
        Y = base[0] * (1 + rnd.gauss(0, noise))
        rs.append([rx * Y * (1 + rnd.gauss(0, noise)), Y, rz * Y * (1 + rnd.gauss(0, noise))])
    XYZ, m, wob = A.settled_average(rs)
    settled.append({"readings": rs, "xyz": XYZ, "n": m, "wobble": wob})

live = []
for hdr in (False, True):
    seq = T.build_sequence(hdr)["patches"]
    tv = T.SimTV(hdr, None, seed=4)
    for basis_mode in ("measured", "none"):
        W = tv.render(seq[0])
        basis = [tv.render(p) for p in seq if p["id"] in ("RED", "GREEN", "BLUE")] if basis_mode == "measured" else None
        for p in seq:
            XYZ = list(tv.render(p))
            for cms in ("samsung", "hsl"):
                out = A.live_reading(p, XYZ, hdr, W[1], basis, cms)
                live.append({"hdr": hdr, "patch_id": p["id"], "XYZ": XYZ, "white_Y": W[1], "basis": basis, "cms": cms,
                             "out": out})
        # a colour far outside the TV's mix, to exercise "out of reach"
        p = [q for q in seq if q["id"] == "CYAN"][0]
        XYZ = list(T.xyY_to_XYZ(0.16, 0.30, 40.0))
        live.append({"hdr": hdr, "patch_id": "CYAN", "XYZ": XYZ, "white_Y": W[1], "basis": basis, "cms": "samsung",
                     "out": A.live_reading(p, XYZ, hdr, W[1], basis, "samsung")})

mism = []
g = A.Guide()
for hdr, mode in ((False, "sdr"), (True, "hdr10")):
    g.mode = mode
    seq = T.build_sequence(hdr)["patches"]
    for white in (None, 120.0 if not hdr else 180.0):
        g.white_Y = white
        for p in seq:
            for XYZ in ([0, 0, 0], list(T.xyY_to_XYZ(*T.D65, 50.0)), list(T.xyY_to_XYZ(0.64, 0.33, 20.0)),
                        list(T.xyY_to_XYZ(*T.D65, 0.5)), list(T.xyY_to_XYZ(0.32, 0.34, 300.0))):
                mism.append({"hdr": hdr, "white_Y": white, "patch_id": p["id"], "XYZ": XYZ, "out": g.mismatch(p, XYZ)})

live_an = []
for XYZ in (list(T.xyY_to_XYZ(*T.D65, 80)), list(T.xyY_to_XYZ(0.32, 0.335, 60)), list(T.xyY_to_XYZ(0.6, 0.33, 20)),
            list(T.xyY_to_XYZ(0.3, 0.55, 50)), list(T.xyY_to_XYZ(0.16, 0.08, 6))):
    for hdr in (False, True):
        live_an.append({"XYZ": XYZ, "hdr": hdr, "out": A.live_analysis(XYZ, hdr)})
dump("guided.json", {"settled": settled, "live": live, "mismatch": mism, "live_analysis": live_an})

# ------------------------------------------------------------------ point finder
finder = []
for hdr, shift, seed in ((False, 0.10, 4), (False, 0.0, 8), (True, 0.10, 6), (True, 0.05, 2)):
    random.seed(seed)
    tv = T.SimTV(hdr, None, seed=seed)
    tv.s["p20_shift"] = shift
    seq = T.finder_sequence(hdr)
    base = T.sweep(T.SimMeter(tv, hdr, ids=seq), hdr, verbose=False, seq=seq)
    tv.apply({f"p20:{c}:{P}": A.FINDER_NUDGE for c, P in A.FINDER_POINTS})
    nudged = T.sweep(T.SimMeter(tv, hdr, ids=seq), hdr, verbose=False, seq=seq)
    base.pop("_missed", None)
    nudged.pop("_missed", None)
    for grid in (5, 10):
        fm = A.finder_map(base, nudged, hdr, grid)
        levels = {p["id"]: p["level"] * 100 for p in T.build_sequence(hdr)["patches"] if p["kind"] in ("grey", "white")}
        sugg = {pid: A.point_for_level(sorted(fm, key=lambda q: q["level"]), L, grid) for pid, L in levels.items()} if fm else {}
        finder.append({"hdr": hdr, "grid": grid, "base": base, "nudged": nudged, "map": fm,
                       "q_base": A.finder_q(base, hdr), "suggest": sugg})
pfl = []
for pairs in ([{"point": 20, "level": 25.3}], [{"point": 20, "level": 28.0}, {"point": 60, "level": 66.5}],
              [{"point": 20, "level": 30.0}, {"point": 40, "level": 47.0}, {"point": 60, "level": 69.0}, {"point": 80, "level": 88.0}]):
    for L in (5, 10, 20, 35, 50, 65, 80, 95, 100):
        for grid in (5, 10):
            pfl.append({"pairs": pairs, "L": L, "grid": grid, "out": A.point_for_level(pairs, L, grid)})
dump("finder.json", {"cases": finder, "point_for_level": pfl})
