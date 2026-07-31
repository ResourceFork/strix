#!/usr/bin/env python3
"""Bench tool for the Variant B controller-takeover Nano, over USB serial.

Why this exists: calibrating a pot emulator means answering questions the app
cannot answer. Is the output moving when it shouldn't? Does the interlock refuse
a slam? What cal value is neutral on *this* car? Doing that by hand through a
serial monitor is impossible, because the firmware's 500ms failsafe disarms
between keystrokes - which cost an entire debugging session before this existed.

    python3 scripts/nano-bench.py probe          protocol + rail + sensors
    python3 scripts/nano-bench.py rail           rail-noise measurement
    python3 scripts/nano-bench.py gate           prove the thrust interlock
    python3 scripts/nano-bench.py hold C1:560    hold a raw cal until Ctrl-C
    python3 scripts/nano-bench.py hold T1:50     hold a throttle percentage
    python3 scripts/nano-bench.py cal 2 8 103    channel 2, 8s, one value
    python3 scripts/nano-bench.py cal 1 6 540 560 580   several in sequence

SETUP. Needs pyserial, and macOS system Python refuses to install into itself
(PEP 668), so use a throwaway venv:

    python3 -m venv /tmp/nanoenv && /tmp/nanoenv/bin/pip install pyserial
    /tmp/nanoenv/bin/python scripts/nano-bench.py probe

SAFETY. Every mode here can move the car.
  - Wheels off the ground, always.
  - "controller ON, car OFF" gives a live rail with no possible motion, which is
    the right state for rail/noise work.
  - The un-driven wiper sits at ~0V, which on this controller is PAST FULL
    FORWARD. Never power the car before the Nano is up and verified at neutral,
    unless the ~10k pull-up mitigation is fitted. See the sketch header.
  - Modes that drive always disarm on the way out, including on Ctrl-C.
"""
from __future__ import annotations

import sys
import time
from collections import Counter

try:
    import serial
except ImportError:
    sys.exit("pyserial missing - see SETUP in this file's docstring")

# Adjust if the board enumerates elsewhere: ls /dev/cu.*usb*
PORT = "/dev/cu.usbserial-131220"
BAUD = 115200
# Measured on this build's Nano at the + rail. Only used to turn counts into
# volts for display; nothing depends on it being exact.
VCC = 4.18
# Opening the port asserts DTR, which resets the board. It must finish booting
# before it will answer.
BOOT_WAIT = 2.5
# Comfortably inside the firmware's 500ms FAILSAFE_MS.
KEEPALIVE = 0.15


def open_port():
    ser = serial.Serial(PORT, BAUD, timeout=0.25)
    time.sleep(BOOT_WAIT)
    ser.reset_input_buffer()
    return ser


def send(ser, cmd, wait=0.25, show=True):
    ser.write((cmd + "\n").encode())
    ser.flush()
    time.sleep(wait)
    out = []
    while True:
        line = ser.readline()
        if not line:
            break
        out.append(line.decode(errors="replace").strip())
    if show:
        print(f"  {cmd:<12} -> {' | '.join(out) if out else '(no reply)'}")
    return out


def field(replies, prefix, index):
    for line in replies:
        if line.startswith(prefix):
            return int(line.split(":")[index])
    return None


# ---------------------------------------------------------------------------


def probe(ser, _args):
    """Protocol round-trip, rail health, sensors, and the failsafe behaviour."""
    print("== alive, rail, sensors ==")
    send(ser, "?", 0.4)
    send(ser, "D?", 0.4)
    for _ in range(3):
        send(ser, "R?", 0.3)
    print("\n   R:<railInUse>:<rawA0>:<valid>. A raw value well under 500 that")
    print("   drifts means A0 is NOT connected and the firmware is running on")
    print("   its hard-coded fallback - it will not tell you otherwise.\n")

    print("== arm then drive INSIDE the failsafe window ==")
    send(ser, "A:1", 0.12)
    send(ser, "T1:0", 0.12)
    send(ser, "?", 0.3)

    print("\n== go quiet for 1.2s, past FAILSAFE_MS ==")
    time.sleep(1.2)
    send(ser, "?", 0.3)
    send(ser, "T1:0", 0.3)
    print("\n   ERR:NOT_ARMED there is CORRECT. The firmware disarms itself after")
    print("   500ms of silence, which is why hand-typing commands cannot work.")
    send(ser, "A:0", 0.3)


def rail(ser, args):
    """Rail-sense noise, and what it does to a held command.

    duty = cal * railCounts / 1023, so rail noise lands directly on the throttle.
    This measured 69mV of wander with the command held perfectly still before the
    oversampling and heavy averaging went in; the car showed it as random
    forward/reverse pulses at rest.
    """
    cal = int(args[0]) if args else 560
    secs = float(args[1]) if len(args) > 1 else 12.0
    duties, rails, raws = [], [], []
    t_end = time.time() + secs
    while time.time() < t_end:
        send(ser, "A:1", 0.0, show=False)
        d = field(send(ser, f"C1:{cal}", 0.08, show=False), "CAL:", 3)
        if d is not None:
            duties.append(d)
        r = send(ser, "R?", 0.05, show=False)
        if field(r, "R:", 1) is not None:
            rails.append(field(r, "R:", 1))
            raws.append(field(r, "R:", 2))
    send(ser, "A:0", 0.2, show=False)

    def stat(name, xs, mv_per_count=None):
        if not xs:
            print(f"  {name}: no samples")
            return 0
        lo, hi = min(xs), max(xs)
        extra = f"  = {(hi - lo) * mv_per_count:.0f} mV" if mv_per_count else ""
        print(f"  {name:14} n={len(xs):4} min={lo:5} max={hi:5} "
              f"spread={hi - lo:4}{extra}")
        return hi - lo

    print(f"held cal {cal} for {secs:g}s\n")
    spread = stat("applied duty", duties, VCC / 1024 * 1000)
    stat("railCounts", rails)
    stat("raw A0", raws, VCC / 1023 * 1000)
    print()
    if spread == 0:
        print("  Output is bit-stable. Any twitching you can still feel is")
        print("  ANALOG - PWM ripple, pickup, or ground bounce - not firmware.")
    else:
        print(f"  The firmware is moving the output by {spread} counts on its own.")
        print("  Rail noise is reaching the duty calculation.")


