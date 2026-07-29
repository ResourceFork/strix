# Parts & Shopping

> What to buy, what you probably already have, and what to skip. Organized by
> build path so you only shop for your own build.

[← Hardware guide](hardware-wiring.md) · [Glossary](glossary.md)

---

## Before you order

1. **Know your path.** [Pick your drive path](hardware-wiring.md#step-1-pick-your-drive-path) first — Path A and Path B need different parts.
2. **Check your drawer.** Resistors, capacitors, and jumper wires are the sort of thing that's already in an Arduino starter kit, an old project, or salvageable from dead electronics. The [substitutions](#substitutions-and-salvage) section says what's interchangeable.
3. **Prices below are snapshots**, not quotes — they drift, and marketplace listings churn. Where a link points at a specific product, it was verified when written; where it points at a search, pick whatever current listing looks reasonable.

---

## Core parts (every build)

| Part | Need | Approx. | Link | Notes |
| --- | --- | --- | --- | --- |
| RC car with an ESC | Required | — | — | The thing you're automating. Its ESC type decides your [path](hardware-wiring.md#step-1-pick-your-drive-path). |
| Arduino Nano (CH340 clone is fine) | Required | $5–10 | [search](https://www.amazon.com/s?k=arduino+nano+ch340) | The car's hands. Clones work; they're what the [CH340](glossary.md#ch340) note is about. |
| Android phone | Required | — | — | Runs the app, camera, and vision model. |
| USB OTG cable/adapter (phone → Nano) | Required | $5–10 | [search](https://www.amazon.com/s?k=usb+otg+cable+usb+c+to+usb+b+mini) | Must be [OTG](glossary.md#otg)-capable, not charge-only. Match your phone's port and the Nano's (usually mini- or micro-USB). |
| Jumper wires | Required | $6–8 | [search](https://www.amazon.com/s?k=dupont+jumper+wires+male+female) | Get a male/female/both assortment. |
| Mini breadboard or ground rail | Recommended | $6–10 | [search](https://www.amazon.com/s?k=mini+breadboard+400+tie+point) | Makes the [common ground](glossary.md#common-ground) tidy instead of a rat's nest. |
| RC battery | Required | — | — | Usually already with the car. See the [battery warning](hardware-wiring.md#rules-that-apply-to-every-build). |

---

## Path A: direct-wired ESC

If your ESC has a signal wire, you need **nothing extra**. The Nano's signal
pins connect straight to the ESC and steering servo with jumper wires.

→ [Path A wiring guide](esc-wiring.md)

---

## Path B: controller takeover

You'll sacrifice a second handheld controller, then drive its two pots
electronically. Pick **one** variant for the drive electronics.

### The controller (required for Path B)

Controllers are **model-specific and must [bind](glossary.md#binding-pairing) to
your car's receiver.** Match it to your car's model number, printed on the car,
the box, or the receiver label.

| Part | For | Approx. | Link |
| --- | --- | --- | --- |
| Hosim **F12025** transmitter | M13, M23, X27, X25, X17, X16, X08, X07, **X15**, X07W, X15W — **this build's match** | ~$25 | [Amazon](https://www.amazon.com/Hosim-Transmitter-Assembly-Parts-F12025/dp/B09ZHT5TLF) |
| Hosim **25-ZJ08** transmitter | The older 9125, 9155, 9156, Q903 family — **not** the X15 | ~$20 | [Amazon](https://www.amazon.com/Transmitter-Assembly-Accessory-25-ZJ08-Hosim/dp/B07BWCRTS2) |

> 💡 **Buy a second controller, keep your original intact.** The modified one
> drives autonomously; the original stays available for manual driving. To
> switch, re-run your car's bind procedure with whichever controller you want
> active.

### Variant B: PWM wiper synthesis — recommended

No special chips. Two resistors and two capacitors, and the Nano synthesizes the
wiper voltages directly. **Best control resolution of any option** and the
cheapest. [How it works](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots).

| Part | Need | Approx. | Link | Notes |
| --- | --- | --- | --- | --- |
| Ceramic capacitors, 1–2.2 µF (need 2) | Required | $10 | [BOJACK 300 pc, 0.1–10 µF](https://www.amazon.com/BOJACK-Capacitor-Multilayer-Monolithic-Assortment/dp/B09YV2659V) | **This build ordered this kit.** Its ten values are exactly the useful range. Bigger alternative: [BOJACK 650 pc](https://www.amazon.com/BOJACK-Ceramic-Capacitor-Assortment-Capacitors/dp/B07P7HRGT9) (~$15). |
| Resistors, 2.2–4.7 kΩ (need 2) | Required | $0–11 | [MUYI 525 pc kit](https://www.amazon.com/dp/B087Q8QCFM) · [SourceTon 525 pc](https://www.amazon.com/Resistor-SourceTon-Values-Resistors-Assortment/dp/B09LYQ3FN7) | **Probably free:** the pots you're removing work as these resistors. See [substitutions](#substitutions-and-salvage). |

> ⚠️ **Don't buy a capacitor kit that stops at 100 nF.** Many popular assortments
> top out there — 100× too small for this filter. You need a kit whose range
> reaches into **µF**, like the ones linked above.

### Variant A: digipot chips — alternative

Drop-in electrical twins for the mechanical pots, at higher cost and lower
resolution than Variant B. Choose this if you'd rather not deal with filters.

| Part | Need | Approx. | Link | Notes |
| --- | --- | --- | --- | --- |
| MCP41010 digipot, 10 kΩ (need 2) | Required | ~$2 ea. from a distributor; **~$12 ea.** on Amazon | [DigiKey](https://www.digikey.com/en/products/base-product/microchip-technology/150/MCP41010/12251) · [Mouser](https://www.mouser.com/c/?q=MCP41010) | 256 [taps](glossary.md#taps-wiper-codes). Amazon single-unit pricing is heavy markup; distributors are cheaper but also ship in days. |
| X9C103S digipot module, 10 kΩ | Budget option | ~$12 for 4 | [Comimark 4-pack](https://www.amazon.com/Comimark-X9C103S-Digital-Potentiometer-Arduino/dp/B07Y23SY7L) | Cheapest per chip, but only 100 taps and an open-loop interface — [see the caveats](#what-not-to-buy). |

---

## Add-on: distance sensors

Optional on both paths, and wired identically for either.
→ [Sensor wiring guide](sensor-wiring.md)

| Part | Need | Approx. | Link | Notes |
| --- | --- | --- | --- | --- |
| HC-SR04 ultrasonic (need 2) | Required for the array | ~$10 for 5 | [ELEGOO 5-pack](https://www.amazon.com/ELEGOO-HC-SR04-Ultrasonic-Distance-MEGA2560/dp/B01COSN7O6/) · [EPLZON 5-pack](https://www.amazon.com/EPLZON-HC-SR04-Ultrasonic-Distance-Arduino/dp/B09PG4HTT1) | Buy a multipack; spares are useful and they're nearly free. |
| VL53L4CD time-of-flight module | Required for the array | $12–20 | [SparkFun breakout](https://www.amazon.com/SparkFun-Distance-Sensor-Measurement-Requirement/dp/B09YWNL4BN) · [DONGKER module](https://www.amazon.com/VL53L4CD-2-7V-5V-Ranging-Breakout-Millimeters/dp/B0DJ74Z8KH) · [Arduino Modulino Distance](https://www.amazon.com/s?k=arduino+modulino+distance) | Any VL53L4CD breakout works with the firmware. The Modulino is the [Qwiic](glossary.md#i²c)-connector flavor. |
| Bidirectional I²C level shifter (BSS138) | Recommended | ~$7 for 10 | [HiLetgo 10-pack](https://www.amazon.com/HiLetgo-Channels-Converter-Bi-Directional-3-3V-5V/dp/B07F7W91LC/) · [Adafruit](https://www.amazon.com/gp/product/B00NAY3J7O/) | Cheap insurance: the ToF module is [3.3V-only and not 5V tolerant](sensor-wiring.md#pin-by-pin-tables). |
| Sensor mounting brackets | Optional | — | — | Often bundled with HC-SR04 multipacks. Hot glue also works. |

---

## Tools

| Tool | Need | Approx. | Link | Notes |
| --- | --- | --- | --- | --- |
| **Multimeter** | **Required for Path B** | $15–100 | [FNIRSI 2C23T](https://www.amazon.com/FNIRSI-2C23T-Oscilloscope-Multimeter-Generator/dp/B0CTTCVJHL) (this build's, also a scope) · [budget search](https://www.amazon.com/s?k=digital+multimeter+auto+ranging) | You can't identify pot wires without one. [How to use it for this job](pot-identification.md#using-your-multimeter). |
| Soldering iron + solder | Required for Path B | $20–40 | [search](https://www.amazon.com/s?k=soldering+iron+kit+temperature+controlled) | Any temperature-controlled kit. |
| Wire strippers / flush cutters | Recommended | $10–15 | [search](https://www.amazon.com/s?k=flush+cutters+wire+strippers+electronics) | |
| Heat-shrink tubing or electrical tape | Recommended | $8 | [search](https://www.amazon.com/s?k=heat+shrink+tubing+assortment) | Insulate your splices. |
| Serial monitor | Required | free | — | The Arduino IDE's built-in monitor at 115200 baud. See the [protocol cheat sheet](serial-protocol.md#driving-it-by-hand). |
| Arduino IDE or `arduino-cli` | Required | free | [arduino.cc](https://www.arduino.cc/en/software) | To flash the firmware. Needs the **VL53L4CD** library if you're building the sensor array. |

---

## Substitutions and salvage

**You may not need to buy the Variant B resistors at all.** The two pots you're
removing from the controller *are* resistors — wiper-to-end on a sacrificed pot
is a perfectly good filter resistor, and you can even dial in the value. Any
loose resistor from 1–10 kΩ works too:

| Bands | Value |
| --- | --- |
| brown-black-red | 1 kΩ |
| red-red-red | 2.2 kΩ |
| yellow-violet-red | 4.7 kΩ |
| brown-black-orange | 10 kΩ |

**Capacitors are the one part you likely can't improvise.** Anything from 0.1 µF
to 10 µF works — look for a ceramic marked `105` (1 µF) or `225` (2.2 µF), or
any small electrolytic can labeled 1/4.7/10 µF. Every Arduino starter kit has a
bag of them, and any dead wall wart, radio, or old toy has several on its board
if you're willing to desolder.

**Digipot value doesn't need to match the pot exactly.** The controller reads the
wiper's [voltage](glossary.md#ratiometric), so what matters is the division
*ratio*, not absolute ohms. A 10 kΩ digipot on this build's ~5 kΩ pot pads
produces identical wiper voltages. Stay within roughly 2× and you're fine.

---

## What not to buy

| Item | Why to skip it |
| --- | --- |
| **X9C103S digipots** (for a fast car) | Only 100 [taps](glossary.md#taps-wiper-codes). Once you software-cap top speed — which you will on a 60 km/h car — that's ~10 distinct forward speeds, chunky exactly where fine control matters. Its up/down interface also has no position readback, so a missed pulse becomes a persistent throttle offset. Fine for a slow car; poor for this one. |
| **MCP41010 at Amazon single-unit prices** | ~$12 each versus ~$2 from DigiKey or Mouser. If you're waiting for shipping anyway, order from a distributor. |
| **Capacitor kits topping out at 100 nF** | Off by 100× for the RC filter. Check that the range reaches µF. |
| **A second battery for the servo** | The ESC's [BEC](glossary.md#bec) already powers it on Path A. |
| **Radio modules to emulate the car's link** | No off-the-shelf module speaks these proprietary [FHSS](glossary.md#fhss) protocols. [Why this doesn't work](controller-takeover.md#why-not-just-emulate-the-radio). |

---

**Next:** your path's wiring guide — [Path A](esc-wiring.md) or [Path B](controller-takeover.md).
