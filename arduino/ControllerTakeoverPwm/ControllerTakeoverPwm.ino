/*
  Multi-channel serial controller for Arduino Nano
  -- PWM / CONTROLLER-TAKEOVER variant (no digipots)

  Drop-in alternative to ControllerTakeover.ino that needs NO digital
  potentiometer chips. Instead of impersonating the controller's pots with
  digipots, this variant synthesizes the WIPER VOLTAGES directly: the
  controller chip never senses resistance, only the voltage a pot's wiper
  divider produces -- so a filtered PWM output driving the wiper wire is
  electrically equivalent, and at 10-bit resolution it is FINER than either
  the MCP41010 (256 taps) or X9C103S (100 taps).

  The serial protocol is IDENTICAL to the other sketches, so the phone app
  and MotorController.kt need no changes. See docs/controller-takeover.md
  (PWM variant section) for rationale and wiring,
  docs/pot-identification.md for labeling the controller's pot wires, and
  docs/controller-takeover-calibration.md for the fill-in worksheet.

  Wire protocol (newline-terminated ASCII lines):
    A:1\n            -- arm (required before T commands take effect)
    A:0\n            -- disarm (channels forced neutral)
    T<ch>:<value>\n  -- set channel <ch> to <value>, -100 to 100
                        ch1 = throttle (drive), ch2 = steering, ch3 = spare
    ?\n              -- ping, replies "OK:<armed>:<t1>:<t2>:<t3>\n"
    D?\n             -- distances, replies "D:<center>,<frontLeft>,<frontRight>\n"
                        each value in mm, -1 = no reading
    R?\n             -- rail sense, replies "R:<inUse>:<rawA0>:<valid>\n"
                        rawA0 low and drifting => A0 is not connected

  Failsafe: if no command arrives for FAILSAFE_MS, both outputs park at
  neutral (trigger released, wheel centered).

  ---- HOW THE RATIOMETRIC COMPENSATION WORKS ----
  A mechanical pot divides the CONTROLLER'S battery rail, so when its AAs sag
  from 4.5V to 4.0V every voltage scales together and the chip (which reads
  ratiometrically against that same rail) never notices. A fixed Nano voltage
  would NOT scale -- calibrated neutral would drift as the batteries drain,
  i.e. a creeping car. Fix: sense the controller rail on A0 and scale the PWM
  duty by it. The math cancels the Nano's own supply voltage exactly:

    V_out           = duty/TOP * Vcc_nano
    analogRead(A0)  = 1023 * V_rail / Vcc_nano
    want V_out      = cal/1023 * V_rail
    => duty         = cal * analogRead(A0) / 1023      (Vcc_nano cancels)

  Calibration constants are therefore RAIL FRACTIONS in 1/1023 units
  (0 = controller ground, 1023 = controller rail), not absolute volts.
  Bonus: because duty can never exceed the measured rail counts, the output
  can never be driven above the controller chip's supply.

  ---- WIRING ----
  Per pot: only the WIPER wire is driven; HIGH taps the rail for sensing and
  LOW ties the grounds. With the Hosim harness pots that's literally the
  6 pot-connector wires onto the Nano plus four passive parts:

    Nano D9  -> [R ~2.2-4.7k] ->+-> throttle WIPER wire (board-side connector)
                                |
                             [C 1-2.2uF]
                                |
                               GND
    Nano D10 -> same RC filter -> steering WIPER wire
    Controller rail (either pot's HIGH wire/pin, they share the rail) -> A0
    Controller ground (pot LOW wires/pins) -> Nano GND  (COMMON GROUND)

  RC filter: R between the Nano pin and the wiper node, C from the wiper node
  to ground. At 15.6kHz PWM even a 34Hz corner (4.7k + 1uF) leaves only
  millivolts of ripple and settles in ~15ms. The element of a sacrificed 5k
  pot works fine as the R. Values are uncritical within R 1-10k, C 0.5-10uF
  (bigger C = smoother but slower to settle).

  !! Bench-test the failure mode before unattended use: if the Nano loses
  power the wiper wires float/decay toward 0V, which is BELOW the trigger's
  normal travel window -- verify what the controller transmits in that state
  (wheels off the ground, unplug the Nano mid-drive and watch).

  HC-SR04 ultrasonic (5V, front-left + front-right corners):
    Front-left:  TRIG -> D2, ECHO -> D3, VCC -> 5V, GND -> GND
    Front-right: TRIG -> D4, ECHO -> D5, VCC -> 5V, GND -> GND

  VL53L4CD time-of-flight (3.3V I2C -- e.g. Arduino Modulino Distance):
    SDA -> A4, SCL -> A5, power -> 3V3 (NOT 5V), GND -> GND
    !! 3.3V-only, not 5V tolerant: power from 3V3, prefer an I2C level
    shifter between the Nano's A4/A5 and the module.
*/

