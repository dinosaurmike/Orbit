# Privacy

Orbit is built so that you always know what leaves the phone, and nothing does without a
reason you'd expect. There's no Orbit company and no Orbit server: nothing is sent to the
people who made it.

## What stays on the phone

| What | Notes |
|---|---|
| Face tracking | ML Kit on the phone. Camera frames are never stored or sent |
| Noticing movement | A tiny 24 × 18 brightness grid compared frame to frame, on the phone |
| Wake word | Offline Vosk speech model on the phone; no sound leaves it for this |
| Smiles, hand gestures | Recognized on the phone (ML Kit, MediaPipe) from the camera picture; nothing is sent |
| Faces it knows (Know who's who, off by default) | A face "fingerprint" (a list of numbers, not a photo) per person, made and compared on the phone |
| Music | Only the beat is worked out, from loudness, on the phone; nothing is recorded |
| Your calendar (off by default) | Read on the phone with your permission |
| Watching for you while asleep | Ordinary sleep checks for a face once a second, on the phone, to wake when you come back (microphone off). **Privacy mode** switches the camera off too |
| Memory notes, your name, hobby, journal, conversation diary, reminders, settings | Stored on the phone |
| Your Gemini API key | Stored on the phone |

## What goes to Google (Gemini), and when

Gemini is Google's service, used with **your own** API key; Google's Gemini API terms apply.
**On the free tier, Google's terms let it use what you send to improve its products, and people
at Google may review it.** The paid tier doesn't. Check Google's current terms
(<https://ai.google.dev/gemini-api/terms>) and choose what suits you.

| What | When |
|---|---|
| Your voice | Only during a conversation (from your tap or wake word, or one Orbit starts: a greeting, a reminder, a nudge) until it ends |
| Memory notes, name, hobby, recent journal, the 20 newest diary lines, this week's moments, pending reminders, date and time; who's in front of it (by name) and today's calendar events, if those are on | At the start of each conversation, as background for Orbit |
| One camera photo | Only when you ask it to look ("what am I holding?"); or, only if you switch on **Notice things when I arrive**, one photo a day when it greets you |
| One photo or picture of your computer screen | Only when you ask it to look at your screen: a photo with its own camera after turning to the screen (with the neck), or, with the optional Orbit Desk, a screenshot |
| Its name, in a short hello line | Only when you tap a voice in settings to hear a sample (once per voice; the sample is then kept on the phone) |
| A hobby question | Once a day, a text request so it can learn about its hobby (no personal data) |
| A few photos of the room | Only when you ask it to **find your screen**: about 16 photos as it looks around, so Gemini can say which shows the screen |

**Conversation diary** (on unless you switch it off: Privacy → "Remember our conversations"):
during a conversation Gemini also returns a text copy of what you said, kept in memory only
until the conversation ends. Then that text is sent to Gemini once more, as one text request,
to write a single diary line (quick commands and small talk are skipped). The text copy is
thrown away; only the diary line is kept, on the phone (newest 300). See, edit or clear it
under Privacy → See what Orbit remembers.

## Other services

| Service | What's sent | When |
|---|---|---|
| alphacephei.com | A download request | Once, when you first turn on the wake word (~40 MB speech model) |
| Radio Browser (radio-browser.info) | The genre, mood or station name you asked for | Only when you ask for radio ("play some jazz") |
| SomaFM / radio stations | A normal stream request, like any radio app | While a station is playing |
| Open-Meteo (weather) | Your **town name** (as you typed or said it), then its rough coordinates | When you ask about the weather, the morning hello mentions it, or (if on) the morning weather in the eyes |

## On your own network

- **Orbit Desk** (optional) sends a screen picture from your computer to the phone over your
  home network, only to a phone with the pairing code, only when asked. The computer lists each
  look and can pause. It's not encrypted on the home network; don't use it on public networks.
- **The neck** (optional) receives head angles over Bluetooth. Nothing else.

## Music

Radio and soundscapes need no account. Soundscapes (rain, ocean…) are made on the phone and
send nothing anywhere.


To control your music apps, Orbit needs Android's **Notification access** (that's what Android
calls permission to see and control other apps' music). Orbit only uses it for music and never
reads your notifications. **Remember what I play** keeps a list of songs and artists on the phone
(Settings → Music → Forget what I've played). "Play…" searches are done by your music app.
## Appliance mode (off unless you switch it on)

"Start when the phone turns on" needs Android's **Display over other apps** permission; Orbit
uses it only to open itself after a restart. "Lock to Orbit" uses Android's own app pinning.
Neither sends anything anywhere.

## Your controls

- **Sleep:** double-tap, or say "goodnight". Microphone off; it only checks (on the phone) for your face to wake up.
- **Privacy mode:** say "privacy mode". Camera and microphone fully off until you double-tap.
- Settings → Privacy → See what Orbit remembers: see, edit or wipe notes, moments and the conversation diary; switch memory or the diary off.
- Settings → Privacy: switch off seeing. Settings → Orbit: the wake word, the greeting, and
  (under Talking) web search.
- Settings → Device → Looking at your computer screen: switch off screen looks or agent news. Orbit Desk: type `p` to pause, `q` to quit.
- Orbit opts out of Android's cloud backup, so its memory and your key aren't copied to your
  Google account. Uninstalling the app deletes everything it stored.
