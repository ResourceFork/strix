# Glossary

> Every acronym and concept used in the Strix hardware docs, in plain language.
> Skim the category you need, or use the index.

[← Hardware guide](hardware-wiring.md) · [Parts & shopping](parts-and-shopping.md) · [Serial protocol](serial-protocol.md)

---

## Index

**A–D:** [Arming](#arming) · [Baud rate](#baud-rate) · [BEC](#bec) · [Binding](#binding-pairing) · [Calibration constants](#calibration-constants) · [CH340](#ch340) · [Common ground](#common-ground) · [Cross-talk](#cross-talk) · [DAC](#dac) · [Depth band](#depth-band) · [Digipot](#digipot) · [Duty cycle](#duty-cycle)

**E–L:** [ESC](#esc) · [Failsafe](#failsafe) · [FHSS](#fhss) · [GPIO](#gpio) · [HIGH / LOW terminals](#high--low-terminals) · [I²C](#i²c) · [Level shifter](#level-shifter) · [LiPo / NiMH](#lipo--nimh)

**M–R:** [Median filter](#median-filter) · [OTG](#otg) · [Open-drain](#open-drain) · [Potentiometer](#potentiometer-pot) · [PWM](#pwm) · [Rail](#rail-supply-rail) · [Ratiometric](#ratiometric) · [RC filter](#rc-low-pass-filter)

**S–Z:** [Sealed receiver+ESC combo](#sealed-receiveresc-combo) · [Servo pulse](#servo-pulse) · [SPI](#spi) · [Taps](#taps-wiper-codes) · [Thrust-inverse cooldown](#thrust-inverse-cooldown) · [Timer1](#timer1) · [ToF](#tof) · [Track](#track) · [Travel window](#travel-window) · [Ultrasonic](#ultrasonic) · [Voltage divider](#voltage-divider) · [Wiper](#wiper)

---

## Power and electrical

### BEC
**Battery Eliminator Circuit.** A small voltage regulator built into most ESCs
that outputs 5–6V on its red servo wire. Its job is to power the steering servo
and receiver from the main battery, so you don't need a second battery.

*Where it matters:* [Path A wiring](esc-wiring.md#the-connectors-explained) — use it to power the servo, **never** the Nano.

### Common ground
A single shared 0V reference that every part of the system connects to. Voltages
only mean something relative to a reference; if two devices don't share ground,
one device's "3.2V signal" is an unknown quantity to the other.

*Where it matters:* Everywhere. It's the [first rule](hardware-wiring.md#rules-that-apply-to-every-build) and the first thing to check when nothing works.

### LiPo / NiMH
The two common RC battery chemistries. **LiPo** (lithium polymer, e.g. "2S" =
7.4V) is lighter and higher-power but intolerant of abuse. **NiMH** (nickel-metal
hydride) is heavier, cheaper, and more forgiving.

*Where it matters:* [Parts list](parts-and-shopping.md#core-parts-every-build). Handle with care — see the battery warning in the [hardware guide](hardware-wiring.md#rules-that-apply-to-every-build).

### Rail (supply rail)
The steady voltage a circuit runs on — "the 5V rail," "the controller's rail."
A handheld RC controller running 3×AA has a ~4.5V rail.

*Where it matters:* [Path B, Variant B](controller-takeover.md#why-the-rail-sense-wire-matters) senses the controller's rail so its output tracks the batteries as they drain.

### Ratiometric
A measurement expressed as a *fraction of the supply rail* rather than an
absolute voltage. If a chip reads ratiometrically, then when its rail sags from
4.5V to 4.0V, a "half rail" input sags along with it and the chip's
interpretation doesn't change.

*Where it matters:* This is why a mechanical pot is immune to battery drain, and why [Variant B](controller-takeover.md#why-the-rail-sense-wire-matters) has to sense the rail to imitate one properly.

### Voltage divider
Two resistances in series with the output taken from between them. The output
voltage is the input scaled by the ratio of the two resistances. A potentiometer
is an adjustable voltage divider.

*Where it matters:* [How a pot works](controller-takeover.md#how-a-digital-pot-replaces-a-mechanical-pot) — the insight that the controller reads a *voltage*, not a resistance, is what makes Variant B possible.

---

## Potentiometers

### Potentiometer (pot)
A variable resistor with three terminals: a fixed resistive track between two
ends, plus a movable contact that slides along it. Turning the knob (or pulling
the trigger) moves the contact. RC controllers use one for the throttle trigger
and one for the steering wheel.

*Where it matters:* [Path B](controller-takeover.md) replaces both of them.

### Wiper
The movable middle terminal of a pot — the one whose voltage changes as the
control moves. **The wiper is the signal the controller's chip actually reads.**

*Where it matters:* [Identifying the pots](pot-identification.md) — getting the wiper right is the one identification you can't recover from in software.

### HIGH / LOW terminals
The two fixed ends of a pot's track. HIGH connects to the supply rail, LOW to
ground. Swapping them mirrors the control's direction, which calibration can
absorb.

*Where it matters:* [Identifying the pots](pot-identification.md#step-3-find-high-and-low-powered-test).

### Track
The resistive element between HIGH and LOW. Its end-to-end value is the pot's
nominal rating — a "5k pot" has a ~5 kΩ track.

*Where it matters:* [Field data](pot-identification.md#field-data-from-this-build) — this build's pots measured ~5.2 kΩ.

### Travel window
The portion of a pot's electrical track that the physical control actually
sweeps. Mechanical stops usually keep the wiper away from both extremes, so a
trigger might only use half the track.

*Where it matters:* This build's trigger uses ~46% of its track, forward-biased — which is why [calibration](controller-takeover-calibration.md) can't assume the midpoint is neutral.

### Digipot
**Digital potentiometer.** A chip that behaves like a pot on three analog pins
but is positioned by a microcontroller instead of a knob. Examples: MCP41010,
X9C103S.

*Where it matters:* [Path B, Variant A](controller-takeover.md#variant-a-digipot-chips).

### Taps (wiper codes)
The number of discrete positions a digipot's wiper can occupy — 256 for the
MCP41010, 100 for the X9C103S. More taps means finer control.

*Where it matters:* [Choosing a variant](controller-takeover.md#pick-a-variant-digipots-or-pwm) — a software speed cap divides your usable steps, so resolution matters more than it first appears.

---

## Signals and interfaces

### PWM
**Pulse-Width Modulation.** A square wave that switches between full-on and
full-off very fast. The fraction of time spent on is the *duty cycle*; averaged
over time, it behaves like an analog voltage between 0 and the rail.

*Where it matters:* [Variant B](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots) uses PWM plus a filter to synthesize wiper voltages.

### Duty cycle
The fraction of a PWM period spent high, usually as a percentage or a raw
count. 25% duty of a 5V signal averages 1.25V.

*Where it matters:* [Variant B calibration](controller-takeover.md#calibration-units) constants are duty values expressed as rail fractions.

### RC low-pass filter
A resistor and capacitor that smooth a fast-switching signal into its average
value — the simplest possible [DAC](#dac). The resistor limits current into the
capacitor; the capacitor holds the average.

*Where it matters:* [Variant B wiring](controller-takeover.md#wiring) — one per channel, turning PWM into a steady wiper voltage.

### DAC
**Digital-to-Analog Converter.** Anything that turns a number into a voltage.
A Nano has no built-in DAC, which is why PWM plus a filter stands in for one.

*Where it matters:* [Variant B](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots).

### Servo pulse
The classic RC signaling scheme: a pulse repeated ~50 times a second, where the
*width* encodes the command — 1000 µs = full one way, 1500 µs = neutral,
2000 µs = full the other way. ESCs and servos both speak it.

*Where it matters:* [Path A](esc-wiring.md) — the Nano's `Servo` library emits these.

### GPIO
**General-Purpose Input/Output.** A microcontroller pin that can be driven high
or low, or read. Important limitation: a GPIO is a *switch*, not a resistor — it
cannot present a resistance value, which is why you can't wire pot wires
straight to a Nano and expect it to act like a pot.

*Where it matters:* [Why Variant B works the way it does](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots).

### Timer1
One of the Nano's three hardware timers, and the only 16-bit one. It drives pins
D9 and D10 and can produce PWM at up to 16-bit resolution.

*Where it matters:* [Variant B](controller-takeover.md#wiring) reconfigures it for 10-bit duty at 15.6 kHz — which is why those two pins aren't interchangeable with others.

### SPI
**Serial Peripheral Interface.** A four-wire bus (clock, data-out, data-in,
chip-select) for talking to chips. Multiple devices share clock and data; each
gets its own chip-select line.

*Where it matters:* [Variant A](controller-takeover.md#variant-a-nano-pin-usage-spi) drives both digipots over SPI.

### I²C
A two-wire bus (SDA data, SCL clock) where each device has an address. Slower
than SPI but uses fewer pins.

*Where it matters:* [The ToF sensor](sensor-wiring.md#pin-by-pin-tables) is I²C on A4/A5.

### Open-drain
A signaling style where devices can only pull a line low, and a pull-up resistor
raises it high. Lets devices of different voltages share a bus more safely — the
reason a 3.3V I²C device often survives a 5V bus.

*Where it matters:* [Level shifting the ToF sensor](sensor-wiring.md#pin-by-pin-tables) — "often survives" isn't "is in spec."

### Level shifter
A small board that translates signals between voltage domains (e.g. 5V Nano ↔
3.3V sensor) in both directions.

*Where it matters:* [Sensor wiring](sensor-wiring.md#pin-by-pin-tables) — recommended for the 3.3V-only ToF module.

---

## Radio and RC

### ESC
**Electronic Speed Controller.** The car's motor driver. It takes battery power
and a small control signal, and drives the motor forward or reverse at the
commanded speed. The Nano never drives a motor directly — it tells the ESC what
to do.

*Where it matters:* Which kind you have decides [your entire build path](hardware-wiring.md#step-1-pick-your-drive-path).

### Sealed receiver+ESC combo
A single potted brick containing both the radio receiver and the ESC, with no
signal input — its only control channel is its 2.4 GHz radio link. Common on
waterproof cars.

*Where it matters:* It's the whole reason [Path B](controller-takeover.md) exists.

### Binding (pairing)
The power-up handshake that associates one transmitter with one receiver. A
controller that isn't bound to your car won't drive it, even if it's the right
model.

*Where it matters:* [Path B build steps](controller-takeover.md#step-by-step-build) — confirm manual driving works *before* modifying anything.

### FHSS
**Frequency-Hopping Spread Spectrum.** A radio scheme that rapidly changes
channels to resist interference. Hobby RC links use proprietary variants with no
public spec.

*Where it matters:* [Why we don't emulate the radio](controller-takeover.md#why-not-just-emulate-the-radio).

---

## Sensing

### ToF
**Time-of-Flight.** A sensor that measures distance by timing how long a light
pulse takes to bounce back. Narrow beam, millimeter accuracy. This build uses a
VL53L4CD (0–1200 mm).

*Where it matters:* [Sensor array](sensor-wiring.md) — the precision center beam.

### Ultrasonic
A sensor that measures distance by timing a sound-pulse echo. Wide cone, cheap,
less precise than ToF, and easily confused by soft or angled surfaces. This build
uses two HC-SR04s.

*Where it matters:* [Sensor array](sensor-wiring.md) — the corner sensors covering the flanks.

### Cross-talk
When one ultrasonic sensor hears another's echo and reports a bogus distance.

*Where it matters:* [Sensor behavior](sensor-wiring.md#how-it-behaves) — the firmware fires sensors round-robin, never simultaneously, which prevents it.

### Depth band
The camera-derived left/center/right depth estimate the vision model produces —
*relative* depth, as opposed to the sensors' absolute millimeters.

*Where it matters:* [Sensor array](sensor-wiring.md) — the three sensors deliberately mirror the three depth bands so both views speak the same vocabulary.

### Median filter
Taking the middle value of several samples instead of the latest one, which
discards single-sample spikes and dropouts.

*Where it matters:* The app median-filters ultrasonic readings — see [sensor behavior](sensor-wiring.md#how-it-behaves).

---

## Software and protocol

### Arming
A safety gate: the firmware ignores all throttle commands until it receives
`A:1`. Disarming (`A:0`) forces every output to neutral.

*Where it matters:* [Serial protocol](serial-protocol.md#arming).

### Failsafe
The firmware's dead-man switch: if no command arrives for 500 ms, it disarms and
parks all outputs at neutral.

*Where it matters:* [Serial protocol](serial-protocol.md#the-500-ms-failsafe).

### Thrust-inverse cooldown
An app-side safety rule: after thrust in one direction, the opposite direction
is blocked for one second so the drivetrain isn't slammed from forward into
reverse at speed. The UI disables the locked-out half of the joystick.

*Where it matters:* [App-side behavior](serial-protocol.md#app-side-behavior).

### Calibration constants
The per-car numbers that map the app's −100…100 range onto the physical
extremes of your hardware — `THR_NEUTRAL`, `THR_MAX`, `STR_LEFT`, and friends.
Every build measures its own.

*Where it matters:* [Calibration worksheet](controller-takeover-calibration.md).

### Baud rate
Serial link speed in bits per second. Everything here runs at **115200**.

*Where it matters:* [Serial protocol](serial-protocol.md#transport).

### CH340
The USB-to-serial chip on most Nano clones. It's what the phone actually talks
to over the USB cable.

*Where it matters:* [Serial protocol](serial-protocol.md#transport).

### OTG
**On-The-Go.** The USB mode that lets a phone act as a host so it can drive a
peripheral (here, the Nano) instead of behaving like one.

*Where it matters:* [Parts list](parts-and-shopping.md#core-parts-every-build) — you need an OTG-capable cable or adapter.

---

**Back to:** [Hardware guide](hardware-wiring.md)
