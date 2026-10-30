// Orbit neck: ESP32 firmware for a 2-servo pan/tilt neck.
//
// The phone (the Orbit app) connects over Bluetooth Low Energy and sends short text lines.
// The same lines can be typed into the Arduino IDE's Serial Monitor (115200 baud, "Newline")
// to test the neck without the phone:
//
//   M 135 90    move: turn servo to 135 degrees, tilt servo to 90 degrees
//   C           go to the center position
//   X           relax both servos (no signal; they go limp)
//   ?           print where everything is
//
// Moves are eased (no sudden jumps) and speed-limited. When the head has been still for a few
// seconds, the turn servo is relaxed to stop it humming; it wakes again on the next move.
//
// Updates: after this version has been flashed once over USB, newer versions can be sent from
// the phone over Bluetooth (Orbit offers it when its built-in copy is newer).
//
// Board: "ESP32 Dev Module". Needs the Espressif ESP32 boards package (Boards Manager:
// "esp32 by Espressif Systems"). Works with versions 2.x and 3.x of that package.
// Tools -> Partition Scheme: "Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)" (room for an
// update next to the running program). Use the same scheme when exporting the update file.

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <Update.h>
#include <Preferences.h>

// This firmware's version. Raise it for each new release; the app reads it from the update file
// (the tag below) and from the neck, and offers the update when the file's is higher.
#define FIRMWARE_VERSION "3"
const char *VERSION_TAG = "ORBIT_NECK_VERSION=" FIRMWARE_VERSION;

// ---------- Settings you may need to change ----------

// Which ESP32 pins the servo signal wires (usually orange or yellow) go to
const int PAN_PIN = 18;   // turn (left/right), the bottom servo
const int TILT_PIN = 19;  // tilt (up/down), the top servo

// How far each servo can turn, and the pulse widths for its two ends (microseconds).
// Typical for these servos, but check the listing or the servo's label: if the head turns
// less or more than the app says, adjust these.
const float PAN_SERVO_DEGREES = 270;   // the kit's turn servo is a 270-degree servo
const float TILT_SERVO_DEGREES = 180;  // the tilt servo is a 180-degree servo
const int PULSE_MIN_US = 500;
const int PULSE_MAX_US = 2500;

// Safety limits in servo degrees: it never goes past these, whatever it's told.
// Narrow them if the phone or cables would hit something.
const float PAN_MIN = 45, PAN_MAX = 225;
const float TILT_MIN = 45, TILT_MAX = 135;

// Where it starts when switched on
const float PAN_CENTER = 135;
const float TILT_CENTER = 90;

// Top speed (degrees per second) and how much of the remaining distance it closes each step
const float MAX_SPEED = 220;
const float EASING = 0.35;

// Gentle start: for a few seconds after switching on, and after the phone (re)connects, it moves
// slowly and eases more, so the first move to wherever the phone wants is soft, not a jolt.
// (A servo can't say where it is, so the neck also remembers where it last stood still and
// starts from there.)
const float GENTLE_SPEED = 35;
const float GENTLE_EASING = 0.06;
const unsigned long GENTLE_MS = 5000;
unsigned long gentleUntil = 0;

// Relax a servo after this long without moving, to stop it humming. The tilt servo carries
// the phone's weight, so it keeps holding by default (set to true to try relaxing it too).
const unsigned long RELAX_AFTER_MS = 3000;
const bool RELAX_PAN = true;
const bool RELAX_TILT = false;

// Bluetooth names (must match the app: Neck.kt)
#define SERVICE_UUID "4f726269-742d-4e65-636b-000000000001"
#define COMMAND_UUID "4f726269-742d-4e65-636b-000000000002"
#define VERSION_UUID "4f726269-742d-4e65-636b-000000000003"  // read: the firmware version
#define UPDATE_UUID "4f726269-742d-4e65-636b-000000000004"   // write: update pieces; read: how it went
#define DEVICE_NAME "Orbit-Neck"

