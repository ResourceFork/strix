# Hardware Wiring Guide

How to wire the RC car's electronics to the Arduino Nano — written so you can
follow it even if you've never touched an RC car or a microcontroller before.

The car's "brain" is an **Arduino Nano**. The phone runs the Strix app and talks
to the Nano over a USB cable. The Nano then sends signals to the parts that
actually move the car.

This page is the overview and table of contents: it introduces the parts, the
one rule that keeps the Nano alive, and the decision that picks your drive
path — then hands off to the focused guides.

## The guides

| Guide | What it covers | Read it when |
| --- | --- | --- |
| [ESC & servo wiring](esc-wiring.md) | The "normal" drive path: wiring a conventional ESC and steering servo straight to the Nano — pin map, connectors, assembly, first power-on test, troubleshooting | Your ESC has a servo-style signal wire |
| [Controller takeover](controller-takeover.md) | The sealed-ESC workaround: the Nano drives digital potentiometers planted inside a spare handheld controller, and the controller's stock radio does the rest | Your ESC is a sealed receiver+ESC brick with no signal input |
| [Calibration worksheet](controller-takeover-calibration.md) | Fill-in checklist for the takeover build: pot measurements, terminal identification, wiper calibration | You're doing the takeover build |
| [Sensor wiring](sensor-wiring.md) | The forward-perception array: center VL53L4CD time-of-flight + corner HC-SR04 ultrasonics — placement, pin tables, verification | You're adding measured obstacle distances (works with either drive path) |

## Which drive path do I need?

Look at your ESC:

- **It has a 3-pin servo-style signal plug** (or a signal wire you can reach) →
  the direct path: [`esc-wiring.md`](esc-wiring.md).
- **It's a sealed, resin-potted receiver+ESC combo** — waterproof Hosim-style
  cars (like this build's X15) pot both into one brick whose only input is its
  own 2.4GHz radio → [`controller-takeover.md`](controller-takeover.md).

The [sensor wiring](sensor-wiring.md) is independent of that choice: its pins
(D2–D5, A4/A5) are free in both firmware variants, so the same sensor wiring
works for either build.

## The parts (and their acronyms)

| Part | What it is | Its job |
| --- | --- | --- |
| **Arduino Nano** | A small microcontroller board | Receives commands from the phone, sends signals to everything else |
| **ESC** — *Electronic Speed Controller* | The RC car's motor controller (the acronym you were after) | Takes power from the battery and drives the main **drive motor** forward/reverse based on a signal wire |
| **Steering servo** | A small geared motor that turns the front wheels | Points the wheels left/right |
| **Drive motor** | The big motor that spins the wheels | Makes the car go; it's driven *by the ESC*, never wired to the Nano directly |
| **RC battery** | Usually a 2S LiPo (7.4V) or NiMH pack | Powers the motor side (high current) |

> **Key idea for newbies:** the Nano never touches motor-level power. It only
> sends tiny *signal* pulses. The ESC is the muscle — it takes the fat battery
> wires and does the heavy lifting. Mixing these two worlds up is the #1 way
> people fry a board, so every guide above keeps them separate: two power
> worlds (phone-powered logic, battery-powered motors) that meet only at one
> **common ground**.

## What's the same everywhere

Whichever path you take:

- **The phone is the power and brains link.** A USB OTG cable powers the Nano
  and carries the serial protocol (115200 baud). The app finds the port
  automatically.
- **The serial protocol never changes.** `A:1` arm, `T1:<v>` throttle,
  `T2:<v>` steering, `?` ping, `D?` distances — both firmware variants speak
  it byte-for-byte identically, so the app doesn't care which build you did.
- **The failsafe never changes.** If commands stop for 500 ms, everything
  parks at neutral. A crashed app or a yanked cable stops the car.
- **Common ground is mandatory.** Every signal is measured relative to it;
  most "nothing works" symptoms are a missing ground.

## Reference

- App-side serial API and protocol: `app/.../MotorController.kt`
- Action → motor mapping (how "TURN_LEFT" becomes throttle+steering): `app/.../DriveCommand.kt`
- Direct-path firmware: `arduino/EscServoController/EscServoController.ino`
- Takeover firmware: `arduino/ControllerTakeover/ControllerTakeover.ino`
