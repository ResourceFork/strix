#!/usr/bin/env python3
"""Generate the Strix breadboard diagram as a Fritzing sketch (.fzz).

Why a generator instead of a hand-drawn file: a .fzz is a zipped XML document
whose parts are referenced by exact moduleId/connectorId, and whose placement is
absolute scene coordinates. Generating it means the diagram is reproducible,
reviewable as a diff, and provably consistent with the wiring the docs describe.

Geometry model (verified against fritzing-app source and shipped sketches):

  * Scene units are 90 per inch.            GraphicsUtils::SVGDPI = 90
  * A part's SVG maps to scene by its declared physical width:
        k = (width_in_inches * 90) / viewBox_width
    where "px" units mean 72 dpi if the SVG has a top-level comment containing
    "Generator: Adobe Illustrator", else 90 dpi.
        TextUtils::convertToInches() / isIllustratorDoc()
  * A connector's scene position is
        instance_xy + QTransform(m11,m12,m21,m22,m31,m32).map(connector_offset)
    with Qt's convention x' = m11*x + m21*y + m31, y' = m12*x + m22*y + m32.
    Validated to 0.001 scene units against AnalogInputPot.fzz.
  * Wire endpoints are instance_xy + (x1,y1) and instance_xy + (x2,y2);
    ViewGeometry::NormalFlag = 64 marks an ordinary wire.

Requires a local clone of fritzing-parts for part geometry:
    python3 scripts/generate-fritzing-sketch.py [--parts DIR] [--out FILE]
"""

from __future__ import annotations

import argparse
import html
import itertools
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

SCENE_DPI = 90.0
SVG_NS = "{http://www.w3.org/2000/svg}"
AI_MARKER = "generator: adobe illustrator"
NORMAL_WIRE_FLAG = 64

# Wire colours (Fritzing's own palette values).
# Fritzing's own wire-palette swatches. Arbitrary hex values render but do not
# match any swatch, so the colour picker shows them as custom and they look
# subtly off next to hand-drawn wires. These are the real values.
BLACK = "#404040"  # Fritzing's "black" is dark grey, not #000000
RED = "#cc1414"
BLUE = "#418dd9"
GREEN = "#25cc35"
ORANGE = "#ef6100"
PURPLE = "#ab58a2"
YELLOW = "#fff800"
WIRE_MILS = "24"

# Colour by ROLE, so the same signal reads the same everywhere:
#   BLACK  ground, every one of them
#   RED    5V
#   ORANGE 3V3 (distinct from 5V on purpose - miswiring these kills the ToF)
#   BLUE   throttle channel, and I2C SDA, and ultrasonic ECHO
#   GREEN  steering channel, and I2C SCL
#   YELLOW ultrasonic TRIG (paired with blue ECHO so the two are never confused)
#   PURPLE controller rail sense into A0


# --------------------------------------------------------------------------
# Part geometry
# --------------------------------------------------------------------------


def to_inches(value: str, illustrator: bool):
    v = (value or "").strip()
    m = re.fullmatch(r"([\d.eE+-]+)\s*(cm|mm|in|px|mil|pt|pc)?", v)
    if not m:
        m = re.match(r"([\d.eE+-]+)", v)
        return float(m.group(1)) / 90.0 if m else None
    num = float(m.group(1))
    div = {
        "cm": 2.54,
        "mm": 25.4,
        "in": 1.0,
        "px": 72.0 if illustrator else 90.0,
        "mil": 1000.0,
        "pt": 72.0,
        "pc": 6.0,
        None: 90.0,
    }[m.group(2)]
    return num / div


class Part:
    """A Fritzing part, with connector offsets resolved into scene units."""

    def __init__(self, parts_dir: str, fzp_rel: str):
        self.parts_dir = parts_dir
        self.fzp_rel = fzp_rel
        root = ET.parse(os.path.join(parts_dir, fzp_rel)).getroot()
        self.root = root
        self.module_id = root.get("moduleId")
        self.title = root.findtext("title")
        self.svg_rel = root.find("./views/breadboardView/layers").get("image")
        svg_path = os.path.join(parts_dir, "svg", "core", self.svg_rel)
        with open(svg_path, encoding="utf-8") as fh:
            text = fh.read()
        self.illustrator = AI_MARKER in text.split("<svg", 1)[0].lower()
        sroot = ET.fromstring(text)
        vb = sroot.get("viewBox").split()
        w_in = to_inches(sroot.get("width"), self.illustrator)
        h_in = to_inches(sroot.get("height"), self.illustrator)
        self.kx = (w_in * SCENE_DPI) / float(vb[2])
        self.ky = (h_in * SCENE_DPI) / float(vb[3])
        self.scene_w = w_in * SCENE_DPI
        self.scene_h = h_in * SCENE_DPI
        self.conn_svgid = {}
        self.conn_name = {}
        for c in root.find("connectors"):
            self.conn_name[c.get("id")] = c.get("name")
            p = c.find("./views/breadboardView/p")
            if p is not None:
                self.conn_svgid[c.get("id")] = p.get("svgId")
        self._centers = self._collect_centers(sroot)

    @staticmethod
    def _collect_centers(sroot):
        out = {}

        def rect_center(el, dx, dy):
            x, y, w, h = el.get("x"), el.get("y"), el.get("width"), el.get("height")
            if None in (x, y, w, h):
                return None
            return float(x) + float(w) / 2 + dx, float(y) + float(h) / 2 + dy

        def walk(el, dx, dy):
            tr = el.get("transform") or ""
            for m in re.finditer(
                r"translate\(\s*([-\d.eE]+)(?:[ ,]+([-\d.eE]+))?\s*\)", tr
            ):
                dx += float(m.group(1))
                dy += float(m.group(2) or 0.0)
            eid = el.get("id")
            if eid:
                pos = None
                if el.get("cx") is not None:
                    pos = (float(el.get("cx")) + dx, float(el.get("cy")) + dy)
                elif el.tag == SVG_NS + "rect":
                    pos = rect_center(el, dx, dy)
                if pos is None:
                    for ch in el.iter():
                        if ch is el:
                            continue
                        if ch.tag == SVG_NS + "circle" and ch.get("cx"):
                            pos = (float(ch.get("cx")) + dx, float(ch.get("cy")) + dy)
                            break
                        if ch.tag == SVG_NS + "rect":
                            pos = rect_center(ch, dx, dy)
                            if pos:
                                break
                if pos:
                    out[eid] = pos
            for ch in el:
                walk(ch, dx, dy)

        walk(sroot, 0.0, 0.0)
        return out

    def offset(self, connector_id: str):
        """Connector offset from the part's top-left, in scene units."""
        svgid = self.conn_svgid.get(connector_id)
        c = self._centers.get(svgid)
        if c is None:
            return None
        return c[0] * self.kx, c[1] * self.ky

    def connector_by_name(self, name: str):
        for cid, nm in self.conn_name.items():
            if nm == name:
                return cid
        return None


