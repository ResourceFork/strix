# Controller Takeover — Calibration Worksheet

A fill-in checklist for the [controller-takeover](controller-takeover.md) build.
Work top to bottom, write your measured values in the blanks, then copy the final
numbers into `arduino/ControllerTakeover/ControllerTakeover.ino`.

Do everything from "Bench test" onward with the **car's wheels off the ground.**

---

## 1. Parts and measurements

Fill these in before you buy/solder:

| Item | Value | Notes |
|---|---|---|
| Car model | `X15W` | (yours) |
| Controller part | `F12025` | Hosim replacement transmitter |
| Trigger pot resistance (HIGH→LOW) | `______ kΩ` | measure end-to-end with a multimeter |
| Wheel pot resistance (HIGH→LOW) | `______ kΩ` | |
| Controller supply rail voltage | `______ V` | usually 3×AA = 4.5V |
| Digipot part chosen | `____________` | e.g. MCP41010 (10k) |
| Digipot max voltage rating | `______ V` | **must be ≥ the controller rail** |

> If the rail voltage is above your digipot's max rating, either pick a
> higher-voltage digipot or feed that controller from a regulated 4.5–5V source.

---

## 2. Identify each pot's terminals

For each pot, use the multimeter to find which physical pad is HIGH (sits at the
rail voltage), WIPER (voltage sweeps as you move the control), and LOW (0V).
Write the pad label/position so you solder the digipot the right way round.

| Pot | HIGH pad | WIPER pad | LOW pad |
|---|---|---|---|
| Trigger (throttle) | `______` | `______` | `______` |
| Wheel (steering) | `______` | `______` | `______` |

Digipot mapping (same for both): `HIGH→PA0`, `WIPER→PW0`, `LOW→PB0`.

---

## 3. Pre-flight checklist

- [ ] Controller drives the car **manually** (before any modification)
- [ ] Both pots replaced with digipots (PA0/PW0/PB0 wired per section 2)
- [ ] Digipot VDD/VSS powered from the controller's rail + ground, within rating
- [ ] SPI wired: `SCK→D13`, `MOSI→D11`, throttle `CS→D7`, steering `CS→D8`
- [ ] **Common ground** between Nano GND and controller GND
- [ ] Firmware flashed with placeholder constants
- [ ] Car wheels **off the ground**
- [ ] Car powered on and paired to the modified controller

---

## 4. Bench test — measure the calibration values

Send each command (from the app or a 115200-baud serial monitor) and tune the
constant until the behavior matches, then record the wiper value.

**Throttle (channel 1)** — do NEUTRAL first, it's the most important:

- [ ] `A:1` then `T1:0` → car fully stopped, no creep → `THR_NEUTRAL = ______`
- [ ] `T1:100` → desired top forward speed → `THR_MAX = ______`
- [ ] `T1:-100` → desired top reverse speed → `THR_MIN = ______`

**Steering (channel 2):**

- [ ] `T2:0` → wheels pointing straight → `STR_CENTER = ______`
- [ ] `T2:100` → full right lock → `STR_RIGHT = ______`
- [ ] `T2:-100` → full left lock → `STR_LEFT = ______`

Then disarm and confirm the parked state:

- [ ] `A:0` → trigger releases to neutral and wheels re-center

> Tips: you don't have to use the pot's full travel — clamp `THR_MAX`/`THR_MIN`
> lower for a gentler car. If a direction is reversed, swap the MIN/MAX (or
> LEFT/RIGHT) values rather than rewiring.

---

## 5. Copy final values into the firmware

Paste your measured numbers into the CALIBRATION block of
`ControllerTakeover.ino`, then re-flash:

```cpp
const int THR_MIN     = ____;  // full reverse
const int THR_NEUTRAL = ____;  // stopped
const int THR_MAX     = ____;  // full forward

const int STR_LEFT    = ____;  // full left lock
const int STR_CENTER  = ____;  // wheels straight
const int STR_RIGHT   = ____;  // full right lock
```

- [ ] Constants updated and firmware re-flashed

---

## 6. Final drive test (wheels still off the ground)

- [ ] `A:1` → arm
- [ ] `T2:-100` / `T2:100` / `T2:0` → steering sweeps left, right, center
- [ ] `T1:30` → wheels spin forward
- [ ] `T1:-30` → wheels spin reverse (correct direction)
- [ ] Disconnect the app briefly → car stops within ~0.5s (failsafe)
- [ ] `A:0` → everything parks at neutral

Once all boxes are checked, put the car on the ground and start slow.
