# Controller Takeover (Sealed ESC Workaround)

How to make the car drive itself when the ESC is a **sealed, resin-potted
receiver + ESC combo** — one you can't wire a signal into because its only
control input is a 2.4GHz radio link, and you can't open it to tap anything.

This is the situation on Hosim (and similar) waterproof cars: the motor speed
controller and the radio receiver are one waterproof brick, the steering servo
plugs into that brick, and the only way in is over the air from the car's own
handheld controller.

> **New to this? Start with the [hardware wiring overview](hardware-wiring.md)**
> for the parts and concepts. The "normal" case — an ESC with a signal wire you
> connect straight to the Nano — is covered in [`esc-wiring.md`](esc-wiring.md).
> This doc is the workaround for when your ESC *doesn't have* a signal wire.

## Why not just emulate the radio?

Tempting idea: put a small radio on the car that impersonates the handheld
controller. In practice this is the hardest possible path. Teardowns of Hosim
gear show the link is a **proprietary, frequency-hopping FSK protocol** across
several 2.4GHz channels, with a pairing/sync handshake, running on an obscure
undocumented RF chip. There's no public spec and no off-the-shelf module that
speaks it. Reverse-engineering it would be an SDR-and-logic-analyzer project with
a real chance of never working.

So we don't touch the radio. Instead:

## The approach: automate a second controller

The handheld controller already speaks the exact radio language the ESC trusts,
and it's already paired. Its trigger and steering wheel are **potentiometers** —
variable resistors the controller's chip reads as "how far is the trigger
pulled" and "how far is the wheel turned."

So we let the **Arduino Nano replace the human hand**: it electronically moves
those two pots via **digital potentiometers**, and the controller transmits the
result over its normal radio link. The sealed ESC never knows the difference.

