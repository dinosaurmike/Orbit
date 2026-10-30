# Orbit enclosure: design brief

Goal: it should look like a dedicated device with glowing eyes, not a phone on a bracket.

## The trick: true black + a dark front

The S21's OLED screen shows **true black** (black pixels are switched off). Orbit draws on a
pure black background, so behind a dark front only the eyes show; the screen edges and
bezels disappear.

## Phone (verify with calipers before cutting)

| | Galaxy S21 (5G) |
|---|---|
| Body | about 151.7 × 71.2 × 7.9 mm, about 170 g |
| Screen | 6.2", flat, 2400 × 1080 |
| Front camera | punch-hole centered on the short edge (on the left or right side in landscape) |
| Ports | USB-C on the bottom short edge, which needs cable clearance |

## Front: three options

| Option | Look | Touch (tap / double-tap / long-press) | Camera |
|---|---|---|---|
| **A. Matte black bezel mask** (printed or cut frame over the phone's edges, open over the screen) | Good. Hides the phone shape | Works normally | Clear |
| **B. Smoked acrylic** (2–3 mm, grey/black tint, about 30–50 % light through) | Best: the "black glass" look | **Won't work through 2–3 mm acrylic** | Needs a hole or clear window (tint and reflections blur face tracking) |
| **C. Thin smoked film** (0.2–0.5 mm tinted PET or screen-protector film on the glass) | Very good | Usually works; turn on Settings → Display → Touch sensitivity | Cut a small hole for the lens |

Suggestion: **C**, or **B** with a clear 6–8 mm hole for the camera. With B, use the wake
word and voice commands ("goodnight", "that's all"), and remove the front to reach settings.
(Ask Claude to add a no-touch way into settings if you go with B.)

Keep the front **matte or anti-glare** if you can; gloss reflects the room and spoils the
floating-eyes effect.

## Cradle on the neck

* **Balance it.** Put the tilt axis through the phone's (plus cradle's) center of mass. Then
  the tilt servo barely has to hold anything: less hum, less heat, and the firmware can relax
  the tilt servo too (`RELAX_TILT = true`).
* Mount to the Yahboom top plate's M3 holes (Yahboom publishes 3D models on GitHub; take the
  hole pattern from those).
* Leave room for the USB-C cable at the bottom, with a gentle bend and a strain relief so the
  cable doesn't fight the pan/tilt movement. Route it down through or behind the neck.
* Let the phone breathe: it runs warm all day. Leave the back open or slotted; don't seal it
  in. (Orbit dims and rests when warm, and sleeps if hot.)
* Make sure the moving parts can't pinch the cable at full turn and tilt (test with
  `M 45 90`, `M 225 90`, `M 135 45`, `M 135 135` from the Serial Monitor).

## Base

* Hide the ESP32, the capacitor and the servo-power wiring inside the base. A matte shell
  over the Yahboom base plate works. Only one power lead should go to the wall, if you can
  manage it: for example one 12 V adapter (about 5 A) feeding two buck converters, one set to
  5 V for the phone's USB charger and ESP32, one set to the servo voltage (check the label
  first).
* A little weight in the base stops it creeping when the head turns quickly.
