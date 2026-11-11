# Changelog

## 0.1.1 (unreleased)

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
  alone (no conversation, no tools). Settings → Computer.
- Looks at your computer screen with the back camera: Orbit faces you, so your screen is usually
  behind or beside it. The front camera stays on you (faces, gestures, "what am I holding?").
  "Find my screen" searches with the back camera too: face bowed down (so the back camera looks
  up, where screens usually are), then level, sweeping side to side; "a bit left" now steers the
  back of its head. Switch it off in Settings → Computer if your screen is in front of Orbit.
- Orbit Desk is its own setting now (it no longer needs screen looks switched on), with a choice
  of using it for screenshots, agent news, or both.
- Smaller download: the release APK only includes phone processors (ARM), not PC-emulator copies.
- Orbit Desk tests (23) run on Windows, macOS and Linux with Python 3.9 and 3.13.
- A fast ear for its own name: a small model trained just for "Orbit", "Orby" and "Or-bee"
  (openWakeWord's method, on about 10,000 synthetic voices plus sound-alikes like "orange", "Robbie"
  and "a bit"). It checks the last 1.3 seconds of sound every 80 ms, so it reacts the moment you
  finish the name instead of waiting for the speech recognizer (about 40 ms after the name ends, in
  tests). Caught 79% of names from 100 voices it never trained on, with one false wake in 5.3 hours
  of other people's conversations. Runs on the phone; other wake words still use the speech
  recognizer. Settings → Talk → "Fast ear for its name". Training scripts and results are in
  `tools/wakeword/`.
- Answers its name out loud: "Yes?", "Mm-hm?" or "I'm listening.", in its own voice, the moment it
  hears "Orbit" (recorded once per voice and kept on the phone; Settings → Talk can switch it off).
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
