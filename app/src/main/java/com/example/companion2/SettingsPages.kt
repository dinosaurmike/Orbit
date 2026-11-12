package com.example.companion2

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------------- The five settings pages ----------------
//
// Each page starts with the few things most people change, then "More…" groups that open for
// everything else. Talking to Orbit changes many of these too ("don't greet me", "louder").

// A group of finer settings that starts closed, so each page stays simple
@Composable
fun MoreOptions(label: String, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(startOpen) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF15181F))
            .clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink, modifier = Modifier.weight(1f))
        Text(if (open) "Hide ▴" else "Show ▾", color = Muted, fontSize = 14.sp)
    }
    if (open) {
        Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// A highlighted card with a title and a few lines
@Composable
fun InfoCard(title: String, text: String, content: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Accent.copy(alpha = 0.08f))
            .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Text(text, fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
        content()
    }
}

// ---- Orbit: who it is ----

@Composable
fun OrbitPage(
    settings: Settings,
    mic: Microphone,
    gemini: GeminiLive,
    wake: WakeWord,
    life: InnerLife,
    sounds: Sounds,
    weather: Weather,
    gestures: Gestures,
    onHatch: () -> Unit,
    onTryMove: (IdleMove) -> Unit
) {
    val name = settings.companionName.ifBlank { "Orbit" }
    if (!gemini.hasKey()) {
        InfoCard("No Gemini key yet", "$name needs a free key to talk. Add it on the Device page.")
    }

    SettingTitle("Name", "What it calls itself.")
    OutlinedTextField(
        value = settings.companionName,
        onValueChange = { settings.companionName = it },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    SettingTitle("Voice", "Tap a voice to hear it.")
    VoicePicker(settings, gemini)

    SettingTitle("Personality", "How it behaves and talks.")
    ChoiceRow {
        PERSONALITIES.forEach { (label, text) ->
            Choice(label, settings.personality == text) { settings.personality = text }
        }
    }

    val lively = settings.liveliness
    SettingTitle(
        "Liveliness: " + when {
            lively < 0.08f -> "Still"
            lively < 0.4f -> "Calm"
            lively < 0.75f -> "Balanced"
            else -> "Lively"
        },
        "How much $name does by itself: glances, little habits, yawns, fireflies. All the way left, it just watches."
    )
    Slider(
        value = lively,
        onValueChange = {
            settings.liveliness = it
            settings.habits = it >= 0.08f
        },
        valueRange = 0f..1f
    )

    SwitchSetting(
        "Talk by saying \"Orby\"",
        "Listens for its name, on the phone only, with a small model trained just for its name " +
            "(other wake words download a free 40 MB speech model the first time). Or just tap the eyes.",
        settings.wakeWordOn
    ) {
        settings.wakeWordOn = it
        if (it) wake.prepare()
    }
    if (settings.wakeWordOn) {
        SwitchSetting(
            "Fast ear for its name",
            "A small model trained just for \"Orbit\", \"Orby\" and \"Or-bee\": it reacts the moment " +
                "you finish saying the name. Turn off to use the slower speech recognizer instead " +
                "(needed for other wake words).",
            settings.fastWake
        ) {
            settings.fastWake = it
            wake.prepare()
        }
        if (settings.fastWake) LearnMyVoice(settings, wake, name)
        SwitchSetting(
            "Connect early",
            "When you start talking to $name while facing it, it connects to Google straight away, so " +
                "if you're saying its name, it's ready sooner. Nothing you say is sent unless you say its name.",
            settings.connectEarly
        ) { settings.connectEarly = it }
        SwitchSetting(
            "Answer \"Yes?\" when called",
            "Says a quick \"Yes?\" or \"Mm-hm?\" in its own voice the moment it hears its name. Recorded " +
                "once in your chosen voice and kept on the phone.",
            settings.wakeReply
        ) { settings.wakeReply = it }
        WakeWordCheck(wake)
    }

    SwitchSetting(
        "Say hello when I arrive",
        "When it sees you after you've been away. This opens a short conversation.",
        settings.greet
    ) { settings.greet = it }

    // Reminders and timers set by voice, with a way to cancel each
    val pending = settings.reminderList()
    if (pending.isNotEmpty()) {
        SettingTitle("Reminders and timers", "Set by voice. When one is due, $name chimes and tells you.")
        val clock = java.time.format.DateTimeFormatter.ofPattern("EEE h:mm a")
        pending.forEach { reminder ->
            val time = java.time.Instant.ofEpochMilli(reminder.first).atZone(java.time.ZoneId.systemDefault())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${clock.format(time)}   ${reminder.second}", color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = { settings.removeReminder(reminder) }) { Text("Cancel") }
            }
        }
    }

    MoreOptions("Talking") {
        SettingTitle("Personality, in words", "Change the words if you like. Takes effect from the next conversation.")
        OutlinedTextField(
            value = settings.personality,
            onValueChange = { settings.personality = it },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
        SwitchSetting(
            "Let me interrupt",
            "Talk over $name mid-sentence. Conversations play like a speakerphone call so it can " +
                "cancel its own echo. Turn off if it keeps interrupting itself.",
            settings.allowInterrupt
        ) { settings.allowInterrupt = it }
        SwitchSetting(
            "Natural conversation",
            "Replies that suit how you sound, and staying quiet when you're talking to someone else. " +
                "Switches itself off if your key doesn't support it.",
            settings.naturalTalk
        ) { settings.naturalTalk = it }
        SwitchSetting("Web search", "Lets it look up current things like news and scores.", settings.webSearch) {
            settings.webSearch = it
        }
        SwitchSetting("Expressions", "Shows feelings with its eyes while it talks.", settings.expressions) {
            settings.expressions = it
        }
        SwitchSetting("Captions", "Shows what it says as text while it talks.", settings.captions) {
            settings.captions = it
        }
        SwitchSetting(
            "Hints at the bottom",
            "The small fading text like •  talk   ••  sleep   —  settings.",
            settings.showHints
        ) { settings.showHints = it }
        if (settings.greet) {
            SettingTitle("Say hello after I've been away: ${settings.greetAfter.toInt()} minutes", "")
            Slider(value = settings.greetAfter, onValueChange = { settings.greetAfter = it }, valueRange = 2f..60f)
        }
        SettingTitle(
            "End after quiet: ${settings.quietTimeout.toInt()} seconds",
            "A conversation ends by itself after this long with nobody talking, to save your free allowance."
        )
        Slider(value = settings.quietTimeout, onValueChange = { settings.quietTimeout = it }, valueRange = 10f..120f)
        SettingTitle(
            "Voice detection",
            "The bar is the microphone; the white line is how loud counts as talking. Move it up if " +
                "background noise counts, down if it misses your voice."
        )
        LevelMeter(mic, settings.voiceThreshold)
        Slider(value = settings.voiceThreshold, onValueChange = { settings.voiceThreshold = it }, valueRange = 0.02f..0.5f)
        if (settings.wakeWordOn) {
            SettingTitle("Wake words", "Separate them with commas, like \"orbit, orby\".")
            OutlinedTextField(
                value = settings.wakePhrase,
                onValueChange = { settings.wakePhrase = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    MoreOptions("Weather") { WeatherSettings(settings, weather) }

    MoreOptions("Sounds and habits") {
        SoundsAndHabits(settings, sounds, weather, gestures, onTryMove)
    }

    MoreOptions("Its own life") {
        OwnLife(settings, life, onHatch)
    }
}

// The live wake-word check: is sound reaching it, and what does it hear?
@Composable
private fun WakeWordCheck(wake: WakeWord) {
    var wakeStatus by remember { mutableStateOf(wake.status) }
    var wakeCheck by remember { mutableStateOf("") }
    var replyStatus by remember { mutableStateOf(VoiceSamples.replyStatus) }
    LaunchedEffect(Unit) {
        while (true) {
            wakeStatus = wake.status
            replyStatus = VoiceSamples.replyStatus
            val now = System.currentTimeMillis()
            wakeCheck = when {
                now - wake.lastWakeAt < 3000 -> "✓ Heard it!"
                !wakeStatus.startsWith("Ready") -> ""
                now - wake.lastSoundAt > 2000 -> "No sound is reaching it (is the microphone allowed?)"
                wake.lastHeard.isEmpty() -> "Listening… try saying it now"
                else -> "Last heard: \"${wake.lastHeard}\""
            }
            delay(300)
        }
    }
    Text(wakeStatus, color = Accent, fontSize = 14.sp)
    if (wakeCheck.isNotEmpty()) Text(wakeCheck, color = Muted, fontSize = 14.sp)
    if (replyStatus.isNotEmpty()) Text(replyStatus, color = Muted, fontSize = 14.sp)
}

@Composable
private fun SoundsAndHabits(
    settings: Settings,
    sounds: Sounds,
    weather: Weather,
    gestures: Gestures,
    onTryMove: (IdleMove) -> Unit
) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var gestureStatus by remember { mutableStateOf(gestures.status) }
    LaunchedEffect(Unit) {
        while (true) {
            gestureStatus = gestures.status
            delay(300)
        }
    }

    SwitchSetting(
        "Sounds",
        "$name's own soft sounds: start-up, waking, listening, sleeping, reminders.",
        settings.soundsOn
    ) { settings.soundsOn = it }
    if (settings.soundsOn) {
        SettingTitle("Sound volume", "")
        Slider(value = settings.soundVolume, onValueChange = { settings.soundVolume = it }, valueRange = 0.05f..1f)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, sound) in listOf(
                "Start-up" to Sound.WHOOSH, "Wake" to Sound.CHIRP, "Listen" to Sound.LISTEN,
                "Talk ends" to Sound.END, "Reminder" to Sound.CHIME, "Timer" to Sound.TIMER,
                "Sleep" to Sound.SIGH, "Yawn" to Sound.YAWN, "Purr" to Sound.PURR
            )) {
                OutlinedButton(onClick = { sounds.play(sound) }) { Text(label) }
            }
        }
    }
    Text("Try a habit (it plays on the eyes):", color = Muted, fontSize = 14.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (move in IDLE_MOVES) {
            OutlinedButton(onClick = { onTryMove(move) }) { Text(move.name.replace('_', ' ')) }
        }
    }
    SwitchSetting("Smile back", "When you smile at it, it smiles back.", settings.smileBack) { settings.smileBack = it }
    SwitchSetting("Petting", "Stroke across its eyes and it purrs.", settings.petting) { settings.petting = it }
    SwitchSetting(
        "Hand gestures",
        "Wave, 👍, 👎, ✌ and 🤟 get a reaction; a finger to your lips (\"shh\") quiets it. Recognized " +
            "on the phone (an 8 MB model downloads the first time).",
        settings.gesturesOn
    ) { on ->
        settings.gesturesOn = on
        if (on) gestures.prepare()
    }
    if (settings.gesturesOn) Text(gestureStatus, color = Accent, fontSize = 14.sp)
    SwitchSetting(
        "Gentle nudges",
        "A stretch after two hours at the desk, and bed after 11 at night (once).",
        settings.nudges
    ) { settings.nudges = it }
}

@Composable
private fun WeatherSettings(settings: Settings, weather: Weather) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var town by remember { mutableStateOf(settings.weatherTown) }
    var result by remember { mutableStateOf("") }
    var weatherStatus by remember { mutableStateOf(weather.status) }
    LaunchedEffect(Unit) {
        while (true) {
            weatherStatus = weather.status
            delay(300)
        }
    }
    SettingTitle(
        "Your town",
        "Ask $name \"what's the weather?\" and it tells you (and shows it in its eyes). It asks for your " +
            "town the first time, or type it here. Only the town is sent, to Open-Meteo (free, no account)."
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = town,
            onValueChange = { town = it },
            singleLine = true,
            placeholder = { Text("e.g. Portland, Maine") },
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = {
            result = "Looking…"
            kotlin.concurrent.thread { result = weather.setTown(town).substringBefore(". Use") }
        }, enabled = town.isNotBlank()) { Text("Save") }
    }
    if (result.isNotEmpty()) Text(result, color = Accent, fontSize = 14.sp)
    SettingTitle("Units", "")
    ChoiceRow {
        Choice("°C", settings.tempUnit == "C") { settings.tempUnit = "C" }
        Choice("°F", settings.tempUnit == "F") { settings.tempUnit = "F" }
    }
    SwitchSetting(
        "Mention it in the morning",
        "The first hello of the day includes the weather, in one sentence.",
        settings.weatherHello
    ) { settings.weatherHello = it }
    SwitchSetting(
        "Show it in the eyes each morning",
        "The first time it sees you each morning, its eyes show today's weather for a few seconds.",
        settings.weatherOn
    ) { settings.weatherOn = it }
    if (settings.weatherTown.isNotBlank()) {
        OutlinedButton(onClick = { weather.show() }) { Text("Show it now") }
        if (weatherStatus.isNotEmpty()) Text("$weatherStatus  (tap Back to see it)", color = Muted, fontSize = 14.sp)
    }
}

