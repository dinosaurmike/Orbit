# Neck protocol

How the Orbit app talks to its body. Anything that speaks this over Bluetooth LE can be a body:
the reference ESP32 pan/tilt neck (`neck/orbit_neck/`), other servos, an animatronic, lights.

## Bluetooth LE

| | |
|---|---|
| Role | The body is a BLE **peripheral** (server); the phone connects to it |
| Service UUID | `4f726269-742d-4e65-636b-000000000001` (advertise it, so the phone can find you) |
| Command characteristic | `4f726269-742d-4e65-636b-000000000002`, **write without response** (write also accepted) |
| Name | Anything; the reference uses `Orbit-Neck` (in the scan response) |

The phone scans for the service UUID, remembers the first body it finds, and reconnects to it
automatically. No pairing in Android's Bluetooth settings is needed. Settings → Neck →
**Find the neck again** forgets it and scans again.

## Commands

Short ASCII lines, each ending in `\n`, one per write (at most 20 bytes, the default BLE size).
The reference firmware accepts the same lines over USB serial (115200 baud) for bench testing.

| Command | Meaning |
|---|---|
| `M <pan> <tilt>` | Move to these **servo angles** in degrees, e.g. `M 135.0 90.0` |
| `C` | Go to the center position |
| `X` | Relax both servos (stop holding) |
| `?` | Print status (serial only) |

About `M`:

- The phone sends it **only when the pose changes**, plus **once a second** as a "still here".
  During smooth moves expect about 30 per second.
- Values already include the user's center trims and flips (set in Device → Neck), and stay
  within the ranges set there, so a body can simply go where it's told. It should still enforce
  its own safe limits.
- The phone smooths the motion; a little extra easing on the body (the reference closes 35 % of
  the gap every 20 ms, max 220 °/s) makes it look natural and hides Bluetooth jitter.

## What the poses mean

The app works in degrees from straight ahead (+ turn = toward the side the eyes look when they
look right on screen; + tilt = down), then adds the center trims: by default the turn servo
centers at 135° (a 270° servo) and the tilt servo at 90°. Typical moves:

| Moment | Head does |
|---|---|
| Following you | turns to keep your face near the middle of the camera |
| Thinking | glances up and to the side |
| Nod / shake head | quick tilt / turn waves |
| Happy, surprised, startled | perks up / pulls back |
| Sad | droops |
| Looking at your desk / screen | tilts right down / turns toward the computer |
| Idle | looks about slowly, chases a firefly, peeks around |
| Privacy sleep | turns away and down |

## Ideas for other bodies

- **Different servos:** change the pin numbers, travel and pulse widths at the top of the
  reference sketch.
- **Lights instead of motion:** map pan/tilt to colour or brightness.
- **A bigger robot:** treat the angles as head angles for your own controller.

If you build one, share it in **Discussions → Show your Orbit**.
