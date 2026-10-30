# Orbit Desk (optional)

You don't need this: with its neck, Orbit simply turns to your screen and looks with its own
camera. Orbit Desk is an extra for **crisp text** (a real screenshot instead of a photo), or for
Orbits without a neck.

A tiny program for your computer that lets Orbit **look at your screen when you ask**:
"what do you think of this?", "can you help with this error?", "does this email sound OK?".

- Orbit only looks when you ask. It never watches your screen on its own.
- Every look is listed in the Orbit Desk window, and you can pause sharing any time.
- Everything stays on your home network until Orbit sends that one picture to Gemini with
  your question.

## Set up (once)

1. **Install Python 3** (free) from <https://www.python.org/downloads/>.
   On Windows, tick **"Add python.exe to PATH"** on the first installer screen.
2. Download this `desk` folder (or the whole Orbit project) to the computer.
3. **Start it:**
   - **Windows:** double-click `run_orbit_desk.bat`.
   - **Mac / Linux:** open a terminal in this folder and run
     `pip3 install -r requirements.txt` then `python3 orbit_desk.py`.
     (Mac: the first time, allow **Screen Recording** for Terminal in System Settings →
     Privacy & Security.)
4. **Windows Firewall** may ask whether Python can use the network: allow it on
   **Private networks** (your home Wi-Fi). Without this the phone can't reach it.
5. The window shows the computer's name, its address and a **6-digit pairing code**.
6. **On Orbit:** long-press the eyes → **Computer** tab → switch on
   **Look at my computer screen** → **Find my computer** → tap your computer → type the
   pairing code → **Test**. You should see **✓ Connected to (your computer)**.
7. Ask Orbit: *"What do you think of what's on my screen?"*

## While it runs

| Type in the window, then Enter | Does |
|---|---|
| `p` | Pause / resume sharing (Orbit gets "sharing is paused") |
| `q` | Quit |

Options: `--monitor 2` shares your second screen (`--monitor 0` = all screens together);
`--new-code` makes a new pairing code (Orbit will need it); `--port 47900` uses another port.

To start it automatically on Windows: press Win + R, type `shell:startup`, and put a shortcut
to `run_orbit_desk.bat` in the folder that opens.

## If Orbit can't find it

- The phone and computer must be on the **same Wi-Fi network** (guest networks usually keep
  devices apart).
- Check the firewall question above (Windows: Settings → Privacy & security → Windows Security →
  Firewall & network protection → Allow an app through firewall → Python → Private).
- Type the address shown in the window (like `192.168.1.23:47800`) into Orbit by hand.

## Privacy and security

- Pictures go only to a phone that knows the pairing code. After 5 wrong codes in a minute it
  refuses everyone for a minute.
- The pairing code is kept in `.orbit_desk.json` in your home folder. Delete it, or run with
  `--new-code`, to make a new one.
- On your home network the picture travels unencrypted (like most home gadgets). Don't run it
  on public or shared networks.
- **Work computers:** your employer's rules apply to sharing what's on your screen with an AI
  service. If in doubt, don't.

## How it works (for tinkerers)

Plain HTTP on port 47800, all `GET`, with the pairing code in an `X-Orbit-Code` header:

| Path | Answer |
|---|---|
| `/hello` | `{"app": "orbit-desk", "name": …, "version": 1}`, no code needed |
| `/check` | `200 {"ok": true, "name": …, "paused": …}`, or `401` (wrong code) / `429` (too many tries) |
| `/screen` | `200` JPEG (longest side 1600 px), or `401` / `423` (paused) / `429` / `500` |

Finding computers: Orbit broadcasts `ORBIT-DESK?` (UDP, port 47801); Orbit Desk replies
`ORBIT-DESK <port> <computer name>`. You can build your own Orbit Desk (say, a tray app) as
long as it answers the same way.