@Composable
private fun OwnLife(settings: Settings, life: InnerLife, onHatch: () -> Unit) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var lifeStatus by remember { mutableStateOf(life.status) }
    LaunchedEffect(Unit) {
        while (true) {
            lifeStatus = life.status
            delay(300)
        }
    }
    SwitchSetting(
        "A hobby of its own",
        "$name studies a hobby a little each day (using Gemini with web search) and keeps a journal. " +
            "It tells you about it when it fits. Suggest a new hobby any time.",
        settings.innerLifeOn
    ) { settings.innerLifeOn = it }
    if (settings.innerLifeOn) {
        SettingTitle("Hobby", "Leave empty and it picks one itself.")
        OutlinedTextField(
            value = settings.hobby,
            onValueChange = { settings.hobby = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SettingTitle("Journal", "What it has learned, newest at the bottom.")
        OutlinedTextField(
            value = settings.journal,
            onValueChange = { settings.journal = it },
            minLines = 4,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { life.doHomework() }) { Text("Do homework now") }
            Text(lifeStatus, color = Muted, fontSize = 14.sp)
        }
    }
    val days = settings.daysKnown()
    SettingTitle(
        "Your friendship",
        if (days == null) "$name hasn't met you properly yet."
        else "You met on ${settings.firstMet} ($days day${if (days == 1L) "" else "s"} ago)."
    )
    OutlinedButton(onClick = onHatch) { Text("Replay its first wake-up") }
}