#include <VL53L4CD.h>
#include <Wire.h>

// ---- PWM outputs (Timer1 -- do not move to non-Timer1 pins) ----
const int PWM_THROTTLE_PIN = 9;  // OC1A -> RC filter -> trigger wiper wire
const int PWM_STEERING_PIN = 10; // OC1B -> RC filter -> wheel wiper wire
const int RAIL_SENSE_PIN = A0;   // controller supply rail (pot HIGH node)

// Timer1 Fast PWM TOP: 16MHz / (1023+1) = 15.6kHz at 10-bit duty resolution.
const int PWM_TOP = 1023;

// ---- Distance sensors (forward-perception array) ----
const int SR04_FL_TRIG = 2; // front-left corner
const int SR04_FL_ECHO = 3;
const int SR04_FR_TRIG = 4; // front-right corner
const int SR04_FR_ECHO = 5;

const unsigned long SENSOR_INTERVAL_MS = 50;
const unsigned long SR04_TIMEOUT_US = 12000;

VL53L4CD tofSensor;
bool tofPresent = false;
int centerMm = -1;
int frontLeftMm = -1;
int frontRightMm = -1;
byte sensorPhase = 0;
unsigned long lastSensorMs = 0;

// ---- CALIBRATION -----------------------------------------------------------
// RAIL FRACTIONS in 1/1023 units (0 = controller GND, 1023 = controller rail)
// that make the *controller* output each extreme. Tune with the wheels off the
// ground. Worksheet: docs/controller-takeover-calibration.md
//
// Wire roles CONFIRMED by the powered socket test on this build:
//   white = HIGH (rail, measured 3.2V)   black = WIPER   red = LOW (ground)
// An earlier revision assumed white was the LOW end and had all six of these
// inverted, which made every command land outside the pot's real travel.
//
// Because ground is the RED end, each fraction is the wiper-to-red resistance
// over the track, i.e. the complement of the white-referenced numbers that were
// bench-measured. Mirroring a pot is 1023 - f, NOT a swap of the two endpoints:
// neither window is centred, so swapping leaves neutral wrong by ~50 counts and
// the car creeps.
//
// Trigger: window 1.99k-4.4k of a 5.2k track, rest 2.77k (white-referenced)
//   => red-referenced fractions ~0.62 / 0.47 / 0.15 of the rail.
const int THR_MIN = 632;     // full reverse
const int THR_NEUTRAL = 478; // stopped -- tune this FIRST so the car can't creep
const int THR_MAX = 157;     // full forward
// Note MIN > MAX now, and LEFT < RIGHT below. That is correct and deliberate:
// map() does not clamp, so it interpolates inverted endpoints fine. Do not
// "tidy" these back into ascending order.
//
// Wheel: window ~52R-4.67k of a 5.2k track, rest ~2.7k (white-referenced)
//   => red-referenced fractions ~0.10 / 0.48 / 0.99.
const int STR_LEFT = 103;    // full left lock
const int STR_CENTER = 492;  // wheels straight
const int STR_RIGHT = 1013;  // full right lock
// ----------------------------------------------------------------------------

const unsigned long FAILSAFE_MS = 500;

// Rail sensing: exponential average of analogRead(A0), refreshed every
// RAIL_INTERVAL_MS. Readings below RAIL_MIN_COUNTS (~2.4V at Vcc=5V) are
// treated as a broken sense wire and the last good value is kept, so a
// flaky A0 connection degrades gracefully instead of slamming the outputs.
const unsigned long RAIL_INTERVAL_MS = 100;
const int RAIL_MIN_COUNTS = 500;
const int RAIL_DEFAULT_COUNTS = 920; // ~4.5V rail on a 5.0V Nano, until sensed
int railCounts = RAIL_DEFAULT_COUNTS;
unsigned long lastRailMs = 0;
// Last raw A0 sample and whether it passed the sanity floor, reported by "R?".
// Without this the rail sense is invisible: an unconnected A0 floats at a low,
// drifting voltage, gets rejected, and the firmware silently runs on
// RAIL_DEFAULT_COUNTS forever with nothing to show anything is wrong. That cost
// a long bench session once.
int lastRailRaw = -1;
bool railValid = false;