class Breadboard(Part):
    def hole(self, col: int, row: str):
        """Offset of a hole from the breadboard's top-left, in scene units."""
        c = self._centers.get(f"pin{col}{row}")
        if c is None:
            raise KeyError(f"no hole pin{col}{row}")
        return c[0] * self.kx, c[1] * self.ky

    def hole_connector(self, col: int, row: str) -> str:
        want = f"pin{col}{row}"
        for cid, svgid in self.conn_svgid.items():
            if svgid == want:
                return cid
        raise KeyError(want)

    def columns_in_row(self, row: str):
        out = []
        for svgid in self.conn_svgid.values():
            m = re.fullmatch(rf"pin(\d+){row}", svgid or "")
            if m:
                out.append(int(m.group(1)))
        return sorted(out)

    def nearest_col(self, col: int, row: str) -> int:
        """Snap to a hole that exists. Rail rows skip a column every five, so a
        computed rail column is often not a real hole."""
        cols = self.columns_in_row(row)
        if not cols:
            raise KeyError(f"row {row} has no holes")
        return min(cols, key=lambda c: (abs(c - col), c))


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
# 90 degrees counter-clockwise: (x, y) -> (y, -x), then shifted back into
# positive space by the part's own width. m32 is filled in per-part.
ROT_CCW = (0.0, -1.0, 1.0, 0.0, 0.0, None)


def qt_map(m, p):
    m11, m12, m21, m22, m31, m32 = m
    return (m11 * p[0] + m21 * p[1] + m31, m12 * p[0] + m22 * p[1] + m32)


# --------------------------------------------------------------------------
# Orthogonal routing
#
# Wires are drawn like PCB raceways: horizontal and vertical only, turning in
# lanes on the 0.1in hole lattice. A group of wires leaving the same connector
# column fans out through a band of vertical lanes, one lane each.
#
# Which wire gets which lane decides whether the fan is clean or tangled, and
# the answer is not intuitive - a wire heading to a deeper row generally has to
# turn before one heading to a shallower row, but the source order fights that.
# So rather than reason it out per module, solve_comb searches lane orderings
# and returns one that provably does not cross itself.
# --------------------------------------------------------------------------


def seg_cross(p1, p2, p3, p4, eps=1e-9, tol=1e-3):
    """True if two segments properly cross (touching at an end doesn't count)."""
    d1 = (p2[0] - p1[0], p2[1] - p1[1])
    d2 = (p4[0] - p3[0], p4[1] - p3[1])
    den = d1[0] * d2[1] - d1[1] * d2[0]
    if abs(den) < eps:
        return False
    t = ((p3[0] - p1[0]) * d2[1] - (p3[1] - p1[1]) * d2[0]) / den
    u = ((p3[0] - p1[0]) * d1[1] - (p3[1] - p1[1]) * d1[0]) / den
    return tol < t < 1 - tol and tol < u < 1 - tol


def seg_overlap(p1, p2, p3, p4, eps=1e-6, min_len=0.5):
    """True if two segments are collinear and share more than a point.

    Two wires lying on top of each other are not a crossing - the determinant is
    zero, so an intersection test says they are fine - but they are worse to look
    at than a crossing, because one wire is simply invisible. Worth scoring.
    """
    d1 = (p2[0] - p1[0], p2[1] - p1[1])
    d2 = (p4[0] - p3[0], p4[1] - p3[1])
    if abs(d1[0] * d2[1] - d1[1] * d2[0]) > eps:
        return False  # not parallel
    # parallel: also require collinear, i.e. p3 on the line through p1,p2
    if abs(d1[0] * (p3[1] - p1[1]) - d1[1] * (p3[0] - p1[0])) > 1e-3:
        return False
    L = (d1[0] ** 2 + d1[1] ** 2) ** 0.5
    if L < eps:
        return False
    # project both segments onto the shared direction and intersect the spans
    def proj(p):
        return ((p[0] - p1[0]) * d1[0] + (p[1] - p1[1]) * d1[1]) / L

    a0, a1 = 0.0, L
    b0, b1 = sorted((proj(p3), proj(p4)))
    return min(a1, b1) - max(a0, b0) > min_len


def polyline_crossings(a, b):
    """How many times polyline a crosses or overlaps polyline b."""
    n = 0
    for s0, s1 in zip(a, a[1:]):
        for t0, t1 in zip(b, b[1:]):
            if seg_cross(s0, s1, t0, t1) or seg_overlap(s0, s1, t0, t1):
                n += 1
    return n


def dogleg(p0, p1, turn_x):
    """Right-angled route: out horizontally, along a vertical lane, then in.

    For connectors in a vertical column, wiring off to the side.
    """
    if abs(p0[1] - p1[1]) < 1e-6:
        return [p0, p1]  # already level: a straight run is prettier
    return [p0, (turn_x, p0[1]), (turn_x, p1[1]), p1]


def dogleg_y(p0, p1, turn_y):
    """Right-angled route: out vertically, along a horizontal lane, then in.

    For connectors in a horizontal row, wiring above or below. Where a pin sits
    directly over its target the whole route collapses to one straight drop,
    which is the tidiest outcome available and worth keeping.
    """
    if abs(p0[0] - p1[0]) < 1e-6:
        return [p0, p1]
    return [p0, (p0[0], turn_y), (p1[0], turn_y), p1]


