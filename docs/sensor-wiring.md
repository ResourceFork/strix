# Add-on: Sensor Wiring

> The forward-perception array — a center time-of-flight beam plus two corner
> ultrasonics — giving the car *measured* obstacle distances in millimeters.

[← Hardware guide](hardware-wiring.md) · [Glossary](glossary.md) · [Parts](parts-and-shopping.md#add-on-distance-sensors) · [Serial protocol](serial-protocol.md)

---

## At a glance

| | |
| --- | --- |
| **What you'll do** | Mount and wire three distance sensors to the Nano's free pins |
| **You'll need** | 2× HC-SR04, 1× VL53L4CD module, an I²C level shifter ([parts](parts-and-shopping.md#add-on-distance-sensors)) |
| **Time** | An hour including mounting |
| **Difficulty** | Easy wiring, fiddly mounting |
| **Works with** | **Either** drive path — these pins are free in both firmware variants |
| **Optional?** | Entirely. Absent sensors report `-1` forever and nothing else breaks. |

---

## Why three sensors, all facing forward

The camera gives *inferred, relative* depth. These sensors give *measured,
absolute* millimeters. Together they cover each other's weaknesses.

| Sensor | Position | Beam | Range | Job |
| --- | --- | --- | --- | --- |
| **VL53L4CD** [time-of-flight](glossary.md#tof) | Center | Narrow, ~18° | 0–1200 mm | Millimeter-accurate "am I about to hit that?" for the driving lane |
| **HC-SR04** [ultrasonic](glossary.md#ultrasonic) ×2 | Front-left and front-right corners | Wide cone, ~15–30° | ~20 mm–2 m | Cover the flanks the center beam misses — where a clipped doorframe or chair leg lives |

Everything looks forward because the mission is deciding **how and when to
advance.** There is deliberately **no rear sensor**: reversing is only ever used
to back out along ground the car has already covered.

The three readings line up with the camera's [depth bands](glossary.md#depth-band)
— left, center, right — so the measured and inferred views of "what's ahead"
speak the same vocabulary.

---

## Placement

```
                 obstacle coverage
        \  wide cone   |narrow|   wide cone  /
         \    ~25deg   | ~18deg|   ~25deg   /
          \            |  beam |           /
           \           |       |          /
       [SR04-L]     [ToF center]     [SR04-R]
      angled ~15deg  bumper height  angled ~15deg
      outward   \        |         /   outward
    +------------+-------+--------+------------+
    | front-left |     front      | front-right|   <- front bumper
    +------------+----------------+------------+
    |                                          |
    |            (phone + camera               |
    |             face forward too)            |
    |                 CAR BODY                 |
    |                                          |
    +------------------------------------------+
                (no rear sensors)
```

| Guidance | Detail |
| --- | --- |
| **Corner angle** | Angle each SR04 ~10–20° outward. Straight ahead wastes them (the ToF covers that); too far out and they stare at walls beside the car. Aim for the cones to *just* overlap the ToF beam a car-length ahead. |
| **Height** | All three at obstacle height — roughly bumper level, high enough that flat ground doesn't reflect into the cone. If the SR04s see the floor, tilt them up a degree or two. |
| **ToF lens** | Keep it clean and unobstructed. No body-shell plastic in front of it — the laser will happily measure the inside of the shell. |

---

## Wiring

```mermaid
flowchart LR
    subgraph nano["Arduino Nano"]
        d2["D2"]
        d3["D3"]
        d4["D4"]
        d5["D5"]
        a4["A4 (SDA)"]
        a5["A5 (SCL)"]
        v5["5V"]
        v33["3V3"]
        gnd["GND"]
    end

    subgraph sl["HC-SR04 front-LEFT corner"]
        slt["TRIG"]
        sle["ECHO"]
        slv["VCC"]
        slg["GND"]
    end

    subgraph sr["HC-SR04 front-RIGHT corner"]
        srt["TRIG"]
        sre["ECHO"]
        srv["VCC"]
        srg["GND"]
    end

    subgraph tof["VL53L4CD ToF module"]
        tsda["SDA"]
        tscl["SCL"]
        tv["3V3"]
        tg["GND"]
    end

    ls["bidirectional I2C\nlevel shifter\n(recommended)"]

    d2 --> slt
    d3 --> sle
    v5 --> slv
    slg --> gnd

    d4 --> srt
    d5 --> sre
    v5 --> srv
    srg --> gnd

    a4 --- ls
    a5 --- ls
    ls --- tsda
    ls --- tscl
    v33 --> tv
    tg --> gnd
```

### Pin-by-pin tables

These pins are free in **both** firmware variants, so the same wiring serves
either build.

**HC-SR04 corners** — 5V devices, direct to the Nano, no level shifting needed.
Each has four labeled pins in a row:

| Sensor | Sensor pin | Nano pin | What it does |
| --- | --- | --- | --- |
| Front-LEFT | VCC | **5V** | power |
| Front-LEFT | Trig | **D2** | "fire a ping" input |
| Front-LEFT | Echo | **D3** | echo-time output |
| Front-LEFT | GND | **GND** | ground |
| Front-RIGHT | VCC | **5V** | power |
| Front-RIGHT | Trig | **D4** | "fire a ping" input |
| Front-RIGHT | Echo | **D5** | echo-time output |
| Front-RIGHT | GND | **GND** | ground |

> 💡 **Left and right are from the car's perspective** (driver's seat), not yours
> looking at it head-on. If the app's L/R readings look swapped, you wired the
> sensors to each other's pins — swap D2/D3 with D4/D5.

**VL53L4CD module** — 3.3V [I²C](glossary.md#i²c). On an Arduino Modulino the
connector is a 4-pin JST-SH Qwiic socket with labels printed on the board (GND,
3V3, SDA, SCL); cut a Qwiic cable and crimp to jumper wires, or use the breakout
pads. Generic breakouts have plain header pins.

| Module pin | Connects to | Nano pin |
| --- | --- | --- |
| SDA | [level shifter](glossary.md#level-shifter) LV side ↔ HV side | **A4** |
| SCL | level shifter LV side ↔ HV side | **A5** |
| 3V3 | direct | **3V3** (never 5V) |
| GND | direct | **GND** |

> ⚠️ **The Qwiic/Modulino ecosystem is 3.3V-only and not 5V tolerant** —
> Arduino's own documentation is explicit. Two rules:
>
> 1. Power it from the Nano's **3V3 pin**, never the 5V rail.
> 2. The classic Nano's I²C lines are 5V logic, so put a **bidirectional I²C
>    level shifter** between them (a ~$2 BSS138 board: HV side → 5V + A4/A5, LV
>    side → 3V3 + module SDA/SCL, grounds common). A direct connection often
>    works in practice — I²C is [open-drain](glossary.md#open-drain) and the
>    module's pull-ups go to 3.3V — but it runs the sensor at the edge of its
>    ratings. The shifter is cheap insurance.

---

## Assembly order

1. **Grounds first.** All three sensors' GND to the Nano's GND. A ground rail on
   a mini breadboard keeps this tidy. As everywhere in these docs,
   [shared ground](glossary.md#common-ground) is what makes every signal
   meaningful.
2. **Wire the corner SR04s** per the table (VCC→5V, Trig/Echo→D2–D5). Mount them
   on the front corners, angled ~15° outward, at bumper height.
3. **Wire the level shifter:** HV→5V, LV→3V3, GND→GND, HV1/HV2→A4/A5,
   LV1/LV2→module SDA/SCL.
4. **Power the module from 3V3** and mount it front-center at bumper height.
5. **Flash the firmware** (either variant). This needs the **VL53L4CD** library:
   Arduino IDE → Library Manager → "VL53L4CD", or
   `arduino-cli lib install VL53L4CD`.
6. **Verify over serial** before trusting the app — see below.

### Verify with the serial monitor

At 115200 baud, send `D?`:

```
D?                    →  D:431,822,760
                          center, front-left, front-right — all in mm
```

A `-1` means that sensor has no reading: absent, out of range, or a missed echo.
Wave a hand in front of each sensor in turn and watch its number move — that's
also how you confirm you didn't swap left and right.

---

## How it behaves

| Behavior | Why it's designed that way |
| --- | --- |
| Firmware samples sensors **round-robin**, one per 50 ms tick | Keeps drive-command latency unaffected, and means the two ultrasonics never fire simultaneously — which prevents [cross-talk](glossary.md#cross-talk) despite their overlapping cones |
| All sensors are **optional** | Absent ones report `-1` forever; a missing ToF module is detected at boot and skipped. Nothing else breaks. |
| App polls at **~5 Hz** with a [median filter](glossary.md#median-filter) | Rejects single-sample ultrasonic ghosts and dropouts |
| App shows "Range · L … · C … · R …" under the camera preview | Same left-to-right order as the depth-band readout above it, so the two views are directly comparable |

---

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| A channel always reads `-1` | Sensor unwired or unpowered, Trig/Echo swapped, or (ToF) I²C wiring — check SDA→A4, SCL→A5. |
| L and R appear swapped in the app | Left/right is the car's perspective — swap the D2/D3 and D4/D5 pairs. |
| SR04 reads ~50–150 mm constantly in open space | It's seeing the floor or the car's own body — raise it or tilt it up slightly. |
| SR04 readings jumpy | Soft or angled surfaces scatter ultrasound. The median filter eats single spikes; persistent jitter means re-aim the sensor. |
| ToF reads a fixed short value | Something is in front of the lens (body shell, tape). It needs clear line of sight. |
| ToF absent after boot but correctly wired | It's only probed at power-up — power-cycle the Nano after fixing wiring. |
| `D?` returns nothing at all | Not a sensor problem: check the serial link itself with `?` ([protocol](serial-protocol.md#driving-it-by-hand)). |

---

## Reference

- [Serial protocol](serial-protocol.md) — the `D?` command and reply format
- [Parts & shopping](parts-and-shopping.md#add-on-distance-sensors)
- Firmware (either variant reads the sensors): [`EscServoController.ino`](../arduino/EscServoController/EscServoController.ino) · [`ControllerTakeover.ino`](../arduino/ControllerTakeover/ControllerTakeover.ino) · [`ControllerTakeoverPwm.ino`](../arduino/ControllerTakeoverPwm/ControllerTakeoverPwm.ino)
- App side: [`DistanceReport.kt`](../app/src/main/java/com/resourcefork/rccontrol/DistanceReport.kt) · [`MotorController.kt`](../app/src/main/java/com/resourcefork/rccontrol/MotorController.kt)

---

**Back to:** [Hardware guide](hardware-wiring.md) · [Path A](esc-wiring.md) · [Path B](controller-takeover.md)
