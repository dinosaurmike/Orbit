# Contributing to Orbit

Thanks for wanting to make Orbit better! Expressions, habits, sounds, personalities, bodies,
translations, bug fixes and photos of your build are all welcome.

## Ground rules

- **Never commit an API key**, `local.properties`, a signing key (`*.jks`, `*.keystore`), or
  anything from `build/`.
- **Privacy first.** Orbit never sends camera, microphone or screen data anywhere unless the
  person asked (or switched on an opt-in feature that says so plainly). New features keep to that.
- **Character over features.** A small touch that makes Orbit feel alive beats a big feature
  that makes it feel like an app.
- **Write for people new to Android.** Comments say *why* in plain words. Settings text explains
  what something does and what it costs (battery, privacy, Gemini allowance).

## Getting set up

1. Install [Android Studio](https://developer.android.com/studio), open the project, let Gradle sync.
2. For testing, add `GEMINI_API_KEY=your_key` to `local.properties` (debug builds only).
3. Run on a real phone (USB debugging). The emulator works for eyes and settings but not
   really for camera tracking or audio.
4. Go through the relevant parts of [TEST_CHECKLIST.md](TEST_CHECKLIST.md) before a pull request.

## A tour of the code

All app code is in `app/src/main/java/com/example/companion2/`:

| File | What's in it |
|---|---|
| `MainActivity.kt` | Start-up, permissions, camera and face detection, movement noticing, sleep, doze, night, heat, reminders, greetings, nudges, first wake-up |
| `Face.kt` | The eyes' screen: moods, mood sounds, looking around, idle-move scheduler, start-up animation |
| `Expressions.kt` | **Every expression and idle move** (`MOVES`, `IDLE_MOVES`), as poses over time |
| `EyeDrawing.kt` | Drawing: classic and holographic eyes, scan lines, countdown ring, weather |
| `Sounds.kt` | Every sound, synthesized in code |
| `GeminiLive.kt` | The conversation: connection, instructions, tools (express, memory, look, reminders, screen…) |
| `Audio.kt` | Microphone and speaker, echo cancelling for interruptions |
| `WakeWord.kt` | Offline wake word (Vosk) |
| `InnerLife.kt` | Hobby homework and journal |
| `Neck.kt` | Where the head points, and the Bluetooth link to the neck |
| `DeskLink.kt` | Talking to Orbit Desk on a computer |
| `ScreenFinder.kt`, `GeminiText.kt` | Finding your screen; one-off Gemini text/photo questions |
| `Gestures.kt`, `People.kt`, `Models.kt` | Hand gestures and knowing who's who (MediaPipe, on the phone); model downloads |
| `DayCalendar.kt`, `MusicSense.kt` | Today's calendar; hearing the beat of music |
| `Weather.kt`, `Heat.kt` | Morning weather; phone temperature |
| `Settings.kt` / `SettingsScreen.kt` | Every option and the settings screen (side list of sections, shared look: `Choice`, `ChoiceRow`, `SwitchSetting`, `SettingTitle`) |
| `OrbitAudio.kt` | Built-in internet radio (SomaFM picks + Radio Browser) and soundscapes made on the phone |
| `SettingsPages.kt` | The five settings pages (Orbit, Eyes, Music, Privacy, Device) and `MoreOptions` groups |
| `MusicControl.kt`, `SpotifyConnect.kt` | Playing and controlling your music (Android media sessions; Spotify Web API with PKCE) |
| `Welcome.kt` | The first-time setup (every step skippable) |
| `BootStart.kt` | Appliance mode: opens Orbit when the phone turns on |
| `VoiceSamples.kt` | The voices, and hearing each one before choosing (Gemini text-to-speech, saved on the phone) |

Outside the app: `desk/` (Orbit Desk for computers), `neck/` (ESP32 firmware, wiring,
enclosure), `docs/`.

## Recipes

### Add an expression Gemini can use

1. In `Expressions.kt`, add an entry to `MOVES`. It gets `progress` (0 → 1 over the move) and a
   `Pose` to adjust: `grow`, `squint`, `glow`, `look`, `tilt`, `smile`, `wink`, `sideBoost`,
   `shakeX/Y`, `spark`, and `neckPan/neckTilt` for the neck.
   ```kotlin
   "proud" to { p, pose ->
       pose.grow = 1.1f
       pose.smile = 0.5f * swell(p)
       pose.neckTilt -= 6f * swell(p)   // chin up
   },
   ```
2. Add its name to `EXPRESSIONS` in `Settings.kt`, so Gemini can pick it.
3. Try it from the buttons in Device → Neck, or ask Orbit to "look proud".

### Add an idle habit

Add the move to `MOVES` as above, then an `IdleMove` to `IDLE_MOVES`: how long it lasts, how
often it's picked (`weight`), whether it needs someone there (`withPerson`), and any sounds at
points through it. Keep habits short and subtle; rare and delightful beats frequent and busy.

### Add a sound

Add a name to the `Sound` enum and a recipe in `Sounds.make()`: `tone()` for glides and bells,
`noise()` for breaths and whooshes. Keep it soft (amplitudes around 0.05–0.3), and add a
preview button in `SoundLifeSettings`.

### Add a personality

Add an entry to `PERSONALITIES` in `Settings.kt`. Keep it short, and keep replies short: it's a
desk friend, not an essay machine.

### Give Orbit a different body

Anything that speaks the [neck protocol](docs/NECK_PROTOCOL.md) over Bluetooth LE works: other
servos, a different pan/tilt, an animatronic, lights. The ESP32 sketch in `neck/orbit_neck/` is
the reference.

### Make another Orbit Desk

The protocol is in [desk/README.md](desk/README.md): a few HTTP endpoints and a UDP reply.
A tray app, a Mac menu-bar app, or a Linux daemon are all welcome.

## Pull requests

- One idea per pull request, with a short note on what it does and how you tested it (which phone).
- Run the unit tests first: `./gradlew testDebugUnitTest`. GitHub runs them on every pull request
  too. Added an expression or idle habit? `ExpressionsTest` checks it automatically (it exists, its
  numbers stay in range, the eyes stay on screen); new logic that doesn't need the phone should
  come with a test of its own in `app/src/test/`.
- Screenshots or a short video for anything visual or animated.
- New settings: a sensible default, help text in plain words, and an entry in `docs/PRIVACY.md`
  if it changes what leaves the phone.
- By contributing you agree your work is released under the project's MIT license.

## Sharing your build

Post photos, eye looks (Settings → Eyes → **Copy my look**), enclosure files and neck variants
in **Discussions → Show your Orbit**. Open-source enclosure designs (STL/STEP) are very welcome
in their own repos; link them and we'll list them in [docs/BUILD_YOUR_OWN.md](docs/BUILD_YOUR_OWN.md).