// ---- Eyes: looks, then fine-tuning ----

val EYE_LOOKS = listOf("Holographic", "Classic", "Neon", "Minimal", "Retro")

@Composable
fun EyesPage(settings: Settings) {
    var look by remember { mutableStateOf("") }
    SettingTitle("Looks", "Pick one; the preview shows it. Fine-tune it below.")
    ChoiceRow {
        EYE_LOOKS.forEach { name ->
            Choice(name, look == name) {
                look = name
                settings.applyEyePreset(name)
            }
        }
    }
    MoreOptions("Customize") { EyeSettings(settings) }
}

// ---- Music ----

@Composable
fun MusicPage(settings: Settings, player: MusicControl) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var access by remember { mutableStateOf(player.hasAccess()) }
    var nowOn by remember { mutableStateOf(player.describe()) }
    var installed by remember { mutableStateOf(player.installedApps()) }
    LaunchedEffect(Unit) {
        while (true) {
            access = player.hasAccess()
            nowOn = player.describe()
            installed = player.installedApps()  // in case one was just installed
            delay(1000)
        }
    }
    val linked = access && installed.isNotEmpty()

    InfoCard(
        "Just ask",
        "\"Play some jazz\", \"something to focus to\", \"play rain\". Radio and $name's own sounds work " +
            "with no account. To stop: tap $name, hold up a hand, or cover its eyes."
    ) {
        Text(nowOn, color = Accent, fontSize = 14.sp)
    }

    // Your own music service, for specific songs and your playlists
    MoreOptions(
        if (linked) "Music accounts  ·  ✓ ${installed.joinToString()}" else "Link music accounts",
        startOpen = !linked
    ) {
        Text(
            "For specific songs and your own playlists, $name uses your music app on this phone, " +
                "signed in to your account. Three steps, once:",
            color = Muted, fontSize = 14.sp, lineHeight = 20.sp
        )
        Text("1.  Tap your music service below. Sign in when it opens (or install it first).", color = Ink, fontSize = 15.sp)
        ChoiceRow {
            MUSIC_APPS.filter { it.third != null }.forEach { (_, label, pkg) ->
                val has = label in installed
                Choice(if (has) "$label ✓" else label, has) { player.openOrGet(pkg!!) }
            }
        }
        Text("2.  Play any song in it for a moment, so $name can find it.", color = Ink, fontSize = 15.sp)
        Text("3.  Tap the button, turn on $name in the list, then come back here.", color = Ink, fontSize = 15.sp)
        if (access) {
            Text("✓ Done. Ask for any song, artist or playlist.", color = Accent, fontSize = 15.sp)
        } else {
            Button(onClick = { player.askAccess() }) { Text("Let $name control music") }
        }
        Text(
            "(Android calls step 3 \"Notification access\". $name only uses it for music; it never " +
                "reads your notifications.)",
            color = Muted, fontSize = 13.sp
        )
        if (installed.size > 1) {
            SettingTitle("Use for songs", "Which of your apps \"play…\" uses.")
            ChoiceRow {
                Choice("Any", settings.musicApp == "") { settings.musicApp = "" }
                MUSIC_APPS.filter { it.second in installed }.forEach { (key, label, _) ->
                    Choice(label, settings.musicApp == key) { settings.musicApp = key }
                }
            }
        }
    }

    MoreOptions("More music options") {
        SwitchSetting("Album colours in the eyes", "The eyes take on the music's colour.", settings.musicColors) {
            settings.musicColors = it
        }
        SwitchSetting("Show the song's name", "Fades in at the bottom when a song starts.", settings.musicTitle) {
            settings.musicTitle = it
        }
        SwitchSetting("Quieter while we talk", "The music drops right down while you talk to $name.", settings.musicDuck) {
            settings.musicDuck = it
        }
        SwitchSetting(
            "Music gestures",
            "Swipe across the eyes to skip (right to left = next), hold up a hand to pause, 👍 to like.",
            settings.musicGestures
        ) { settings.musicGestures = it }
        SwitchSetting(
            "Dance",
            "Bobs to the beat of any music it hears (and sways, with the neck).",
            settings.musicOn
        ) {
            settings.musicOn = it
            settings.musicDance = it
        }
        SwitchSetting(
            "React to songs",
            "A happy look when a favourite comes on; rarely, a word about it mid-conversation.",
            settings.musicReact
        ) { settings.musicReact = it }
        SwitchSetting(
            "Remember what I play",
            "Kept on the phone, so it knows what you like and what you focus to.",
            settings.musicMemory
        ) { settings.musicMemory = it }
        if (settings.musicLog.isNotBlank()) TextButton(onClick = { player.forget() }) { Text("Forget what I've played") }
    }
}

