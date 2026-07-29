#!/usr/bin/env python3
"""Validate a .fzz against the parts library Fritzing will actually load.

This exists because of a real failure: an earlier revision validated against a
fritzing-parts *git clone*, which carried a part the installed application had
never heard of. Fritzing resolves parts by moduleId out of its own bundled
library and ignores the path recorded in the sketch, so it opened with
"Unable to find 1 part(s)".

Checks performed:
  1. the archive is a valid zip holding one well-formed .fz document
  2. every moduleIdRef resolves in the target parts library
  3. every connectorId referenced exists on that part
  4. every connection is reciprocal, with no dangling modelIndex
  5. modelIndex values are unique

    python3 scripts/validate-fritzing-sketch.py [FZZ] [--parts DIR]
"""

from __future__ import annotations

import argparse
import glob
import os
import sys
import xml.etree.ElementTree as ET
import zipfile

# Parts that Fritzing provides internally rather than from the parts library.
BUILTIN_MODULE_IDS = {"WireModuleID", "NoteModuleID", "GroundPlaneModuleID"}

PARTS_SEARCH = [
    "/Applications/Fritzing.app/Contents/Resources/fritzing-parts",
    os.path.expanduser("~/Applications/Fritzing.app/Contents/Resources/fritzing-parts"),
    "/usr/share/fritzing/fritzing-parts",
]


def find_parts_dir():
    for d in PARTS_SEARCH:
        if os.path.isdir(os.path.join(d, "core")):
            return d
    return None


def index_library(parts_dir):
    """moduleId -> (relative fzp path, set of connector ids)."""
    lib = {}
    for sub in ("core", "contrib", "obsolete", "user"):
        d = os.path.join(parts_dir, sub)
        if not os.path.isdir(d):
            continue
        for f in glob.glob(os.path.join(d, "*.fzp")):
            try:
                root = ET.parse(f).getroot()
            except ET.ParseError:
                continue
            mid = root.get("moduleId")
            if not mid:
                continue
            conns = root.find("connectors")
            ids = {c.get("id") for c in conns} if conns is not None else set()
            lib[mid] = (os.path.relpath(f, parts_dir), ids)
    return lib


def validate(fzz_path, parts_dir):
    problems = []
    lib = index_library(parts_dir)
    print(f"parts library : {parts_dir}")
    print(f"                {len(lib)} moduleIds indexed")

    with zipfile.ZipFile(fzz_path) as z:
        names = z.namelist()
        fz = [n for n in names if n.endswith(".fz")]
        if not fz:
            print(f"FAIL: no .fz document inside {fzz_path}")
            return 1
        xml = z.read(fz[0]).decode("utf-8")
    root = ET.fromstring(xml)
    print(f"sketch        : {fzz_path}")
    print(f"                {fz[0]}, {len(xml)} bytes, xml OK")

    instances = root.find("instances").findall("instance")
    by_index = {}
    for inst in instances:
        mi = inst.get("modelIndex")
        if mi in by_index:
            problems.append(f"duplicate modelIndex {mi}")
        by_index[mi] = inst

    used = {}
    for inst in instances:
        mid = inst.get("moduleIdRef")
        title = inst.findtext("title") or "?"
        used.setdefault(mid, 0)
        used[mid] += 1
        if mid in BUILTIN_MODULE_IDS:
            continue
        if mid not in lib:
            problems.append(
                f"{title}: moduleIdRef NOT IN LIBRARY -> {mid}  "
                f"(Fritzing will report a missing part)"
            )
            continue
        _, valid = lib[mid]
        for conn in inst.findall(".//breadboardView/connectors/connector"):
            cid = conn.get("connectorId")
            if cid not in valid:
                problems.append(f"{title} ({mid}): unknown connectorId {cid}")

    edges = set()
    for inst in instances:
        src = inst.get("modelIndex")
        for conn in inst.findall(".//breadboardView/connectors/connector"):
            scid = conn.get("connectorId")
            for c in conn.findall("./connects/connect"):
                edges.add((src, scid, c.get("modelIndex"), c.get("connectorId")))

    non_recip = [
        f"non-reciprocal: {a}.{ac} -> {b}.{bc}"
        for (a, ac, b, bc) in edges
        if (b, bc, a, ac) not in edges
    ]
    dangling = sorted({b for (_, _, b, _) in edges if b not in by_index})
    problems += non_recip
    problems += [f"dangling modelIndex reference: {b}" for b in dangling]

    print(f"instances     : {len(instances)} ({len(by_index)} unique modelIndex)")
    print(f"endpoints     : {len(edges)}  non-reciprocal={len(non_recip)}  "
          f"dangling={len(dangling)}")
    print("parts used    :")
    for mid, n in sorted(used.items(), key=lambda kv: -kv[1]):
        where = "built-in" if mid in BUILTIN_MODULE_IDS else lib.get(mid, ("MISSING",))[0]
        print(f"                {n:>3}x  {mid[:52]:<52} {where}")

    print()
    if problems:
        print(f"FAIL: {len(problems)} problem(s)")
        for p in problems[:30]:
            print("   -", p)
        return 1
    print("PASS: every part resolves in the installed library; connections are "
          "reciprocal and complete.")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("fzz", nargs="?",
                    default="docs/diagrams/strix-takeover-breadboard.fzz")
    ap.add_argument("--parts", default=None,
                    help="parts library to check against; defaults to the "
                         "installed Fritzing app")
    args = ap.parse_args(argv)
    parts = args.parts or find_parts_dir()
    if not parts:
        print("error: no installed Fritzing parts library found; pass --parts",
              file=sys.stderr)
        return 2
    if not os.path.exists(args.fzz):
        print(f"error: {args.fzz} not found", file=sys.stderr)
        return 2
    return validate(args.fzz, parts)


if __name__ == "__main__":
    raise SystemExit(main())