def solve_comb(endpoints, lanes, axis="x"):
    """Assign each (p0, p1) its own lane so the bundle doesn't cross itself.

    axis="x" routes through vertical lanes (for pins in a column), axis="y"
    through horizontal lanes (for pins in a row). Tries lane orderings and keeps
    the best. Returns (crossings, [pts, ...]) in the caller's original order;
    crossings == 0 means provably clean.

    Searching beats reasoning here. The intuitive rule - wires heading further
    turn later - is wrong often enough to matter, because the pin order fights
    the destination order whenever a connector puts power on its outside pins
    and signal on its inside ones, which is most of them.
    """
    n = len(endpoints)
    if n > len(lanes):
        raise ValueError(f"need {n} lanes, got {len(lanes)}")
    bend = dogleg if axis == "x" else dogleg_y
    best = None
    for perm in itertools.permutations(range(n)):
        routes = [None] * n
        for slot, idx in enumerate(perm):
            p0, p1 = endpoints[idx]
            routes[idx] = bend(p0, p1, lanes[slot])
        total = sum(
            polyline_crossings(routes[i], routes[j])
            for i in range(n)
            for j in range(i + 1, n)
        )
        if best is None or total < best[0]:
            best = (total, routes)
        if total == 0:
            break
    return best


# --------------------------------------------------------------------------
# Sketch model
# --------------------------------------------------------------------------


