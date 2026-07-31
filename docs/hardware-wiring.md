# Strix Hardware Guide

> **Start here.** This page explains what you're building, helps you pick your
> build path, and links to every other hardware document.

[Glossary](glossary.md) · [Parts & shopping](parts-and-shopping.md) · [Serial protocol](serial-protocol.md)

---

## What you're building

An RC car that drives itself, with an Android phone as its brain and an
**Arduino Nano** as its hands.

```
   Android phone  ──USB──▶  Arduino Nano  ──signals──▶  the car's motors
   (camera, VLM,             (translates                (drive + steering)
    the app)                  commands)
```

Three things to internalize before you touch a wire:

1. **The phone does the thinking.** It runs the camera, the vision model, and
   the app. It powers the Nano and talks to it over one USB cable.
2. **The Nano only sends tiny signals.** It never carries motor current. The
   car's own electronics do the heavy lifting.
3. **Everything shares one ground.** That shared reference is what makes the
   Nano's signals meaningful to the car. Most "nothing works" problems are a
   missing ground.

---

## Step 1: pick your drive path

This is the only decision that changes what you buy and build. **Look at your
car's ESC** (the box the motor wires run into):

| What you see                                                                     | Your path                                                 | Why                                                                                                                            |
| -------------------------------------------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| A 3-pin servo-style **signal plug** you can reach                                | **[Path A: direct-wired ESC](esc-wiring.md)**             | The Nano can drive the ESC directly. Simplest build.                                                                           |
| A **sealed, resin-potted brick** — motor wires in, no signal input, antenna wire | **[Path B: controller takeover](controller-takeover.md)** | Nothing to plug into. The Nano impersonates a handheld controller's controls instead, and the car's stock radio does the rest. |

Not sure? If the only way to command the car is its handheld remote, and the
ESC has no connector besides power and motor leads, you're on Path B.
Waterproof cars (Hosim, and most cars sold as "all-terrain") are Path B.

> 💡 **Distance sensors are independent of this choice.** The
> [sensor array](sensor-wiring.md) uses pins that are free in both paths, so
> you can add it to either build, whenever you like.

---

## Step 2: read your path's documents

| Document                                                                | What's in it                                                                  | Read it when                                |
| ----------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------- |
| **[Parts & shopping](parts-and-shopping.md)**                           | Every part, with Amazon links, prices, and what you can substitute or skip    | Before you order anything                   |
| **[Glossary](glossary.md)**                                             | Every acronym and concept in one place — ESC, BEC, wiper, duty cycle, ToF…    | Any time a term is unfamiliar               |
| **[Path A: ESC & servo wiring](esc-wiring.md)**                         | Direct-wired drive: pin map, connectors, assembly, first power-on             | You're on Path A                            |
| **[Path B: controller takeover](controller-takeover.md)**               | Sealed-ESC workaround: how it works, the two variants, build steps            | You're on Path B                            |
| **[Path B: identify the pots](pot-identification.md)**                  | Multimeter bench procedure to label the controller's pot wires before you cut | You're on Path B, before wiring             |
| **[Path B: calibration worksheet](controller-takeover-calibration.md)** | Fill-in-the-blanks checklist for measurements and calibration values          | You're on Path B, during the build          |
| **[Path B: bring-up log](controller-takeover-bringup.md)**              | What this car actually measured, and the findings that cost time              | Anything on Path B is misbehaving           |
| **[Add-on: sensor wiring](sensor-wiring.md)**                           | Forward-perception array: time-of-flight + ultrasonics, placement and pins    | You want measured obstacle distances        |
| **[Serial protocol](serial-protocol.md)**                               | The command language the phone and Nano share, and how to drive it by hand    | Testing, debugging, or writing code         |
| **[Diagrams](diagrams/README.md)**                                      | A Fritzing breadboard diagram of the Path B build, and how to regenerate it   | You want a picture of the breadboard layout |

### Suggested reading order

**Path A build:** [Parts](parts-and-shopping.md) → [ESC wiring](esc-wiring.md) → [Sensors](sensor-wiring.md) (optional) → [Protocol](serial-protocol.md) for testing

**Path B build:** [Parts](parts-and-shopping.md) → [Takeover overview](controller-takeover.md) → [Identify the pots](pot-identification.md) → [Worksheet](controller-takeover-calibration.md) → [Bring-up log](controller-takeover-bringup.md) → [Sensors](sensor-wiring.md) (optional)

**Just adding sensors to a working car:** [Sensors](sensor-wiring.md) → [Protocol](serial-protocol.md)

---

## The complete pin map

