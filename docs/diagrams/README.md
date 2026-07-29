# Diagrams

[← Hardware guide](../hardware-wiring.md)

## `strix-takeover-breadboard.fzz`

A [Fritzing](https://fritzing.org) breadboard diagram of the
[Path B / Variant B](../controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots)
build: the Nano synthesizing both controller wiper voltages through RC filters.

**Open it:** install Fritzing, then `File → Open`. Export a picture with
`File → Export → as Image → PNG` (or SVG) for use in the docs.

### What's in it

| Element         | Detail                                                                                                                                                               |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Breadboard      | Full-size 830-point (RSR 03MB102)                                                                                                                                    |
| Arduino Nano    | Rotated 90° **CW** so the USB end faces the left edge; digital side (D1–D12) in row **H**, analog side (VIN/5V/A0/3V3/D13) in row **D**, columns 2–16                |
| Throttle filter | 4.7 kΩ + 1 µF from **D9**, across the channel at column 20, output at column 24                                                                                      |
| Steering filter | 4.7 kΩ + 1 µF from **D10**, across the channel at column 32, output at column 36                                                                                     |
| Ultrasonics     | Two HC-SR04 above the board — TRIG/ECHO on **D2/D3** (front-left) and **D4/D5** (front-right), power from the top rails                                              |
| Time-of-flight  | 4-pin header standing in for the VL53L4CD, behind a bidirectional I²C level shifter: HV side to **A4/A5 + 5V**, LV side to the module, **3V3 straight off the Nano** |
| Rails           | Z = top ground, Y = top +5V, X = bottom ground, W = bottom +5V (bridged to Y)                                                                                        |
| Loose wire ends | Blue → trigger wiper · Green → wheel wiper · Purple → controller rail (A0 sense) · Black → controller ground                                                         |
| Notes           | Five on-canvas notes covering the circuit, the controller wiring, the ultrasonics, and the 3.3 V rule                                                                |

Left/right on the ultrasonics is the **car's** perspective. Placement and aiming
are in [`sensor-wiring.md`](../sensor-wiring.md).

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
- All 192 connection endpoints are reciprocal, with no dangling references.
- Every part pin declared as plugged into a hole lands on that hole's computed
  position — worst case **9 mils** (0.009"), which is the resistor's fixed
  0.409" lead span against a 0.4" four-column pitch.
- Rail targets are snapped to holes that exist: rail rows skip a column every
  five, so a computed rail column frequently isn't a real hole.

**Verified by eye (first revision):** the Nano's rotation direction, and that no
wire is drawn collinear over a component. Both were wrong in the first cut — the
USB faced the wrong way, and a run along row B hid R1 completely.

**Still not verified:** fine cosmetics — wire curvature, note placement, label
overlap. The schematic and PCB views are unstyled; only breadboard view was laid
out deliberately.

If a part shows up in the wrong place, the generator is the thing to fix, not the
`.fzz`.
