/*
  Multi-channel serial controller for Arduino Nano
  -- DIGIPOT / CONTROLLER-TAKEOVER variant

  Drop-in alternative to MultiChannelController.ino for cars whose ESC is a
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

  Failsafe: if no command arrives for FAILSAFE_MS, both pots are parked at
  neutral (trigger released, wheel centered).

  ---- WIRING ----
  SPI to both digital potentiometers (e.g. MCP41010, 10k):
    Nano D13 (SCK)  -> both digipots' SCK/CLK
    Nano D11 (MOSI) -> both digipots' SI (data in)
    Nano D7         -> throttle digipot CS
    Nano D8         -> steering digipot CS
    Nano GND        -> controller GND   (COMMON GROUND IS REQUIRED)

  Each digipot replaces one mechanical pot inside the controller:
    Digipot PA0 (high)  -> controller pot HIGH terminal (its supply-rail node)
    Digipot PW0 (wiper) -> controller chip input (where the pot wiper was)
    Digipot PB0 (low)   -> controller pot LOW terminal (ground node)
  Power each digipot's VDD/VSS from the controller's rail + ground, staying
  within the digipot's max voltage (MCP41xxx is ~5.5V max -- if the controller
  runs on 4xAA/6V, use a higher-voltage part or a regulated 4.5-5V feed).
*/

#include <SPI.h>

// ---- Digipot chip-select pins ----
const int CS_THROTTLE = 7; // digipot replacing the trigger pot  (channel 1)
const int CS_STEERING = 8; // digipot replacing the wheel pot    (channel 2)

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

  lastCommandTime = millis();
}

void loop() {
  readSerial();

  if (armed && millis() - lastCommandTime > FAILSAFE_MS) {
    armed = false;
    setNeutral();
  }
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