Because we're sacrificing a controller to this (its pots get replaced), **buy a
second controller** and keep your original intact for manual driving. See
[Buying a matching controller](#buying-a-matching-controller) below.

### What stays the same

Nothing changes on the phone or in the app. The Nano still speaks the exact same
serial protocol (`A:1`, `T1:<v>`, `T2:<v>`, `?`) documented in
`MotorController.kt`. Only the Nano *firmware* changes: where it used to write a
servo pulse to a pin, it now sets a digital-pot position. The 500 ms failsafe
still works — it just parks both pots at neutral (trigger released, wheel
centered) instead of neutralizing a servo output.

## The big picture

```mermaid
flowchart LR
    subgraph phone["Android phone"]
        app["Strix app"]
    end

    subgraph nano["Arduino Nano"]
        usb["USB in"]
        spi["SPI bus<br/>SCK + MOSI + 2x CS"]
        gnd["GND (common)"]
    end

    subgraph ctrl["2nd Hosim controller (sacrificial, carried on/near car)"]
        dpT["Digipot 1<br/>replaces trigger pot"]
        dpS["Digipot 2<br/>replaces wheel pot"]
        mcu["Controller chip"]
        rf["2.4GHz radio + antenna"]
    end

    subgraph sealed["Sealed RX/ESC brick (untouched)"]
        rx["Receiver + ESC"]
        motor["Drive motor"]
        servo["Steering servo"]
    end

    app -->|"USB serial 115200"| usb
    usb -. powers .-> nano
    spi -->|"set position"| dpT
    spi -->|"set position"| dpS
    dpT -->|"wiper voltage"| mcu
    dpS -->|"wiper voltage"| mcu
    mcu --> rf
    rf -. "2.4GHz radio (stock)" .-> rx
    rx --> motor
    rx --> servo
    gnd --- ctrl
```

The chain is: **phone → Nano → digipots → controller chip → stock radio →
sealed ESC → motor + steering servo.** Two separate battery worlds again — the
phone powers the Nano, the controller runs on its own AA cells — meeting only at
a shared ground.

## How a digital pot replaces a mechanical pot

A mechanical potentiometer has three terminals:

```
  [HIGH] ---/\/\/\/\--- [LOW]
                |
             [WIPER]  <- voltage here depends on knob position; the chip reads this
```

- **HIGH** and **LOW** are the two ends of the resistive track (one goes to the
  controller's small supply rail, the other to ground).
- **WIPER** is the moving tap. Its voltage slides between HIGH and LOW as you
  move the trigger/wheel. The controller chip reads this voltage.

A **digital potentiometer** (e.g., MCP41010) has the same three analog terminals
(often labelled PA / PW / PB) plus a few digital pins the Nano drives over SPI.
You wire it in place of the mechanical pot — HIGH→PA, WIPER→PW, LOW→PB — and the
Nano sets the wiper position (0–255) in software. Electrically it's identical to
someone turning the knob.

The big advantage: the digipot divides the controller's **own** supply rail, so
the wiper voltage tracks exactly what the controller expects — you don't have to
know or match its precise voltage.

## Bill of materials

| Qty | Part | Notes |
| --- | --- | --- |
| 1 | **Second Hosim controller** matching your car | Sacrificial. Keep the original for manual driving. See below. |
| 2 | **Digital potentiometer**, SPI, matched to the controller's pot value | e.g., MCP41010 (10k). Match the resistance to the controller's pots and pick a part rated **above** the controller's supply voltage. |
| 1 | Arduino Nano | You already have this. |
| — | Hookup wire, header, solder | To tap into the controller board and wire SPI. |
| 1 (optional) | Analog mux (74HC4053) per channel | Only if you want to keep manual control *on the sacrificed controller* too — usually unnecessary since the original stays intact. |

> ⚠️ **Digipot voltage rating.** Common digipots (MCP41xxx) top out around 5.5V.
> If the controller runs on 4×AA (6V) that's over spec — either pick a
> higher-voltage digipot or run that controller from a regulated 4.5–5V source.
> Measure the controller's rail before buying (step 3 under "Before you build").

> **Pot value nuance:** the controller chip reads the wiper as a voltage into
> a high-impedance input, so what it sees is the *division ratio*, not the
> absolute resistance. A 10k digipot on a ~5k pot's pads produces the same
> wiper voltages and normally works fine — stay within roughly 2× of the
> measured track value. (The Hosim trigger pot measured ~5k; see the field
> note under [Wired pots](#wired-pots-red--black--white-harness).) For an
> exact 5k match, the MCP4151-502 exists, but it speaks a different SPI
> command format than the MCP41xxx `0x11` write in the sketch, so `setWiper()`
> needs a small tweak if you choose it.

## Nano pin usage

SPI on the Nano (Uno/Nano pinout):

| Signal | Nano pin | Goes to |
| --- | --- | --- |
| SCK (clock) | **D13** | Both digipots' SCK |
| MOSI (data) | **D11** | Both digipots' SI/data |
| CS — throttle | **D7** | Digipot 1 chip-select |
| CS — steering | **D8** | Digipot 2 chip-select |
| GND | **GND** | Common ground with the controller |

Two digipots share SCK and MOSI; each gets its own chip-select (CS) so the Nano
can address them independently. (These pins are a starting suggestion — any two
free digital pins work for the CS lines.)

> **Common ground is mandatory.** The Nano's GND must connect to the controller's
> ground. Without it, the wiper voltages the digipots produce are meaningless to
> the controller chip.

## Digipot pin-by-pin (MCP41010)

The part that trips people up: **the controller's pots never wire to the Nano
at all.** The mechanical pots come *out*, the digipots take their place inside
the controller, and each digipot straddles the two worlds:

- its **digital side** (CS, SCK, SI) is driven by the Nano over SPI — these
  plus ground are the only five wires between Nano and controller;
- its **analog side** (PA0 / PW0 / PB0) lands on the exact pads the mechanical
  pot vacated, powered by the controller's own battery rail — the Nano's 5V
  never enters the controller.

For the MCP41010 in the 8-pin DIP package, both chips wire identically except
for CS:

| Digipot pin | Name | Wire to | Notes |
| --- | --- | --- | --- |
| 1 | CS | Nano **D7** (trigger chip) / **D8** (wheel chip) | The only pin that differs between the two chips |
| 2 | SCK | Nano **D13** | Shared by both chips |
| 3 | SI | Nano **D11** | Shared by both chips |
| 4 | VSS | Controller **ground** | Same node as the pot's LOW pad; ties into the common ground with the Nano |
| 5 | PB0 | Controller pot **LOW** pad | Where the old pot's ground-side terminal sat |
| 6 | PW0 | Controller pot **WIPER** pad | The pad the controller chip reads |
| 7 | PA0 | Controller pot **HIGH** pad | The pad at the controller's supply rail |
| 8 | VDD | Controller **supply rail** (~4.5V from 3×AA) | Never the Nano's 5V; must stay within the digipot's rating (see the BOM warning) |

Working order per control: identify the pot's three pads with a multimeter
*while the pot is still in place* (HIGH sits at the rail voltage, LOW at 0V,
WIPER sweeps as you move the trigger/wheel — step 2 of
[Before you build](#before-you-build--open-the-controller-and-confirm)), label
the pads, desolder the pot, then land PA0/PW0/PB0 on those same three pads.
Because the digipot divides the controller's own rail, the wiper voltage
automatically matches what the controller chip expects.

### Wired pots (red / black / white harness)

Some controllers — Hosim's included — don't board-mount the pots: each pot
hangs off a **3-wire harness**. That's the easy case: nothing to desolder from
the main board. Cut (or unplug) the three wires at the pot and land the
harness side on the digipot instead. The servo-style color convention
*suggests* this mapping:

| Wire color | Pot terminal | Digipot pin |
| --- | --- | --- |
| Red | HIGH — controller supply rail | **PA0** (pin 7) |
| Black | LOW — ground | **PB0** (pin 5) |
| White | WIPER — the signal the controller chip reads | **PW0** (pin 6) |

> **Field note (this build's Hosim controller):** the convention did **not**
> hold. **Black was the wiper** — it sat on the pot's center terminal. On the
> wheel (steering) pot, measured isolated: black↔white swept ~52Ω (full
> right) → 4.67kΩ (full left), resting ~2.7k at the spring-return center;
> black↔red swept the mirror image, 5.2kΩ (right) → ~570Ω (left). The two
> wiper readings summed to a constant ~5.2k at every position — the
> healthy-pot proof, and that constant *is* the end-to-end track value
> (5k nominal). The wiper never reaches the red end (~570Ω floor): the
> wheel's mechanical travel uses only part of the electrical track. That's
> normal — but it means the digipot, which has no mechanical stops, can
> command positions the physical control never could. Calibrate
> `STR_LEFT`/`STR_RIGHT` (and `THR_MIN`/`THR_MAX`) to the car's actual
> limits, not the digipot's 0/255 extremes. Resistance can't tell HIGH from
> LOW — the powered DC-volts check settles that.
>
> The trigger (throttle) pot repeated the topology on the same ~5k track,
> with two twists. Its sweep direction is opposite the wheel's (full forward
> drives the wiper toward the *red* end, ~840Ω), and it uses a much narrower
> window — black↔white rested at 2.77k and swept only 1.99k (full reverse) →
> 4.4k (full forward), about 46% of the electrical track. The window is also
> lopsided: forward gets roughly twice the electrical span of reverse, the
> classic RC throttle layout. That's why `THR_NEUTRAL` lands nowhere near the
> digipot's 128 midpoint and is calibrated first.
>
> **Bonus discovery: these pots unplug from the main board** — each pigtail
> ends in a small connector. That improves the build twice over:
>
> 1. **Measure the pot isolated** (unplugged) so nothing else on the board can
>    skew the Ω readings. Probing female connector sockets is fiddly — push a
>    snug jumper pin into each socket and clip the probes to those. Healthy
>    pot: wiper↔each-end sweeps, end↔end sits steady at the track value, and
>    at any trigger position the two wiper readings sum to the track value.
> 2. **Skip the wire-cutting entirely.** Cut the pigtail at the *pot body*,
>    keep the connector, and solder the digipot to the connector side per the
>    identified roles — the digipot becomes a plug-in replacement and the
>    controller board is never touched.
>
> With the pot unplugged, identify the board-side pins directly: controller
> on (car **off**, so garbage radio commands can't move it), meter on DC V,
> black probe on battery negative. The socket pin at the rail is HIGH → PA0,
> the 0V pin is LOW → PB0, and the leftover pin — floating, so it may read
> drifting nonsense — is the wiper input → PW0. Record pin *positions*: the
> connector enforces the mapping from here on.

**Verify with a meter before cutting** — colors are a habit, not a spec.
Controller on, pot still connected, black probe on battery negative, probe
each wire at the pot's terminals: steady rail voltage = HIGH, steady 0V = LOW,
sweeps as you move the control = WIPER. Getting the **wiper** right is the one
that matters — a HIGH/LOW swap only mirrors the calibration constants (the
calibration procedure absorbs that), but a misidentified wiper feeds the
controller chip garbage. Cross-check: the wiper is usually the pot's
physically middle terminal.

Keep the removed pots intact and labeled — restoring the controller to stock
is then just a resolder.

## Before you build — open the controller and confirm

1. **Confirm the trigger and wheel are potentiometers** (3 terminals, wiper
   voltage sweeps smoothly), not on/off switches. If they're switches, this
   approach only gives full-on/full-off; consider swapping the ESC instead
   (see `esc-wiring.md`).
2. **Identify each pot's three terminals** with a multimeter: HIGH (sits at the
   rail voltage), LOW (0V/ground), WIPER (sweeps between the two as you move the
   control).
3. **Measure the controller's supply rail** (usually 3×AA = 4.5V). This sets the
   digipot's required voltage rating.
4. **Note the pot resistance** (measure end-to-end: HIGH to LOW) so you can match
   the digipot value (10k is typical).

## Firmware changes (Nano)

A ready-to-flash variant lives at
**`arduino/ControllerTakeover/ControllerTakeover.ino`** —
open it, fill in the calibration constants (next section), and upload. The
command parsing (`A:`, `T1:`, `T2:`, `?`) is byte-for-byte identical to
`arduino/EscServoController/EscServoController.ino`; only the *output*
changes: instead of `Servo.writeMicroseconds()`, it sets a digipot wiper. The
relevant pieces:

```cpp
#include <SPI.h>

const int CS_THROTTLE = 7;   // digipot 1 (replaces the trigger pot)
const int CS_STEERING = 8;   // digipot 2 (replaces the wheel pot)

// ---- CALIBRATION (measure these on your controller — see below) ----
// Wiper values (0-255) that make the controller output each extreme.
const int THR_MIN = 20,  THR_NEUTRAL = 128, THR_MAX = 235;  // reverse / stop / forward
const int STR_LEFT = 20, STR_CENTER  = 128, STR_RIGHT = 235; // left / center / right

void setWiper(int csPin, int value) {
  value = constrain(value, 0, 255);
  digitalWrite(csPin, LOW);
  SPI.transfer(0x11);        // MCP41xxx command: write data to pot 0
  SPI.transfer(value);
  digitalWrite(csPin, HIGH);
}

// Map app throttle (-100..100) onto the calibrated wiper range, split at neutral.
int throttleToWiper(int v) {
  return (v >= 0) ? map(v, 0, 100, THR_NEUTRAL, THR_MAX)
                  : map(v, -100, 0, THR_MIN, THR_NEUTRAL);
}
int steeringToWiper(int v) {
  return (v >= 0) ? map(v, 0, 100, STR_CENTER, STR_RIGHT)
                  : map(v, -100, 0, STR_LEFT, STR_CENTER);
}

void setNeutral() {
  setWiper(CS_THROTTLE, THR_NEUTRAL);
  setWiper(CS_STEERING, STR_CENTER);
}

void setup() {
  Serial.begin(115200);
  pinMode(CS_THROTTLE, OUTPUT); digitalWrite(CS_THROTTLE, HIGH);
  pinMode(CS_STEERING, OUTPUT); digitalWrite(CS_STEERING, HIGH);
  SPI.begin();
  setNeutral();
}
```

Then in the command handler, replace the servo writes:

```cpp
// T1 = throttle (drive), T2 = steering — same protocol as before.
if (channel == 1) setWiper(CS_THROTTLE, throttleToWiper(val));
if (channel == 2) setWiper(CS_STEERING, steeringToWiper(val));
```

- **Arm/disarm** and the **500 ms failsafe** call `setNeutral()` instead of
  writing neutral servo pulses — same safety behavior, new output layer.
- Channel 3 is unused in this setup.

## Calibration procedure

> **Fill-in worksheet:** [`controller-takeover-calibration.md`](controller-takeover-calibration.md)
> walks these steps with blanks for every measured value and a checkbox per step.

Because the digipot range rarely maps 1:1 to the controller's full stick travel,
calibrate once with the **wheels off the ground** and the car powered/paired:

1. Load firmware with rough values (start with the constants above).
2. Send `A:1` then `T1:0` — the car should sit still. If it creeps, adjust
   `THR_NEUTRAL` up/down until it's dead stopped. This is the most important one.
3. Send `T1:100` and `T1:-100`; adjust `THR_MAX` / `THR_MIN` so full throttle
   matches the fastest you want (you don't have to use the pot's full range).
4. Repeat for steering: `T2:0` centers the wheels (tune `STR_CENTER`), then
   `T2:-100` / `T2:100` for full left/right lock (`STR_LEFT` / `STR_RIGHT`).
5. Re-flash with the tuned constants.

## Step-by-step build

Do all first tests with the **car's wheels off the ground**.

1. **Pair the controller to the car** using its normal bind procedure, and make
   sure it drives the car manually first. Don't modify anything until that works.
2. **Power off**, open the controller, and locate the trigger and wheel pots.
3. **Desolder/replace each pot with a digipot**: controller HIGH→PA, WIPER→PW,
   LOW→PB. Power the digipot's VDD/VSS from the controller's rail and ground
   (keeping it within the digipot's voltage rating).
4. **Wire SPI** from the Nano: SCK→D13, MOSI→D11, and each digipot's CS to D7/D8.
5. **Tie grounds together** — Nano GND to the controller ground.
6. **Load the new firmware** and run the calibration procedure above.
7. **Mount the controller board on/near the car.** Range is irrelevant at inches;
   just keep the antenna clear of metal and the motor.

## First drive test

With the wheels still off the ground and the car paired:

```
A:1        arm
T1:0       throttle neutral (car should not move)
T2:0       steering centered
T2:-100    wheels full left
T2:100     wheels full right
T2:0       recenter
T1:30      30% forward
T1:-30     30% reverse
A:0        disarm (parks both pots at neutral)
```

Confirm the steering sweeps and the wheels spin the right way before putting the
car on the ground.

## Troubleshooting

| Symptom | Likely cause / fix |
| --- | --- |
| Controller no longer drives the car at all | Not paired, or a digipot terminal is miswired. Verify manual behavior before/after each change; re-run the bind procedure. |
| Car creeps at `T1:0` | `THR_NEUTRAL` is off — nudge it until the car is dead stopped. |
| Throttle/steering reversed | Swap `..._MIN`/`..._MAX` (or `LEFT`/`RIGHT`) in the calibration constants. |
| Only full-on or full-off, no in-between | The controls may be switches, not pots — this approach can't add proportionality; swap the ESC instead. |
| Erratic/jumpy values | Missing common ground between Nano and controller, or digipot powered above its voltage rating. |
| Digipot runs hot / dies | Over-voltage — controller rail exceeds the digipot's max (see the BOM warning). |
| Car keeps moving after app disconnects | Failsafe not wired to `setNeutral()` — verify the 500 ms timeout path. |

## Buying a matching controller

Hosim controllers are **model-specific and must bind to your car's receiver**, so
match the controller to your car's model number (printed on the car, the box, or
the receiver label). The two official Hosim replacement transmitters:

- **[Hosim F12025 transmitter](https://www.amazon.com/Hosim-Transmitter-Assembly-Parts-F12025/dp/B09ZHT5TLF)**
  — **the match for this build.** Listed for **M13, M23, X27, X25, X17, X16, X08,
  X07, X15, X07W, and X15W**, and confirmed visually identical to the X15's stock
  controller. This is the one to buy for a Hosim X15.
- **[Hosim 25-ZJ08 transmitter](https://www.amazon.com/Transmitter-Assembly-Accessory-25-ZJ08-Hosim/dp/B07BWCRTS2)**
  — the older **9125, 9155, 9156, Q903** family. Listed only for reference; **not**
  for the X15.

> **Match the controller to your car's model.** The X15 uses the F12025 above.
> For a different Hosim, buy the transmitter whose listing names your model —
> the wrong family won't bind to your receiver.

**Pairing note:** these systems bind one controller to the receiver via a
power-up sequence. Keep your **original controller for manual driving** and the
**second (modified) one for autonomous mode** — to switch, re-run the bind
procedure with whichever controller you want active. Confirm your specific
model's bind steps in its manual.

## Reference

- Hardware overview and doc map: [`hardware-wiring.md`](hardware-wiring.md)
- The "normal" wired-ESC guide: [`esc-wiring.md`](esc-wiring.md)
- Calibration worksheet: [`controller-takeover-calibration.md`](controller-takeover-calibration.md)
- Ready-to-flash firmware: `arduino/ControllerTakeover/ControllerTakeover.ino`
- Original servo/ESC firmware: `arduino/EscServoController/EscServoController.ino`
- App-side serial protocol (unchanged): `app/.../MotorController.kt`
- Action → throttle/steering mapping: `app/.../DriveCommand.kt`