// ---- Privacy: what it sees and remembers ----

@Composable
fun PrivacyPage(settings: Settings, people: People, calendar: DayCalendar) {
    val name = settings.companionName.ifBlank { "Orbit" }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        settings.calendarOn = ok
    }
    var confirmWipe by remember { mutableStateOf(false) }

    val chosen = settings.privacyPreset()
    SettingTitle("How much $name notices", "One choice sets the details below. Fine-tune them any time.")
    ChoiceRow {
        listOf("Private", "Balanced", "Social").forEach { preset ->
            Choice(preset, chosen == preset) {
                settings.applyPrivacyPreset(preset)
                if (settings.peopleOn) people.prepare()
            }
        }
    }
    Text(
        when (chosen) {
            "Private" -> "Never takes photos, doesn't greet you, doesn't learn faces. Talking still works when you tap."
            "Balanced" -> "Looks only when you ask, says hello when you arrive, remembers what you tell it."
            "Social" -> "Also glances at you once a day when you arrive, and learns who's who in your home."
            else -> "Custom: you've fine-tuned the details."
        },
        color = Muted, fontSize = 14.sp
    )
    Text(
        "Always: face tracking happens on the phone and camera images never leave it. Your voice goes " +
            "to Google's Gemini only during a conversation. Say \"privacy mode\" to switch the camera " +
            "and microphone fully off.",
        color = Muted, fontSize = 14.sp
    )

    SwitchSetting(
        "Remember things about me",
        "Short notes about you, kept on the phone and sent to Gemini at the start of each conversation.",
        settings.memoryOn
    ) { settings.memoryOn = it }
    if (settings.memoryOn) {
        SwitchSetting(
            "Remember our conversations",
            "After each conversation, a one-line diary entry about what you talked about (written by Gemini " +
                "from the conversation's text, kept on the phone), so it can pick up where you left off.",
            settings.diaryOn
        ) { settings.diaryOn = it }
    }
    MoreOptions("See what $name remembers") {
        SettingTitle("Your name", "")
        OutlinedTextField(
            value = settings.userName,
            onValueChange = { settings.userName = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        SettingTitle("Notes", "One per line. Edit or delete anything.")
        OutlinedTextField(
            value = settings.memory,
            onValueChange = { settings.memory = it },
            minLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        val week = settings.momentList().takeLast(12)
        if (week.isNotEmpty()) {
            SettingTitle("Recent moments", "For \"what did we do this week?\".")
            week.forEach { Text(it, color = Ink, fontSize = 14.sp) }
            TextButton(onClick = { settings.moments = "" }) { Text("Clear moments") }
        }
        val waiting = settings.messageList()
        if (waiting.isNotEmpty()) {
            SettingTitle("Messages waiting", "Passed on when $name sees them (if it knows their face) or they ask.")
            waiting.forEach { Text("For ${it.to}, from ${it.from}: ${it.text}", color = Ink, fontSize = 14.sp) }
            TextButton(onClick = { settings.messages = "" }) { Text("Delete these messages") }
        }
        if (settings.diary.isNotBlank()) {
            SettingTitle("Conversation diary", "One line per conversation, newest last. Edit or delete anything.")
            OutlinedTextField(
                value = settings.diary,
                onValueChange = { settings.diary = it },
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(onClick = { settings.diary = "" }) { Text("Clear the diary") }
        }
        SettingTitle("Your birthday", "Month-day, e.g. 07-21. Or just tell it.")
        OutlinedTextField(
            value = settings.birthday,
            onValueChange = { settings.birthday = it.filter { c -> c.isDigit() || c == '-' }.take(5) },
            singleLine = true,
            placeholder = { Text("MM-DD") },
            modifier = Modifier.width(200.dp)
        )
        Button(onClick = {
            if (confirmWipe) {
                settings.memory = ""
                settings.userName = ""
                settings.moments = ""
                settings.diary = ""
            }
            confirmWipe = !confirmWipe
        }) { Text(if (confirmWipe) "Tap again to forget everything" else "Forget everything") }
    }

    MoreOptions("Recent conversations, in detail") {
        val context = LocalContext.current
        var shown by remember { mutableStateOf(ConvoLog.recent()) }
        var copiedAt by remember { mutableLongStateOf(0L) }
        SwitchSetting(
            "Keep a detailed log",
            "The last ${ConvoLog.KEEP} conversations: why each one started, what it heard and said, and " +
                "what it did (tools, photos). Kept on this phone only. If $name does something odd, " +
                "this shows why.",
            settings.convoLogOn
        ) {
            settings.convoLogOn = it
            ConvoLog.enabled = it
            if (!it) {
                ConvoLog.clear()
                shown = emptyList()
            }
        }
        if (settings.convoLogOn && shown.isEmpty()) {
            Text("Nothing yet. Talk to $name and it shows up here.", color = Muted, fontSize = 14.sp)
        }
        shown.forEach { convo ->
            Text(
                ConvoLog.asText(convo), color = Ink, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp)
            )
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Orbit conversation", ConvoLog.asText(convo)))
                copiedAt = convo.startedAt
            }) { Text(if (copiedAt == convo.startedAt) "Copied" else "Copy this conversation") }
        }
        if (shown.isNotEmpty()) {
            TextButton(onClick = {
                ConvoLog.clear()
                shown = emptyList()
            }) { Text("Clear the log") }
        }
    }

    MoreOptions("Fine-tune") {
        SwitchSetting(
            "Let it see when I ask",
            "\"What am I holding?\": it takes one photo and sends it to Gemini. Never on its own.",
            settings.vision
        ) { settings.vision = it }
        if (settings.vision && settings.greet) {
            SwitchSetting(
                "Notice things when I arrive",
                "Once a day, as it says hello, one photo so it can mention something (a new haircut).",
                settings.noticeThings
            ) { settings.noticeThings = it }
        }
        KnowWhosWho(settings, people)
        SwitchSetting(
            "My calendar",
            "Reads today's events from this phone's calendar, so it knows your day and gives a " +
                "heads-up ring 10 minutes before each event.",
            settings.calendarOn
        ) { on ->
            if (!on) settings.calendarOn = false
            else if (calendar.allowed()) settings.calendarOn = true
            else askCalendar.launch(android.Manifest.permission.READ_CALENDAR)
        }
    }
}

