# Orbit

![Orbit: an open-source AI desk companion](docs/images/orbit-header.gif)

[![Android build](https://github.com/dinosaurmike/Orbit/actions/workflows/android.yml/badge.svg)](https://github.com/dinosaurmike/Orbit/actions/workflows/android.yml)
[![Latest release](https://img.shields.io/github/v/release/dinosaurmike/Orbit)](https://github.com/dinosaurmike/Orbit/releases/latest)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

**An open-source AI desk companion. An old Android phone becomes its face.**

Orbit is a pair of glowing holographic eyes living on a phone on your desk. It watches you when
you're there, gets up to its own little things when you're not, and when you talk to it, it
talks back (Google's Gemini Live, with your own free key). It remembers you, has a hobby it
studies a bit each day, and grows into a friendship over weeks. Add a $70 pan/tilt neck and it
physically turns to look at you.

No subscription. No company servers. If this project vanished tomorrow, your Orbit would keep
working.

[![Watch Orbit in action on YouTube](docs/images/orbit-video.jpg)](https://youtu.be/iJuS2ayV5mY)

## How it works

```mermaid
flowchart LR
  phone["<b>Android phone</b><br/>Kotlin app<br/>eyes · face tracking<br/>wake word · memory"]
  gem["<b>Gemini Live API</b><br/>speech in, voice out,<br/>tool calls"]
  esp["<b>ESP32 neck</b><br/>Bluetooth LE peripheral"]
  servo["<b>Pan / tilt servos</b>"]
  phone <-- "WebSocket: mic 16 kHz up, voice 24 kHz down" --> gem
  phone -- "BLE: M pan tilt, ~30/s" --> esp
  esp -- "eased PWM" --> servo
```

- **The phone does everything it can locally.** Face tracking (ML Kit), the wake word (Vosk), the eyes,
  memory, timers and reminders all run on the phone. Camera images never leave it unless you ask Orbit
  to look at something.
- **Gemini Live is the voice.** Each conversation is one WebSocket to the Gemini Live API: microphone
  audio streams up at 16 kHz and Orbit's voice streams back at 24 kHz, so you can interrupt it. Gemini
  calls the app's tools to change its expression, set timers and reminders, check the weather
  (Open-Meteo), search the web, take a photo or turn to look at your screen.
- **The neck is a tiny Bluetooth LE peripheral.** The phone turns your face position into servo angles
  and sends short text lines (`M <pan> <tilt>`, about 30 a second while moving). The ESP32 eases each
  move (35% of the gap every 20 ms, at most 220°/s) and drives two hobby servos. Any BLE gadget that
  speaks the [neck protocol](docs/NECK_PROTOCOL.md) can be Orbit's body.
- **No Orbit servers.** Your phone talks straight to Google with your own key; there is no backend to
  shut down.

## What it does

**Feels alive**
- Holographic eyes (drifting cyan/magenta fringes, glow, rainbow sheen, CRT scan lines), or a
  plain classic style. All adjustable, with a live preview, and shareable as a text code.
- Follows your face (on the phone; camera images never leave it), with tiny eye flicks like real eyes.
- Moods you can read: listening, thinking, talking, dozing, asleep (breathing, floating z's).
- Little habits: slow blinks, stretches, sneezes, hiccups, a double-take when you come back,
  jumping at a loud noise, glancing at movement, chasing a firefly, daydreaming about its hobby.
- Its own sounds, synthesized in code: a whoosh at start-up, chirps, chimes, a sleepy sigh.
- Smiles back when you smile; purrs when you stroke its eyes; waves back when you wave
  (and reacts to 👍 👎 ✌ 🤟, or goes quiet at a "shh" finger); bobs along to music.
- Birthday confetti, Christmas snow, a Halloween glow.
- The screen's edge glows a color when it matters: green for a timer, amber for a reminder,
  violet while you focus, red when it isn't listening. A clear "offline" sign when the internet's down.

**Talks with you**
- Tap, or say **"Orbit"** / **"Orby"** (offline wake word). Interrupt it any time.
- Gemini picks expressions as it talks: happy arches, surprise, a nod, a wink…
- "What am I holding?" (camera), "what's on my desk?" (looks down), and "what do you think of
  this?" (it **turns its head to your computer screen** and looks; nothing to install on the
  computer, or use the optional [Orbit Desk](desk/README.md) for crisp text). Only when you ask.
- Reminders and timers, shown as a ring draining around one eye; focus sessions ("help me focus
  for 25 minutes"); your calendar's day, with a heads-up ring before each event.
- Knows who's who: learns the faces of your household (on the phone) and greets each by name.
- "What did we do this week?": a little recap of your moments together.
- Weather when you ask ("will it rain later?", "what about Saturday?"): it says it once, with the
  high and low and roughly when, while its eyes zoom in to show an animated weather icon in one eye
  and the temperature in the other. It asks your town once and remembers it; °C or °F.
- Messages for your household: "tell Sarah dinner's ready", and it passes it on when it sees her
  (or she asks), then tells you she got it.
- Knows the time and can search the web.

**Plays your music**
- "Play some jazz", "something to focus to", "play rain": internet radio and its own soundscapes
  (rain, ocean, fire, brown noise, ambient…) work with no account at all.
- Specific songs and your playlists from your own music service (Spotify, YouTube Music, Amazon
  Music, Apple Music, Deezer, Tidal, Pandora, SoundCloud): sign in to its app on the phone and
  link it in three taps. "Skip", "who's this?", "stop the music in 30 minutes".
- Its eyes take on the album's colours, bob and sway to the beat, and half-close for slow songs.
- The music dips while you talk to it. Swipe across the eyes to skip, thumbs up to like.
- Need quiet fast? Tap it, hold up a hand, or cover its eyes: everything stops.

**Grows with you**
- Remembers you (notes on the phone you can see, edit or wipe), and keeps a one-line diary of
  your conversations, so it can pick up where you left off ("how did the build go?").
- Has a hobby, does a little homework each day, keeps a journal, and tells you about it.
- Remembers when you met; celebrates a week, a month, 100 days.
- Gentle nudges: a stretch after two hours at the desk, bed when it's late.

**Feels like a device, not an app**
- Appliance mode: starts when the phone turns on, stays pinned on screen, comes back by itself
  after a problem.
- Its own sound signature, a spinning comet in the corner while it thinks, eyes that lift with
  each syllable as it talks, and plain-spoken errors that retry by themselves.

**With a neck, it watches you like a dog would**
- Keeps your face centred when you're near, keeps a looser eye on you across the room, and glances
  away now and then. Loses you? It looks the way you went, turns toward movement, and scans the room.
- "Look to your left", "face me", "look over there and tell me what you see".
- Gentle, natural moves: a soft start, unhurried drifting, a bow when it falls asleep.
- Neck firmware updates over Bluetooth from the app, in one tap.

**Looks after itself (and you)**
- Dozes when nobody's around (burn-in protection), rests when the phone is warm.
- A live status page with check marks: internet, key, camera, neck, battery, temperature.
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
2. Open **Orbit** and allow the camera and microphone. It wakes up for the first time.
3. A short welcome setup walks you through a free Gemini key, its name and voice, and
   appliance mode (every step can be skipped).
4. Tap the eyes, or say "Hey Orby", and talk.

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
