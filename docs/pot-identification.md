# Identify the Controller's Pots

> A bench procedure: figure out which of the controller's pot wires is the
> wiper, which is the supply rail, and which is ground — **before** you cut
> anything.

[← Path B: controller takeover](controller-takeover.md) · [Hardware guide](hardware-wiring.md) · [Glossary](glossary.md)

---

## At a glance

|                      |                                                                                                                                                                                                          |
| -------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **What you'll do**   | Measure both pots with a multimeter and label their three wires by role                                                                                                                                  |
| **You'll need**      | A [multimeter](parts-and-shopping.md#tools), the controller, its batteries                                                                                                                               |
| **Time**             | 20–30 minutes for both pots                                                                                                                                                                              |
| **Applies to**       | Both takeover variants — [PWM](controller-takeover.md#variant-b-pwm-wiper-synthesis-no-digipots) and [digipot](controller-takeover.md#variant-a-digipot-pin-by-pin-mcp41010) builds need the same labels |
| **When you're done** | Sections 1–2 of the [calibration worksheet](controller-takeover-calibration.md) are filled in and you can wire with confidence                                                                           |

---

## Why this matters

A [potentiometer](glossary.md#potentiometer-pot) has three terminals and you need
to know which is which:

```
  [HIGH] ───/\/\/\/\/\/\─── [LOW]        HIGH → the controller's supply rail
                  │                       LOW → ground
               [WIPER]                  WIPER → the voltage the chip reads
```

The stakes are lopsided, which is good news:

| Get this wrong  | Consequence                                                                                                                                                                                                                                                                |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **WIPER**       | The controller chip reads garbage. Nothing works, and no amount of calibration fixes it.                                                                                                                                                                                   |
| **HIGH vs LOW** | Every command lands outside the control's real travel — full forward reads as hard reverse, and neutral creeps. Fixable in software, but only by replacing all the [calibration constants](glossary.md#calibration-constants) with their complements, not by swapping two. |

So: **be certain about the wiper _and_ about HIGH versus LOW.** An earlier version
of this page told you not to lose sleep over HIGH/LOW because a mix-up "just
mirrors the direction, swap two constants". That is wrong, and it cost a long
debugging session on this very build.

Getting it backwards inverts the wiper fraction, so the correct fix is
**`f → 1023 − f` on every constant**:

| Constant                 | White as LOW | Red as LOW (correct here) |
| ------------------------ | ------------ | ------------------------- |
| `THR_MIN` (full reverse) | 391          | 632                       |
| `THR_NEUTRAL`            | 545          | 478                       |
| `THR_MAX` (full forward) | 866          | 157                       |
| `STR_LEFT`               | 920          | 103                       |
| `STR_CENTER`             | 531          | 492                       |
| `STR_RIGHT`              | 10           | 1013                      |

Swapping MIN and MAX would give you 866 / 545 / 391 where the truth is
632 / **478** / 157. Neutral is wrong by 67 counts, because neither window is
centred — from the trigger's 2.77 kΩ resting point, forward gets about twice the
electrical span of reverse. A swap leaves the car creeping at rest with wrong
travel limits at both ends.

Note the complements come out with **MIN greater than MAX**, and LEFT less than
RIGHT. That's correct — `map()` doesn't clamp, so it interpolates inverted
endpoints fine. Don't tidy them back into ascending order.

> ⚠️ **Colors are a habit, not a spec.** The servo-style convention suggests
> red = HIGH, black = LOW, white = WIPER. On this build's controller that was
> **wrong** — black was the wiper. Measure; don't assume.

---

## Using your multimeter

You'll use two modes: **resistance (Ω)** with the controller off, and **DC volts**
with it on.

### Any meter

| Mode           | Controller power | What it tells you                                                       |
| -------------- | ---------------- | ----------------------------------------------------------------------- |
| Resistance (Ω) | **OFF**          | Which wire is the wiper, and the pot's [track](glossary.md#track) value |
| DC volts       | **ON**           | Which end is HIGH and which is LOW                                      |

> ⚠️ **Never use resistance mode on a powered circuit.** The readings are
> meaningless and it's hard on the meter.

### If you have the FNIRSI 2C23T

This build's meter, and its 3-in-1 nature is the main gotcha:

1. **Choose Multimeter mode** from the device's main menu. It's also an
   oscilloscope and a signal generator; you don't want either here.
2. **Use the two banana jacks on the bottom edge** — red into the V/Ω input,
   black into COM. The connectors on the _top_ edge are oscilloscope channels;
   don't probe with those.
3. **It's auto-ranging**, so there's no range dial to fight. Pick the function
   and read the screen.
4. **Read the unit suffix.** On a 10,000-count display, `4.67 kΩ` and `4.67 Ω`
   look nearly identical at a glance. The suffix is the whole story.
5. **`OL` means open** — no connection. Check your probe contact before you
   conclude anything about the pot.
6. **For DC volts**, the meter auto-detects AC vs DC. If the screen says AC,
   press **AUTO** to get the DC reading.

---

## Step 1: get access to the pots

Look at how the pots attach to the controller board:

| What you find                                            | What it means                                                                                                                                                                                      |
| -------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Each pot hangs off a 3-wire harness with a connector** | The easy case. Nothing to desolder from the main board — unplug the pot to measure it in isolation, and later keep the connector so your replacement becomes plug-in. **This build is this case.** |
| **Pots soldered directly to the board**                  | Measure in place first (see the [caveat](#troubleshooting-odd-readings) about in-circuit readings), label the pads, then desolder.                                                                 |

> 💡 **If your pots unplug, your build just got better.** Cut the pigtail at the
> _pot body_, keep the connector, and wire your replacement to the connector
> side. The controller board is never modified, and reverting to stock is a
> plug swap.

---

## Step 2: find the wiper (resistance test)

Controller **off**, pot unplugged if possible.

1. Set the meter to **resistance (Ω)**.
2. Probe pairs of wires while moving the control (pull the trigger, turn the
   wheel) through its full travel.
3. Interpret:

| Reading behavior                                | What that pair is                                                                    |
| ----------------------------------------------- | ------------------------------------------------------------------------------------ |
| **Changes** as you move the control             | One of the two wires is the **wiper**                                                |
| **Steady** at some value regardless of position | You're across the two ends (HIGH↔LOW) — that value is the [track](glossary.md#track) |

The wire that's part of _both_ changing pairs is the wiper.

**Probing tip:** if the pot's connector is female, probe tips barely reach the
contacts. Push a snug jumper pin or header pin into each socket and clip your
probes onto those instead.

**Cross-check:** the wiper is almost always the pot's physically **middle**
terminal.

### The health check that confirms everything

At any control position, the wiper's two readings must sum to the track value:

```
(wiper ↔ end A) + (wiper ↔ end B) ≈ (end A ↔ end B)
```

That's just the wiper splitting one fixed track into two parts. If the sum holds
at every position, the pot is healthy and your wire identification is right. If
it doesn't, something is disconnected or you've mislabeled a wire.

---

## Step 3: find HIGH and LOW (powered test)

Resistance can't distinguish the two ends — they're symmetric. Voltage can.

> ⚠️ **Turn the car OFF for this test.** A powered controller with modified or
> floating pot inputs can transmit garbage throttle over its radio.

1. Controller **on**, car **off**.
2. Meter on **DC volts**.
3. Black probe on **battery negative** — the spring terminal in the battery bay
   is easiest. An alligator clip there frees a hand.
4. Touch the red probe to each of the pot's three connection points in turn:

| Reading                                                        | Role                        |
| -------------------------------------------------------------- | --------------------------- |
| Steady ~4.5V (3×AA) — the [rail](glossary.md#rail-supply-rail) | **HIGH**                    |
| Steady 0V                                                      | **LOW**                     |
| Sweeps between them as you move the control                    | **WIPER** (confirms step 2) |

**Also write down the rail voltage** — it goes in the
[worksheet](controller-takeover-calibration.md#1-parts-and-measurements) and,
for a digipot build, determines whether your chip's voltage rating is adequate.

**Measuring at the board-side socket instead** (pot unplugged): the same test
works, and it's what you want for a plug-in replacement. The wiper _input_ pin
will float with nothing driving it, so it may read drifting nonsense — identify
it by elimination once you've found the rail and ground pins. Record pin
**positions** in the connector, since the connector now enforces your mapping.

### Two readings that look wrong but aren't

- **The wiper doesn't reach 0V or full rail.** Normal. Mechanical stops keep the
  control inside a [travel window](glossary.md#travel-window) narrower than the
  electrical track.
- **Everything reads negative.** Your black probe is on the positive battery
  terminal. Move it.

---

## Step 4: record it

Fill in [worksheet section 2](controller-takeover-calibration.md#2-identify-each-pots-terminals)
with your labels, then map them to your variant:

| Role  | Variant B (PWM)                                                                              | Variant A (digipot)     |
| ----- | -------------------------------------------------------------------------------------------- | ----------------------- |
| WIPER | Nano D9 (throttle) or D10 (steering), through an [RC filter](glossary.md#rc-low-pass-filter) | Digipot **PW0** (pin 6) |
| HIGH  | Nano **A0** (rail sense — one pot's is enough)                                               | Digipot **PA0** (pin 7) |
| LOW   | Nano **GND**                                                                                 | Digipot **PB0** (pin 5) |

> 💡 **Keep the removed pots, labeled.** They're your path back to a stock
> controller, and on a Variant B build their resistive elements can serve as the
> [filter resistors](parts-and-shopping.md#substitutions-and-salvage).

---

## Field data from this build

Measured on a Hosim F12025 controller, both pots **unplugged and isolated**,
black probe on the black (center) wire. Use this as a worked example — your
numbers will differ.

### The wires

| Wire                        | Role (measured)     | Convention would have said |
| --------------------------- | ------------------- | -------------------------- |
| **Black** (center terminal) | **WIPER**           | LOW ❌                     |
| **White**                   | **HIGH** — the rail | WIPER ❌                   |
| **Red**                     | **LOW** — ground    | HIGH ❌                    |

**Every one of the three is the opposite of what its colour suggests.** Don't
guess this pot from colours; measure it.

### Powered socket test result

Controller on, pot unplugged, black probe on the controller's battery negative:

| Socket pin | Measured  | Role                                         |
| ---------- | --------- | -------------------------------------------- |
| white      | **3.2 V** | HIGH / rail                                  |
| black      | 0.005 V   | wiper input (floats with nothing driving it) |
| red        | 0 V       | LOW / ground                                 |

> ⚠️ **The rail is 3.2 V, not the 4.5 V you'd expect from 3×AA.** Whether that's
> tired batteries or a regulated supply on the board, it matters: it's the
> denominator for every calibration fraction. This is exactly why the
> [rail-sense wire into A0](controller-takeover.md#why-the-rail-sense-wire-matters)
> isn't optional — with it connected the firmware scales to whatever the rail
> actually is. Leave A0 unwired and it falls back to a hard-coded guess of 4.5 V,
> which on a 3.2 V rail puts every output about 40% too high.
>
> An unconnected A0 doesn't read 0 V either — it **floats**, drifting around
> 0.3–0.9 V. Check it with `R?` rather than assuming.

### Wheel (steering) pot

| Pair          | Full right | Full left | At rest  |
| ------------- | ---------- | --------- | -------- |
| black ↔ white | 52 Ω       | 4.67 kΩ   | ~2.7 kΩ  |
| black ↔ red   | 5.2 kΩ     | ~570 Ω    | ~2.55 kΩ |

### Trigger (throttle) pot

| Pair          | Full reverse | Full forward | At rest |
| ------------- | ------------ | ------------ | ------- |
| black ↔ white | 1.99 kΩ      | 4.4 kΩ       | 2.77 kΩ |
| black ↔ red   | 3.7 kΩ       | ~840 Ω       | 2.55 kΩ |

### What the numbers tell us

- **Both pots are healthy, track ≈ 5.2 kΩ** (5k nominal). The pair sums hold at
  every position — 52 Ω + 5.2 kΩ and 4.67 kΩ + 570 Ω both land at ~5.24 kΩ.
- **The two pots sweep in opposite senses.** Wheel-right drives the wiper toward
  the _white_ end; trigger-forward drives it toward the _red_ end. Electrically
  meaningless, but wire by measurement rather than by analogy between the two.
- **The trigger uses only ~46% of its electrical track** (1.99 k–4.4 k of
  5.2 k), and the window is **lopsided**: from its 2.77 kΩ resting point,
  forward gets roughly twice the electrical span of reverse. That's the classic
  RC throttle layout — and it's why `THR_NEUTRAL` lands nowhere near the
  midpoint of your output range and gets [calibrated first](controller-takeover-calibration.md#4-bench-test--measure-the-calibration-values).
- **Neither control reaches its track's ends.** Your electronic replacement has
  no mechanical stops, so it _can_ command positions the physical control never
  could. Calibrate to the car's real limits, not your output range's extremes.

---

## Troubleshooting odd readings

| Symptom                                                              | Likely cause / fix                                                                                                                                        |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `OL` or wildly jumping resistance                                    | Probe contact. Use jumper pins in connector sockets rather than probing bare tips into them.                                                              |
| A pair reads oddly **while the pot is still connected to the board** | In-circuit measuring: capacitors and chips on that node absorb the meter's test current, so readings drift or climb. Unplug the pot and measure it alone. |
| Readings jump when you wiggle a wire                                 | Broken crimp or cracked solder joint at the pot lug or the connector. Hold the probes still and wiggle each wire to localize it.                          |
| Wiper sweep is jumpy or drops out mid-travel                         | Worn track. Cosmetic here — that pot is being replaced anyway — but note it if you plan to keep the pot as a spare.                                       |
| The sum check doesn't hold                                           | A wire isn't connected where you think, or you're reading two different pots. Re-probe all three pairs at one control position.                           |
| Everything reads 0 V on the powered test                             | Controller off, dead batteries, or your black probe isn't on battery negative.                                                                            |

---

**Next:** back to [Path B build steps](controller-takeover.md#step-by-step-build), or fill in the [calibration worksheet](controller-takeover-calibration.md).
