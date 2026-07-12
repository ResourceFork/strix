/*
  Multi-channel serial controller for Arduino Nano
  - Up to 3 servo/ESC channels (throttle or steering)
  - 1 RGB LED output (PWM, common-cathode assumed)

  Wire protocol (newline-terminated ASCII lines):
    A:1\n            -- arm (required before T commands take effect)
    A:0\n            -- disarm (all channels forced neutral, LED off)
    T<ch>:<value>\n  -- set channel <ch> (1-3) to <value>, -100 to 100
                        e.g. "T1:50\n" sets channel 1 to 50% forward
    C:<r>,<g>,<b>\n  -- set RGB LED, each 0-255, e.g. "C:255,0,128\n"
    ?\n              -- ping, replies "OK:<armed>:<t1>:<t2>:<t3>\n"

  Failsafe: if no command arrives for FAILSAFE_MS, all channels go
  neutral and the LED turns off.

  ---- WIRING ----
  Servo/ESC channels (signal wire only -- share ground, do NOT power
  the Nano's 5V rail from the ESC/servo battery):
    Channel 1 signal -> D9
    Channel 2 signal -> D10
    Channel 3 signal -> D11
    All ESC/servo ground wires -> Nano GND (common ground is required)

  RGB LED (common-cathode assumed):
    Red leg    -> D3  -> 220-330ohm resistor -> LED red anode
    Green leg  -> D5  -> 220-330ohm resistor -> LED green anode
    Blue leg   -> D6  -> 220-330ohm resistor -> LED blue anode
    LED cathode (common, usually the longest pin) -> Nano GND

  If your LED is common-ANODE instead, wire the common leg to 5V
  instead of GND, and set INVERT_LED below to true (common-anode
  LEDs light up on LOW, so PWM values need to be inverted).
*/

#include <Servo.h>

const int CH1_PIN = 9;
const int CH2_PIN = 10;
const int CH3_PIN = 11;

const int LED_R_PIN = 3;
const int LED_G_PIN = 5;
const int LED_B_PIN = 6;
const bool INVERT_LED = false; // set true for common-anode RGB LEDs

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

  pinMode(LED_R_PIN, OUTPUT);
  pinMode(LED_G_PIN, OUTPUT);
  pinMode(LED_B_PIN, OUTPUT);
  setLed(0, 0, 0);

  lastCommandTime = millis();
}

void loop() {
  readSerial();

  if (armed && millis() - lastCommandTime > FAILSAFE_MS) {
    armed = false;
    setAllNeutral();
    setLed(0, 0, 0);
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
      setAllNeutral();
      setLed(0, 0, 0);
    }
    Serial.println(armed ? "ARMED" : "DISARMED");
    return;
  }

  if (line.startsWith("C:")) {
    lastCommandTime = millis();
    int firstComma = line.indexOf(',');
    int secondComma = line.indexOf(',', firstComma + 1);
    if (firstComma == -1 || secondComma == -1) {
      Serial.println("ERR:BAD_COLOR");
      return;
    }
    int r = constrain(line.substring(2, firstComma).toInt(), 0, 255);
    int g = constrain(line.substring(firstComma + 1, secondComma).toInt(), 0, 255);
    int b = constrain(line.substring(secondComma + 1).toInt(), 0, 255);
    setLed(r, g, b);
    Serial.print("LED:");
    Serial.print(r); Serial.print(",");
    Serial.print(g); Serial.print(",");
    Serial.println(b);
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

void setLed(int r, int g, int b) {
  if (INVERT_LED) {
    r = 255 - r;
    g = 255 - g;
    b = 255 - b;
  }
  analogWrite(LED_R_PIN, r);
  analogWrite(LED_G_PIN, g);
  analogWrite(LED_B_PIN, b);
}
