# Diagrams

[← Hardware guide](../hardware-wiring.md)

## `strix-takeover-breadboard.fzz`

A [Fritzing](https://fritzing.org) breadboard diagram of the
[Path B / Variant B](../controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots)
build: the Nano synthesizing both controller wiper voltages through RC filters.

**Open it:** install Fritzing, then `File → Open`. Export a picture with
`File → Export → as Image → PNG` (or SVG) for use in the docs.

### What's in it

| Element | Detail |
| --- | --- |
| Breadboard | Full-size 830-point (RSR 03MB102) |
| Arduino Nano | Rotated 90° CCW so the USB end faces the left edge; pins in rows **H** (upper: 5V/3V3/A0) and **D** (lower: D2–D12), columns 2–16 |
| Throttle filter | 4.7 kΩ + 1 µF from **D9**, output at column 26 |
| Steering filter | 4.7 kΩ + 1 µF from **D10**, output at column 38 |
| Rails | Z = top ground, Y = top +5V, X = bottom ground (bridged to Z) |
| Loose wire ends | Blue → trigger wiper · Green → wheel wiper · Orange → controller rail (A0 sense) · Black → controller ground |
| Notes | Four on-canvas notes explaining the circuit and the off-board connections |

Distance sensors are deliberately **not** shown — see
[`sensor-wiring.md`](../sensor-wiring.md).

### Regenerating it

The file is generated, not hand-drawn, so it diffs and stays consistent with the
docs:

```bash
python3 scripts/generate-fritzing-sketch.py            # needs a fritzing-parts clone
python3 scripts/generate-fritzing-sketch.py --parts /path/to/fritzing-parts
```

The generator resolves real part geometry out of the
[fritzing-parts](https://github.com/fritzing/fritzing-parts) library and computes
absolute scene coordinates itself. Its header documents the geometry model and
which `fritzing-app` source constants each rule came from.

### How much to trust it

**Verified mechanically:**

- Every `moduleIdRef` and `connectorId` resolves against the real parts library.
- All 112 connection endpoints are reciprocal, with no dangling references.
- Every part pin declared as plugged into a hole lands on that hole's computed
  position — worst case **9 mils** (0.009"), which is the resistor's fixed
  0.409" lead span against a 0.4" four-column pitch.

**Not verified:** nobody has opened it in Fritzing yet. Expect to nudge
cosmetics — wire curvature, note placement, label overlap. The schematic and PCB
views are unstyled; only breadboard view was laid out deliberately.

If a part shows up in the wrong place, the generator is the thing to fix, not the
`.fzz`.
