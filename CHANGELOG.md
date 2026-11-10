# Changelog

## 0.1.1 (unreleased)

- Answers its name out loud: "Yes?", "Mm-hm?" or "I'm listening.", in its own voice, the moment it
  hears "Orbit" (recorded once per voice and kept on the phone; Settings → Talk can switch it off).
- Quicker to react to its name: the eyes perk up the moment you start talking to it (while it can
  see you), then give a quick, bright "yes?" look once it's sure it heard its name. No more "thinking"
  spinner while it connects: it's already listening.
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