class Sketch:
    def __init__(self, parts_dir: str):
        self.parts_dir = parts_dir
        self.instances = []
        self.next_index = 100000
        self.checks = []

    def _index(self):
        self.next_index += 7
        return self.next_index

    def add_part(self, part: Part, x, y, title, transform=IDENTITY, props=None):
        inst = {
            "kind": "part",
            "part": part,
            "module_id": part.module_id,
            "path": os.path.join(self.parts_dir, part.fzp_rel),
            "index": self._index(),
            "x": x,
            "y": y,
            "title": title,
            "transform": transform,
            "props": props or {},
            "connects": {},
        }
        self.instances.append(inst)
        return inst

    def add_wire(self, p0, p1, color, title):
        inst = {
            "kind": "wire",
            "module_id": "WireModuleID",
            "path": ":/resources/parts/core/wire.fzp",
            "index": self._index(),
            "x": p0[0],
            "y": p0[1],
            "x2": p1[0] - p0[0],
            "y2": p1[1] - p0[1],
            "color": color,
            "title": title,
            "connects": {"connector0": [], "connector1": []},
        }
        self.instances.append(inst)
        return inst

    def add_wire_path(self, points, color, title):
        """One logical wire drawn through bendpoints.

        Fritzing has no multi-point wire: dragging a bendpoint into a wire
        splits it into separate WireModuleID instances joined end to end. This
        emits that chain and returns the first and last segment so the caller
        can attach the real anchors.
        """
        segments = []
        for i in range(len(points) - 1):
            segments.append(
                self.add_wire(
                    points[i],
                    points[i + 1],
                    color,
                    title if i == 0 else f"{title}_bend{i}",
                )
            )
        for a, b in zip(segments, segments[1:]):
            self.join(a, "connector1", b, "connector0",
                      "breadboardWire", "breadboardWire")
        return segments[0], segments[-1]

    def add_note(self, x, y, w, h, text, title):
        self.instances.append(
            {
                "kind": "note",
                "module_id": "NoteModuleID",
                "path": ":/resources/parts/core/note.fzp",
                "index": self._index(),
                "x": x,
                "y": y,
                "w": w,
                "h": h,
                "text": text,
                "title": title,
            }
        )

    @staticmethod
    def join(a, a_conn, b, b_conn, a_layer, b_layer):
        """Record a reciprocal connection between two instances."""
        a["connects"].setdefault(a_conn, []).append((b_conn, b["index"], b_layer))
        b["connects"].setdefault(b_conn, []).append((a_conn, a["index"], a_layer))

    def connector_scene(self, inst, conn_id):
        off = inst["part"].offset(conn_id)
        if off is None:
            return None
        mapped = qt_map(inst["transform"], off)
        return inst["x"] + mapped[0], inst["y"] + mapped[1]

    # -- emit -------------------------------------------------------------

    def to_xml(self, name):
        out = []
        out.append('<?xml version="1.0" encoding="UTF-8"?>')
        out.append('<module fritzingVersion="1.0.3" icon="">')
        out.append("    <views>")
        out.append(
            '        <view name="breadboardView" backgroundColor="#ffffff" '
            'gridSize="0.05in" showGrid="1" alignToGrid="1" viewFromBelow="0"/>'
        )
        out.append(
            '        <view name="schematicView" backgroundColor="#ffffff" '
            'gridSize="0.1in" showGrid="1" alignToGrid="1" viewFromBelow="0"/>'
        )
        out.append(
            '        <view name="pcbView" backgroundColor="#333333" '
            'gridSize="0.05in" showGrid="1" alignToGrid="1" viewFromBelow="0"/>'
        )
        out.append("    </views>")
        out.append("    <instances>")
        for inst in self.instances:
            out.append(self._instance_xml(inst))
        out.append("    </instances>")
        out.append("</module>")
        return "\n".join(out) + "\n"

    def _connects_xml(self, conns, indent):
        pad = " " * indent
        lines = []
        for conn_id, targets in sorted(conns.items()):
            if not targets:
                continue
            lines.append(f'{pad}<connector connectorId="{conn_id}" layer="breadboard">')
            lines.append(f"{pad}    <geometry x=\"0\" y=\"0\"/>")
            lines.append(f"{pad}    <connects>")
            for tconn, tindex, tlayer in targets:
                lines.append(
                    f'{pad}        <connect connectorId="{tconn}" '
                    f'modelIndex="{tindex}" layer="{tlayer}"/>'
                )
            lines.append(f"{pad}    </connects>")
            lines.append(f"{pad}</connector>")
        return lines

    def _instance_xml(self, inst):
        pad = " " * 8
        L = []
        L.append(
            f'{pad}<instance moduleIdRef="{inst["module_id"]}" '
            f'modelIndex="{inst["index"]}" path="{inst["path"]}">'
        )
        if inst["kind"] == "part":
            for k, v in inst["props"].items():
                L.append(f'{pad}    <property name="{k}" value="{v}"/>')
        L.append(f'{pad}    <title>{inst["title"]}</title>')
        if inst["kind"] == "note":
            L.append(f'{pad}    <text>{html.escape(inst["text"])}</text>')
        L.append(f"{pad}    <views>")

        if inst["kind"] == "note":
            L.append(f'{pad}        <breadboardView layer="breadboardNote">')
            L.append(
                f'{pad}            <geometry z="9.5" x="{inst["x"]:.4f}" '
                f'y="{inst["y"]:.4f}" width="{inst["w"]}" height="{inst["h"]}"/>'
            )
            L.append(f"{pad}        </breadboardView>")
        elif inst["kind"] == "wire":
            L.append(f'{pad}        <breadboardView layer="breadboardWire">')
            L.append(
                f'{pad}            <geometry z="4.5" x="{inst["x"]:.4f}" '
                f'y="{inst["y"]:.4f}" x1="0" y1="0" '
                f'x2="{inst["x2"]:.4f}" y2="{inst["y2"]:.4f}" '
                f'wireFlags="{NORMAL_WIRE_FLAG}"/>'
            )
            L.append(
                f'{pad}            <wireExtras mils="{WIRE_MILS}" '
                f'color="{inst["color"]}" opacity="1" banded="0"/>'
            )
            L.append(f"{pad}            <connectors>")
            for conn_id in ("connector0", "connector1"):
                targets = inst["connects"].get(conn_id) or []
                if not targets:
                    continue
                L.append(
                    f'{pad}                <connector connectorId="{conn_id}" '
                    'layer="breadboardWire">'
                )
                L.append(f'{pad}                    <geometry x="0" y="0"/>')
                L.append(f"{pad}                    <connects>")
                for tconn, tindex, tlayer in targets:
                    L.append(
                        f'{pad}                        <connect connectorId="{tconn}" '
                        f'modelIndex="{tindex}" layer="{tlayer}"/>'
                    )
                L.append(f"{pad}                    </connects>")
                L.append(f"{pad}                </connector>")
            L.append(f"{pad}            </connectors>")
            L.append(f"{pad}        </breadboardView>")
        else:
            layer = "breadboardbreadboard" if inst.get("is_breadboard") else "breadboard"
            L.append(f'{pad}        <breadboardView layer="{layer}">')
            t = inst["transform"]
            if t != IDENTITY:
                L.append(
                    f'{pad}            <geometry z="2.5" x="{inst["x"]:.4f}" '
                    f'y="{inst["y"]:.4f}">'
                )
                L.append(
                    f'{pad}                <transform m11="{t[0]:.6f}" '
                    f'm12="{t[1]:.6f}" m13="0" m21="{t[2]:.6f}" m22="{t[3]:.6f}" '
                    f'm23="0" m31="{t[4]:.4f}" m32="{t[5]:.4f}" m33="1"/>'
                )
                L.append(f"{pad}            </geometry>")
            else:
                L.append(
                    f'{pad}            <geometry z="2.5" x="{inst["x"]:.4f}" '
                    f'y="{inst["y"]:.4f}"/>'
                )
            if inst["connects"]:
                L.append(f"{pad}            <connectors>")
                L += self._connects_xml(inst["connects"], 16 + 8)
                L.append(f"{pad}            </connectors>")
            L.append(f"{pad}        </breadboardView>")

        # Give the other two views a position so nothing piles up at the origin.
        if inst["kind"] != "note":
            sx = 100 + (inst["index"] % 9) * 40
            sy = 100 + (inst["index"] % 7) * 40
            if inst["kind"] == "wire":
                L.append(
                    f'{pad}        <schematicView layer="schematicTrace">'
                    f'<geometry z="3.5" x="{sx}" y="{sy}" x1="0" y1="0" '
                    f'x2="20" y2="0" wireFlags="0"/></schematicView>'
                )
                L.append(
                    f'{pad}        <pcbView layer="copper0">'
                    f'<geometry z="3.5" x="{sx}" y="{sy}" x1="0" y1="0" '
                    f'x2="20" y2="0" wireFlags="0"/></pcbView>'
                )
            else:
                # The breadboard part keeps its own layer name in every view,
                # matching what Fritzing itself writes.
                other = (
                    "breadboardbreadboard"
                    if inst.get("is_breadboard")
                    else "schematic"
                )
                pcb = "breadboardbreadboard" if inst.get("is_breadboard") else "copper0"
                L.append(
                    f'{pad}        <schematicView layer="{other}">'
                    f'<geometry z="2.5" x="{sx}" y="{sy}"/></schematicView>'
                )
                L.append(
                    f'{pad}        <pcbView layer="{pcb}">'
                    f'<geometry z="2.5" x="{sx}" y="{sy}"/></pcbView>'
                )
        L.append(f"{pad}    </views>")
        L.append(f"{pad}</instance>")
        return "\n".join(L)


def note_html(body: str) -> str:
    paras = "".join(
        '<p style=" margin-top:0px; margin-bottom:0px; margin-left:0px; '
        'margin-right:0px; -qt-block-indent:0; text-indent:0px;">'
        f"{html.escape(line) if line else '<br />'}</p>"
        for line in body.split("\n")
    )
    return (
        '<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0//EN" '
        '"http://www.w3.org/TR/REC-html40/strict.dtd">\n'
        '<html><head><meta name="qrichtext" content="1" />'
        '<style type="text/css">\np, li { white-space: pre-wrap; }\n'
        "</style></head>"
        '<body style=" font-family:\'MS Shell Dlg 2\'; font-size:8.25pt; '
        'font-weight:400; font-style:normal;">'
        f"{paras}</body></html>"
    )


# --------------------------------------------------------------------------
# The Strix layout
# --------------------------------------------------------------------------

# Breadboard row -> which half it belongs to is implicit; these are the rows the
# layout uses. Rails: Z = top ground, Y = top +, X = bottom ground, W = bottom +
# (established from the blue/red marker lines in breadboard2.svg).
TOP_GND, TOP_POS, BOT_GND, BOT_POS = "Z", "Y", "X", "W"

