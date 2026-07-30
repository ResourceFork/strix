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

### Routing: raceways, not diagonals

Every wire is drawn like a PCB trace — horizontal and vertical only, turning in
lanes on the same 0.1" lattice as the holes. Two route shapes cover almost
everything:

- **Drop, lane, drop** for pins in a row wiring above or below the board.
- **Out, lane, in** for pins in a column wiring off to the side.

Where a pin already lines up with its target the route collapses to one straight
run, which is the tidiest outcome and worth keeping. The level shifter's whole HV
side lands that way: sat below the board with no rotation, its HV row already
faces the Nano, so **SDA, SCL, 5V and ground are four straight verticals with no
bend at all.**

Which wire gets which lane is not obvious, so the generator doesn't guess — it
searches lane orderings and keeps one that provably doesn't cross or overlap
itself (`solve_comb`). The intuitive rule, that wires heading further should turn
later, is wrong whenever a connector puts power on its outer pins and signal on
its inner ones, because then the pin order fights the destination order. That's
every HC-SR04.

Two findings worth keeping, both learned the hard way:

- **Offer lanes on _both_ sides of a pin row.** An HC-SR04's TRIG sits left of
  ECHO, but TRIG lands on the higher-numbered Arduino pin, whose column is
  further _right_. Reversed start and end order looks like it must cross — and it
  does, if every lane is on one side. Given a lane above the pin row as well, one
  wire steps up over the module and back down, and the fan resolves cleanly. With
  lanes only below, this fan is unsolvable.
- **Overlaps are invisible to a crossing test.** Two collinear wires have a zero
  determinant, so an intersection check calls them fine while one is drawn
  underneath the other. `check-fritzing-crossings.py` scores both, and the router
  treats a hidden wire as ten times worse than a crossing, since a crossing is at
  least legible.

### Known crossings

Two, both genuine, both explained rather than tidied away:

| Crossing                                              | Why                                                                                                                                                                                                                                                                                         |
| ----------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `D10_run` × `GND_top`                                 | The Nano's digital ground pin sits at column 13, between D10 at column 4 and the filter it feeds at column 32. Any run between them crosses that riser.                                                                                                                                     |
| `controller_ground_common` × `controller_rail_common` | Both sockets carry their pins in the same order, so the pairs interleave — pin 1 (trigger), pin 3 (trigger), pin 1 (wheel), pin 3 (wheel). Interleaved chords on the same side of a line always cross. Mirroring one socket would nest them, but a socket's pin order isn't ours to invent. |

`python3 scripts/check-fritzing-crossings.py` reports the count, so a future
layout change can't quietly add more. The generator also prints any fan it
couldn't fully untangle.

<details>
<summary>Earlier approach, and why it was wrong</summary>

An earlier revision routed the parallel runs into three horizontal lanes below
the board and called that solved. It wasn't: the lanes stopped the wires being
drawn _on top of_ each other, but they were ordered so the wires still swapped
over each other en route. Separating and not-crossing are different properties
and only the first was checked. That revision shipped 28 crossings, including the
three-wire ToF fan crossing itself and the two commoning jumpers crossing.

</details>

### Historical: bendpoints in the file format

Worth knowing before you read the XML or diff two revisions: **Fritzing has no
multi-point wire.** Dragging a bendpoint into a wire splits it into separate
`WireModuleID` instances joined end to end, so a route with two bends is stored as
three wire instances. The sketch's **34 logical runs are stored as 73 wire
instances**, and `Sketch.add_wire_path` emits those chains itself. Extra segments
are named `<run>_bend1`, `_bend2` rather than left to Fritzing's auto `Wire5`
naming, so a diff stays readable.

This also means **moving a part in the app does not re-route its wires** — Fritzing
rubber-bands the existing bendpoints along, so a fan that was laid out in parallel
lanes comes out smeared and often tangled. After hand-editing, regenerate rather
than trusting what the drag produced, and check the crossing count.

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
- All 290 connection endpoints are reciprocal, with no dangling references, and
  every `modelIndex` is unique. That count includes the wire-to-wire joins that
  implement bendpoints.
- Wire crossings are counted, not eyeballed: 2, both explained above.
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
