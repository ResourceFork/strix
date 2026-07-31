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

| Item                               | Value          | Notes                                                                                                                                   |
| ---------------------------------- | -------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| Car model                          | `X15`          | (this build)                                                                                                                            |
| Controller part                    | `F12025`       | [Hosim replacement transmitter](parts-and-shopping.md#the-controller-required-for-path-b)                                               |
| Trigger pot track (HIGH→LOW)       | `~5.2 kΩ`      | 5k nominal — via wiper-pair sum                                                                                                         |
| Wheel pot track (HIGH→LOW)         | `~5.2 kΩ`      | 5k nominal — sum constant at every position                                                                                             |
| **Controller supply rail voltage** | `3.2 V`        | **Not 4.5 V** — measure, don't assume. [Powered test](pot-identification.md#step-3-find-high-and-low-powered-test)                      |
| Nano Vcc over USB                  | `4.18 V`       | Needed to read A0 counts as volts                                                                                                       |
| **(A)** Digipot part chosen        | `____________` | MCP41010 (10k works — [ratiometric](glossary.md#ratiometric))                                                                           |
| **(A)** Digipot max voltage rating | `______ V`     | **Must be ≥ the controller rail**                                                                                                       |
| **(B)** Filter R per channel       | `1 kΩ ×2`      | Two cascaded stages — [one is not enough](controller-takeover-bringup.md#one-rc-stage-is-not-enough--and-rc-doesnt-tell-you-the-ripple) |
| **(B)** Filter C per channel       | `2.2 µF ×2`    | 0.14 mV ripple, 11 ms settling                                                                                                          |
| **(B)** Wiper pull-up to HIGH      | `~10 kΩ`       | Un-driven wiper = past full throttle without it                                                                                         |

> ⚠️ **(A)** If the rail voltage exceeds your digipot's rating, either pick a
> higher-voltage part or feed that controller from a regulated 4.5–5V source.

---

## 2. Identify each pot's terminals

→ **Full procedure: [Identify the pots](pot-identification.md)**

Record which wire or pad is which. Getting the **wiper** right is the one that
can't be fixed in software.

| Pot                | HIGH    | WIPER   | LOW   |
| ------------------ | ------- | ------- | ----- |
| Trigger (throttle) | `white` | `black` | `red` |
| Wheel (steering)   | `white` | `black` | `red` |

> ⚠️ **This build's wires are all three opposite to the colour convention** —
> white is HIGH, red is LOW, and forward is the _low_-voltage end. Verify yours;
> don't inherit these.

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

Drive each command, tune the constant until the behavior matches, then record the
value.

> ⚠️ **A serial monitor cannot do this.** The firmware disarms after 500 ms of
> silence, so hand-typed commands expire between keystrokes and nothing appears to
> work. Use the app, or the bench tool that feeds a keepalive for you:
>
> ```bash
> python3 -m venv /tmp/nanoenv && /tmp/nanoenv/bin/pip install pyserial
> /tmp/nanoenv/bin/python scripts/nano-bench.py hold C2:492
> ```
>
> `C<ch>:<0..1023>` sets a raw rail fraction directly, bypassing the calibration
> constants — so you can find each value live instead of re-flashing per guess.
> `hold` keeps it there until Ctrl-C. Full tool:
> [`scripts/nano-bench.py`](../scripts/nano-bench.py).

> 💡 **Do steering first.** It cannot run away from you, wheel _angle_ is
> unambiguous where wheel _speed_ is guesswork, and the servo is a far more
> sensitive noise detector than the ESC — its deadband hides jitter the servo
> shows you immediately. Get steering clean and you've validated your wiring,
> your filter and your rail scaling before throttle ever moves.

**Steering (channel 2)** — start here:

- [ ] `A:1` then `T2:0` → wheels straight, servo **silent and still** → `STR_CENTER = ______`
- [ ] `T2:100` → full right lock → `STR_RIGHT = ______`
- [ ] `T2:-100` → full left lock → `STR_LEFT = ______`
- [ ] Hold at centre for 30 s → no hunting, buzzing or pulsing

> ⚠️ **A servo that won't hold still is telling you about ripple, not
> calibration.** Don't proceed to throttle until it's quiet. The fastest
> confirmation is to plug the mechanical pot back in: if _it_ is silent and your
> emulator isn't, the difference is your filter.
> [What that cost us](controller-takeover-bringup.md#one-rc-stage-is-not-enough--and-rc-doesnt-tell-you-the-ripple).

**Throttle (channel 1)** — NEUTRAL first; it's the only one whose error is
dangerous rather than annoying:

- [ ] `A:1` then `T1:0` → car **fully stopped**, no creep, no pulsing → `THR_NEUTRAL = ______`
- [ ] `T1:100` → desired top forward speed → `THR_MAX = ______`
- [ ] `T1:-100` → desired top reverse speed → `THR_MIN = ______`

> ⚠️ **Pulses in _both_ directions at rest** means you are centred in the ESC
> deadband **and** your noise exceeds it. No neutral value fixes that — the noise
> has to come down. Pulses in one direction only means neutral is genuinely off.

**Parked state:**

- [ ] `A:0` → trigger releases to neutral and wheels re-center

<details>
<summary><strong>This build's measured values</strong> (worked example)</summary>

| Constant            | Derived from resistance | **Measured**                        |
| ------------------- | ----------------------- | ----------------------------------- |
| `THR_MIN` (reverse) | 632                     | **620** — deliberate bring-up clamp |
| `THR_NEUTRAL`       | 478                     | **560**                             |
| `THR_MAX` (forward) | 157                     | **480** — deliberate bring-up clamp |
| `STR_LEFT`          | 103                     | **103**                             |
| `STR_CENTER`        | 492                     | **492**                             |
| `STR_RIGHT`         | 1013                    | **1013**                            |

**Steering needed no correction; throttle neutral was 82 counts out.** The pot
arithmetic wasn't wrong — throttle simply has a second calibration in series that
steering doesn't: the ESC's own neutral point plus the **FR.TRIM** knob. Derive to
find the ballpark, measure to find the value.

Throttle is clamped to roughly a fifth of its travel here, which is prudence on a
60 km/h drivetrain, not a measurement.
[Full bring-up log](controller-takeover-bringup.md).

</details>

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

> 💡 `THR_MIN > THR_MAX` is **correct** when forward is the low-voltage end, as it
> is on this build. Don't tidy them into ascending order — `map()` interpolates
> inverted endpoints fine, and swapping them silently reverses your throttle.

> ⚠️ **Some Nano clones need the old bootloader to accept an upload** — symptom is
> `not in sync: resp=0x00`. Add `:cpu=atmega328old` to the FQBN. It affects
> uploading only and has nothing to do with the sketch's own baud rate, a
> coincidence that invites the wrong guess:
>
> ```bash
> arduino-cli upload --fqbn arduino:avr:nano:cpu=atmega328old \
>   -p /dev/cu.usbserial-131220 arduino/ControllerTakeoverPwm
> ```

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

> ⚠️ **Expect the Nano-power test to fail without the pull-up.** On this build an
> un-driven wiper decays to ~0 V, which is **past full forward** — and an
> unpowered Nano isn't neutral, its protection diodes actively hold the node low.
> The ~10 kΩ wiper-to-HIGH resistor from [section 1](#1-parts-and-measurements) is
> what turns that into something survivable. Until it's fitted, never power the
> car before the Nano is up and verified at neutral.

Once every box is checked, put the car on the ground and start slow.

---

**Back to:** [Path B: controller takeover](controller-takeover.md) · **Optional next:** [add distance sensors](sensor-wiring.md)