// ---------- Servo driving ----------

const int PWM_FREQ = 50;   // standard servo signal: 50 pulses a second
const int PWM_BITS = 14;   // 0..16383 across the 20 ms between pulses
const int PAN_CHANNEL = 0, TILT_CHANNEL = 1;  // only used by the 2.x boards package

struct Joint {
  int pin;
  int channel;
  float degrees;     // the servo's full travel
  float minAngle, maxAngle;
  float target;      // where it's heading
  float current;     // where it is now (as far as we know)
  bool relaxed;
  bool relaxAllowed;
  unsigned long lastMoved;
};

Joint pan = {PAN_PIN, PAN_CHANNEL, PAN_SERVO_DEGREES, PAN_MIN, PAN_MAX, PAN_CENTER, PAN_CENTER, false, RELAX_PAN, 0};
Joint tilt = {TILT_PIN, TILT_CHANNEL, TILT_SERVO_DEGREES, TILT_MIN, TILT_MAX, TILT_CENTER, TILT_CENTER, false, RELAX_TILT, 0};

void pwmAttach(Joint &s) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcAttach(s.pin, PWM_FREQ, PWM_BITS);
#else
  ledcSetup(s.channel, PWM_FREQ, PWM_BITS);
  ledcAttachPin(s.pin, s.channel);
#endif
}

void pwmWrite(Joint &s, uint32_t duty) {
#if ESP_ARDUINO_VERSION_MAJOR >= 3
  ledcWrite(s.pin, duty);
#else
  ledcWrite(s.channel, duty);
#endif
}

// Sends the pulse for an angle (0 .. the servo's full travel)
void writeAngle(Joint &s, float angle) {
  float us = PULSE_MIN_US + (angle / s.degrees) * (PULSE_MAX_US - PULSE_MIN_US);
  uint32_t duty = (uint32_t)(us / 20000.0 * ((1 << PWM_BITS) - 1));
  pwmWrite(s, duty);
  s.relaxed = false;
}

void relax(Joint &s) {
  pwmWrite(s, 0);  // no pulses: the servo stops holding (and stops humming)
  s.relaxed = true;
}

void setTarget(Joint &s, float angle) {
  angle = constrain(angle, s.minAngle, s.maxAngle);
  if (fabs(angle - s.target) > 0.3) s.lastMoved = millis();
  s.target = angle;
}

// One step of easing toward the target, every 20 ms
void stepServo(Joint &s, float dt) {
  float distance = s.target - s.current;
  if (fabs(distance) > 0.2) {
    bool gentle = millis() < gentleUntil;
    float move = distance * (gentle ? GENTLE_EASING : EASING);
    float limit = (gentle ? GENTLE_SPEED : MAX_SPEED) * dt;
    s.current += constrain(move, -limit, limit);
    s.lastMoved = millis();
    writeAngle(s, s.current);
  } else if (s.relaxAllowed && !s.relaxed && millis() - s.lastMoved > RELAX_AFTER_MS) {
    relax(s);
  } else if (s.relaxed && !s.relaxAllowed) {
    writeAngle(s, s.current);  // relaxing was switched off with X earlier: hold again
  }
}

// ---------- Commands (from Bluetooth or the Serial Monitor) ----------

// Bluetooth writes arrive on another task; they're handed over through this buffer
portMUX_TYPE commandLock = portMUX_INITIALIZER_UNLOCKED;
char latestCommand[64] = "";
volatile bool commandWaiting = false;

