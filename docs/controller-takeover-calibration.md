# Path B: Calibration Worksheet

> A fill-in checklist for the [controller takeover](controller-takeover.md)
> build. Work top to bottom, write your measured values in the blanks, then copy
> the final numbers into your firmware.

[← Path B: controller takeover](controller-takeover.md) · [Identify the pots](pot-identification.md) · [Serial protocol](serial-protocol.md) · [Glossary](glossary.md)

---

## Before you start

|                                |                                                                                                                        |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------- |
| **Your variant**               | ☐ A — digipots (`ControllerTakeover.ino`, values **0–255**) ☐ B — PWM (`ControllerTakeoverPwm.ino`, values **0–1023**) |
| **Procedure for sections 1–2** | [Identify the pots](pot-identification.md) — the multimeter how-to                                                     |
| **Command reference**          | [Driving it by hand](serial-protocol.md#driving-it-by-hand)                                                            |

> ⚠️ Everything from [section 4](#4-bench-test--measure-the-calibration-values)
> onward happens with the **car's wheels off the ground.**

The steps are identical for both variants — only the units differ.

---

## 1. Parts and measurements

Fill these in before you buy or solder. → [How to measure](pot-identification.md#using-your-multimeter)

| Item                               | Value          | Notes                                                                                            |
| ---------------------------------- | -------------- | ------------------------------------------------------------------------------------------------ |
| Car model                          | `X15`          | (this build)                                                                                     |
| Controller part                    | `F12025`       | [Hosim replacement transmitter](parts-and-shopping.md#the-controller-required-for-path-b)        |
| Trigger pot track (HIGH→LOW)       | `~5.2 kΩ`      | 5k nominal — via wiper-pair sum                                                                  |
| Wheel pot track (HIGH→LOW)         | `~5.2 kΩ`      | 5k nominal — sum constant at every position                                                      |
| **Controller supply rail voltage** | `______ V`     | Usually 3×AA = 4.5V. [Powered test](pot-identification.md#step-3-find-high-and-low-powered-test) |
| **(A)** Digipot part chosen        | `____________` | MCP41010 (10k works — [ratiometric](glossary.md#ratiometric))                                    |
| **(A)** Digipot max voltage rating | `______ V`     | **Must be ≥ the controller rail**                                                                |
| **(B)** Filter R per channel       | `______ Ω`     | 2.2–4.7 kΩ; a sacrificed pot's element works                                                     |
| **(B)** Filter C per channel       | `______ µF`    | 1–2.2 µF                                                                                         |

> ⚠️ **(A)** If the rail voltage exceeds your digipot's rating, either pick a
> higher-voltage part or feed that controller from a regulated 4.5–5V source.

---

## 2. Identify each pot's terminals

→ **Full procedure: [Identify the pots](pot-identification.md)**

Record which wire or pad is which. Getting the **wiper** right is the one that
can't be fixed in software.

| Pot                | HIGH     | WIPER    | LOW      |
| ------------------ | -------- | -------- | -------- |
| Trigger (throttle) | `______` | `______` | `______` |
| Wheel (steering)   | `______` | `______` | `______` |

Then map to your variant:

| Role  | Variant B (PWM)                                                                | Variant A (digipot) |
| ----- | ------------------------------------------------------------------------------ | ------------------- |
| WIPER | D9 (throttle) / D10 (steering) via [RC filter](glossary.md#rc-low-pass-filter) | **PW0**             |
| HIGH  | **A0** (rail sense, one pot's is enough)                                       | **PA0**             |
| LOW   | **GND**                                                                        | **PB0**             |

<details>
<summary><strong>This build's measured values</strong> (worked example)</summary>

Black wire was the **wiper** on both pots — the color convention did not hold.
Track ≈ 5.2 kΩ. Full data and interpretation:
[field data](pot-identification.md#field-data-from-this-build).

| Pot     | Wiper ↔ white                | Wiper ↔ red                 | At rest |
| ------- | ---------------------------- | --------------------------- | ------- |
| Wheel   | 52 Ω (R) → 4.67 kΩ (L)       | 5.2 kΩ (R) → ~570 Ω (L)     | ~2.7 kΩ |
| Trigger | 1.99 kΩ (rev) → 4.4 kΩ (fwd) | 3.7 kΩ (rev) → ~840 Ω (fwd) | 2.77 kΩ |

Key consequence: the trigger uses only ~46% of its track, forward-biased — so
`THR_NEUTRAL` lands nowhere near the midpoint of the output range.

</details>

---

## 3. Pre-flight checklist

- [ ] Controller drives the car **manually**, before any modification
- [ ] Both pots removed and replaced per your variant
- [ ] **(A)** Digipot VDD/VSS powered from the controller's rail + ground, within rating
- [ ] **(A)** SPI wired: `SCK→D13`, `MOSI→D11`, throttle `CS→D7`, steering `CS→D8`
- [ ] **(B)** RC filters built: `D9→R→wiper`, `D10→R→wiper`, `C` from each wiper node to ground
- [ ] **(B)** Rail sense wired: controller rail → `A0`
- [ ] **[Common ground](glossary.md#common-ground)** between Nano GND and controller GND
- [ ] Firmware flashed with placeholder constants
- [ ] Car wheels **off the ground**
- [ ] Car powered on and paired to the modified controller

---

## 4. Bench test — measure the calibration values

Send each command from the app or a 115200-baud serial monitor, tune the constant
until the behavior matches, then record the value.

**Throttle (channel 1)** — do NEUTRAL first, it's the only one whose error is
dangerous rather than annoying:

- [ ] `A:1` then `T1:0` → car **fully stopped**, no creep → `THR_NEUTRAL = ______`
- [ ] `T1:100` → desired top forward speed → `THR_MAX = ______`
- [ ] `T1:-100` → desired top reverse speed → `THR_MIN = ______`

**Steering (channel 2):**

- [ ] `T2:0` → wheels pointing straight → `STR_CENTER = ______`
- [ ] `T2:100` → full right lock → `STR_RIGHT = ______`
- [ ] `T2:-100` → full left lock → `STR_LEFT = ______`

**Parked state:**

- [ ] `A:0` → trigger releases to neutral and wheels re-center

> 💡 **Two tips that save a lot of grief.** You don't have to use the control's
> full travel — clamping `THR_MAX`/`THR_MIN` lower is how you make a 60 km/h car
> usable indoors. And if a direction comes out reversed, replace every value with
> **`1023 − value`** rather than rewiring — a plain MIN/MAX swap leaves neutral
> wrong and the car creeping.

---

## 5. Copy final values into the firmware

Paste your numbers into the CALIBRATION block of your variant's sketch, then
re-flash:

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

## 6. Final drive test

Wheels still off the ground.

- [ ] `A:1` → arm
- [ ] `T2:-100` / `T2:100` / `T2:0` → steering sweeps left, right, center
- [ ] `T1:30` → wheels spin forward
- [ ] `T1:-30` → wheels spin reverse (correct direction)
- [ ] Disconnect the app briefly → car stops within ~0.5s ([failsafe](serial-protocol.md#the-500-ms-failsafe))
- [ ] **(B)** Cut power to the Nano mid-drive → note what the controller transmits ([why](controller-takeover.md#failure-mode-to-bench-test))
- [ ] `A:0` → everything parks at neutral

Once every box is checked, put the car on the ground and start slow.

---

**Back to:** [Path B: controller takeover](controller-takeover.md) · **Optional next:** [add distance sensors](sensor-wiring.md)