@Composable
private fun KnowWhosWho(settings: Settings, people: People) {
    val name = settings.companionName.ifBlank { "Orbit" }
    var peopleStatus by remember { mutableStateOf(people.status) }
    var meetingNow by remember { mutableStateOf(false) }
    var newPerson by remember { mutableStateOf("") }
    var knownPeople by remember { mutableStateOf(people.names()) }
    LaunchedEffect(Unit) {
        while (true) {
            peopleStatus = if (people.meeting != null) "Look at the camera for a moment…" else people.status
            if (meetingNow && people.meeting == null) {
                meetingNow = false
                knownPeople = people.names()
            }
            delay(300)
        }
    }
    SwitchSetting(
        "Know who's who",
        "$name learns the faces in your home (\"Orby, this is Sarah\") and greets each by name. It keeps " +
            "a face \"fingerprint\" (numbers, not a photo) on the phone. Not a security system.",
        settings.peopleOn
    ) { on ->
        settings.peopleOn = on
        if (on) people.prepare()
    }
    if (settings.peopleOn) {
        Text(peopleStatus, color = Accent, fontSize = 14.sp)
        knownPeople.forEach { person ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(person, color = Ink, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    people.forget(person)
                    knownPeople = people.names()
                }) { Text("Forget") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newPerson,
                onValueChange = { newPerson = it },
                singleLine = true,
                placeholder = { Text("Name of the person in front of the camera") },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                people.meet(newPerson)
                meetingNow = true
                newPerson = ""
            }, enabled = newPerson.isNotBlank() && people.ready) { Text("Learn face") }
        }
    }
}