void handleCommand(String line) {
  line.trim();
  if (line.length() == 0) return;
  char kind = toupper(line.charAt(0));
  if (kind == 'M') {
    float p, t;
    if (sscanf(line.c_str() + 1, "%f %f", &p, &t) == 2) {
      pan.relaxAllowed = RELAX_PAN;
      tilt.relaxAllowed = RELAX_TILT;
      setTarget(pan, p);
      setTarget(tilt, t);
    }
  } else if (kind == 'C') {
    setTarget(pan, PAN_CENTER);
    setTarget(tilt, TILT_CENTER);
  } else if (kind == 'X') {
    pan.relaxAllowed = true;
    tilt.relaxAllowed = true;
    relax(pan);
    relax(tilt);
  } else if (kind == '?') {
    Serial.printf("firmware %s | pan %.1f -> %.1f%s | tilt %.1f -> %.1f%s | %s\n", FIRMWARE_VERSION,
                  pan.current, pan.target, pan.relaxed ? " (relaxed)" : "",
                  tilt.current, tilt.target, tilt.relaxed ? " (relaxed)" : "",
                  BLEDevice::getServer()->getConnectedCount() > 0 ? "phone connected" : "no phone");
  }
}

class CommandCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    auto value = characteristic->getValue();  // String on 3.x, std::string on 2.x
    portENTER_CRITICAL(&commandLock);
    strncpy(latestCommand, value.c_str(), sizeof(latestCommand) - 1);
    latestCommand[sizeof(latestCommand) - 1] = 0;
    commandWaiting = true;
    portEXIT_CRITICAL(&commandLock);
  }
};

// ---------- Updates over Bluetooth ----------
//
// The phone writes to the update characteristic, each write starting with one byte:
//   1 + size (4 bytes, little-endian)   start an update of this many bytes
//   2 + data                            the next piece of the file
//   3                                   finished: check it, and restart into it
//   4                                   cancel
// Reading the characteristic says how it's going ("receiving", "done", "error: ...").

BLECharacteristic *updateCharacteristic = nullptr;
volatile bool updating = false;
volatile bool restartSoon = false;
unsigned long restartAt = 0;
size_t updateSize = 0;
size_t updateWritten = 0;

void setUpdateStatus(const String &text) {
  if (updateCharacteristic) updateCharacteristic->setValue(text.c_str());
  Serial.println("Update: " + text);
}

class UpdateCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *characteristic) override {
    uint8_t *data = characteristic->getData();
    size_t length = characteristic->getLength();
    if (length == 0) return;
    if (data[0] == 1 && length >= 5) {
      updateSize = (size_t)data[1] | ((size_t)data[2] << 8) | ((size_t)data[3] << 16) | ((size_t)data[4] << 24);
      updateWritten = 0;
      if (Update.isRunning()) Update.abort();
      updating = Update.begin(updateSize);
      setUpdateStatus(updating ? "receiving" : "error: not enough room (check the partition scheme)");
    } else if (data[0] == 2 && updating) {
      size_t piece = length - 1;
      if (Update.write(data + 1, piece) != piece) {
        updating = false;
        Update.abort();
        setUpdateStatus("error: couldn't write the update");
        return;
      }
      updateWritten += piece;
    } else if (data[0] == 3 && updating) {
      updating = false;
      if (updateWritten == updateSize && Update.end(true)) {
        setUpdateStatus("done");
        restartAt = millis() + 1500;  // time for the phone to read "done"
        restartSoon = true;
      } else {
        String why = Update.errorString();
        Update.abort();
        setUpdateStatus("error: " + why);
      }
    } else if (data[0] == 4) {
      if (Update.isRunning()) Update.abort();
      updating = false;
      setUpdateStatus("cancelled");
    }
  }
};

class ConnectionCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    Serial.println("Phone connected");
    gentleUntil = millis() + GENTLE_MS;  // its first moves after (re)connecting are soft
  }
  void onDisconnect(BLEServer *server) override {
    Serial.println("Phone disconnected; waiting for it to come back");
    BLEDevice::startAdvertising();  // let the phone find us again
  }
};

