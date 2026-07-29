#!/usr/bin/env python3
"""Count wire crossings in a .fzz breadboard view.

Crossings are what bendpoint lanes exist to prevent, but nothing in the sketch
format records that intent, so they come back silently. Two ways they creep in:
dragging a part in Fritzing rubber-bands its bendpoints along instead of
re-routing, and a generated fan can be laid into separate lanes yet still be
ordered so the wires swap over each other.

This turns "does it look tangled" into a number you can regress against. A
crossing is cosmetic, not an error - the sketch is still electrically correct -
so this is deliberately separate from validate-fritzing-sketch.py, whose
PASS/FAIL means the file is sound.

Ignores segment pairs in the same logical run, and pairs that merely share an
endpoint (wires meeting at a hole are connected, not crossing).

    python3 scripts/check-fritzing-crossings.py [FZZ]

Exits non-zero if any crossing is found.
"""
import sys
import xml.etree.ElementTree as ET
import zipfile

fzz = sys.argv[1] if len(sys.argv) > 1 else (
    "/Users/stepblk/Source/strix/docs/diagrams/strix-takeover-breadboard.fzz")

with zipfile.ZipFile(fzz) as z:
    name = [n for n in z.namelist() if n.endswith(".fz")][0]
    root = ET.fromstring(z.read(name).decode("utf-8"))

insts = {i.get("modelIndex"): i for i in root.find("instances").findall("instance")}


def is_wire(mi):
    i = insts.get(mi)
    return i is not None and i.get("moduleIdRef") == "WireModuleID"


segs = {}
for mi, inst in insts.items():
    if inst.get("moduleIdRef") != "WireModuleID":
        continue
    bv = inst.find("./views/breadboardView")
    g = bv.find("geometry")
    X, Y = float(g.get("x")), float(g.get("y"))
    ends = {}
    for conn in bv.findall("./connectors/connector"):
        ends[conn.get("connectorId")] = [
            (c.get("modelIndex"), c.get("connectorId"))
            for c in conn.findall("./connects/connect")
        ]
    segs[mi] = {
        "title": inst.findtext("title") or "?",
        "a": (X + float(g.get("x1") or 0), Y + float(g.get("y1") or 0)),
        "b": (X + float(g.get("x2") or 0), Y + float(g.get("y2") or 0)),
        "ends": ends,
    }

# group segments into runs so we don't flag a run against itself
run_of, seen = {}, set()
for mi in segs:
    if mi in seen:
        continue
    comp, stack = set(), [mi]
    while stack:
        cur = stack.pop()
        if cur in comp:
            continue
        comp.add(cur)
        for tg in segs[cur]["ends"].values():
            for tmi, _ in tg:
                if is_wire(tmi) and tmi not in comp:
                    stack.append(tmi)
    seen |= comp
    names = [segs[m]["title"] for m in comp
             if not segs[m]["title"].lower().startswith("wire")
             and "_bend" not in segs[m]["title"]]
    label = names[0] if names else sorted(segs[m]["title"] for m in comp)[0]
    for m in comp:
        run_of[m] = label


def cross(p1, p2, p3, p4, eps=1e-6):
    """Proper crossing point of two segments, or None."""
    d1 = (p2[0] - p1[0], p2[1] - p1[1])
    d2 = (p4[0] - p3[0], p4[1] - p3[1])
    den = d1[0] * d2[1] - d1[1] * d2[0]
    if abs(den) < eps:
        return None
    t = ((p3[0] - p1[0]) * d2[1] - (p3[1] - p1[1]) * d2[0]) / den
    u = ((p3[0] - p1[0]) * d1[1] - (p3[1] - p1[1]) * d1[0]) / den
    tol = 1e-3
    if tol < t < 1 - tol and tol < u < 1 - tol:
        return (p1[0] + t * d1[0], p1[1] + t * d1[1])
    return None


def overlap(p1, p2, p3, p4, eps=1e-6, min_len=0.5):
    """Collinear segments sharing more than a point.

    Not a crossing - the determinant is zero so an intersection test passes them
    - but worse to look at, because one wire is drawn invisibly underneath the
    other. Reported separately.
    """
    d1 = (p2[0] - p1[0], p2[1] - p1[1])
    d2 = (p4[0] - p3[0], p4[1] - p3[1])
    if abs(d1[0] * d2[1] - d1[1] * d2[0]) > eps:
        return False
    if abs(d1[0] * (p3[1] - p1[1]) - d1[1] * (p3[0] - p1[0])) > 1e-3:
        return False
    L = (d1[0] ** 2 + d1[1] ** 2) ** 0.5
    if L < eps:
        return False

    def proj(p):
        return ((p[0] - p1[0]) * d1[0] + (p[1] - p1[1]) * d1[1]) / L

    b0, b1 = sorted((proj(p3), proj(p4)))
    return min(L, b1) - max(0.0, b0) > min_len


def shares_point(s, t, eps=0.75):
    for p in (s["a"], s["b"]):
        for q in (t["a"], t["b"]):
            if abs(p[0] - q[0]) < eps and abs(p[1] - q[1]) < eps:
                return True
    return False


keys = sorted(segs)
hits = []
laps = []
for i, mi in enumerate(keys):
    for mj in keys[i + 1:]:
        if run_of[mi] == run_of[mj]:
            continue
        s, t = segs[mi], segs[mj]
        if overlap(s["a"], s["b"], t["a"], t["b"]):
            laps.append((run_of[mi], run_of[mj], s["a"]))
        if shares_point(s, t):
            continue
        pt = cross(s["a"], s["b"], t["a"], t["b"])
        if pt:
            hits.append((run_of[mi], run_of[mj], pt))

print(f"{fzz}")
print(f"wire segments: {len(segs)}   logical runs: {len(set(run_of.values()))}\n")
if not hits:
    print("no wire crossings")
else:
    seen_pairs = {}
    for a, b, pt in hits:
        seen_pairs.setdefault(tuple(sorted((a, b))), []).append(pt)
    print(f"{len(hits)} crossing(s) among {len(seen_pairs)} run pair(s):\n")
    for (a, b), pts in sorted(seen_pairs.items()):
        where = ", ".join(f"({p[0]:.1f},{p[1]:.1f})" for p in pts)
        print(f"  {a}  x  {b}")
        print(f"      at {where}")

if laps:
    pairs = sorted({tuple(sorted((a, b))) for a, b, _ in laps})
    print(f"\n{len(laps)} collinear overlap(s) among {len(pairs)} run pair(s) "
          f"- one wire hidden under another:\n")
    for a, b in pairs:
        print(f"  {a}  ==  {b}")

raise SystemExit(1 if (hits or laps) else 0)
