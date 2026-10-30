# Orbit launch kit

Everything needed to show Orbit off at release: images, a 30-second demo video, store text,
and release notes.

## Images (ready)

| File | Size | Use |
|---|---|---|
| `docs/images/orbit-header.png` | 1280 × 640 | Top of the README, and GitHub's social preview (repo **Settings → General → Social preview → Edit → Upload an image**) |
| `docs/images/play-feature-graphic.png` | 1024 × 500 | Google Play "feature graphic" (if it ever goes on Play) |

To change them: edit `docs/images/source/eyes.html`, then in that folder run
`node render.js ..` (needs Node and Playwright).

## Screenshots (take these on the phone)

Take them with the phone's own screenshot (Samsung: Power + Volume down), sideways, at night
mode off, with **Hints at the bottom** on.

1. **The eyes, watching you** (sit in front of it; plain dark background behind)
2. **Happy arches** (say "make a happy face")
3. **Listening, with captions on** (Settings → Conversation → Captions)
4. **A timer running**: green screen edge + countdown ring ("set a 3 minute timer")
5. **Asleep** with the floating z's (double-tap)
6. **Settings**: the Eyes page with the live preview
7. **Settings**: Conversation → Voice cards
8. **Welcome setup**: the first page (Memory → "Replay its first wake-up" won't show it; use a fresh install or clear the app's storage)

## 30-second demo video

Film the real thing, sideways, in a dim room so the eyes glow. A phone on a tripod is plenty.
No music needed: Orbit's own sounds are the soundtrack.

| Time | Shot | What happens |
|---|---|---|
| 0–3 s | Black screen, phone on the desk | Tap: the moon orbits the planet and settles into two eyes (start-up whoosh + signature) |
| 3–7 s | Over your shoulder | You sit down; the eyes find you, do a double-take, smile |
| 7–14 s | Side on, you and Orbit | "Hey Orby, what am I holding?" → it looks, answers |
| 14–19 s | Close on the eyes | "Set a 2 minute timer" → green screen edge, ring starts draining |
| 19–23 s | Close on the eyes | You wave → it waves back; thumbs up → happy |
| 23–27 s | Wide | You leave; it glances around, a firefly drifts past, it dozes |
| 27–30 s | Black, text | **Orbit**. Open source. Your old phone, reborn. github.com/dinosaurmike/Orbit |

With the neck: swap the 23–27 s shot for the head turning to follow you across the room.

## Store and listing text

**Name (30 characters max):** Orbit: AI desk companion

**Short description (80 max):** Your old phone becomes a friendly AI companion with eyes that follow you.

**Full description:**

> Orbit turns a spare Android phone into a little companion for your desk. Two glowing
> holographic eyes watch you, react to you and get up to their own small habits. Tap them or
> say "Hey Orby" to talk: Orbit answers in a natural voice, remembers you over time, sets
> timers and reminders, looks at what you're holding, and even has a hobby of its own.
>
> * Eyes that follow your face (on the phone; camera images never leave it)
> * Natural voice conversations with Google's Gemini, using your own free key
> * Remembers you, greets you, celebrates milestones
> * Timers, reminders, focus sessions, your calendar's day
> * Smiles back, waves back, purrs when you stroke its eyes
> * Sleeps when you leave, wakes when you come back; privacy mode switches everything off
> * Optional $70 pan/tilt neck so it physically turns to look at you
>
> Free and open source (MIT). No account, no subscription, no company servers.

**Category:** Lifestyle (or Entertainment). **Content rating:** Everyone.
**Privacy policy link:** the PRIVACY.md page on GitHub.

## GitHub release notes (first release)

> **Orbit 1.0: hello!**
>
> The first public release of Orbit, an open-source AI desk companion for an old Android phone.
>
> **Install:** download `Orbit-1.0.apk` below, open it on the phone, and follow the welcome setup.
> You'll need a free Gemini API key (the setup shows you how).
>
> **Optional:** `orbit-desk.zip` (lets it see your computer screen) and the neck firmware in `neck/`.
>
> Made by Mike Warren (@dinosaurmike). Share your build in Discussions → Show your Orbit!

## A post to share it

> I turned my old phone into a desk companion. 👀 Orbit has holographic eyes that follow me,
> talks with Gemini, remembers things, sets timers, and waves back. It's free and open source:
> github.com/dinosaurmike/Orbit