NANO_FIRST_COL = 2  # leftmost Nano pin column
NANO_UPPER_ROW = "H"  # digital side: D1..D12 (so D9/D10 live here)
NANO_LOWER_ROW = "D"  # analog side: VIN/5V/A7..A0/3V3/D13

# The Nano's SVG is vertical with the ICSP header at the low-y edge and the USB
# connector at the high-y edge. Rotating 90 degrees CLOCKWISE maps local y to
# decreasing scene x, which puts the USB end on the LEFT:
#     map(x, y) = (scene_h - y, x)
# so the digital pin row (local x = 4.5) becomes the upper breadboard row and
# pin k sits at column NANO_FIRST_COL + (14 - k).
NANO_PIN_COUNT = 15


def nano_col(first_col: int, k: int) -> int:
    return first_col + (NANO_PIN_COUNT - 1 - k)


# Pin index along each header, counting from the ICSP end (k = 0).
DIGITAL_K = {
    "D1": 0, "D0": 1, "RESET": 2, "GND": 3, "D2": 4, "D3": 5, "D4": 6,
    "D5": 7, "D6": 8, "D7": 9, "D8": 10, "D9": 11, "D10": 12, "D11": 13,
    "D12": 14,
}
ANALOG_K = {
    "VIN": 0, "GND": 1, "RESET": 2, "5V": 3, "A7": 4, "A6": 5, "A5": 6,
    "A4": 7, "A3": 8, "A2": 9, "A1": 10, "A0": 11, "AREF": 12, "3V3": 13,
    "D13": 14,
}


