# Changelog

## 0.1.2 (2026-11-12)

- Fixed: 0.1.1 stopped every time it opened. GLaDOS mode checked its setting before the settings
  were loaded. A new test now checks that start-up never uses a part before it's ready.
- If Orbit ever stops unexpectedly, Settings → Device shows "Orbit stopped unexpectedly" with a
  Copy report button (where in the code it stopped; nothing about you), even for a crash while
  starting.

## 0.1.1 (2026-11-12)

- GLaDOS mode (Settings → Device): hang Orbit upside down from a shelf or the ceiling. The eyes and
  cameras turn the right way up, the neck's turn and tilt swap to match, and Find my screen looks
  down first (hanging, your screen is below it). See neck/NECK_SETUP.md.
- Orbit Desk: agents can send any language and emoji. On Windows, a message with an emoji
  ("Build ✅") could stop the MCP and hook commands, or crash the window's log line; text from
  agents is now read as UTF-8, and anything the window can't show prints as "?". Saving the
  settings file is also safe when two phones pair at once, and retries if Windows briefly has the
  file open.
- "What just happened?": Settings → Privacy → Recent conversations, in detail. The last 10
  conversations: why each one started (its name, a tap, an arrival hello), what it heard and said,
  which tools it used and with what, photos it looked at, and how it ended. Kept on the phone;
  copy one to share when something odd happens.
- Connect early: when you start talking while facing it, it connects to Google straight away, so
  "Orby, …" gets an answer sooner. Nothing is sent unless you say its name; otherwise the
  connection closes after 4 seconds (at most one early connection every 20 seconds).
- Teach it your voice (Settings → Orbit): say its name 10 times and it learns how sure its ear
  should be for you (never below a safe floor). Shows "before: 6 of 10, now: 10 of 10".
- Update notices: once a day it checks GitHub for a newer version and shows a card in Settings →
  Device.
- Your Gemini key is now sealed with Android's Keystore (moved automatically).
- One-step releases: GitHub Actions → Release → type the version. It raises the version number,
  builds and signs the APK, and publishes the release with notes from this changelog.
- Android Lint now fails the build on any warning; the existing warnings are fixed.
- Orbit Desk is now paired once and encrypted. Before, the pairing code went over your Wi-Fi with
  every request, and screen pictures and agent news went unencrypted. Now Orbit pairs once (tap
  **Pair**; a key exchange checked with the code), then signs each request and the computer
  encrypts what it sends back (AES-256-GCM). Copied requests and seen codes are useless to anyone
  else on the network. Pair on a home network you trust: someone actively intercepting at the
  moment you pair could still work out the code. Orbit Desk needs the `cryptography` add-on
  (`requirements.txt`); the agent commands don't. Update both: an older phone or computer says
  what to update.
- Fixed: it once announced "I only speak English unless prompted" when someone walked in. The
  arrival hello opens a conversation, a noise sounded like another language, and Gemini said its
  rule out loud. Its rules are now silent (it never mentions them or explains them unless you ask
  how it works), it only answers speech clearly said to it (not footsteps, a TV, or people talking
  among themselves), and after an arrival hello it stays quiet until you speak to it.
- Smooth wake-up: hearing its name no longer makes the eyes stutter. Two things used to happen in
  the same instant: the phone switched into call mode on the thread that draws the eyes (a slow
  system call that froze them for a moment), and a quick "wake" flash fought the switch to the
  listening look. Now call mode switches on the sound thread, the eyes stay calm while it
  connects, then glide from idle into listening over half a second (slow start, soft landing),
  with the head lifting slightly in step. A tap still makes the eyes attentive straight away.
- Say it all in one go: "Orby, what time is it?" From the moment it hears its name, everything
  you say is kept and passed to Gemini as soon as it's connected (before, the first second or so
  was thrown away). It only says "Yes?" if you pause after its name; if you carry straight on, it
  just listens. No greeting either: it answers what you asked. The eyes still react the instant
  it hears its name. (Tested: the fast ear still catches 95% of names said with a request
  straight after, about 66 ms after the name ends.)
- Quick replies are "Yes?", "Mm-hm?" and "I'm listening." ("What's up?" and "Hey!" sounded like
  a greeting cutting in.)
- Fixed: it sometimes said its own expression out loud ("happy face") after making one. Its
  instructions now say expressions are silent body language, and that tools work silently.
- Fixed: Orbit could describe things it hadn't seen (asked "what's on my desk?", it made things
  up). Photos were sent to Gemini as live video, a separate stream with no guarantee it arrives
  before Gemini answers, so Gemini sometimes answered without the photo. Now each photo goes into
  the conversation itself, in order, before Gemini is told the look is done. Looks also use a real
  photo from the camera instead of a small 640×480 face-tracking frame.
- Truthful, always: Orbit's instructions now put honesty above being helpful or playful. It never
  guesses, invents or fills in gaps; when it can't see, can't read or doesn't know, it says so,
  warmly ("I can't quite tell. Can you give me some clues?"). It says a quick "let me have a
  look…" while it takes a photo, so you know something's happening.
  Every photo and screenshot comes with the same rule, and if a look fails it's told to say it
  can't see rather than describe anything. "Find my screen" only picks a photo that clearly shows
  a screen.
