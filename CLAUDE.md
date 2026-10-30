# Orbit: guide for contributors and AI coding assistants

Orbit is an open-source AI desk companion. An old Android phone is the face and the brain: it shows
animated holographic eyes, talks through Google's Gemini Live API, and can sit on a small pan/tilt
servo neck (two servos and an ESP32 over Bluetooth Low Energy) so it can look at you and act out
its moods. It is a friendly character and a natural way to talk to AI, not a task robot.

* Goal: a polished, working proof of concept, published as open source (MIT). Not a product.
* Budget: free to run on Gemini's free tier; paid tiers are optional.
* Created by Mike Warren ([dinosaurmike](https://github.com/dinosaurmike)).

## Ground rules

* **Never put a Gemini API key in source code or commit it.** It lives in `local.properties`
  (git-ignored) as `GEMINI_API_KEY`, compiled into **debug builds only**; release builds get an
  empty string and users paste their own key in Settings.
* The product is called **Orbit** everywhere people can see (nickname "Orby"). The Kotlin package
  `com.example.companion2`, the Gradle namespace and the SharedPreferences file
  `companion_settings` keep their original names: renaming them would lose users' settings.
* Prefer small, readable changes that match the surrounding code: plain-language comments that say
  *why*, settings through the `Settings` class, UI built from the shared settings components.
* Separate verified facts from assumptions, especially for hardware and third-party APIs. Mark
  anything not yet tested on a device in `TEST_CHECKLIST.md`.
* Soft, pleasant sounds only; nothing that stays static on screen for long (OLED burn-in).

## Repository layout

```
app/src/main/java/com/example/companion2/   the Android app (Kotlin, Jetpack Compose)
app/src/main/assets/orbit_neck.bin          neck firmware shipped inside the app (Bluetooth updates)
neck/orbit_neck/orbit_neck.ino              ESP32 neck firmware (Arduino)
neck/NECK_SETUP.md, neck/ENCLOSURE.md       parts, wiring, flashing, enclosure notes
desk/                                       Orbit Desk: optional Python helper for screen pictures
docs/                                       build guide, privacy, neck protocol, launch kit, appliance mode
TEST_CHECKLIST.md                           everything built but not yet tested on a device
```

### App source files

| File | What it holds |
|---|---|
| `MainActivity.kt` | Activity, permissions, camera + ML Kit face detection, sleep/doze, greetings, reminders loop, tool routing (`voiceCommand`, `musicTool`), message delivery |
| `Settings.kt` | Constants and the `Settings` class (every option, Compose-state backed, SharedPreferences) |
| `Face.kt` | `Mood`, `FaceTracker`, the `Face` composable (moods, expressions, looking, weather zoom view, hints) and the start-up `Intro` |
| `EyeDrawing.kt` | Eye drawing (holographic and classic), weather icons and tints, state border, music bars, small corner icons |
| `Expressions.kt` | Every expression and idle move as data (`Pose`, `MOVES`, `IDLE_MOVES`), read by both the eyes and the neck |
| `GeminiLive.kt` | The conversation: WebSocket, setup, system instructions, function tools, transcripts, diary, reconnects |
| `GeminiText.kt` | One-off Gemini text/photo requests (model picking, `ask`, `askAboutPhotos`) |
| `Audio.kt` | `Microphone` (16 kHz, echo cancelling) and `Speaker` (24 kHz, call mode for interrupting) |
| `WakeWord.kt` | Offline wake word with Vosk (model downloaded on first use) |
| `VoiceDirection.kt` | Stereo voice direction (GCC-PHAT); currently a test in Settings |
| `Neck.kt` | `Neck` (where the head points: watching, scanning, gestures) and `NeckLink` (BLE, firmware updates) |
| `Sounds.kt` | All sounds, synthesized at start-up |
| `Weather.kt` | Open-Meteo forecasts in words and in the eyes |
| `MusicControl.kt`, `OrbitAudio.kt`, `MusicSense.kt` | Music apps via media sessions, built-in radio + soundscapes, beat detection |
| `SpotifyConnect.kt` | Optional Spotify Web API control (dormant: no UI) |
| `People.kt`, `Gestures.kt`, `Models.kt` | On-device MediaPipe face fingerprints and hand gestures, model downloads |
| `InnerLife.kt` | Its hobby: a daily "homework" request and journal |
| `DayCalendar.kt` | Today's calendar events |
| `ScreenFinder.kt`, `DeskLink.kt` | Finding and looking at the computer screen (camera + neck, or Orbit Desk) |
| `Heat.kt` | Thermal watchdog |
| `SettingsScreen.kt`, `SettingsPages.kt` | Settings UI (Orbit, Eyes, Music, Privacy, Device), shared components, neck settings |
| `Welcome.kt`, `VoiceSamples.kt`, `BootStart.kt` | First-run setup, voice previews, start on boot |

## How it works

### Eyes and moods
* Two rings on a Compose `Canvas` (offscreen layer), landscape, full screen, keep-screen-on.
  Holographic style (white core, drifting cyan/magenta fringes, glow, rainbow sheen, glint, trail,
  optional CRT scan lines with whole-pixel spacing) or classic.
* `Mood`: IDLE, LISTENING, HEARING, THINKING, SPEAKING, DOZING, ASLEEP. A 30 Hz mood loop sets
  goals; a per-frame loop eases toward them with `easing(rate, frameMs)` so motion is identical at
  60 or 120 Hz. Looking follows a calm spring (`LOOK_SPRING`/`LOOK_DAMPING`).
* Sleeping and dozing eyes are closed, upward-arched slits; closing flattens the lids to a line and
  swaps to the arch at the flattest moment. A drowsy wind-down (slow blinks, a yawn) precedes dozing.
* Expressions and idle moves live in `Expressions.kt` (`MOVES`, `IDLE_MOVES`), including rare silly
  ones (glitch, blink game, dizzy, boing).
* Information in the eyes: a countdown ring for the next reminder; a coloured glow around the
  screen edge only when it means something (red not listening, violet focus, green timer, amber
  upcoming); the weather **zoom view** (both eyes grow and centre: an animated colour icon in one,
  the temperature with the red high and blue low in the other); corner icons for offline, thinking
  and music playing; tap hints stacked large on the left, fading after 6 s.

### Conversation
* Gemini Live over OkHttp WebSocket; key in the `x-goog-api-key` header. Audio replies, voices and
  personality presets from settings, captions from `outputAudioTranscription`.
* Interrupting uses speakerphone call mode so echo cancelling works. Context-window compression and
  session resumption; reconnects on `goAway`; transient errors retry automatically.
* Ends after a quiet spell (default 30 s). After starting music it closes itself so the music
  comes back to full volume (the music would otherwise keep the conversation open, ducked).
* Function tools (most answered in `GeminiLive.answerTools`): expressions, memory
  (`remember`, `forget`, `set_user_name`), `search_diary`, reminders/timers, focus sessions,
  moments, hobby, birthday, `look` (one photo, only when asked), screen looks, `find_screen` /
  `aim_screen`, `turn_head`, `check_view` (can it see you, from face tracking), messages
  (`leave_message`, `get_messages`), weather (`get_weather`, `set_location`), music
  (`play_music`, `play_radio`, `play_sounds`, `music`, `now_playing`, `music_sleep_timer`),
  volume, sleep, privacy mode, end conversation. Slow tools (weather, music) answer on their own
  thread.
* Memory stays on the phone: notes (80), moments (120), a conversation **diary** (one line per
  meaningful conversation, written by a single text request when it ends; newest 20 in the
  instructions, older ones via `search_diary`), messages for people, music history, learned
  faces. Cloud backup is disabled (`allowBackup="false"`).

### Neck (optional)
* `Neck.step(dt)` runs about 30×/s. Pose in degrees from straight ahead (+ pan = the way the eyes
  move right on screen, + tilt = down), sent as `M <pan> <tilt>` over BLE when it changes.
* **Watching, like a dog:** with a face in view it keeps the face centred (hysteresis dead zone
  `watchZone`; each new sighting corrects 80 % toward the head pose 150 ms earlier plus the face
  offset, to allow for camera delay). Near (face size > 7 % of the picture) it watches closely and
  glances away every 45–120 s; far away it watches loosely and looks about more.
* **Lost you:** waits 2.5 s, looks the way you went, glances at movement, then scans the room
  (11 s eased sweep) every 25–45 s (more often in a conversation, every ~2 min after 5 min away),
  doing its own thing in between. Its own moves are speed- and acceleration-limited (30°/s,
  90°/s²) so they drift rather than snap. "Face me" starts a search.
* Moods and expressions add gestures (nod, shake, perk up, droop, music bob and sway). Sleep bows
  the head; privacy mode turns it away and down.
* `NeckLink`: BLE central with service `4f726269-742d-4e65-636b-000000000001`; characteristics
  `…0002` commands (write without response), `…0003` firmware version (read), `…0004` updates.
  Requests MTU 517; one write in flight, newest move wins; reconnects by itself.

### Neck firmware (`neck/orbit_neck/orbit_neck.ino`)
* Arduino, esp32 core 2.x and 3.x. LEDC 50 Hz on GPIO 18 (pan, 270° servo) and 19 (tilt, 180°),
  500–2500 µs, hard limits, eased moves (max 220°/s), pan relaxes when still.
* Soft start: remembers where it last stood still (Preferences) and starts there; moves gently for
  5 s after power-on and after each phone connection.
* **Bluetooth updates:** the app carries the firmware in `assets/`, reads the version tag
  (`ORBIT_NECK_VERSION=<n>`) from the file and the neck, and offers a one-tap update (begin/data/end
  packets, `Update` library, restart). Partition scheme must be **Minimal SPIFFS (1.9MB APP with
  OTA)**. To release new firmware: raise `FIRMWARE_VERSION`, Sketch → Export Compiled Binary, copy
  `orbit_neck.ino.bin` into `app/src/main/assets/`.
* Serial commands at 115200: `M p t`, `C`, `X`, `?`.

### Privacy and power
* Face tracking, face fingerprints, gestures, beat detection and the wake word run on the phone.
  Audio goes to Gemini only during a conversation; photos only when asked (or the opt-in daily
  "notice things").
* Four modes: attentive, dozing (dim, drifting, burn-in protection), sleep (mic off, wakes when it
  sees you), privacy sleep (camera and mic fully off, wakes on double-tap).
* Heat watchdog dims and rests the phone when warm and sleeps when hot. Appliance mode: start on
  boot, pin on screen, come back after a crash. Settings → Device shows live status checks.

## Building

* Android Studio, Kotlin, Jetpack Compose, Gradle Kotlin DSL with a version catalog.
  compileSdk/targetSdk 37, minSdk 28.
* Key dependencies: CameraX, ML Kit face detection, OkHttp, Vosk + JNA (AARs), MediaPipe
  tasks-vision. R8 keep rules in `app/src/main/keepRules/rules.keep`.
* Put `GEMINI_API_KEY=<your key>` in `local.properties` for debug builds, or paste a key in the app.

## Design decisions

* Phone ↔ neck over **BLE**, not USB. The AI chooses from predefined gestures and tools, never raw
  motor commands.
* Local cues (you start or stop talking) drive listening/thinking looks immediately, without
  waiting for the AI.
* Face tracking stays on the device; photos go to the AI only when the person asks.
* Wake word: Vosk (any phrase, no training) rather than Porcupine or openWakeWord.
* The character is generic and friendly by default, remembers the person across days, and may
  start a conversation (greetings, reminders, messages) but can be told not to.

## Roadmap

* Turn toward a voice (the stereo test exists; wire it to the wake word if the phone supports it).
* Orbit-to-Orbit calls (WebRTC; "avatar mode" where one Orbit's eyes and neck mirror the other
  person, or video).
* Single-cord power for the neck base (USB-C PD or a laptop adapter → buck converters), enclosure
  and phone cradle.
* Physical volume knob and mute button on the neck's ESP32.
* Publishing: final applicationId, signing, a release APK and a GitHub Release.
