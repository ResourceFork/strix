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
BLACK = "#000000"
RED = "#ff1a1a"
BLUE = "#418dd9"
GREEN = "#47bb24"
ORANGE = "#ff7e00"
WIRE_MILS = "24"


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


IDENTITY = (1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
# 90 degrees counter-clockwise: (x, y) -> (y, -x), then shifted back into
# positive space by the part's own width. m32 is filled in per-part.
ROT_CCW = (0.0, -1.0, 1.0, 0.0, 0.0, None)


def qt_map(m, p):
    m11, m12, m21, m22, m31, m32 = m
    return (m11 * p[0] + m21 * p[1] + m31, m12 * p[0] + m22 * p[1] + m32)


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
TOP_GND, TOP_POS, BOT_GND = "Z", "Y", "X"

NANO_FIRST_COL = 2  # leftmost Nano pin column; USB then faces the left edge
NANO_UPPER_ROW = "H"  # 5V / 3V3 / A0 side
NANO_LOWER_ROW = "D"  # D2..D12 side, so the filters sit in rows C/B/A below


def build(parts_dir: str, out_path: str) -> int:
    bb = Breadboard(parts_dir, "core/breadboard2.fzp")
    nano = Part(parts_dir, "core/Arduino Nano3(fix).fzp")
    res = Part(parts_dir, "core/resistor.fzp")
    cap = Part(parts_dir, "core/capacitor_ceramic_100mil.fzp")

    sk = Sketch(parts_dir)

    BX, BY = 120.0, 220.0
    bb_inst = sk.add_part(bb, BX, BY, "Breadboard1")
    bb_inst["is_breadboard"] = True

    def hole_scene(col, row):
        hx, hy = bb.hole(col, row)
        return BX + hx, BY + hy

    def tie(part_inst, conn_id, col, row):
        """Declare a part connector as plugged into a hole, and check geometry."""
        sk.join(
            part_inst,
            conn_id,
            bb_inst,
            bb.hole_connector(col, row),
            "breadboard",
            "breadboardbreadboard",
        )
        want = hole_scene(col, row)
        got = sk.connector_scene(part_inst, conn_id)
        if got:
            err = ((got[0] - want[0]) ** 2 + (got[1] - want[1]) ** 2) ** 0.5
            sk.checks.append((part_inst["title"], conn_id, f"pin{col}{row}", err))

    def wire_hole_to_hole(c0, r0, c1, r1, color, title):
        p0, p1 = hole_scene(c0, r0), hole_scene(c1, r1)
        w = sk.add_wire(p0, p1, color, title)
        sk.join(w, "connector0", bb_inst, bb.hole_connector(c0, r0),
                "breadboardWire", "breadboardbreadboard")
        sk.join(w, "connector1", bb_inst, bb.hole_connector(c1, r1),
                "breadboardWire", "breadboardbreadboard")
        return w

    def wire_hole_to_free(c0, r0, dx, dy, color, title):
        p0 = hole_scene(c0, r0)
        p1 = (p0[0] + dx, p0[1] + dy)
        w = sk.add_wire(p0, p1, color, title)
        sk.join(w, "connector0", bb_inst, bb.hole_connector(c0, r0),
                "breadboardWire", "breadboardbreadboard")
        return w

    # ---- Arduino Nano, rotated 90 CCW so the USB end faces the left edge ----
    nano_t = (0.0, -1.0, 1.0, 0.0, 0.0, nano.scene_w)
    upper_dy = qt_map(nano_t, (nano.scene_w - 4.5, 13.5))[1]  # ~4.5
    nano_x = hole_scene(NANO_FIRST_COL, NANO_LOWER_ROW)[0] - 13.5
    nano_y = hole_scene(NANO_FIRST_COL, NANO_UPPER_ROW)[1] - upper_dy
    nano_inst = sk.add_part(nano, nano_x, nano_y, "Nano1", transform=nano_t)

    # Pin k index -> column. Lower row: D1,D0,RESET,GND,D2..D12 (connector16..30)
    #                Upper row: VIN,GND,RESET,5V,A7..A0,AREF,3V3,D13 (31..45)
    for k in range(15):
        col = NANO_FIRST_COL + k
        tie(nano_inst, f"connector{16 + k}", col, NANO_LOWER_ROW)
        tie(nano_inst, f"connector{31 + k}", col, NANO_UPPER_ROW)

    col_of = {
        "D9": NANO_FIRST_COL + 11,
        "D10": NANO_FIRST_COL + 12,
        "GND_top": NANO_FIRST_COL + 1,
        "5V": NANO_FIRST_COL + 3,
        "A0": NANO_FIRST_COL + 11,
        "3V3": NANO_FIRST_COL + 13,
    }

    # ---- grounds ----
    wire_hole_to_hole(col_of["GND_top"], "J", col_of["GND_top"], TOP_GND,
                      BLACK, "GND_nano_to_top_rail")
    wire_hole_to_hole(58, TOP_GND, 58, BOT_GND, BLACK, "GND_rail_bridge")
    wire_hole_to_hole(col_of["5V"], "J", col_of["5V"], TOP_POS,
                      RED, "5V_nano_to_top_rail")

    # ---- throttle filter: D9 -> R1 -> node -> C1 -> ground ----
    wire_hole_to_hole(col_of["D9"], "A", 22, "A", BLUE, "D9_to_filter")
    r1 = sk.add_part(res, 0, 0, "R1", props={"resistance": "4.7kΩ"})
    r1_off = res.offset("connector0")
    r1_target = hole_scene(22, "B")
    r1["x"], r1["y"] = r1_target[0] - r1_off[0], r1_target[1] - r1_off[1]
    tie(r1, "connector0", 22, "B")
    tie(r1, "connector1", 26, "B")

    c1 = sk.add_part(cap, 0, 0, "C1", props={"capacitance": "1µF"})
    c1_off = cap.offset("connector0")
    c1_target = hole_scene(26, "C")
    c1["x"], c1["y"] = c1_target[0] - c1_off[0], c1_target[1] - c1_off[1]
    tie(c1, "connector0", 26, "C")
    tie(c1, "connector1", 27, "C")
    wire_hole_to_hole(27, "B", 27, BOT_GND, BLACK, "C1_to_ground")
    wire_hole_to_free(26, "A", 0, 86, BLUE, "OUT_trigger_wiper")

    # ---- steering filter: D10 -> R2 -> node -> C2 -> ground ----
    wire_hole_to_hole(col_of["D10"], "B", 34, "B", GREEN, "D10_to_filter")
    r2 = sk.add_part(res, 0, 0, "R2", props={"resistance": "4.7kΩ"})
    r2_target = hole_scene(34, "C")
    r2["x"], r2["y"] = r2_target[0] - r1_off[0], r2_target[1] - r1_off[1]
    tie(r2, "connector0", 34, "C")
    tie(r2, "connector1", 38, "C")

    c2 = sk.add_part(cap, 0, 0, "C2", props={"capacitance": "1µF"})
    c2_target = hole_scene(38, "D")
    c2["x"], c2["y"] = c2_target[0] - c1_off[0], c2_target[1] - c1_off[1]
    tie(c2, "connector0", 38, "D")
    tie(c2, "connector1", 39, "D")
    wire_hole_to_hole(39, "B", 39, BOT_GND, BLACK, "C2_to_ground")
    wire_hole_to_free(38, "A", 0, 86, GREEN, "OUT_wheel_wiper")

    # ---- rail sense + ground out to the controller ----
    wire_hole_to_free(col_of["A0"], "J", 0, -95, ORANGE, "IN_controller_rail_sense")
    wire_hole_to_free(48, BOT_GND, 0, 60, BLACK, "OUT_controller_ground")

    # ---- notes ----
    sk.add_note(
        BX - 6, BY - 190, 300, 104,
        note_html(
            "STRIX - controller takeover, Variant B (PWM wiper synthesis)\n"
            "\n"
            "The Nano synthesizes the two wiper voltages that the handheld\n"
            "controller's trigger and steering pots used to produce. Each\n"
            "channel is a 4.7k + 1uF RC low-pass filter on a Timer1 PWM pin.\n"
            "D9 = throttle, D10 = steering, A0 = controller rail sense."
        ),
        "Note_title",
    )
    sk.add_note(
        BX + 300, BY - 190, 292, 104,
        note_html(
            "ORANGE (A0): to the controller's pot HIGH / rail pin.\n"
            "Scales every duty to the sagging AA rail so calibrated\n"
            "neutral cannot drift. Do not omit this wire.\n"
            "\n"
            "Nano is rotated so the USB end faces the board edge; the\n"
            "phone's OTG cable exits left and powers the Nano."
        ),
        "Note_rail",
    )
    sk.add_note(
        BX + 60, BY + 250, 300, 92,
        note_html(
            "BLUE  -> controller TRIGGER pot WIPER pin\n"
            "GREEN -> controller WHEEL pot WIPER pin\n"
            "BLACK -> controller GROUND (pot LOW pin)\n"
            "\n"
            "Identify which harness wire is the wiper before cutting:\n"
            "see docs/pot-identification.md. On this build's Hosim\n"
            "controller the BLACK harness wire was the wiper."
        ),
        "Note_outputs",
    )
    sk.add_note(
        BX + 390, BY + 250, 210, 76,
        note_html(
            "Distance sensors (HC-SR04 x2 on D2-D5, VL53L4CD on A4/A5\n"
            "via a 3.3V level shifter) are documented separately in\n"
            "docs/sensor-wiring.md and are not shown here."
        ),
        "Note_sensors",
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


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--parts", default=os.path.expanduser("~/Source/fritzing-parts"),
                    help="path to a fritzing-parts clone")
    ap.add_argument("--out", default="docs/diagrams/strix-takeover-breadboard.fzz",
                    help="output .fzz path")
    args = ap.parse_args(argv)
    if not os.path.isdir(args.parts):
        print(f"error: fritzing-parts not found at {args.parts}", file=sys.stderr)
        print("clone it, or pass --parts DIR", file=sys.stderr)
        return 2
    return build(args.parts, args.out)


if __name__ == "__main__":
    raise SystemExit(main())
