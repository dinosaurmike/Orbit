# Orbit neck: wiring, flashing and first test

The neck is the Yahboom 2-DOF pan/tilt kit (metal servos, no control board) driven by an
ESP32 dev board. The phone talks to the ESP32 over Bluetooth; the ESP32 drives both servos.
No Arduino Uno, Raspberry Pi or Bluetooth pairing needed.

## Parts

| Part | Notes |
|---|---|
| Yahboom 2-DOF pan/tilt kit | Turn servo (bottom, 270°) + tilt servo (top, 180°) |
| ESP32 dev board (AYWHP, ESP32-WROOM) | Powered by its own USB-C cable |
| Servo power supply | **Check the voltage on the servo labels first.** Likely 6–7.4 V (unverified). Allow about 5 A. A 6 V 5 A supply is the safe choice if the label allows it |
| 1000 µF capacitor, 16 V or more | Across the servo supply, close to the servos. Smooths the current spikes that can reset the ESP32 |
| Female-female jumper wires | 3 for signals and ground |
| A DC barrel jack adapter or terminal block | To split the supply out to both servos |
| USB-C **data** cable | Some cables are charge-only and won't flash the board |

## Wiring

```
 Servo supply +  ───────┬──────────── turn servo red (+)
                        ├──────────── tilt servo red (+)
                      [1000 µF]  (long leg / unmarked side to +, stripe to −)
 Servo supply −  ───────┴──────┬───── turn servo brown/black (−)
                               ├───── tilt servo brown/black (−)
                               └───── ESP32 GND        ← shared ground (important)

 ESP32 pin 18 (D18 / G18) ─────────── turn servo signal (orange/yellow)
 ESP32 pin 19 (D19 / G19) ─────────── tilt servo signal (orange/yellow)
 ESP32 USB-C ──────────────────────── USB charger (or the PC while flashing)
```

* **Never power the servos from the ESP32's 3V3 or 5V/VIN pins.** They draw far more than
  the board can supply and will brown it out or damage it.
* The ESP32's 3.3 V signal is enough for these servos (standard PWM).
* Every ground must be connected together: supply −, both servo −, ESP32 GND.
* When the ESP32 starts, both servos jump to center. Keep fingers and the phone clear the
  first time, and mount the horns so center is roughly straight ahead.

## Flashing the ESP32 (one time, on a computer that lets you install software)

