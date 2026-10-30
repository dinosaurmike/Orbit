# True appliance mode (advanced, coming later)

For a phone that will only ever be Orbit. Not available yet: this page describes how it will
work, so you can plan. Everyday users don't need it: **Settings → Device** (start when the
phone turns on, lock to Orbit) covers most of it.

## What it adds

Android lets one app be the "device owner" of a dedicated phone, like a shop display. As
device owner, Orbit could:

* pin itself **silently** (no "App pinned" message, ever)
* skip the lock screen after a restart (power on → straight into Orbit)
* be the home screen, so Home always returns to Orbit
* keep the screen on while plugged in and hide the status bar for good
* grant itself camera and microphone access without prompts
* start reliably at boot

## What it will take

1. **A phone with no accounts on it** when it's switched on: usually a factory reset, skipping
   every sign-in during setup. On Samsung phones, Secure Folder counts too. (You can sign in to
   Google again afterwards, e.g. for YouTube Music.)
2. **One command from a computer** with USB debugging on (the exact line will be here).
3. **To undo it:** a "Leave appliance mode" button in Orbit's settings, or a factory reset.

It is tied to the app's ID, so it should only be set up on a release build of Orbit.
