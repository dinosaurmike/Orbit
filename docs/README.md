# Orbit

**An open-source AI desk companion. An old Android phone becomes its face.**

Orbit is a pair of glowing holographic eyes living on a phone on your desk. It watches you when
you're there, gets up to its own little things when you're not, and when you talk to it, it
talks back (Google's Gemini Live, with your own free key). It remembers you, has a hobby it
studies a bit each day, and grows into a friendship over weeks. Add a $70 pan/tilt neck and it
physically turns to look at you.

No subscription. No company servers. If this project vanished tomorrow, your Orbit would keep
working.

## What it does

**Feels alive**
- Holographic eyes (drifting cyan/magenta fringes, glow, rainbow sheen, CRT scan lines), or a
  plain classic style. All adjustable, with a live preview, and shareable as a text code.
- Follows your face (on the phone; camera images never leave it), with tiny eye flicks like real eyes.
- Moods you can read: listening, thinking, talking, dozing, asleep (breathing, floating z's).
- Little habits: slow blinks, stretches, sneezes, hiccups, a double-take when you come back,
  jumping at a loud noise, glancing at movement, chasing a firefly, daydreaming about its hobby.
- Its own sounds, synthesized in code: a whoosh at start-up, chirps, chimes, a sleepy sigh.

**Talks with you**
- Tap, or say **"Orbit"** / **"Orby"** (offline wake word). Interrupt it any time.
- Gemini picks expressions as it talks: happy arches, surprise, a nod, a wink…
- "What am I holding?" (camera), "what's on my desk?" (looks down), and "what do you think of
  this?" (it **turns its head to your computer screen** and looks; nothing to install on the
  computer, or use the optional [Orbit Desk](desk/README.md) for crisp text). Only when you ask.
- Reminders and timers, shown as a ring draining around one eye.
- Knows the time, can search the web, shows the morning weather in its eyes.

**Grows with you**
- Remembers you (notes on the phone you can see, edit or wipe).
- Has a hobby, does a little homework each day, keeps a journal, and tells you about it.
- Remembers when you met; celebrates a week, a month, 100 days.
- Gentle nudges: a stretch after two hours at the desk, bed when it's late.

**Looks after itself (and you)**
- Dozes when nobody's around (burn-in protection), rests when the phone is warm.
- **Sleep:** double-tap or say "goodnight"; it wakes by itself when it sees you come back.
  **Privacy mode:** say "privacy mode" and the camera and microphone switch fully off.

## What you need

- An Android phone or tablet, **Android 9 or newer**. An old one is perfect.
- A **free Gemini API key**: <https://aistudio.google.com/apikey>
- Optional: a [pan/tilt neck](neck/NECK_SETUP.md) (about $80 in parts), so it can turn to look
  at you and your screen.

## Install

1. Download the latest `.apk` from this repository's **Releases** page and open it on the phone
   (allow installing from your browser or files app when asked).
2. Open **Orbit**, allow the camera and microphone. It wakes up for the first time and says hello.
3. Settings open on the **Conversation** tab: **Get a free key** → sign in, copy → **Paste** → **Test**.
4. Tap **Back**, tap the screen, and talk.

Tip: run it plugged in, sideways (landscape), at about eye height.

| On the eyes | Does |
|---|---|
| **•** Tap | Start or end a conversation |
| **••** Double-tap | Sleep / wake (camera and microphone fully off while asleep) |
| **—** Long-press | Settings |

## Make your own

Orbit is meant to be built on, remixed, and given a body of your own:

- **[Build your own Orbit](docs/BUILD_YOUR_OWN.md)**: from phone-only (free) to neck and enclosure.
- **[Neck protocol](docs/NECK_PROTOCOL.md)**: any Bluetooth LE gadget that understands a few
  text commands can be Orbit's body.
- **[Contributing](CONTRIBUTING.md)**: add an expression, an idle habit, a sound, a personality.
- **Share your look:** Settings → Eyes → **Copy my look** gives a line of text anyone can paste.
- Show off your build in **Discussions → Show your Orbit**.

## Privacy

Face tracking, the wake word and memory stay on the phone. Your voice goes to Gemini only during
a conversation. Photos and screen pictures are taken only when you ask (or, if you switch it on,
one hello photo a day). Full details: [docs/PRIVACY.md](docs/PRIVACY.md).

## Building from source

1. Install **Android Studio** and open this project.
2. Optional, for testing: put `GEMINI_API_KEY=your_key` in `local.properties`. It's only built
   into *debug* builds, never release builds. **Never commit `local.properties`.**
3. Click **Run**.

See [CONTRIBUTING.md](CONTRIBUTING.md) for a tour of the code.

## Built with

[Gemini Live API](https://ai.google.dev/gemini-api/docs/live) ·
[ML Kit face detection](https://developers.google.com/ml-kit/vision/face-detection) ·
[CameraX](https://developer.android.com/media/camera/camerax) ·
[Vosk](https://alphacephei.com/vosk/) (Apache 2.0) · [OkHttp](https://square.github.io/okhttp/) ·
Jetpack Compose · [Open-Meteo](https://open-meteo.com/) · ESP32 / Arduino

## Who made it

Orbit was created by **Mike Warren** ([@dinosaurmike](https://github.com/dinosaurmike)) and is
open source, so it's also made by everyone who contributes. Thank you!

## License

MIT. See [LICENSE](LICENSE). Build it, change it, sell your neck kits; just keep the license notice.