1. Install **Arduino IDE 2** from https://www.arduino.cc/en/software
2. Open it. **File → Preferences**, in *Additional boards manager URLs* paste:
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json` → OK.
3. **Tools → Board → Boards Manager**, search **esp32**, install
   **"esp32 by Espressif Systems"** (takes a few minutes).
4. Plug the ESP32 in with the USB-C data cable.
   * If no new COM port appears in **Tools → Port**, install the CP210x driver:
     https://www.silabs.com/developers/usb-to-uart-bridge-vcp-drivers → *Downloads* →
     *CP210x Windows Drivers*, unzip, right-click `silabser.inf` → Install. Unplug and replug.
5. **File → Open** → `orbit_neck/orbit_neck.ino` (keep it inside its `orbit_neck` folder).
6. **Tools → Board → esp32 → ESP32 Dev Module**, and **Tools → Port →** the new COM port.
7. Click **Upload** (→). If it sits on `Connecting.....`, hold the board's **BOOT** button
   * Tested 2026-10-30: some boards (e.g. CP2102 ones) never enter upload mode by themselves
     ("Wrong boot mode detected (0x13)"). Hold **BOOT** from clicking Upload until "Writing at…"
     appears, or: hold BOOT, tap EN, release BOOT, then Upload, and tap EN when it's done.
     "Hard resetting via RTS pin..." is the normal last line.
   * The neck won't appear in the phone's Bluetooth menu; that's normal (Orbit connects to it
     directly over Bluetooth LE, no pairing).
   until the percentage starts climbing.
8. Done when it says *Hard resetting via RTS pin*.

## Bench test (no phone yet)

1. With the servo supply on, open **Tools → Serial Monitor**, set **115200 baud** and
   **Newline**. Press the board's **EN** (reset) button: you should see
   `Orbit neck ready.`
2. Type `?` → it prints where both servos are.
3. Type `M 160 90` → the head turns a bit one way. `M 110 90` → the other way.
   `M 135 75` / `M 135 105` → tilts. `C` → back to center.
4. Check nothing hits anything at the extremes: `M 45 90`, `M 225 90`, `M 135 45`,
   `M 135 135`. If something would collide, narrow `PAN_MIN/MAX` and `TILT_MIN/MAX` at the
   top of the sketch and upload again.

If the ESP32 resets when the servos move (you see `Orbit neck ready.` again), the supply
is sagging: check the capacitor, the shared ground, and that the supply gives enough amps.

## Connecting the phone

1. Neck powered, ESP32 running (no pairing in Android's Bluetooth menu; the app does it).
2. In Orbit, long-press the eyes → **Device** → **Add a neck (optional)** → switch **Neck** on →
   allow *Nearby devices*.
3. The status should go *Looking for the neck… → Connecting… → Neck connected*.
4. Tap **Hold straight ahead**, then move the two *Straight ahead* sliders until the phone
   faces forward and level. Tap again to let go.
5. Sit in front of it and move to one side. The head should turn **toward** you. If it turns
   away (and keeps going), switch on **Flip turn**. Same for up/down with **Flip tilt**.
6. Try the move buttons (Nod, Shake head, Happy, Sad, Curious, Look away).
7. **Follow speed**, **Turn range** and the up/down limits tune how it moves.

## How it behaves

* The eyes lead, the head follows: small moves are just the eyes; once you're clearly off to
  one side, the head turns to face you.
* Nobody around: it looks about slowly every 6–15 s. Dozing: head low, drifting slowly.
* Privacy sleep (double-tap / "goodnight"): head turns away and down, so it's clearly not
  watching.
* Thinking: glances up and to the side. Talking: a small bob with its voice.
* Expressions: nod, shake head, perk up (happy), pull back (surprised), droop (sad), curious
  tilt, look away.
* After 3 s still, the turn servo relaxes to stop humming; the tilt servo keeps holding the
  phone's weight (change `RELAX_TILT` in the sketch to try relaxing it too).
* If the phone disconnects, the neck stays where it was and waits; the app reconnects by
  itself every few seconds.

## Updates over Bluetooth (firmware version 2 and later)

Version 2 can be updated from the phone, so the USB cable and the BOOT button are only needed
once more.

**Once, with the USB cable:**
1. In the Arduino IDE: **Tools → Partition Scheme → Minimal SPIFFS (1.9MB APP with OTA/190KB SPIFFS)**.
   (It leaves room for an update next to the running program. Always use this scheme from now on.)
2. Upload `orbit_neck.ino` as before (hold BOOT if it sits on `Connecting…`). The Serial Monitor
   should say `Orbit neck ready (firmware 2)`.

**Putting the update file inside the app** (each time the firmware changes):
1. Raise `FIRMWARE_VERSION` at the top of `orbit_neck.ino` (e.g. "2" → "3").
2. **Sketch → Export Compiled Binary.** Arduino puts the file in the sketch's folder:
   `orbit_neck\build\esp32.esp32.esp32\orbit_neck.ino.bin`.
3. Copy it to `Companion2\app\src\main\assets\` (make the `assets` folder if it isn't there) and
   rename it **`orbit_neck.bin`**.
4. Build and run the app as usual.

**Updating:** Orbit → Settings → Device → Neck shows "Neck firmware: version 2 · version 3 is ready
to install". Tap **Update the neck** (about a minute). The neck checks the file, restarts into it
(the head may jump to the middle), and reconnects by itself. If anything goes wrong it keeps its
old version.