def gate(ser, _args):
    """Prove the firmware refuses a forward->reverse slam.

    This interlock originally lived only in the app, which meant anything talking
    straight to the serial port bypassed it - and one such script threw a live
    car from full forward into full reverse. It is in the firmware now, and this
    is the test that it stays there.
    """
    FWD, NEU, REV = 480, 560, 620
    rc = field(send(ser, "R?", 0.3, show=False), "R:", 1)
    exp = {n: (n * rc) // 1023 for n in (FWD, NEU, REV)}
    print(f"railCounts={rc}   expected duty: fwd={exp[FWD]} "
          f"neutral={exp[NEU]} rev={exp[REV]}\n")

    def near(a, b, tol=12):
        # railCounts is a slow average and drifts a few counts between reads.
        # The three targets are ~60 counts apart, so this stays unambiguous.
        return a is not None and abs(a - b) <= tol

    def step(label, cmd, want, want_name, other, other_name):
        d = field(send(ser, cmd, 0.15, show=False), "CAL:", 3)
        if near(d, want):
            print(f"  {label}: duty={d} = {want_name}  OK")
        elif near(d, other):
            print(f"  {label}: duty={d} = {other_name}  *** FAILED ***")
        else:
            print(f"  {label}: duty={d} matched neither ({want}/{other})")

    send(ser, "A:1", 0.15, show=False)
    step("forward applies      ", f"C1:{FWD}", exp[FWD], "forward",
         exp[NEU], "neutral")
    step("slam to reverse      ", f"C1:{REV}", exp[NEU], "neutral REFUSED",
         exp[REV], "reverse ALLOWED")
    time.sleep(1.3)
    send(ser, "A:1", 0.05, show=False)
    step("reverse after cooldown", f"C1:{REV}", exp[REV], "reverse",
         exp[NEU], "neutral")
    step("slam back to forward ", f"C1:{FWD}", exp[NEU], "neutral REFUSED",
         exp[FWD], "forward ALLOWED")
    print()
    for bad in (0, 1023):
        d = field(send(ser, f"C1:{bad}", 0.12, show=False), "CAL:", 3)
        ok = d is not None and 0 <= d <= 1023
        print(f"  cal {bad:4} -> duty {d}  {'in range' if ok else 'OUT OF RANGE'}")
    send(ser, "A:0", 0.25, show=False)


def hold(ser, args):
    """Hold one command indefinitely, keepalive-fed, until Ctrl-C.

    The mode to use when someone is watching the car: there is no timing to
    catch, the value simply stays put for as long as you need.
    """
    cmd = args[0] if args else "C1:560"
    print(f"holding '{cmd}' every {KEEPALIVE * 1000:.0f}ms. Ctrl-C stops "
          f"and disarms.")
    n = 0
    try:
        while True:
            send(ser, "A:1", 0.0, show=False)
            send(ser, cmd, KEEPALIVE, show=False)
            n += 1
            if n % 20 == 0:
                r = send(ser, "?", 0.1, show=False)
                print(f"  [{n}] {' | '.join(r) if r else '(no reply)'}")
    except KeyboardInterrupt:
        pass
    finally:
        send(ser, "A:0", 0.2, show=False)
        print("\ndisarmed")


def cal(ser, args):
    """Step a channel through raw cal values, holding each one.

    Bells and a countdown on every transition, because the operator is watching
    the wheels rather than the screen. Prefer ONE value per run if someone has to
    report what they saw - correlating a multi-step sequence from memory does not
    work, which we learned the hard way.
    """
    chan = args[0]
    secs = float(args[1])
    values = [int(v) for v in args[2:]]
    print(f"\nchannel {chan}, {len(values)} step(s) of {secs:g}s. "
          f"Ctrl-C stops and parks.")
    print("A bell rings at each step. Watch the wheels, not this window.\n")
    for i in range(5, 0, -1):
        print(f"  starting in {i}...", flush=True)
        time.sleep(1.0)
    try:
        for idx, v in enumerate(values, 1):
            print("\a" + "=" * 52, flush=True)
            print(f"\a  STEP {idx} of {len(values)}   cal = {v}", flush=True)
            print("=" * 52, flush=True)
            t_end = time.time() + secs
            first = True
            while time.time() < t_end:
                send(ser, "A:1", 0.0, show=False)
                r = send(ser, f"C{chan}:{v}", KEEPALIVE, show=False)
                if first:
                    print(f"    applied: {' | '.join(r)}", flush=True)
                    first = False
                left = t_end - time.time()
                if left > 0:
                    print(f"    ...{left:4.1f}s left on step {idx}", flush=True)
    except KeyboardInterrupt:
        print("\ninterrupted")
    finally:
        send(ser, "A:0", 0.2, show=False)
        print("\a\n>>> DONE, DISARMED, parked at neutral.\n", flush=True)


MODES = {"probe": probe, "rail": rail, "gate": gate, "hold": hold, "cal": cal}


def main(argv):
    mode = argv[1] if len(argv) > 1 else "probe"
    if mode not in MODES:
        print(__doc__)
        return 2
    ser = open_port()
    try:
        MODES[mode](ser, argv[2:])
    finally:
        ser.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