bool armed = false;
int lastThrottle[3] = {0, 0, 0};
unsigned long lastCommandTime = 0;

// Currently-applied targets as rail fractions; re-applied whenever the rail
// reading updates so the outputs stay ratiometric even while parked.
int curThrottleCal;
int curSteeringCal;

String inputBuffer;

void setup() {
  Serial.begin(115200);

  setupPwm();
  sampleRail(true);
  setNeutral();
  setupSensors();

  lastCommandTime = millis();
}

void loop() {
  readSerial();
  sampleSensors();
  sampleRail(false);

  if (armed && millis() - lastCommandTime > FAILSAFE_MS) {
    armed = false;
    setNeutral();
  }
}

// ---------------------------------------------------------------------------
// PWM wiper synthesis
// ---------------------------------------------------------------------------

void setupPwm() {
  pinMode(PWM_THROTTLE_PIN, OUTPUT);
  pinMode(PWM_STEERING_PIN, OUTPUT);
  // Timer1 Fast PWM, mode 14 (TOP = ICR1), non-inverting OC1A/OC1B, no
  // prescale: 16MHz / 1024 = 15.6kHz. Far above the RC corner, so ripple at
  // the controller's input is millivolts. millis()/delay() (Timer0) and the
  // sensors are unaffected. analogWrite must NOT be used on D9/D10 now.
  TCCR1A = _BV(COM1A1) | _BV(COM1B1) | _BV(WGM11);
  TCCR1B = _BV(WGM13) | _BV(WGM12) | _BV(CS10);
  ICR1 = PWM_TOP;
  OCR1A = 0;
  OCR1B = 0;
}

// Refresh the cached rail reading and keep both outputs scaled to it.
// force=true blocks until a plausible reading (or accepts the default) at
// boot so the first neutral isn't computed from garbage.
void sampleRail(bool force) {
  if (!force && millis() - lastRailMs < RAIL_INTERVAL_MS) return;
  lastRailMs = millis();

  analogRead(RAIL_SENSE_PIN); // throwaway: settle the ADC mux
  int reading = analogRead(RAIL_SENSE_PIN);
  lastRailRaw = reading;
  railValid = (reading >= RAIL_MIN_COUNTS);
  if (reading >= RAIL_MIN_COUNTS) {
    // Exponential average smooths ADC noise without a sag-tracking lag.
    railCounts = force ? reading : (railCounts * 3 + reading) / 4;
  }
  applyOutputs();
}

// cal (0..1023 rail fraction) -> duty (0..railCounts). Because railCounts is
// the measured rail, the synthesized voltage can never exceed the
// controller's supply, whatever the Nano's own Vcc happens to be.
void applyOutputs() {
  OCR1A = (unsigned int)(((unsigned long)constrain(curThrottleCal, 0, 1023) * railCounts) / 1023UL);
  OCR1B = (unsigned int)(((unsigned long)constrain(curSteeringCal, 0, 1023) * railCounts) / 1023UL);
}

void setTargets(int throttleCal, int steeringCal) {
  curThrottleCal = throttleCal;
  curSteeringCal = steeringCal;
  applyOutputs();
}

// ---------------------------------------------------------------------------
// Distance sensors
// ---------------------------------------------------------------------------

void setupSensors() {
  pinMode(SR04_FL_TRIG, OUTPUT);
  digitalWrite(SR04_FL_TRIG, LOW);
  pinMode(SR04_FL_ECHO, INPUT);
  pinMode(SR04_FR_TRIG, OUTPUT);
  digitalWrite(SR04_FR_TRIG, LOW);
  pinMode(SR04_FR_ECHO, INPUT);

  // ToF is optional: a missing/unwired module must not hang the controller.
  Wire.begin();
  Wire.setClock(400000);
  tofSensor.setTimeout(100);
  tofPresent = tofSensor.init();
  if (tofPresent) {
    tofSensor.setRangeTiming(50, 0); // 50ms budget, continuous ranging
    tofSensor.startContinuous();
  }
}

// One HC-SR04 measurement: 10us trigger pulse, echo time -> mm (at 343 m/s,
// round trip). 0 echo (timeout / out of range) reports -1.
int readSr04Mm(int trigPin, int echoPin) {
  digitalWrite(trigPin, HIGH);
  delayMicroseconds(10);
  digitalWrite(trigPin, LOW);
  unsigned long us = pulseIn(echoPin, HIGH, SR04_TIMEOUT_US);
  if (us == 0) return -1;
  return (int)((us * 343UL) / 2000UL);
}

