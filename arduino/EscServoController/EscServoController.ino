/*
  Multi-channel serial controller for Arduino Nano
  - Up to 3 servo/ESC channels (throttle or steering)
  - Distance telemetry: 1x VL53L4CD time-of-flight (I2C, e.g. Arduino Modulino
    Distance) + 2x HC-SR04 ultrasonic (front / rear)

  Wire protocol (newline-terminated ASCII lines):
    A:1\n            -- arm (required before T commands take effect)
    A:0\n            -- disarm (all channels forced neutral)
    T<ch>:<value>\n  -- set channel <ch> (1-3) to <value>, -100 to 100
                        e.g. "T1:50\n" sets channel 1 to 50% forward
    ?\n              -- ping, replies "OK:<armed>:<t1>:<t2>:<t3>\n"
    D?\n             -- distances, replies "D:<center>,<frontLeft>,<frontRight>\n"
                        each value in mm, -1 = no reading (absent sensor,
                        out of range, or no echo)

  Failsafe: if no command arrives for FAILSAFE_MS, all channels go neutral.

  Sensor layout: a forward-perception array. The ToF is the precision center
  beam; the two ultrasonics mount on the FRONT CORNERS angled slightly
  outward, so their wide cones cover the flanks the center beam misses.
  (No rear sensor: reversing is only used to back out along ground the car
  has already covered.)

  ---- WIRING ----
  Servo/ESC channels (signal wire only -- share ground, do NOT power
  the Nano's 5V rail from the ESC/servo battery):
    Channel 1 signal -> D9
    Channel 2 signal -> D10
    Channel 3 signal -> D11
    All ESC/servo ground wires -> Nano GND (common ground is required)

  HC-SR04 ultrasonic (5V, front-left + front-right corners):
    Front-left:  TRIG -> D2, ECHO -> D3, VCC -> 5V, GND -> GND
    Front-right: TRIG -> D4, ECHO -> D5, VCC -> 5V, GND -> GND

  VL53L4CD time-of-flight (3.3V I2C -- e.g. Arduino Modulino Distance):
    SDA -> A4, SCL -> A5, power -> 3V3 (NOT 5V), GND -> GND
    !! The Modulino/Qwiic ecosystem is 3.3V-only and not 5V tolerant.
    Power it from the Nano's 3V3 pin and prefer a bidirectional I2C
    level shifter between the Nano's 5V A4/A5 and the module.
*/

#include <Servo.h>
#include <VL53L4CD.h>
#include <Wire.h>

const int CH1_PIN = 9;
const int CH2_PIN = 10;
const int CH3_PIN = 11;

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

const unsigned long FAILSAFE_MS = 500;
const int PULSE_MIN = 1000;
const int PULSE_NEUTRAL = 1500;
const int PULSE_MAX = 2000;

Servo ch1, ch2, ch3;
bool armed = false;
int lastThrottle[3] = {0, 0, 0};
unsigned long lastCommandTime = 0;

String inputBuffer;

void setup() {
  Serial.begin(115200);

  ch1.attach(CH1_PIN);
  ch2.attach(CH2_PIN);
  ch3.attach(CH3_PIN);
  ch1.writeMicroseconds(PULSE_NEUTRAL);
  ch2.writeMicroseconds(PULSE_NEUTRAL);
  ch3.writeMicroseconds(PULSE_NEUTRAL);

  setupSensors();

  lastCommandTime = millis();
}

void loop() {
  readSerial();
  sampleSensors();

  if (armed && millis() - lastCommandTime > FAILSAFE_MS) {
    armed = false;
    setAllNeutral();
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
      setAllNeutral();
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

    int pulse = map(val, -100, 100, PULSE_MIN, PULSE_MAX);
    lastThrottle[channel - 1] = val;
    switch (channel) {
      case 1: ch1.writeMicroseconds(pulse); break;
      case 2: ch2.writeMicroseconds(pulse); break;
      case 3: ch3.writeMicroseconds(pulse); break;
    }
    Serial.print("SET:");
    Serial.print(channel);
    Serial.print(":");
    Serial.println(val);
    return;
  }

  Serial.println("ERR:UNKNOWN_CMD");
}

void setAllNeutral() {
  ch1.writeMicroseconds(PULSE_NEUTRAL);
  ch2.writeMicroseconds(PULSE_NEUTRAL);
  ch3.writeMicroseconds(PULSE_NEUTRAL);
  lastThrottle[0] = 0;
  lastThrottle[1] = 0;
  lastThrottle[2] = 0;
}
