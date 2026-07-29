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

### Regenerating and validating it

The file is generated, not hand-drawn, so it diffs and stays consistent with the
docs:

```bash
python3 scripts/generate-fritzing-sketch.py     # regenerate
python3 scripts/validate-fritzing-sketch.py     # check before opening Fritzing
```

The generator resolves real part geometry from a parts library and computes
absolute scene coordinates itself. Its header documents the geometry model and
which `fritzing-app` source constants each rule came from.

> ⚠️ **Resolve against the parts library the _application_ ships, not a
> fritzing-parts git clone.** Fritzing looks parts up by `moduleId` in its own
> bundled library and ignores the path recorded in the sketch. A clone tracks
> `develop` and can carry parts the installed release has never heard of — that
> is exactly how an earlier revision shipped an HC-SR04 whose `moduleId`
> (`hc-sr04_bf8299a_002`) existed in the clone but not in the app, so the sketch
> opened with _"Unable to find 1 part(s)"_. Both scripts now default to
> `/Applications/Fritzing.app/Contents/Resources/fritzing-parts` and take
> `--parts DIR` to override.

### How much to trust it

**Verified mechanically** by `validate-fritzing-sketch.py`:

- Every `moduleIdRef` resolves in the **installed** app's library, and every
  `connectorId` exists on that part.
- All 192 connection endpoints are reciprocal, with no dangling references, and
  every `modelIndex` is unique.
- Every part pin declared as plugged into a hole lands on that hole's computed
  position — worst case **9 mils** (0.009"), which is the resistor's fixed
  0.409" lead span against a 0.4" four-column pitch.
- Rail targets snap to holes that exist: rail rows skip a column every five, so
  a computed rail column frequently isn't a real hole.

**Fixed after opening it in Fritzing:**

| Symptom                               | Cause                                                                                                   |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| USB faced the wrong way               | The Nano SVG's low-y edge is the ICSP header, not the USB end, so the rotation went the wrong direction |
| R1 invisible                          | A wire ran collinear along the same row as the resistor, drawing over it                                |
| "Unable to find 1 part(s)"            | Validated against a fritzing-parts clone instead of the installed app                                   |
| Long diagonal wires across the canvas | Modules parked too far from the pins they wire to                                                       |

**Still not verified:** fine cosmetics — wire curvature and label overlap. The
schematic and PCB views are unstyled; only breadboard view was laid out
deliberately.

If a part shows up in the wrong place, the generator is the thing to fix, not the
`.fzz`.