Every Nano pin used by every build, in one table. Columns are alternatives —
you use one drive-path column plus the sensor column.

| Nano pin | Path A: direct ESC    | Path B-A: digipots  | Path B-B: PWM            | Sensors (any build)         |
| -------- | --------------------- | ------------------- | ------------------------ | --------------------------- |
| **D2**   | —                     | —                   | —                        | Front-left ultrasonic TRIG  |
| **D3**   | —                     | —                   | —                        | Front-left ultrasonic ECHO  |
| **D4**   | —                     | —                   | —                        | Front-right ultrasonic TRIG |
| **D5**   | —                     | —                   | —                        | Front-right ultrasonic ECHO |
| **D7**   | —                     | Throttle digipot CS | —                        | —                           |
| **D8**   | —                     | Steering digipot CS | —                        | —                           |
| **D9**   | ESC signal            | —                   | Throttle PWM → RC filter | —                           |
| **D10**  | Steering servo signal | —                   | Steering PWM → RC filter | —                           |
| **D11**  | Spare (channel 3)     | SPI MOSI            | —                        | —                           |
| **D13**  | —                     | SPI SCK             | —                        | —                           |
| **A0**   | —                     | —                   | Controller rail sense    | —                           |
| **A4**   | —                     | —                   | —                        | ToF SDA (I²C)               |
| **A5**   | —                     | —                   | —                        | ToF SCL (I²C)               |
| **3V3**  | —                     | —                   | —                        | ToF power (**never 5V**)    |
| **5V**   | —                     | —                   | —                        | Ultrasonic power            |
| **GND**  | Common ground         | Common ground       | Common ground            | Common ground               |

Firmware for each column: [`EscServoController.ino`](../arduino/EscServoController/EscServoController.ino) ·
[`ControllerTakeover.ino`](../arduino/ControllerTakeover/ControllerTakeover.ino) ·
[`ControllerTakeoverPwm.ino`](../arduino/ControllerTakeoverPwm/ControllerTakeoverPwm.ino)

---

## Building it on a breadboard

The whole thing fits on one **full-size 830-point breadboard** with no soldering.
Path B needs two RC filters — two components each — and everything else is
jumpers.

### Orient the Nano first

Plug it in at the **column-1 end of the board, USB pointing off the edge**, away
from your components.

That cable comes out constantly — every reflash, every move between phone and
laptop. Pointed inward it lies across the filters and lifts jumpers every time
you touch it. Pointed outward, all the strain goes off the board.

### What the Nano covers, and what's left

A Nano's two header rows sit 0.6" apart, which is exactly six holes. So it
straddles the centre channel with one header in **row D** and the other in **row
H**. That's not a choice — it's what the board's width gives you.

| Rows                     | State                                                                       |
| ------------------------ | --------------------------------------------------------------------------- |
| **H**                    | Digital header — D1–D12, so **D9/D10** (the Variant B wiper pins) land here |
| **D**                    | Analog header — VIN, 5V, **A0**, 3V3, A4/A5, D13                            |
| **E, F, G**              | Under the body. Unreachable — don't plan anything here                      |
| **A, B, C** and **I, J** | Free, and where your jumpers and components go                              |

### Two habits worth adopting

**Keep runs and components in different rows.** A jumper lying across a resistor
hides it and makes it impossible to probe. This bit the diagram too — R1 was
invisible for an entire revision because a wire ran down its row and drew right
over it.

**Land power on both sides.** The Nano has a GND pin on each header, so take each
one to the ground rail nearest it: row H's GND up to the top rail, row D's GND
down to the bottom. 5V goes to the bottom rail, then one bridge jumper carries it
across to the top. Now every rail is live and you never reach over the board for
power.

Exact holes for every part and every wire are in the
[Fritzing diagram](diagrams/README.md).

---

## Can I use a different board?

Short version: **any 5 V / 16 MHz AVR is a drop-in. Anything else is a rework.**
The Nano is the default because it's small, cheap, 5 V, and breadboard-native.

**Drop-in — Uno, Pro Mini, Nano clones.** Same ATmega328P, same timers, same pin
numbers, so the pin map above applies unchanged. The Uno doesn't seat in a
breadboard so you'd jumper across to it; the Pro Mini has no USB, which defeats
the phone link.

<details>
<summary><strong>Mega — buys less than you'd think, and hides a trap</strong></summary>

