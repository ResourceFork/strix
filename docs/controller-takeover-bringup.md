# Path B: Bring-Up Log and Hard-Won Findings

> **Read this before touching the Variant B build.** It records what is actually
> true about this car, as opposed to what the theory predicted — and several of
> those disagree. Every number here was measured on the hardware.

[Hub](hardware-wiring.md) · [Takeover guide](controller-takeover.md) · [Identify the pots](pot-identification.md) · [Worksheet](controller-takeover-calibration.md)

---

## At a glance

| | |
| --- | --- |
| **Works** | Steering, both locks and centre. Throttle direction and neutral. Rail sensing. Both ultrasonics. Thrust interlock. |
| **Outstanding** | Two-pole filter rebuild, 10 kΩ pull-up, widening throttle travel, ToF sensor |
| **Known hazard** | Un-driven wiper ≈ 0 V = **past full throttle**. Not yet mitigated. |
| **Bench tool** | [`scripts/nano-bench.py`](../scripts/nano-bench.py) |

---

## This build's confirmed numbers

### Wire roles — all three opposite to what the colours suggest

| Wire | Role | Measured at socket |
| --- | --- | --- |
| **white** | **HIGH** — rail | 3.2 V |
| **black** (centre) | **WIPER** | 0.005 V floating |
| **red** | **LOW** — ground | 0 V |

The rail is **3.2 V, not the 4.5 V** you'd expect from 3×AA. Whether that's a
tired pack or a regulated supply, it's the denominator for every calibration
fraction — which is exactly why the A0 rail-sense wire isn't optional.

### Voltages and counts

| Thing | Value |
| --- | --- |
| Nano Vcc (measured at + rail) | 4.18 V |
| Controller rail as A0 counts | ~800–812, valid |
| Wiper node at neutral | ~1.79 V |
| Nano serial port | `/dev/cu.usbserial-131220` |

### Calibration constants

| Constant | Derived from resistance | **Actual** | Note |
| --- | --- | --- | --- |
| `THR_MIN` reverse | 632 | **620** | bring-up clamp, not full travel |
| `THR_NEUTRAL` | 478 | **560** | derived value was 82 counts off |
| `THR_MAX` forward | 157 | **480** | bring-up clamp, not full travel |
| `STR_LEFT` | 103 | **103** | confirmed, full lock |
| `STR_CENTER` | 492 | **492** | confirmed, visibly straight |
| `STR_RIGHT` | 1013 | **1013** | confirmed, full lock |

**Steering needed no correction; throttle was 82 counts out.** That difference is
the single most useful thing on this page. The pot maths and the complement
arithmetic were *right all along* — throttle has a second calibration in series
that steering doesn't: the ESC's own neutral point, plus the **FR.TRIM** knob.

> 💡 **FR.TRIM is a forward speed governor**, not a trim in the usual sense. Fully
> clockwise leaves forward uncapped; rolling it back limits top speed regardless
> of what we command. That makes it a genuine hardware safety lever on a 60 km/h
> car — use it.

---

## Findings that cost real time

### Forward is the LOW voltage end

Ground is the **red** wire, so the trigger's forward end sits near **0.5 V** and
reverse near **2.0 V**. Pushing forward makes the number go *down*. Everything
downstream inverts from this, including `THR_MIN > THR_MAX` in the sketch, which
is correct and must not be "tidied" into ascending order — `map()` doesn't clamp
and interpolates inverted endpoints fine.

### A HIGH/LOW mix-up needs the complement, not a swap

