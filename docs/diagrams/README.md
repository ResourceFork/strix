# Diagrams

[← Hardware guide](../hardware-wiring.md)

## `strix-takeover-breadboard.fzz`

A [Fritzing](https://fritzing.org) breadboard diagram of the
[Path B / Variant B](../controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots)
build: the Nano synthesizing both controller wiper voltages through RC filters.

**Open it:** install Fritzing, then `File → Open`. Export a picture with
`File → Export → as Image → PNG` (or SVG) for use in the docs.

### What's in it

| Element            | Detail                                                                                                                                                                                                                                |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Breadboard         | Full-size 830-point (RSR 03MB102)                                                                                                                                                                                                     |
| Arduino Nano       | Rotated 90° **CW** so the USB end faces the left edge; digital side (D1–D12) in row **H**, analog side (VIN/5V/A0/3V3/D13) in row **D**, columns 2–16                                                                                 |
| Throttle filter    | 4.7 kΩ + 1 µF from **D9**, across the channel at column 20, output at column 24                                                                                                                                                       |
| Steering filter    | 4.7 kΩ + 1 µF from **D10**, across the channel at column 32, output at column 36                                                                                                                                                      |
| Ultrasonics        | Two HC-SR04 above the board — TRIG/ECHO on **D2/D3** (front-left) and **D4/D5** (front-right), power from the top rails                                                                                                               |
| Time-of-flight     | 4-pin header standing in for the VL53L4CD, behind a bidirectional I²C level shifter: HV side to **A4/A5 + 5V**, LV side to the module, **3V3 straight off the Nano**                                                                  |
| Rails              | Z = top ground, Y = top +5V, X = bottom ground, W = bottom +5V (bridged to Y)                                                                                                                                                         |
| Controller sockets | Two polarized 3-pin connectors standing in for the sockets the mechanical pots unplug from. **Pin 2 (centre) is the wiper** on both. Pins 1 and 3 are drawn commoned, which is why one rail-sense wire and one ground wire serve both |
| Notes              | Four on-canvas notes covering the circuit, the controller sockets, the ultrasonics, and the 3.3 V rule                                                                                                                                |

Left/right on the ultrasonics is the **car's** perspective. Placement and aiming
are in [`sensor-wiring.md`](../sensor-wiring.md).

### Wire colours

Assigned by **role**, so the same signal reads the same everywhere, using
Fritzing's own palette swatches. Arbitrary hex values do render, but the colour
picker reports them as custom and they look subtly off beside hand-drawn wires.

| Colour | Swatch    | Role                                                                                      |
| ------ | --------- | ----------------------------------------------------------------------------------------- |
| Black  | `#404040` | Ground, every one of them. Fritzing's "black" is dark grey, not `#000000`                 |
| Red    | `#cc1414` | 5 V                                                                                       |
| Orange | `#ef6100` | 3.3 V — deliberately distinct from 5 V, because confusing the two destroys the ToF module |
| Blue   | `#418dd9` | Throttle channel, I²C SDA, ultrasonic ECHO                                                |
| Green  | `#25cc35` | Steering channel, I²C SCL                                                                 |
| Yellow | `#fff800` | Ultrasonic TRIG, paired against blue ECHO so the two are never mixed up                   |
| Purple | `#ab58a2` | Controller rail sense into A0                                                             |

### Routing lanes and bendpoints

Six runs are routed rather than drawn straight, because a straight line would put
them on top of each other or across a part's body. Fritzing has no multi-point
wire — dragging a bendpoint into a wire **splits it into separate
`WireModuleID` instances joined end to end**. So the sketch's 34 logical runs are
stored as 42 wire instances, and the generator emits that chain itself
(`Sketch.add_wire_path`); the extra segments are named `<run>_bend1`, `_bend2`
rather than left to Fritzing's auto `Wire5`, so a diff stays readable.

Three **lanes** run below the board, 0.1" (9 scene units) apart:

| Lane | Runs that use it                                             |
| ---- | ------------------------------------------------------------ |
| 0    | `ToF_3V3_to_LV` (orange), `controller_ground_common` (black) |
| 1    | `ToF_SCL_to_LV2` (green), `controller_rail_common` (purple)  |
| 2    | `ToF_SDA_to_LV1` (blue)                                      |

Two runs sharing a lane is fine here — the ToF fan-out and the controller staples
occupy different spans of x and never meet.

Two patterns produce all six:

- **Fan-out.** The three ToF wires all travel between the same pair of parts. Each
  drops to its own lane, crosses in the gap just past the shifter's right edge
  (`TURN_X`), and comes back up. Without this they merge into one thick
  indistinguishable line. `3V3_to_shifter_LV` uses a single bend for a different
  reason: to pass around the shifter's left side instead of over its body.
- **Staple.** The two commoning jumpers between the controller sockets span the
  same two parts. Each drops into a lane 22 units in from its pin, runs across,
  and comes back up 22 units before the far pin — so they nest instead of
  overlapping.

Both were learned from a hand-routed pass in the app. To re-learn after editing by
hand, compare the two archives run by run rather than eyeballing the render: fold
each bend chain back into its parent run, then check colour, segment count, and
bendpoint coordinates.

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
- All 228 connection endpoints are reciprocal, with no dangling references, and
  every `modelIndex` is unique. That count includes the wire-to-wire joins that
  implement bendpoints.
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