def build(parts_dir: str, out_path: str) -> int:
    bb = Breadboard(parts_dir, "core/breadboard2.fzp")
    nano = Part(parts_dir, "core/Arduino Nano3(fix).fzp")
    res = Part(parts_dir, "core/resistor.fzp")
    cap = Part(parts_dir, "core/capacitor_ceramic_100mil.fzp")
    # Use the part that ships with the Fritzing application. A fritzing-parts
    # git clone can be ahead of the installed release and carry a replacement
    # (hc-sr04_bf8299a_002) whose moduleId the app cannot resolve, which makes
    # the sketch open with "Unable to find 1 part(s)".
    sr04 = Part(parts_dir, "core/HC-SR04 Ultrasonic Distance Sensor.fzp")
    shift = Part(parts_dir, "core/I2C level shifter bidirectional.fzp")
    # No VL53L4CD part exists in the library, so a 4-pin header stands in for
    # the module's Qwiic breakout; the note names it explicitly.
    tof_part = Part(parts_dir, "core/sparkfun-connectors-m04-jst-pth.fzp")
    # A polarized 3-pin connector stands in for the sockets on the controller
    # board that the trigger and wheel pot harnesses plug into.
    pot_conn = Part(parts_dir, "core/sparkfun-connectors-m03-polar_lock.fzp")

    sk = Sketch(parts_dir)

    BX, BY = 120.0, 220.0
    bb_inst = sk.add_part(bb, BX, BY, "Breadboard1")
    bb_inst["is_breadboard"] = True

    RAIL_ROWS = (TOP_GND, TOP_POS, BOT_GND, BOT_POS)

    def snap(col, row):
        """Rail rows have gaps; land on a hole that actually exists."""
        return bb.nearest_col(col, row) if row in RAIL_ROWS else col

    def hole_scene(col, row):
        hx, hy = bb.hole(snap(col, row), row)
        return BX + hx, BY + hy

    def hole_cid(col, row):
        return bb.hole_connector(snap(col, row), row)

    def tie(part_inst, conn_id, col, row):
        """Declare a part connector as plugged into a hole, and check geometry."""
        sk.join(
            part_inst,
            conn_id,
            bb_inst,
            hole_cid(col, row),
            "breadboard",
            "breadboardbreadboard",
        )
        want = hole_scene(col, row)
        got = sk.connector_scene(part_inst, conn_id)
        if got:
            err = ((got[0] - want[0]) ** 2 + (got[1] - want[1]) ** 2) ** 0.5
            sk.checks.append((part_inst["title"], conn_id, f"pin{col}{row}", err))

    def wire_hole_to_hole(c0, r0, c1, r1, color, title, bends=()):
        p0, p1 = hole_scene(c0, r0), hole_scene(c1, r1)
        first, last = sk.add_wire_path([p0, *bends, p1], color, title)
        sk.join(first, "connector0", bb_inst, hole_cid(c0, r0),
                "breadboardWire", "breadboardbreadboard")
        sk.join(last, "connector1", bb_inst, hole_cid(c1, r1),
                "breadboardWire", "breadboardbreadboard")
        return first

    def wire_hole_to_free(c0, r0, dx, dy, color, title):
        p0 = hole_scene(c0, r0)
        p1 = (p0[0] + dx, p0[1] + dy)
        w = sk.add_wire(p0, p1, color, title)
        sk.join(w, "connector0", bb_inst, hole_cid(c0, r0),
                "breadboardWire", "breadboardbreadboard")
        return w

    def place_by_connector(inst, conn_id, col, row):
        """Shift a part so one of its connectors lands exactly in a hole."""
        off = qt_map(inst["transform"], inst["part"].offset(conn_id))
        target = hole_scene(col, row)
        inst["x"], inst["y"] = target[0] - off[0], target[1] - off[1]

    def wire_conn_to_hole(inst, conn_id, col, row, color, title, bends=()):
        p0 = sk.connector_scene(inst, conn_id)
        p1 = hole_scene(col, row)
        first, last = sk.add_wire_path([p0, *bends, p1], color, title)
        sk.join(first, "connector0", inst, conn_id, "breadboardWire", "breadboard")
        sk.join(last, "connector1", bb_inst, hole_cid(col, row),
                "breadboardWire", "breadboardbreadboard")
        return first

    def wire_conn_to_conn(a, ac, b, bc, color, title, bends=()):
        p0, p1 = sk.connector_scene(a, ac), sk.connector_scene(b, bc)
        first, last = sk.add_wire_path([p0, *bends, p1], color, title)
        sk.join(first, "connector0", a, ac, "breadboardWire", "breadboard")
        sk.join(last, "connector1", b, bc, "breadboardWire", "breadboard")
        return first

    # ---- Arduino Nano, rotated 90 CW so the USB end faces the left edge ----
    nano_t = (0.0, 1.0, -1.0, 0.0, nano.scene_h, 0.0)
    nano_x = hole_scene(nano_col(NANO_FIRST_COL, 0), NANO_UPPER_ROW)[0] - qt_map(
        nano_t, (4.5, 13.5)
    )[0]
    nano_y = hole_scene(NANO_FIRST_COL, NANO_UPPER_ROW)[1] - qt_map(
        nano_t, (4.5, 13.5)
    )[1]
    nano_inst = sk.add_part(nano, nano_x, nano_y, "Nano1", transform=nano_t)
    for k in range(NANO_PIN_COUNT):
        col = nano_col(NANO_FIRST_COL, k)
        tie(nano_inst, f"connector{16 + k}", col, NANO_UPPER_ROW)  # digital side
        tie(nano_inst, f"connector{31 + k}", col, NANO_LOWER_ROW)  # analog side

    dig = {n: nano_col(NANO_FIRST_COL, k) for n, k in DIGITAL_K.items()}
    ana = {n: nano_col(NANO_FIRST_COL, k) for n, k in ANALOG_K.items()}

    # ---- power rails ----
    # Digital-side GND feeds the top ground rail; analog-side GND the bottom one.
    wire_hole_to_hole(dig["GND"], "I", dig["GND"], TOP_GND, BLACK, "GND_top")
    wire_hole_to_hole(ana["GND"], "C", ana["GND"], BOT_GND, BLACK, "GND_bottom")
    wire_hole_to_hole(ana["5V"], "B", ana["5V"], BOT_POS, RED, "5V_to_bottom_rail")
    wire_hole_to_hole(58, BOT_POS, 58, TOP_POS, RED, "5V_rail_bridge")

    # ---- throttle: D9 -> across the channel -> R1 -> C1 -> ground ----
    # Runs live in row I; the drop column must be past the Nano (>16) so the
    # top and bottom halves of that column are separate nodes.
    wire_hole_to_hole(dig["D9"], "I", 20, "I", BLUE, "D9_run")
    wire_hole_to_hole(20, "F", 20, "E", BLUE, "D9_cross_channel")
    r1 = sk.add_part(res, 0, 0, "R1", props={"resistance": "4.7kΩ"})
    place_by_connector(r1, "connector0", 20, "D")
    tie(r1, "connector0", 20, "D")
    tie(r1, "connector1", 24, "D")
    c1 = sk.add_part(cap, 0, 0, "C1", props={"capacitance": "1µF"})
    place_by_connector(c1, "connector0", 24, "C")
    tie(c1, "connector0", 24, "C")
    tie(c1, "connector1", 25, "C")
    wire_hole_to_hole(25, "B", 25, BOT_GND, BLACK, "C1_to_ground")

    # ---- steering: D10 -> across the channel -> R2 -> C2 -> ground ----
    wire_hole_to_hole(dig["D10"], "J", 32, "J", GREEN, "D10_run")
    wire_hole_to_hole(32, "F", 32, "E", GREEN, "D10_cross_channel")
    r2 = sk.add_part(res, 0, 0, "R2", props={"resistance": "4.7kΩ"})
    place_by_connector(r2, "connector0", 32, "D")
    tie(r2, "connector0", 32, "D")
    tie(r2, "connector1", 36, "D")
    c2 = sk.add_part(cap, 0, 0, "C2", props={"capacitance": "1µF"})
    place_by_connector(c2, "connector0", 36, "C")
    tie(c2, "connector0", 36, "C")
    tie(c2, "connector1", 37, "C")
    wire_hole_to_hole(37, "B", 37, BOT_GND, BLACK, "C2_to_ground")

    # Bring the A0 rail-sense net along the board to sit near the connectors,
    # so its wire out is short instead of crossing the whole sketch.
    wire_hole_to_hole(ana["A0"], "B", 21, "B", PURPLE, "A0_rail_sense_run")

    # ---- ultrasonics: HC-SR04 x2, above the board, on D2..D5 ----
    # D2/D3 (front-left) sit at columns 12/11 and D4/D5 (front-right) at 10/9,
    # so the modules are offset in that same order - left one to the right of
    # the pair, right one to the left - and their wires never cross each other.
    # Both modules are ~1.8in wide but their four pins span only 4 columns, so
    # they sit side by side straddling that pin group. Front-right takes the
    # left slot (its pins D4/D5 are the lower columns) so no wire crosses the
    # other module. Offsets keep every run under about an inch.
    SR_LIFT = 120.0
    sr_right = sk.add_part(sr04, 0, 0, "SR04_frontRight")
    place_by_connector(sr_right, "connector1", dig["D4"], "J")
    sr_right["x"] -= 82.0
    sr_right["y"] -= SR_LIFT
    wire_conn_to_hole(sr_right, "connector1", dig["D4"], "J", YELLOW, "SR04R_TRIG_D4")
    wire_conn_to_hole(sr_right, "connector2", dig["D5"], "J", BLUE, "SR04R_ECHO_D5")
    wire_conn_to_hole(sr_right, "connector0", 3, TOP_POS, RED, "SR04R_VCC")
    wire_conn_to_hole(sr_right, "connector3", 5, TOP_GND, BLACK, "SR04R_GND")

    sr_left = sk.add_part(sr04, 0, 0, "SR04_frontLeft")
    place_by_connector(sr_left, "connector1", dig["D2"], "J")
    sr_left["x"] += 61.0
    sr_left["y"] -= SR_LIFT
    wire_conn_to_hole(sr_left, "connector1", dig["D2"], "J", YELLOW, "SR04L_TRIG_D2")
    wire_conn_to_hole(sr_left, "connector2", dig["D3"], "J", BLUE, "SR04L_ECHO_D3")
    wire_conn_to_hole(sr_left, "connector0", 18, TOP_POS, RED, "SR04L_VCC")
    wire_conn_to_hole(sr_left, "connector3", 19, TOP_GND, BLACK, "SR04L_GND")

    # ---- 3.3V time-of-flight sensor, behind a bidirectional I2C level shifter --
    # The Nano's I2C lines are 5V; the VL53L4CD module is 3.3V-only and not 5V
    # tolerant, so the shifter sits between them. HV side faces the Nano.
    # Sit the shifter directly below the Nano's A4/A5 pins so its HV wires are
    # short verticals rather than diagonals across the whole board.
    shifter = sk.add_part(shift, 0, 0, "LevelShifter1")
    hv1 = shift.connector_by_name("HV1")
    shifter["x"] = hole_scene(ana["A4"], "A")[0] - shift.offset(hv1)[0]
    shifter["y"] = BY + bb.scene_h + 70.0
    hv2 = shift.connector_by_name("HV2")
    hv = shift.connector_by_name("HV")
    lv1 = shift.connector_by_name("LV1")
    lv2 = shift.connector_by_name("LV2")
    lv = shift.connector_by_name("LV")
    gnds = [c for c, n in shift.conn_name.items() if n == "GND"]
    wire_conn_to_hole(shifter, hv1, ana["A4"], "C", BLUE, "A4_SDA_to_HV1")
    wire_conn_to_hole(shifter, hv2, ana["A5"], "B", GREEN, "A5_SCL_to_HV2")
    wire_conn_to_hole(shifter, hv, 46, BOT_POS, RED, "shifter_HV_5V")
    wire_conn_to_hole(shifter, gnds[0], 46, BOT_GND, BLACK, "shifter_GND")

    # Routing lanes under the board. Several runs down here would otherwise be
    # drawn on top of each other; each takes its own lane 0.1in apart so every
    # wire stays separately traceable. Lane depths follow a hand-routed pass.
    LANE = [BY + bb.scene_h + 136.0 + 9.0 * i for i in range(3)]
    # The three ToF wires all turn in the gap just past the shifter's right edge.
    TURN_X = shifter["x"] + shift.scene_w + 1.5

    # 3V3 comes straight off the Nano, not from a rail: only this module uses it.
    # One bend takes it around the shifter's left side instead of over the body.
    wire_conn_to_hole(shifter, lv, ana["3V3"], "A", ORANGE, "3V3_to_shifter_LV",
                      bends=[(shifter["x"] - 30.0, shifter["y"] - 2.0)])

    tof = sk.add_part(tof_part, 0, 0, "ToF_VL53L4CD")
    tof["x"] = shifter["x"] + 92.0
    tof["y"] = shifter["y"] + 10.0
    tof_conns = sorted(
        tof_part.conn_svgid,
        key=lambda c: int("".join(ch for ch in c if ch.isdigit()) or 0),
    )
    if len(tof_conns) >= 4 and all(tof_part.offset(c) for c in tof_conns[:4]):
        # These three run parallel between the same two parts, so they fan out
        # into separate lanes rather than overlapping into one thick line.
        wire_conn_to_conn(tof, tof_conns[0], shifter, lv1, BLUE, "ToF_SDA_to_LV1",
                          bends=[(TURN_X, LANE[2])])
        wire_conn_to_conn(tof, tof_conns[1], shifter, lv2, GREEN, "ToF_SCL_to_LV2",
                          bends=[(TURN_X, LANE[1])])
        wire_conn_to_conn(tof, tof_conns[2], shifter, lv, ORANGE, "ToF_3V3_to_LV",
                          bends=[(TURN_X, LANE[0])])
        wire_conn_to_hole(tof, tof_conns[3], 52, BOT_GND, BLACK, "ToF_GND")
    else:
        print("  ! ToF stand-in part has no usable connector geometry; skipped")
        sk.instances.remove(tof)

    # ---- the handheld controller's two pot connectors -----------------------
    # These are the sockets the mechanical pots unplug from. Pin 2 (centre) is
    # the WIPER on both, matching what the meter found on this build. Both
    # sockets share the controller's rail and ground internally, which is why a
    # single rail-sense wire and a single ground wire are enough.
    CONN_Y = BY + bb.scene_h + 96.0
    PIN_HIGH, PIN_WIPER, PIN_LOW = "connector0", "connector1", "connector2"

    trigger_conn = sk.add_part(pot_conn, BX + 240.0, CONN_Y, "TriggerPotConnector")
    wheel_conn = sk.add_part(pot_conn, BX + 360.0, CONN_Y, "WheelPotConnector")

    # Filter outputs into each socket's wiper pin.
    wire_conn_to_hole(trigger_conn, PIN_WIPER, 24, "A", BLUE, "OUT_trigger_wiper")
    wire_conn_to_hole(wheel_conn, PIN_WIPER, 36, "A", GREEN, "OUT_wheel_wiper")
    # Rail sense in, ground out. Source columns are ordered left-to-right to
    # match the pin order so none of these three wires cross each other.
    wire_conn_to_hole(trigger_conn, PIN_HIGH, 21, "A", PURPLE,
                      "IN_controller_rail_sense")
    wire_conn_to_hole(trigger_conn, PIN_LOW, 31, BOT_GND, BLACK,
                      "OUT_controller_ground")
    # Commoned inside the controller - drawn so it is obvious why the wheel
    # socket needs no rail or ground wire of its own. Both jumpers span the same
    # pair of parts, so each drops into its own lane and runs across there
    # instead of the two being drawn on top of each other.
    def staple(a, ac, b, bc, lane, color, title, inset=22.0):
        ax = sk.connector_scene(a, ac)[0]
        bx = sk.connector_scene(b, bc)[0]
        wire_conn_to_conn(a, ac, b, bc, color, title,
                          bends=[(ax + inset, lane), (bx - inset, lane)])

    staple(trigger_conn, PIN_HIGH, wheel_conn, PIN_HIGH, LANE[1], PURPLE,
           "controller_rail_common")
    staple(trigger_conn, PIN_LOW, wheel_conn, PIN_LOW, LANE[0], BLACK,
           "controller_ground_common")

    # ---- notes ----
    # Vertical zones, so nothing lands on the board or the modules:
    #   above:  notes | ultrasonic modules | board
    #   below:  board | shifter + ToF and the controller stubs | notes
    NOTE_ABOVE_Y = BY - SR_LIFT - sr04.scene_h - 130.0
    NOTE_BELOW_Y = BY + bb.scene_h + 160.0
    sk.add_note(
        BX - 6, NOTE_ABOVE_Y, 320, 128,
        note_html(
            "STRIX - controller takeover, Variant B (PWM wiper synthesis)\n"
            "\n"
            "The Nano synthesizes the two wiper voltages that the handheld\n"
            "controller's trigger and steering pots used to produce. Each\n"
            "channel is a 4.7k + 1uF RC low-pass filter on a Timer1 PWM pin.\n"
            "D9 = throttle, D10 = steering, A0 = controller rail sense.\n"
            "\n"
            "The Nano is rotated so its USB end faces the left board edge;\n"
            "the phone's OTG cable exits left and powers the Nano."
        ),
        "Note_title",
    )
    sk.add_note(
        BX + 330, NOTE_ABOVE_Y, 320, 128,
        note_html(
            "FORWARD-PERCEPTION ARRAY (above the board)\n"
            "\n"
            "HC-SR04 ultrasonics, front-left and front-right corners:\n"
            "  front-left  TRIG/ECHO -> D2 / D3\n"
            "  front-right TRIG/ECHO -> D4 / D5\n"
            "  VCC -> top + rail, GND -> top - rail\n"
            "Mount them on the corners angled ~15 deg outward at bumper\n"
            "height. Left/right is the CAR's perspective, not yours.\n"
            "Placement and aiming: docs/sensor-wiring.md"
        ),
        "Note_ultrasonics",
    )
    sk.add_note(
        BX - 6, NOTE_BELOW_Y, 320, 150,
        note_html(
            "3.3V TIME-OF-FLIGHT SENSOR - the centre beam\n"
            "\n"
            "The 4-pin header stands in for the VL53L4CD / Modulino\n"
            "Distance module; Fritzing has no part for it.\n"
            "\n"
            "SDA/SCL cross a bidirectional I2C level shifter: the Nano's\n"
            "I2C is 5V logic and the module is 3.3V-only and NOT 5V\n"
            "tolerant. HV side faces the Nano (A4/A5 + 5V), LV side faces\n"
            "the module.\n"
            "ORANGE 3V3 runs straight off the Nano's 3V3 pin - never a 5V\n"
            "rail - and drives only this module (~50mA budget)."
        ),
        "Note_tof",
    )
    sk.add_note(
        BX + 330, NOTE_BELOW_Y, 320, 150,
        note_html(
            "THE HANDHELD CONTROLLER'S POT SOCKETS\n"
            "\n"
            "The two 3-pin connectors are the sockets the mechanical pots\n"
            "unplug from. On both, pin 2 (CENTRE) is the WIPER - that is\n"
            "what the meter found on this build, where the BLACK harness\n"
            "wire was the wiper, not the colour convention's white.\n"
            "\n"
            "  pin 1 HIGH  - controller rail, sensed by A0 (PURPLE)\n"
            "  pin 2 WIPER - driven by the RC filter (BLUE / GREEN)\n"
            "  pin 3 LOW   - controller ground (BLACK)\n"
            "\n"
            "Pins 1 and 3 are commoned inside the controller, so one rail\n"
            "wire and one ground wire serve both sockets.\n"
            "PURPLE is the rail sense: it scales every PWM duty to the\n"
            "sagging AA rail so calibrated neutral cannot drift. Do not\n"
            "omit it. Identify the wiper BEFORE cutting anything:\n"
            "docs/pot-identification.md"
        ),
        "Note_outputs",
    )

    xml = sk.to_xml(os.path.basename(out_path))
    inner = os.path.splitext(os.path.basename(out_path))[0] + ".fz"
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(inner, xml)

    # ---- report ----
    worst = max((c[3] for c in sk.checks), default=0.0)
    parts = sum(1 for i in sk.instances if i["kind"] == "part")
    wires = sum(1 for i in sk.instances if i["kind"] == "wire")
    notes = sum(1 for i in sk.instances if i["kind"] == "note")
    print(f"wrote {out_path}")
    print(f"  parts={parts}  wires={wires}  notes={notes}  "
          f"instances={len(sk.instances)}")
    print(f"  hole placement checks: {len(sk.checks)}, "
          f"worst error {worst:.3f} scene units ({worst / 90 * 1000:.1f} mils)")
    for title, conn, pin, err in sk.checks:
        if err > 1.0:
            print(f"    ! {title}.{conn} -> {pin}: {err:.3f} units")
    return 0 if worst < 2.0 else 1