// ---- Device: key, appliance, neck, screen, phone care ----

@Composable
fun DevicePage(
    settings: Settings,
    gemini: GeminiLive,
    heat: HeatWatch,
    neck: Neck,
    desk: DeskLink,
    player: MusicControl,
    onFindScreen: () -> Unit
) {
    // A quick health check: everything important at a glance, refreshed every second
    StatusChecks(settings, gemini, heat, neck, player)

    // A newer Orbit is out (it checks GitHub once a day)
    Updates(settings, BuildConfig.VERSION_NAME).available()?.let { newer ->
        val links = LocalUriHandler.current
        InfoCard(
            "Orbit $newer is out",
            "You have ${BuildConfig.VERSION_NAME}. On the release page, download the .apk under Assets " +
                "and open it: it installs over this one and keeps everything (settings, memories, your key)."
        ) {
            OutlinedButton(onClick = { links.openUri(RELEASES_PAGE) }) { Text("See what's new") }
        }
    }
    SwitchSetting(
        "Tell me about updates",
        "Once a day it asks GitHub for the latest version number. Nothing about you is sent.",
        settings.updateCheck
    ) { settings.updateCheck = it }

    SettingTitle(
        "Gemini key",
        "Orbit talks through Google's Gemini. A key is free: Get a free key → sign in and copy it → Paste → Test."
    )
    KeySetup(settings, gemini)

    ApplianceSettings(settings)

    SwitchSetting(
        "Night schedule",
        "Between these hours the eyes dim, or it sleeps (it still wakes if it sees you).",
        settings.nightOn
    ) { settings.nightOn = it }
    if (settings.nightOn) {
        ChoiceRow {
            Choice("Dim", !settings.nightSleep) { settings.nightSleep = false }
            Choice("Sleep", settings.nightSleep) { settings.nightSleep = true }
        }
        SettingTitle("Night starts: ${hourLabel(settings.nightStart.toInt())}", "")
        Slider(value = settings.nightStart, onValueChange = { settings.nightStart = it }, valueRange = 0f..23f, steps = 22)
        SettingTitle("Night ends: ${hourLabel(settings.nightEnd.toInt())}", "")
        Slider(value = settings.nightEnd, onValueChange = { settings.nightEnd = it }, valueRange = 0f..23f, steps = 22)
    }

    MoreOptions(if (settings.neckOn) "Neck" else "Add a neck (optional)") {
        NeckSettings(settings, neck, gemini, onFindScreen)
    }
    MoreOptions("Looking at your computer screen") { ComputerSettings(settings, desk) }
    MoreOptions("Tracking and phone care") {
        SettingTitle("Follow side to side", "How far the eyes move when you move left and right.")
        Slider(value = settings.gainX, onValueChange = { settings.gainX = it }, valueRange = 0.5f..3f)
        SettingTitle("Follow up and down", "How far the eyes move when you move up and down.")
        Slider(value = settings.gainY, onValueChange = { settings.gainY = it }, valueRange = 0.5f..5f)
        SwitchSetting("Flip side to side", "Turn on if the eyes move the opposite way from you.", settings.flipX) {
            settings.flipX = it
        }
        SettingTitle(
            "Doze after: ${settings.dozeAfter.toInt()} minutes",
            "With nobody around this long, the eyes dim and drift (protects the screen, keeps the phone cool)."
        )
        Slider(value = settings.dozeAfter, onValueChange = { settings.dozeAfter = it }, valueRange = 1f..15f)
        SettingTitle(
            "Then sleep after: " + if (settings.sleepAfterDoze < 1f) "never" else "${settings.sleepAfterDoze.toInt()} more minutes",
            "After dozing this long, it falls properly asleep (the floating z's). It still wakes when it sees you."
        )
        Slider(value = settings.sleepAfterDoze, onValueChange = { settings.sleepAfterDoze = it }, valueRange = 0f..60f)
        Text(
            "Phone temperature: ${heat.describe()}. Warm (42°C+): dimmer eyes, fewer face checks. Hot (46°C+): " +
                "it sleeps until it cools down.",
            color = Muted, fontSize = 14.sp
        )
    }
    MoreOptions("About Orbit") { AboutPage() }
}