Getting the ends backwards inverts the wiper fraction. The fix is
**`f → 1023 − f` on every constant**, not swapping MIN and MAX. A swap leaves
neutral wrong by ~67 counts because neither travel window is centred, and the car
creeps. See [the detail](pot-identification.md#why-this-matters).

### The un-driven wiper is full throttle

An un-driven node decays toward 0 V, which here is **past full forward**. Worse,
an unpowered Nano isn't high-impedance — its pin protection diodes clamp toward a
dead rail and *actively hold the node low*. So any moment the board isn't driving
(not booted, unplugged, crashed) the car sees more than full throttle.

**Mitigation, not yet fitted:** ~10 kΩ from each wiper node to the controller's
HIGH pin. With the Nano dead that holds the node near 1.4 V, close to neutral,
instead of 0 V. It also caps commandable forward at roughly half throttle, which
on this car is a feature. Until it's in: **never power the car before the Nano is
up and verified at neutral.**

### One RC stage is not enough — and R×C doesn't tell you the ripple

A single 4.7 kΩ + 1 µF leaves ~**14 mV** of 15.6 kHz PWM residue. That sounds
negligible, but it's *coherent*, and the controller's ADC undersamples it into a
slow beat the steering servo visibly follows.

Found by a control experiment worth remembering: **plug the mechanical pot back
in.** With the pot the servo is perfectly still; with a single-stage emulator it
hunts. A pot has zero ripple by construction; an averaged square wave does not.

| Filter | Ripple | Settling | Impedance |
| --- | --- | --- | --- |
| 4.7 kΩ + 1 µF, one stage | 14.2 mV | 24 ms | 4.7 kΩ |
| 1 kΩ + 4.7 µF, one stage | **14.2 mV — identical** | 24 ms | 1 kΩ |
| 4.7 kΩ + 10 µF, one stage | 1.4 mV | 235 ms | 4.7 kΩ |
| **1 kΩ + 2.2 µF, two stages** | **0.14 mV** | **11 ms** | **1 kΩ** |
| 1 kΩ + 4.7 µF, two stages | 0.03 mV | 24 ms | 1 kΩ |

Two filters with the same R×C have the **same ripple and the same settling**, so
trading R against C buys nothing. Cascading attenuates by the *square*: ripple
falls ~100× while the response gets *faster* than the single stage it replaces.
Confirmed empirically — swapping 1 µF for 10 µF on one channel visibly reduced
the wobble, which is what identified ripple as the cause.

### Rail-sense noise lands straight on the throttle

`duty = cal × railCounts / 1023`, so noise on A0 becomes throttle movement. A
single ADC read spread **56 counts (229 mV)** on a live controller and put
**69 mV of wander** on the wiper with the command held perfectly still — which
the car showed as random forward/reverse pulses at rest.

Fixed with 32× oversampling and a 1/64 exponential average (`RAIL_OVERSAMPLE`,
`RAIL_SMOOTH`). Battery sag takes minutes, so a ~6 s time constant costs nothing.
Result: raw spread 56 → 11 counts, applied duty **17 → 0**.

### Deadband narrower than the noise

Before the noise fix, cal 530 gave forward-only pulses and cal 560 gave pulses in
*both* directions at the same rate. Two-sided pulsing means you're centred in the
deadband **and** the noise exceeds it — at which point no amount of neutral
placement helps and the noise itself has to come down. Useful diagnostic shape.

### `DUTY_HYSTERESIS` was a mistake

Added at 3 counts to stop output dither. Wrong twice: with the rail properly
filtered the duty doesn't move at all (18 consecutive bit-identical samples), so
there was nothing to suppress — and a threshold converts slow drift into discrete
**jumps**, which a position servo dislikes more than drift. Left at 1, i.e. off,
with the reasoning in the sketch.

### The interlock has to be in the firmware

`THRUST_INVERSE_COOLDOWN` originally lived only in the app. The app is not the
only thing that drives this board: a bench script talking straight to the serial
port bypassed it entirely and threw a live car from full forward into full
reverse. **An interlock a direct command can walk around is not an interlock.** It
now sits at the last choke point before the timer, so app, serial console and
calibration scripts all pass through it. `nano-bench.py gate` verifies it.

### This Nano needs the old bootloader

Uploads fail with `not in sync: resp=0x00` on the default board setting. It's an
old-bootloader clone — 57600 baud, not 115200:

```bash
arduino-cli upload --fqbn arduino:avr:nano:cpu=atmega328old -p /dev/cu.usbserial-131220 arduino/ControllerTakeoverPwm
```

Affects **uploading only**. The sketch's own `Serial.begin(115200)` is unrelated,
and the coincidence of the two numbers invites exactly the wrong guess.

### The app has to stream, not fire once

The firmware disarms after 500 ms of silence and only `A:` and `T` refresh that
timer — the distance poll doesn't. Joystick input is edge-triggered, so holding
the stick still emits no events. Fixed with a 150 ms keepalive in `RCViewModel`.
Consequence for bench work: **hand-typing commands cannot work**, which is why
`nano-bench.py` exists.

---

## Bench method

Controller and car are separately powered, and that's the lever:

| State | Use for |
| --- | --- |
| **Controller ON, car OFF** | Rail and noise work. Live rail, motion impossible. |
| **Controller ON, car ON, wheels up** | Calibration. Only with a hand on the power. |

```bash
python3 -m venv /tmp/nanoenv && /tmp/nanoenv/bin/pip install pyserial
/tmp/nanoenv/bin/python scripts/nano-bench.py probe
```

`C<ch>:<0..1023>` sets a raw rail fraction directly, bypassing the calibration
mapping. That's what makes live calibration possible instead of one reflash per
guess. `R?` reports rail counts, raw A0, and validity — a raw value well under
500 that drifts means A0 is disconnected and the firmware is silently running on
its fallback.

**Hold one value at a time** when someone is reporting what they see. Multi-step
sequences can't be correlated from memory, and terminal output may not appear
until the run finishes.

---

## Outstanding

1. **Two-pole filter** — 1 kΩ + 2.2 µF twice, per channel. The last known cause
   of the servo wobble.
2. **10 kΩ pull-up** to each HIGH pin. The one hazard firmware cannot fix.
3. **Widen throttle travel.** Currently clamped to ~⅕, prudent while noise sat
   near the deadband. Revisit after the filter.
4. **ToF sensor** reads −1; ultrasonics both work.
5. **Re-verify with the app** rather than serial, end to end.

---

## Method lessons

Three things that would have saved most of a night:

- **Calibrate the safe, position-visible channel first.** Steering can't run
  away, and wheel *angle* is unambiguous where wheel *speed* isn't. It's also the
  more sensitive noise detector — the servo revealed jitter the ESC's deadband
  hid.
- **A/B against the part you're replacing.** The pot-versus-emulator test settled
  in thirty seconds a question that theory had been circling for an hour.
- **Prefer measured over derived.** The resistance maths was internally correct
  and still mislocated throttle neutral by 82 counts, because it couldn't know
  about the ESC's neutral or the trim knob. Derive to find the ballpark; measure
  to find the value.

---

**Next:** [Calibration worksheet](controller-takeover-calibration.md) — record your own numbers.
