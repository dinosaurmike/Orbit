# Build your own Orbit

Start free with a phone, and add the rest when you want to. Every level is complete on its own.

## Level 1: Phone only (free)

- Any Android phone or tablet, **Android 9+**, with a front camera. Old is fine; OLED screens
  (true black) look best, because the eyes then float in darkness.
- A stand that holds it **sideways** at about eye height, and a charger. It runs plugged in.
- A free Gemini API key. Install the app (see the [README](../README.md)) and you're done.

Phone care: it's plugged in all day. Turn on your phone's battery protection (Samsung:
"Protect battery", others: "Adaptive charging" or an 80 % limit) and now and then check the
battery isn't swelling (lay the phone flat; it shouldn't rock).

## Level 2: A neck (about $80)

So it physically turns to look at you, nods, droops, looks down at your desk, and **turns to
your computer screen** when you ask "what do you think of this?" (show it where the screen is:
Settings → Device → Neck → Your screen). Nothing to install on the computer.

| Part | Notes |
|---|---|
| Pan/tilt kit with standard PWM servos | Reference: Yahboom 2-DOF metal pan/tilt (270° pan + 180° tilt). Any two hobby servos strong enough for your phone work; set their travel at the top of the sketch |
| ESP32 dev board | Bluetooth LE built in; drives both servos directly |
| Servo power supply | Matching your servos' voltage, ~5 A. **Not** from the ESP32's pins |
| 1000 µF capacitor, jumper wires | Smoothing, connections |

Wiring, flashing and calibration: [neck/NECK_SETUP.md](../neck/NECK_SETUP.md).

Other bodies are welcome: anything that understands the [neck protocol](NECK_PROTOCOL.md)
(a few text commands over Bluetooth LE) can be Orbit's body.

## Level 3: An enclosure

Make it look like a device, not a phone on a bracket: a matte shell, a dark front so only the
eyes glow, the cable tidied away. Design notes (front options vs. touch, balancing the tilt
axis, heat, cables): [neck/ENCLOSURE.md](../neck/ENCLOSURE.md).

## Optional: Orbit Desk

For crisp screen text, or to see your screen without a neck, run
[Orbit Desk](../desk/README.md) on your computer and pair it in **Settings → Device → Looking at your computer screen**.

## Make it yours

- **Name, voice and personality:** Settings → Orbit. **Memory:** Settings → Privacy.
- **The eyes:** Settings → Eyes. Share your look with **Copy my look**, and try others' with
  **Paste a look**.
- **Habits and sounds:** Settings → Orbit → Sounds and habits. Want new ones? See [CONTRIBUTING.md](../CONTRIBUTING.md).

## Community builds

Show yours in **Discussions → Show your Orbit**: photos, eye looks, enclosure files, other bodies.
Open-source designs get listed here.

*(No community builds listed yet. Be the first!)*