**The trap.** Variant B synthesizes wiper voltages with PWM on **D9/D10**. On the
Nano those are **Timer1, which is 16-bit** — that's what lets the sketch run
10-bit duty at 15.6 kHz. On a Mega, D9/D10 are **Timer2, which is 8-bit**. The
sketch's timer setup no longer applies and you quietly lose a factor of four in
throttle resolution, with nothing to warn you. To get it back you'd relocate the
wiper pins onto one of the Mega's 16-bit timers — Timer1 on pins 11/12, Timer3 on
pins 2/3/5.

**What it genuinely buys.** Three extra hardware UARTs. The Nano has exactly one
and the USB link owns it, so if you ever want a serial GPS, IMU, or telemetry
radio running _at the same time_ as the phone, that's the real reason to move.
Secondarily: more pins, more SRAM, and on Path A it sidesteps the Servo library's
claim on Timer1.

**What it costs.** Bigger, heavier, draws more, and it's shield-format — it
doesn't sit on a breadboard, it hangs off one.

**Verdict:** stay on the Nano unless you specifically need concurrent serial
devices.

</details>

**Not a swap — ESP32, RP2040, STM32 "Blue Pill" and other 3.3 V boards.** These
change the electrical assumptions, not just the pinout, and Variant B is the
blocker. The firmware compensates for the controller's draining batteries by
[sensing its rail on A0](controller-takeover.md#why-the-rail-sense-wire-matters)
and scaling every output against it, which works because the PWM output and the
ADC reading share one Vcc and it cancels out. On a regulated 3.3 V board the PWM
tops out at 3.3 V while the controller rail sits near 4.5 V (3×AA), so **you
can't reach the end of the pot's travel at all** — no full throttle, no full
steering lock. Fixing that takes an op-amp stage, not a rewire. Sensors are a
wash: you'd drop the ToF [level shifter](sensor-wiring.md), then have to add
level handling for the 5 V ultrasonics.

---

## Rules that apply to every build

These hold no matter which path you take. Everything else is detail.

| Rule                                                                                                          | Why                                                                                                        |
| ------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- |
| **One common ground.** Nano GND ties to the ESC/servo/controller ground.                                      | Every signal is a _voltage relative to ground_. Without a shared reference, the receiving chip sees noise. |
| **Two power worlds, meeting only at ground.** The phone powers the Nano; the car's battery powers the motors. | Bridging them is the #1 way people destroy a Nano. Never feed motor-side 5–6V into the Nano's 5V pin.      |
| **Wheels off the ground for every first test.** Prop the car on a box.                                        | A miscalibrated throttle sends a 60 km/h car across the room.                                              |
| **Arm before you drive.** Nothing moves until the app sends `A:1`.                                            | Prevents a stray startup command from launching the car. See [arming](serial-protocol.md#arming).          |
| **The 500 ms failsafe is your friend.** If commands stop, everything parks at neutral.                        | A crashed app or yanked cable stops the car instead of leaving it running.                                 |

> ⚠️ **Battery safety.** LiPo packs are not forgiving. Don't charge them
> unattended, don't puncture them, don't leave them in a hot car, and stop
> using any pack that's puffed or damaged.

---

## This build's configuration

The docs are written to be general, but they're grounded in one real build. Where
you see measured numbers, they came from this car:

|                            |                                                                                                                                    |
| -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **Car**                    | Hosim X15 — waterproof, sealed receiver+ESC brick, ~60 km/h                                                                        |
| **Drive path**             | **Path B**, controller takeover (no signal wire exists to tap)                                                                     |
| **Variant**                | **B — PWM wiper synthesis** (no digipot chips; see [why](controller-takeover.md#pick-a-variant-digipots-or-pwm))                   |
| **Sacrificial controller** | Hosim F12025 transmitter ([parts](parts-and-shopping.md#path-b-controller-takeover))                                               |
| **Controller pots**        | ~5.2 kΩ, 3-wire harness with connectors, **black wire = wiper** ([measurements](pot-identification.md#field-data-from-this-build)) |
| **Sensors**                | Center VL53L4CD time-of-flight + two corner HC-SR04 ultrasonics                                                                    |

If your car differs, the _procedures_ still apply — substitute your own
measurements for the numbers.

---

## Reference

- **App-side serial API:** [`MotorController.kt`](../app/src/main/java/com/resourcefork/rccontrol/MotorController.kt)
- **Action → motor mapping** (how `TURN_LEFT` becomes throttle + steering): [`DriveCommand.kt`](../app/src/main/java/com/resourcefork/rccontrol/DriveCommand.kt)
- **Vision setup:** [on-device VLM](on-device-vlm.md) · [self-hosted VLM server](self-hosted-vlm-server.md)

---

**Next:** [Parts & shopping](parts-and-shopping.md) — figure out what you need to buy.