# Prefer the parts library that the Fritzing application itself loads. Resolving
# against a git clone instead is how the "Unable to find part" bug happened: the
# clone can carry parts the installed release has never heard of.
PARTS_SEARCH = [
    "/Applications/Fritzing.app/Contents/Resources/fritzing-parts",
    os.path.expanduser("~/Applications/Fritzing.app/Contents/Resources/fritzing-parts"),
    "/usr/share/fritzing/fritzing-parts",
    os.path.expanduser("~/Source/fritzing/fritzing-parts"),
    os.path.expanduser("~/Source/fritzing-parts"),
]


def find_parts_dir():
    for d in PARTS_SEARCH:
        if os.path.isdir(os.path.join(d, "core")):
            return d
    return None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--parts", default=None,
                    help="parts library to resolve against; defaults to the "
                         "installed Fritzing app's bundled parts")
    ap.add_argument("--out", default="docs/diagrams/strix-takeover-breadboard.fzz",
                    help="output .fzz path")
    args = ap.parse_args(argv)
    parts = args.parts or find_parts_dir()
    if not parts:
        print("error: no Fritzing parts library found. Install Fritzing, or "
              "pass --parts DIR", file=sys.stderr)
        return 2
    if not os.path.isdir(parts):
        print(f"error: parts library not found at {parts}", file=sys.stderr)
        return 2
    print(f"parts library: {parts}")
    return build(parts, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