// Round-robin: one sensor per tick keeps worst-case loop blocking at a single
// pulseIn timeout (~12ms), and the two ultrasonics never fire together so
// they can't hear each other's echoes despite overlapping cones.
void sampleSensors() {
  if (millis() - lastSensorMs < SENSOR_INTERVAL_MS) return;
  lastSensorMs = millis();
  switch (sensorPhase) {
    case 0:
      if (tofPresent && tofSensor.dataReady()) {
        uint16_t mm = tofSensor.read(); // returns promptly when dataReady
        centerMm = tofSensor.timeoutOccurred() ? -1 : (int)mm;
      }
      break;
    case 1:
      frontLeftMm = readSr04Mm(SR04_FL_TRIG, SR04_FL_ECHO);
      break;
    case 2:
      frontRightMm = readSr04Mm(SR04_FR_TRIG, SR04_FR_ECHO);
      break;
  }
  sensorPhase = (sensorPhase + 1) % 3;
}

// ---------------------------------------------------------------------------
// Serial protocol
// ---------------------------------------------------------------------------

void readSerial() {
  while (Serial.available() > 0) {
    char c = Serial.read();
    if (c == '\n') {
      handleCommand(inputBuffer);
      inputBuffer = "";
    } else if (c != '\r') {
      inputBuffer += c;
    }
  }
}

void handleCommand(const String& line) {
  if (line.length() == 0) return;

  if (line == "?") {
    Serial.print("OK:");
    Serial.print(armed ? "1" : "0");
    Serial.print(":");
    Serial.print(lastThrottle[0]);
    Serial.print(":");
    Serial.print(lastThrottle[1]);
    Serial.print(":");
    Serial.println(lastThrottle[2]);
    return;
  }

  if (line == "R?") {
    // R:<railCountsInUse>:<lastRawA0>:<1 if raw passed the sanity floor>
    // raw well below RAIL_MIN_COUNTS and drifting means A0 is not connected.
    Serial.print("R:");
    Serial.print(railCounts);
    Serial.print(":");
    Serial.print(lastRailRaw);
    Serial.print(":");
    Serial.println(railValid ? "1" : "0");
    return;
  }

  if (line == "D?") {
    // Latest sampled values -- never measures on demand, so the reply is
    // immediate and this handler never blocks.
    Serial.print("D:");
    Serial.print(centerMm);
    Serial.print(",");
    Serial.print(frontLeftMm);
    Serial.print(",");
    Serial.println(frontRightMm);
    return;
  }

  if (line.startsWith("A:")) {
    int val = line.substring(2).toInt();
    armed = (val == 1);
    lastCommandTime = millis();
    if (!armed) {
      setNeutral();
    }
    Serial.println(armed ? "ARMED" : "DISARMED");
    return;
  }

  if (line.startsWith("T") && line.length() > 2 && line.charAt(2) == ':') {
    int channel = line.charAt(1) - '0'; // '1', '2', '3' -> 1, 2, 3
    int val = constrain(line.substring(3).toInt(), -100, 100);
    lastCommandTime = millis();

    if (!armed) {
      Serial.println("ERR:NOT_ARMED");
      return;
    }
    if (channel < 1 || channel > 3) {
      Serial.println("ERR:BAD_CHANNEL");
      return;
    }

    lastThrottle[channel - 1] = val;
    switch (channel) {
      case 1: setTargets(throttleToCal(val), curSteeringCal); break;
      case 2: setTargets(curThrottleCal, steeringToCal(val)); break;
      case 3: /* spare -- no output wired on this channel */ break;
    }
    Serial.print("SET:");
    Serial.print(channel);
    Serial.print(":");
    Serial.println(val);
    return;
  }

  Serial.println("ERR:UNKNOWN_CMD");
}

// Map app throttle (-100..100) to a rail-fraction cal value, split around
// neutral so 0 always lands exactly on the calibrated stop point (no creep
// at idle).
int throttleToCal(int v) {
  return (v >= 0) ? map(v, 0, 100, THR_NEUTRAL, THR_MAX)
                  : map(v, -100, 0, THR_MIN, THR_NEUTRAL);
}

int steeringToCal(int v) {
  return (v >= 0) ? map(v, 0, 100, STR_CENTER, STR_RIGHT)
                  : map(v, -100, 0, STR_LEFT, STR_CENTER);
}

void setNeutral() {
  setTargets(THR_NEUTRAL, STR_CENTER);
  lastThrottle[0] = 0;
  lastThrottle[1] = 0;
  lastThrottle[2] = 0;
}