// One line of the status card: ✓ working, • fine but worth knowing, ✗ needs attention
private class Check(val state: Int, val text: String)

@Composable
private fun StatusChecks(settings: Settings, gemini: GeminiLive, heat: HeatWatch, neck: Neck, player: MusicControl) {
    val context = LocalContext.current
    var checks by remember { mutableStateOf(emptyList<Check>()) }
    LaunchedEffect(Unit) {
        while (true) {
            heat.check()
            checks = statusChecks(context, settings, gemini, heat, neck, player)
            delay(1000)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Accent.copy(alpha = 0.08f))
            .border(1.dp, Accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text("Status", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        checks.forEach { check ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    when (check.state) { 1 -> "✓"; -1 -> "✗"; else -> "•" },
                    color = when (check.state) { 1 -> Color(0xFF7BE495); -1 -> Color(0xFFFF8A80); else -> Color(0xFFFFC857) },
                    fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(24.dp)
                )
                Text(check.text, fontSize = 14.sp, color = Ink, lineHeight = 20.sp)
            }
        }
    }
}

private fun statusChecks(
    context: android.content.Context, settings: Settings, gemini: GeminiLive, heat: HeatWatch, neck: Neck, player: MusicControl
): List<Check> {
    val list = mutableListOf<Check>()
    val allowed = { p: String ->
        androidx.core.content.ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    // Internet
    val net = context.getSystemService(android.net.ConnectivityManager::class.java)
    val online = net?.getNetworkCapabilities(net.activeNetwork)
        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    list += if (online) Check(1, "Internet") else Check(-1, "No internet: Orbit can't talk until it's back")
    list += if (gemini.hasKey()) Check(1, "Gemini key added") else Check(-1, "No Gemini key yet (below)")
    // Camera and microphone
    val camera = allowed(android.Manifest.permission.CAMERA)
    val microphone = allowed(android.Manifest.permission.RECORD_AUDIO)
    list += when {
        !camera || !microphone -> Check(-1, "Not allowed: " + listOfNotNull(if (!camera) "camera" else null, if (!microphone) "microphone" else null).joinToString(" and ") + " (Android settings → Apps → Orbit → Permissions)")
        neck.seesYou() -> Check(1, "Camera and microphone · it sees you right now")
        else -> Check(1, "Camera and microphone · nobody in view at the moment")
    }
    list += if (settings.wakeWordOn) Check(1, "Listening for its name") else Check(0, "Wake word off (tap the eyes to talk)")
    // Neck
    if (settings.neckOn) {
        val link = neck.link
        list += when {
            link.connected -> Check(1, "Neck connected" + (if (link.firmware > 0) " · firmware ${link.firmware}" else "") +
                (if (link.updateAvailable()) " · an update is ready (Neck, below)" else ""))
            else -> Check(-1, "Neck: ${link.status}")
        }
        if (link.connected && neck.doing.isNotEmpty()) list += Check(0, "Head: ${neck.doing}")
    } else {
        list += Check(0, "No neck")
    }
    list += if (player.hasAccess()) Check(1, "Music apps connected") else Check(0, "Music apps not connected (radio still works)")
    list += if (settings.weatherTown.isNotBlank()) Check(1, "Weather for ${settings.weatherTown}") else Check(0, "No town for the weather yet (ask it \"what's the weather?\")")
    // Power and temperature
    val battery = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
    val plugged = (battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val percent = battery?.let {
        val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        if (level >= 0 && scale > 0) level * 100 / scale else -1
    } ?: -1
    list += if (plugged) Check(1, "Plugged in" + (if (percent >= 0) " · battery $percent %" else ""))
    else Check(0, "On battery" + (if (percent >= 0) " ($percent %)" else "") + ": plug it in to keep it running")
    list += when (heat.level) {
        0 -> Check(1, "Phone temperature: ${heat.describe()}")
        1 -> Check(0, "Phone temperature: ${heat.describe()} (resting a little)")
        else -> Check(-1, "Phone temperature: ${heat.describe()} (sleeping to cool down)")
    }
    return list
}

// "Teach it your voice": say its name 10 times; it learns how sure its ear should be for you
@Composable
fun LearnMyVoice(settings: Settings, wake: WakeWord, name: String) {
    var training by remember { mutableStateOf(wake.training) }
    var takes by remember { mutableIntStateOf(0) }
    var ready by remember { mutableStateOf(false) }
    var last by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf("") }
    var hasRecordings by remember { mutableStateOf(wake.myVoiceFolder.list()?.isNotEmpty() == true) }
    // Follow the training while it runs (it happens on the wake word's own thread)
    LaunchedEffect(training) {
        val t = training ?: return@LaunchedEffect
        while (true) {
            takes = t.takes
            ready = t.ready
            last = t.lastScore
            if (t.done) {
                result = t.result
                hasRecordings = true
                training = null
                break
            }
            delay(200)
        }
    }
    val custom = settings.earThreshold < STANDARD_EAR_THRESHOLD
    SettingTitle(
        "Teach it your voice",
        "Say \"${settings.wakePhrase.split(",").first().trim().replaceFirstChar { it.uppercase() }}\" " +
            "$TRAINING_TAKES times, pausing between each, and $name learns how your voice sounds to its " +
            "ear. The recordings stay on this phone." +
            if (custom) " Now set for your voice." else ""
    )
    val t = training
    if (t != null) {
        Text(
            when {
                !ready -> "Getting ready…"
                takes == 0 -> "Say it now"
                else -> "$takes of $TRAINING_TAKES  ·  last one: ${(last * 100).toInt()}% sure"
            },
            color = Accent
        )
        OutlinedButton(onClick = {
            wake.stopTraining()
            training = null
        }) { Text("Stop") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                result = ""
                training = wake.startTraining()
                if (training == null) result = "Couldn't start: the fast ear isn't ready yet."
            }) { Text(if (custom) "Teach it again" else "Start") }
            if (custom) {
                OutlinedButton(onClick = {
                    settings.earThreshold = STANDARD_EAR_THRESHOLD
                    result = "Back to the standard setting."
                }) { Text("Reset") }
            }
        }
        if (result.isNotEmpty()) Text(result, color = Accent, fontSize = 14.sp)
        if (hasRecordings) {
            TextButton(onClick = {
                wake.myVoiceFolder.deleteRecursively()
                hasRecordings = false
            }) { Text("Delete my recordings") }
        }
    }
}