- Works with your coding agents. When Antigravity, Cursor, Codex, Claude Code or any script on the
  computer finishes, gets stuck or needs your decision, Orbit looks at you and says so ("Codex is
  done: all tests pass"). Away from your desk? It tells you when it sees you back; a blue glow
  means an agent is waiting on you. Agent-agnostic: Orbit Desk is now an MCP server
  (`orbit_notify`, `orbit_status`, `orbit_express`), a hook for agents that have hooks, a command
  (`orbit_desk.py notify done "…"`) and a local web endpoint. Agents can only reach it from the
  computer itself, and only send short news and reactions. Orbit says the news with Gemini's voice
  alone (no conversation, no tools). Settings → Device → Looking at your computer screen.
- Looks at your computer screen with the back camera: Orbit faces you, so your screen is usually
  behind or beside it. The front camera stays on you (faces, gestures, "what am I holding?").
  "Find my screen" searches with the back camera too: face bowed down (so the back camera looks
  up, where screens usually are), then level, sweeping side to side; "a bit left" now steers the
  back of its head. Switch it off in Settings → Device → Looking at your computer screen if your screen is in front of Orbit.
- Orbit Desk is its own setting now (it no longer needs screen looks switched on), with a choice
  of using it for screenshots, agent news, or both.
- Smaller download: the release APK only includes phone processors (ARM), not PC-emulator copies.
- Orbit Desk tests (23) run on Windows, macOS and Linux with Python 3.9 and 3.13.
- Behaviour bench (`tools/behavior`): runs Orbit's real instructions against the real Gemini Live
  API several times per scenario (a black desk photo, a drawn desk photo, tool words said out
  loud, background talk after the arrival hello, mumbling) and reports pass rates, so instruction
  fixes can be checked ("passed 15/15"). Runs nightly on GitHub once the `GEMINI_API_KEY` secret
  is added; its own tests use a fake Gemini and need no key.
- A fast ear for its own name: a small model trained just for "Orbit", "Orby" and "Or-bee"
  (openWakeWord's method, on about 10,000 synthetic voices plus sound-alikes like "orange", "Robbie"
  and "a bit"). It checks the last 1.3 seconds of sound every 80 ms, so it reacts the moment you
  finish the name instead of waiting for the speech recognizer (about 40 ms after the name ends, in
  tests). Caught 79% of names from 100 voices it never trained on, with one false wake in 5.3 hours
  of other people's conversations. Runs on the phone; other wake words still use the speech
  recognizer. Settings → Orbit → "Fast ear for its name". Training scripts and results are in
  `tools/wakeword/`.
- Answers its name out loud: "Yes?", "Mm-hm?" or "I'm listening.", in its own voice, the moment it
  hears "Orbit" (recorded once per voice and kept on the phone; Settings → Orbit can switch it off).
- Quicker to react to its name: the eyes perk up the moment you start talking to it (while it can
  see you), then give a quick, bright "yes?" look once it's sure it heard its name. No more "thinking"
  spinner while it connects: it's already listening.
- The quick "Yes?" now plays through the conversation's own speaker: on some phones the switch to
  call mode muted it.
- Settings show whether the quick replies are recorded (or why not); a failed try waits 10 minutes.
- Speaks English unless you ask for another language (it sometimes switched on its own when it
  misheard).
- Unit tests for music beat detection and for every expression and idle move, run with Android
  Lint and a secret scan on every push (GitHub Actions).
- Fixed: a faint dark box could show across the top of the eyes. The CRT scan lines are now drawn
  line by line at exact pixel positions instead of as one repeating gradient, which some phone GPUs
  draw in low precision.
- Fixed: during a conversation, a loud noise (a fan, a clatter, the neck's own motors) made Orbit
  "think": the eyes and head swung up and to the right for a few seconds, over and over, and the
  conversation never timed out. Now it only waits for a reply once Gemini has heard actual words.
- Fixed: with the neck, the head hunted back and forth during conversations. It remembered where
  the head pointed without its gestures (bobbing as it talks, glancing up while it thinks), so each
  gesture looked like you'd moved. It now remembers the real pose, and ignores pictures taken while
  the head is swinging fast.
- Calmer conversations: the eyes only glance up to "think" when Gemini takes more than about a
  second to answer, and the glance is smaller. Quick replies keep the eyes on you instead of flicking
  up and back on every turn. The head no longer joins in; it stays on you.
- Homework that fails (for example when Gemini's free daily limit is used up) now waits three hours
  before trying again, instead of retrying every 30 seconds.

## 0.1.0 (2026-10-31, first public release)

First release of Orbit: holographic eyes that follow you, Gemini Live voice conversation with
interruptions, offline wake word ("Orbit" / "Orby"), expressions, memory, a hobby and journal,
friendship milestones, little habits and idle moves, synthesized sounds, reminders and timers
with a countdown ring, morning weather in the eyes, looking at things / your desk / your
computer screen (Orbit Desk) when asked, privacy sleep, dozing, night schedule, heat watchdog,
shareable eye looks, and an optional ESP32 pan/tilt neck.

Also in this release: a simpler five-page settings screen with live status checks; built-in
internet radio and soundscapes plus control of your own music apps; the weather in words and as a
zoomed-in view in the eyes (any day of the week); a conversation diary; messages for people in the
home; "can you see me?" answered from face tracking; calmer eyes with a natural wind-down into
dozing and sleep; rare silly idle moves; and for the neck: dog-like watching and room scans, voice
head moves ("look left", "face me"), a soft start, and firmware updates over Bluetooth.