void setupBluetooth() {
  BLEDevice::init(DEVICE_NAME);
  BLEDevice::setMTU(517);  // big pieces, so updates are quick
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ConnectionCallbacks());
  BLEService *service = server->createService(SERVICE_UUID);
  BLECharacteristic *command = service->createCharacteristic(
      COMMAND_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  command->setCallbacks(new CommandCallbacks());
  BLECharacteristic *version = service->createCharacteristic(VERSION_UUID, BLECharacteristic::PROPERTY_READ);
  version->setValue(VERSION_TAG + strlen("ORBIT_NECK_VERSION="));
  updateCharacteristic = service->createCharacteristic(
      UPDATE_UUID, BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_READ);
  updateCharacteristic->setCallbacks(new UpdateCallbacks());
  updateCharacteristic->setValue("idle");
  service->start();

  // The service goes in the advertisement (the phone looks for it); the name goes in the
  // scan response, so both fit
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  BLEAdvertisementData advert;
  advert.setFlags(0x06);  // general discoverable, Bluetooth LE only
  advert.setCompleteServices(BLEUUID(SERVICE_UUID));
  BLEAdvertisementData scanResponse;
  scanResponse.setName(DEVICE_NAME);
  advertising->setAdvertisementData(advert);
  advertising->setScanResponseData(scanResponse);
  BLEDevice::startAdvertising();
}

// ---------- Main ----------

String serialLine;
unsigned long lastStep = 0;

// ---------- Remembering where it stood ----------
// Saved in the ESP32's own memory when the head has been still for 2 seconds (and moved since
// the last save), so after a power cut it starts where it actually is.
Preferences memory;
float savedPan = PAN_CENTER, savedTilt = TILT_CENTER;

void savePoseWhenStill() {
  unsigned long still = millis() - max(pan.lastMoved, tilt.lastMoved);
  if (still < 2000) return;
  if (fabs(pan.current - savedPan) < 1.0 && fabs(tilt.current - savedTilt) < 1.0) return;
  savedPan = pan.current;
  savedTilt = tilt.current;
  memory.putFloat("pan", savedPan);
  memory.putFloat("tilt", savedTilt);
}

void setup() {
  Serial.begin(115200);
  pwmAttach(pan);
  pwmAttach(tilt);
  // Start where it last stood still (so it doesn't jump), then drift gently to the middle
  memory.begin("orbit-neck", false);
  savedPan = constrain(memory.getFloat("pan", PAN_CENTER), PAN_MIN, PAN_MAX);
  savedTilt = constrain(memory.getFloat("tilt", TILT_CENTER), TILT_MIN, TILT_MAX);
  pan.current = savedPan;
  tilt.current = savedTilt;
  gentleUntil = millis() + GENTLE_MS;
  writeAngle(pan, pan.current);
  writeAngle(tilt, tilt.current);
  pan.lastMoved = tilt.lastMoved = millis();
  setupBluetooth();
  Serial.printf("Orbit neck ready (firmware %s). Type ? for status, or M 135 90 to move.\n", FIRMWARE_VERSION);
}

void loop() {
  // An update has been received and checked: restart into it
  if (restartSoon && millis() > restartAt) ESP.restart();

  // A command from the phone (ignored while an update is coming in)
  if (commandWaiting && !updating) {
    char copy[64];
    portENTER_CRITICAL(&commandLock);
    strcpy(copy, latestCommand);
    commandWaiting = false;
    portEXIT_CRITICAL(&commandLock);
    handleCommand(String(copy));
  }

  // A line typed in the Serial Monitor
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      handleCommand(serialLine);
      serialLine = "";
    } else if (serialLine.length() < 60) {
      serialLine += c;
    }
  }

  // Ease the servos along, 50 times a second
  unsigned long now = millis();
  if (now - lastStep >= 20) {
    float dt = (now - lastStep) / 1000.0;
    if (dt > 0.1) dt = 0.1;
    lastStep = now;
    stepServo(pan, dt);
    stepServo(tilt, dt);
    if (!updating) savePoseWhenStill();
  }
}
