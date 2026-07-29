# Serial Protocol Reference

> The command language the phone and the Nano share. Identical across all three
> firmware variants, so the app never needs to know which build you did.

[← Hardware guide](hardware-wiring.md) · [Glossary](glossary.md)

---

## At a glance

| | |
| --- | --- |
| **Transport** | USB serial, 115200 baud, 8N1 |
| **Format** | Newline-terminated ASCII lines, both directions |
| **Safety gates** | [Arming](#arming) + a [500 ms failsafe](#the-500-ms-failsafe) |
| **Who speaks it** | All three sketches; app side is [`MotorController.kt`](../app/src/main/java/com/resourcefork/rccontrol/MotorController.kt) |

---

## Transport

The phone is the USB host (via [OTG](glossary.md#otg)); the Nano's
[CH340](glossary.md#ch340) chip is the device. The app finds the port
automatically — there's nothing to configure.

| Setting | Value |
| --- | --- |
| Baud rate | 115200 |
| Data / parity / stop | 8 / none / 1 |
| Line ending | `\n` (the firmware also tolerates and discards `\r`) |
| Encoding | US-ASCII |

---

## Commands

Every command is a single line ending in `\n`.

| Command | Meaning | Reply |
| --- | --- | --- |
| `A:1` | [Arm](#arming) — required before any throttle command takes effect | `ARMED` |
| `A:0` | Disarm — forces every output to neutral | `DISARMED` |
| `T1:<v>` | Set **throttle** (drive), `v` from −100 to 100 | `SET:1:<v>` |
| `T2:<v>` | Set **steering**, `v` from −100 to 100 | `SET:2:<v>` |
| `T3:<v>` | Spare channel — wired only on [Path A](esc-wiring.md), unused by the car | `SET:3:<v>` |
| `?` | Ping / status | `OK:<armed>:<t1>:<t2>:<t3>` |
| `D?` | Latest [distance](sensor-wiring.md) samples | `D:<center>,<frontLeft>,<frontRight>` |

**Value conventions** for `T1`/`T2`:

| Value | Channel 1 (throttle) | Channel 2 (steering) |
| --- | --- | --- |
| `100` | Full forward | Full right |
| `0` | Stop / neutral | Centered |
| `-100` | Full reverse | Full left |

Values outside −100…100 are clamped by the firmware, so you can't overdrive it
with a bad number.

---

## Replies

| Reply | Meaning |
| --- | --- |
| `ARMED` / `DISARMED` | Arm state changed |
| `SET:<ch>:<v>` | Channel accepted and applied |
| `OK:<armed>:<t1>:<t2>:<t3>` | Status: `armed` is `1`/`0`, followed by the last commanded value for each channel |
| `D:<center>,<frontLeft>,<frontRight>` | Distances in **millimeters**; `-1` means no reading (sensor absent, out of range, or missed echo) |
| `ERR:NOT_ARMED` | A throttle command arrived before `A:1` |
| `ERR:BAD_CHANNEL` | Channel outside 1–3 |
| `ERR:UNKNOWN_CMD` | Unparseable line |

> 💡 **The firmware acknowledges everything, and nothing consumes those acks.**
> The app's reader drains and discards lines until it finds the prefix it asked
> for, so a serial buffer holding several stale `SET:`/`ARMED` lines is normal,
> not a bug.

---

## Safety semantics

### Arming

Throttle commands do nothing until the controller is armed. This exists so a
stray command — a serial glitch, a half-open app, a reconnect — can't move a car
that's sitting on the bench.

```
T1:50      → ERR:NOT_ARMED     (ignored)
A:1        → ARMED
T1:50      → SET:1:50          (car moves)
```

Disarming with `A:0` immediately forces all outputs to neutral.

### The 500 ms failsafe

While armed, the firmware expects to keep hearing from the phone. If **500 ms**
pass with no command of any kind, it disarms itself and parks every output at
neutral.

This is what makes a crashed app, a yanked USB cable, or a dead phone battery
into a stop rather than a runaway. Note that *any* command resets the timer — the
app's periodic status pings are enough to hold the link alive during idle
moments.

> ✅ **Test it deliberately.** With the wheels off the ground, drive the car, then
> unplug the USB cable. The car should stop within about half a second. Do this
> before you ever put the car on the floor.

---

## How values reach the hardware

Same protocol, three different output layers. This is the only place the builds
differ, and it's why each has its own calibration.

| Build | What a `T1:<v>` becomes |
| --- | --- |
| [Path A: direct ESC](esc-wiring.md) | A [servo pulse](glossary.md#servo-pulse): −100…100 maps linearly onto 1000…2000 µs, neutral at 1500 µs |
| [Path B, Variant A: digipots](controller-takeover.md#variant-a-digipot-chips) | A digipot [wiper code](glossary.md#taps-wiper-codes) (0–255), from your calibration constants |
| [Path B, Variant B: PWM](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots) | A [duty cycle](glossary.md#duty-cycle) expressed as a rail fraction (0–1023), then scaled by the measured controller rail |

**One subtlety worth knowing:** Path A maps the whole −100…100 range with a
single linear conversion, because a servo's neutral is a standardized 1500 µs.
The takeover variants instead map the two halves separately — `-100…0` and
`0…100` — so that `0` always lands *exactly* on your calibrated neutral value.
On a car whose trigger neutral isn't at the midpoint of its travel (this build's
isn't, [by a lot](pot-identification.md#field-data-from-this-build)), the split
mapping is what keeps `T1:0` from creeping.

---

## App-side behavior

Things the app does on top of the raw protocol:

| Behavior | Detail |
| --- | --- |
| **Distance polling** | ~5 Hz while connected, with a [median filter](glossary.md#median-filter) over recent samples to reject single-sample ultrasonic ghosts |
| **[Thrust-inverse cooldown](glossary.md#thrust-inverse-cooldown)** | After thrust in one direction, the opposite direction is held at neutral for 1 second so the drivetrain isn't slammed forward-to-reverse at speed. Applies to joystick, drive pad, and autonomous pilots alike; the UI disables the locked-out half of the joystick. |
| **Channel restriction** | The app only ever drives channels 1 and 2 — channel 3 exists in the firmware but nothing uses it |
| **Reconnect** | The port is reopened on demand; the failsafe covers the gap |

---

## Driving it by hand

You don't need the app to test hardware. Open the Arduino IDE's serial monitor at
**115200 baud** with line endings set to newline, and paste these in order.

**Steering check** (safe with the drive motor disconnected):

```
A:1        arm
T2:0       center
T2:-100    full left
T2:100     full right
T2:0       recenter
A:0        disarm
```

**Throttle check** (wheels off the ground, motor reconnected):

```
A:1        arm
T1:0       neutral — the car must not move
T1:30      30% forward
T1:0       stop
T1:-30     30% reverse
A:0        disarm
```

**Diagnostics:**

```
?          expect OK:1:0:0:0 when armed and idle
D?         expect D:431,822,760 (mm) — or D:-1,-1,-1 with no sensors wired
```

> ⚠️ Remember the [failsafe](#the-500-ms-failsafe): typing by hand takes longer
> than 500 ms, so the controller disarms between your commands. That's correct
> behavior. Re-send `A:1` if you get `ERR:NOT_ARMED` mid-session.

---

## Reference

- Firmware: [`EscServoController.ino`](../arduino/EscServoController/EscServoController.ino) · [`ControllerTakeover.ino`](../arduino/ControllerTakeover/ControllerTakeover.ino) · [`ControllerTakeoverPwm.ino`](../arduino/ControllerTakeoverPwm/ControllerTakeoverPwm.ino)
- App serial client: [`MotorController.kt`](../app/src/main/java/com/resourcefork/rccontrol/MotorController.kt)
- Distance parsing: [`DistanceReport.kt`](../app/src/main/java/com/resourcefork/rccontrol/DistanceReport.kt)
- Action → throttle/steering mapping: [`DriveCommand.kt`](../app/src/main/java/com/resourcefork/rccontrol/DriveCommand.kt)

---

**Back to:** [Hardware guide](hardware-wiring.md)
