/*
  Multi-channel serial controller for Arduino Nano
  -- DIGIPOT / CONTROLLER-TAKEOVER variant

  Drop-in alternative to EscServoController.ino for cars whose ESC is a
  sealed receiver+ESC combo that can't be wired to a signal pin (e.g. Hosim
  X15W). Instead of emitting servo/ESC pulses, this variant drives two SPI
  digital potentiometers that replace the trigger and steering-wheel pots
  inside a spare 2.4GHz handheld controller. The controller then transmits
  over its normal, already-paired radio link to a sealed receiver+ESC.

  The serial protocol is IDENTICAL to the original sketch, so the phone app
  and MotorController.kt need no changes. See docs/controller-takeover.md for
  the full rationale and wiring, and docs/controller-takeover-calibration.md
  for the fill-in calibration worksheet.

  Wire protocol (newline-terminated ASCII lines):
    A:1\n            -- arm (required before T commands take effect)
    A:0\n            -- disarm (channels forced neutral)
    T<ch>:<value>\n  -- set channel <ch> to <value>, -100 to 100
                        ch1 = throttle (drive), ch2 = steering, ch3 = spare
    ?\n              -- ping, replies "OK:<armed>:<t1>:<t2>:<t3>\n"
    D?\n             -- distances, replies "D:<center>,<frontLeft>,<frontRight>\n"
                        each value in mm, -1 = no reading (absent sensor,
                        out of range, or no echo)

  Sensor layout: a forward-perception array. The ToF is the precision center
  beam; the two ultrasonics mount on the FRONT CORNERS angled slightly
  outward, so their wide cones cover the flanks the center beam misses.
  (No rear sensor: reversing is only used to back out along ground the car
  has already covered.)

  Failsafe: if no command arrives for FAILSAFE_MS, both pots are parked at
  neutral (trigger released, wheel centered).

  ---- WIRING ----
  SPI to both digital potentiometers (e.g. MCP41010, 10k):
    Nano D13 (SCK)  -> both digipots' SCK/CLK
    Nano D11 (MOSI) -> both digipots' SI (data in)
    Nano D7         -> throttle digipot CS
    Nano D8         -> steering digipot CS
    Nano GND        -> controller GND   (COMMON GROUND IS REQUIRED)

  HC-SR04 ultrasonic (5V, front-left + front-right corners):
    Front-left:  TRIG -> D2, ECHO -> D3, VCC -> 5V, GND -> GND
    Front-right: TRIG -> D4, ECHO -> D5, VCC -> 5V, GND -> GND

  VL53L4CD time-of-flight (3.3V I2C -- e.g. Arduino Modulino Distance):
    SDA -> A4, SCL -> A5, power -> 3V3 (NOT 5V), GND -> GND
    !! The Modulino/Qwiic ecosystem is 3.3V-only and not 5V tolerant.
    Power it from the Nano's 3V3 pin and prefer a bidirectional I2C
    level shifter between the Nano's 5V A4/A5 and the module.

  Each digipot replaces one mechanical pot inside the controller:
    Digipot PA0 (high)  -> controller pot HIGH terminal (its supply-rail node)
    Digipot PW0 (wiper) -> controller chip input (where the pot wiper was)
    Digipot PB0 (low)   -> controller pot LOW terminal (ground node)
  Power each digipot's VDD/VSS from the controller's rail + ground, staying
  within the digipot's max voltage (MCP41xxx is ~5.5V max -- if the controller
  runs on 4xAA/6V, use a higher-voltage part or a regulated 4.5-5V feed).
*/

#include <SPI.h>
#include <VL53L4CD.h>
#include <Wire.h>

// ---- Digipot chip-select pins ----
const int CS_THROTTLE = 7; // digipot replacing the trigger pot  (channel 1)
const int CS_STEERING = 8; // digipot replacing the wheel pot    (channel 2)

// ---- Distance sensors (forward-perception array) ----
const int SR04_FL_TRIG = 2; // front-left corner
const int SR04_FL_ECHO = 3;
const int SR04_FR_TRIG = 4; // front-right corner
const int SR04_FR_ECHO = 5;

// One sensor is sampled per tick (round-robin) so pulseIn() blocking stays
// bounded and the serial loop keeps its latency.
const unsigned long SENSOR_INTERVAL_MS = 50;
// ~2m max range: beyond that the echo pulse would exceed this and we report -1.
const unsigned long SR04_TIMEOUT_US = 12000;

VL53L4CD tofSensor;
bool tofPresent = false;
int centerMm = -1;     // center ToF beam (mm), -1 = no reading
int frontLeftMm = -1;  // front-left corner ultrasonic (mm)
int frontRightMm = -1; // front-right corner ultrasonic (mm)
byte sensorPhase = 0;
unsigned long lastSensorMs = 0;

// MCP41xxx command byte: "write data to potentiometer 0".
const byte DIGIPOT_WRITE_P0 = 0x11;

// ---- CALIBRATION -----------------------------------------------------------
// Wiper positions (0-255) that make the *controller* output each extreme.
// These are PLACEHOLDERS -- measure and tune with the car's wheels off the
// ground. Worksheet: docs/controller-takeover-calibration.md
const int THR_MIN     = 20;  // full reverse
const int THR_NEUTRAL = 128; // stopped -- tune this FIRST so the car can't creep
const int THR_MAX     = 235; // full forward

const int STR_LEFT    = 20;  // full left lock
const int STR_CENTER  = 128; // wheels straight
const int STR_RIGHT   = 235; // full right lock
// ----------------------------------------------------------------------------

const unsigned long FAILSAFE_MS = 500;

bool armed = false;
int lastThrottle[3] = {0, 0, 0};
unsigned long lastCommandTime = 0;

String inputBuffer;

void setup() {
  Serial.begin(115200);

  pinMode(CS_THROTTLE, OUTPUT);
  pinMode(CS_STEERING, OUTPUT);
  digitalWrite(CS_THROTTLE, HIGH); // CS idle high
  digitalWrite(CS_STEERING, HIGH);
  SPI.begin();

  setNeutral();
  setupSensors();

  lastCommandTime = millis();
}

void loop() {
  readSerial();
  sampleSensors();

  if (armed && millis() - lastCommandTime > FAILSAFE_MS) {
    armed = false;
    setNeutral();
  }
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
// pulseIn timeout (~12ms) instead of all sensors back to back. It also means
// the two ultrasonics never fire together, so they can't hear each other's
// echoes (cross-talk) despite their overlapping cones.
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
      case 1: setWiper(CS_THROTTLE, throttleToWiper(val)); break;
      case 2: setWiper(CS_STEERING, steeringToWiper(val)); break;
      case 3: /* spare -- no digipot wired on this channel */ break;
    }
    Serial.print("SET:");
    Serial.print(channel);
    Serial.print(":");
    Serial.println(val);
    return;
  }

  Serial.println("ERR:UNKNOWN_CMD");
}

// Map app throttle (-100..100) to a wiper value, split around neutral so that
// 0 always lands exactly on the calibrated stop point (no creep at idle).
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
  lastThrottle[0] = 0;
  lastThrottle[1] = 0;
  lastThrottle[2] = 0;
}

// Send one 0-255 wiper position to an MCP41xxx-style SPI digital pot.
void setWiper(int csPin, int value) {
  value = constrain(value, 0, 255);
  SPI.beginTransaction(SPISettings(1000000, MSBFIRST, SPI_MODE0));
  digitalWrite(csPin, LOW);
  SPI.transfer(DIGIPOT_WRITE_P0);
  SPI.transfer((byte)value);
  digitalWrite(csPin, HIGH);
  SPI.endTransaction();
}
