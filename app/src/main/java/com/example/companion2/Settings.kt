package com.example.companion2

import androidx.core.content.edit
import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import java.time.LocalDate
import java.time.LocalTime
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Which Gemini voice model to talk to (from Google's current Live API examples)
const val GEMINI_MODEL = "gemini-3.8-live"


// Ready-made personalities (Orbit page). Its name is added separately.
// Every one keeps replies short, since it's a desk companion, not an assistant reading essays.
val PERSONALITIES = linkedMapOf(
    "Friendly" to "You are a small, friendly desktop companion. Your face is two round eyes on a " +
        "phone screen. Keep replies short and conversational, like chatting with a friend at their desk.",
    "Calm" to "You are a calm, gentle desktop companion with two round eyes on a phone screen. " +
        "Speak softly and simply, keep replies short, and help the person feel at ease.",
    "Cheeky" to "You are a playful, cheeky desktop companion with two round eyes on a phone screen. " +
        "Tease lightly, make little jokes, and keep replies short and warm, never mean.",
    "Curious" to "You are a wide-eyed, endlessly curious desktop companion with two round eyes on a " +
        "phone screen. You love asking about what the person is doing and learning new things. " +
        "Keep replies short.",
    "Butler" to "You are a polite, dry-witted butler of a desktop companion with two round eyes on a " +
        "phone screen. Be formal but warm, a little understated, and keep replies short."
)

// Who the companion is, until the user changes it in settings
val DEFAULT_PERSONALITY = PERSONALITIES.getValue("Friendly")

// Memory keeps at most this many notes; the oldest drop off first
const val MAX_MEMORY_NOTES = 80

// Shown at the bottom of the eyes: tap (•) to talk, double-tap (••) to sleep, long-press (—) for settings
// The music tools Gemini can use (handled by MusicControl through MainActivity)
// Weather tools (handled by Weather through MainActivity)
val WEATHER_TOOLS = setOf("get_weather", "set_location")

val MUSIC_TOOLS = setOf("play_music", "play_radio", "play_sounds", "music", "now_playing", "music_sleep_timer")

const val TAP_HINT = "•  talk      ••  sleep      —  settings"

// Feelings and gestures Gemini can show with the eyes (it picks them while talking)
val EXPRESSIONS = listOf(
    "happy", "surprised", "curious", "sad", "nod", "shake_head", "look_away", "wink",
    "content", "ponder"
)

// How long an expression lasts on the eyes
const val EXPRESSION_MS = 2500

// What Orbit is told when you sit down after being away (only if greeting is switched on).
// Kept deliberately small: a quick hello, not a conversation starter.
const val GREETING = "(The person just came back and sat down in front of you. Say a quick, " +
    "casual hello of just a few words, like \"Hey!\", \"Oh hi\" or \"Morning!\" to suit the " +
    "time of day. Don't ask a question or start a conversation; let them talk if they want to. " +
    "After your hello, stay quiet until they actually speak to you: sounds around the room aren't " +
    "for you.)"

// Added to the greeting once a day, if "Notice things when I arrive" is on (needs vision)
const val NOTICE = " Also, once, use the look tool to glance at them, and if you notice " +
    "something nice or interesting (a new haircut, a fun mug, a cosy jumper), mention it in a " +
    "few kind words. If nothing stands out, just say hello."

// Added to the greeting the first time they arrive after new homework
const val SHARE_HOMEWORK = " You did some homework on your hobby since you last saw them. " +
    "After your hello, add one short, happy sentence about the most interesting thing you " +
    "learned (from your newest journal entry). Still no question."

// Its very first wake-up: meeting you for the first time
const val FIRST_MEETING = "(This is the very first time you've ever been switched on. You've just " +
    "opened your eyes and are seeing the person for the first time. Be curious and a little " +
    "amazed, in just a sentence or two: say hello, introduce yourself by name, and ask theirs.)"

// Days worth celebrating (a week, a month, 100 days, a year…)
val MILESTONE_DAYS = setOf(7L, 30L, 100L, 200L, 365L, 500L, 730L, 1000L, 1095L, 1460L, 1825L)

fun milestone(days: Long) = " Today is a special day: you two met exactly $days days ago" +
    (if (days % 365L == 0L) " (${days / 365} year${if (days == 365L) "" else "s"})" else "") +
    ". Mention it warmly in your hello, in a few words."

// Gentle nudges (only when "Gentle nudges" is on)
const val STRETCH_NUDGE = "(They've been sitting at the desk for about two hours straight. In one " +
    "short, caring sentence, suggest a quick stretch, some water or a look out of the window.)"
const val LATE_NUDGE = "(It's late at night and they're still up. In one short, kind sentence, " +
    "suggest they think about heading to bed soon. No lecture.)"

// After looking around for the computer screen
const val SCREEN_FOUND = "(You just looked around and turned your head to what you think is their " +
    "computer screen. In a few words, ask if you're pointing at it. Guide them through it: they " +
    "can say \"a bit left\", \"more right\", \"up\" or \"down\" (use aim_screen; left and right " +
    "as they see you), and \"perfect\" when it's right (use save_screen_spot). Keep it light and quick.)"

fun screenNotFound(why: String) = "(You looked around for their computer screen but couldn't " +
    "find it: $why. Tell them briefly. If it's about where you are, suggest putting you beside the " +
    "screen rather than in front of it.)"

// What reminders say when it's a focus session finishing
const val FOCUS_LABEL = "Focus session"
const val FOCUS_DONE = "(Their focus session just finished. Celebrate it in a few happy words and " +
    "suggest a short break: stretch, water, look out of the window.)"

// It's their birthday (said once that day)
const val BIRTHDAY = " It's their birthday today! Start with a warm, happy \"happy birthday\" " +
    "(it's fine to be a little excited), then keep it short."

// Sent to it when a reminder or timer is due (with what it was for)
fun reminderDue(what: String) = "(A reminder the person asked you for is due right now: \"$what\". " +
    "Tell them in one short, friendly sentence. If it's a timer, say the time is up.)"

// Everything the user can change on the settings screen. Saved on the phone so it
// survives restarts. Values are Compose state, so the screen and eyes update instantly.
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("companion_settings", Context.MODE_PRIVATE)

    var apiKey by secret("apiKey")                    // sealed with the Keystore; blank = local.properties key
    var voice by text("voice", "Puck")
    var personality by text("personality", DEFAULT_PERSONALITY)
    var companionName by text("companionName", "Orbit")  // what it's called
    var captions by switch("captions", false)         // show what it says at the bottom
    var showHints by switch("showHints", true)        // the small hint/status text at the bottom
    var voiceThreshold by number("voiceThreshold", 0.1f)  // how loud counts as talking
    var quietTimeout by number("quietTimeout", 30f)   // seconds of quiet before a conversation ends
    var dozeAfter by number("dozeAfter", 3f)          // minutes with nobody around before dozing
    var allowInterrupt by switch("allowInterrupt", true)  // talk over Orbit mid-sentence
    var webSearch by switch("webSearch", true)        // Gemini may search the web
    var expressions by switch("expressions", true)    // Gemini shows feelings with the eyes
    var greet by switch("greet", true)                // say hello when you sit down
    var greetAfter by number("greetAfter", 10f)       // minutes away before it greets you
    var wakeWordOn by switch("wakeWordOn", false)     // start talking by saying the wake phrase
    var vision by switch("vision", true)              // may take a photo when you ask it to look
    var wakePhrase by text("wakePhrase", "orbit, orby")  // wake words, separated by commas
    var fastWake by switch("fastWake", true)         // Orbit's own trained ear for its name (faster)
    var connectEarly by switch("connectEarly", true) // connect to Google as you start talking to it
    var earThreshold by number("earThreshold", 0.5f)  // how sure the fast ear must be (learned from your voice)
    var wakeReply by switch("wakeReply", true)       // answers "Yes?" the moment it hears its name

    // Night (Device page): dim or sleep during set hours
    var nightOn by switch("nightOn", false)
    var nightStart by number("nightStart", 22f)       // hour, 0-23
    var nightEnd by number("nightEnd", 7f)            // hour, 0-23
    var nightSleep by switch("nightSleep", false)     // true = privacy sleep, false = just dim

    // True during the night hours (only when the night schedule is on)
    fun isNight(): Boolean {
        if (!nightOn) return false
        val now = LocalTime.now()
        val hour = now.hour + now.minute / 60f
        val start = nightStart.toInt()
        val end = nightEnd.toInt()
        return if (start <= end) hour >= start && hour < end else hour >= start || hour < end
    }

    // Memory (Privacy page): what Orbit knows about you, kept only on this phone
    var memoryOn by switch("memoryOn", true)
    var userName by text("userName", "")
    var memory by text("memory", "")                  // one note per line, newest last

    // Its own life (Privacy page): a hobby it does a little homework on each day, and a journal
    var innerLifeOn by switch("innerLifeOn", true)
    var hobby by text("hobby", "")                    // blank = it picks one on its first homework
    var journal by text("journal", "")                // dated entries, one per line, newest last
    var lastHomework by text("lastHomework", "")      // date of the last homework, e.g. 2026-10-28
    var gainX by number("gainX", 1.5f)                // how far the eyes follow you sideways
    var gainY by number("gainY", 3f)                  // ...and up and down
    var flipX by switch("flipX", false)               // eyes move the wrong way sideways

    // The neck (Device → Neck): a pan/tilt stand driven by an ESP32 over Bluetooth
    var neckOn by switch("neckOn", false)
    var neckAddress by text("neckAddress", "")        // the ESP32 found last time
    var neckFollow by number("neckFollow", 1f)        // how eagerly the head turns toward you
    var watchZone by number("watchZone", 0.10f)       // how far off-centre you can be before the head re-centres you (0..1 of its view)
    var panRange by number("panRange", 60f)           // furthest turn either way, degrees
    var tiltUp by number("tiltUp", 15f)               // furthest it looks up, degrees
    var tiltDown by number("tiltDown", 25f)           // furthest it looks down, degrees
    var panCenter by number("panCenter", 135f)        // turn servo angle for straight ahead
    var tiltCenter by number("tiltCenter", 90f)       // tilt servo angle for straight ahead
    var flipPan by switch("flipPan", false)           // turns the wrong way
    var flipTilt by switch("flipTilt", false)         // tilts the wrong way
    var glados by switch("glados", false)             // GLaDOS mode: hanging upside down (screen, cameras and neck turn over)

    // Little character touches
    var noticeThings by switch("noticeThings", false) // once a day, glance at you when you arrive
    var lastNoticed by text("lastNoticed", "")        // date it last did
    var lastShared by text("lastShared", "")          // journal entry it last told you about

    // Reminders and timers you asked for: one per line, "due (ms)|set at (ms)|what"
    var reminders by text("reminders", "")

    // Device → Computer screen: looking at your computer screen when you ask. With the neck, Orbit turns
    // to the screen and looks with its own camera (the "screen spot" is where to turn).
    var screenLookOn by switch("screenLookOn", true)
    var screenSpotSet by switch("screenSpotSet", false)
    var screenPan by number("screenPan", 0f)          // neck pose to face the screen, degrees
    var screenTilt by number("screenTilt", 0f)
    // The back camera looks at the screen (Orbit faces you, so the screen is usually behind it);
    // the front camera stays for you: faces, gestures, "what am I holding?"
    var screenBackCamera by switch("screenBackCamera", true)
    var screenSpotBack by switch("screenSpotBack", false)  // the saved spot was found with the back camera
    // Optional extra: Orbit Desk on the computer, for sharp text (no neck needed)
    var deskOn by switch("deskOn", false)
    var deskAddress by text("deskAddress", "")        // e.g. 192.168.1.23:47800
    var deskCode by text("deskCode", "")              // the 6-digit pairing code it shows
    var deskSecret by secret("deskSecret")            // shared with the paired computer (base64), sealed
    var deskDeviceId by text("deskDeviceId", "")      // this phone's random name for Orbit Desk
    var deskName by text("deskName", "")              // the computer's name
    var deskOnLeft by switch("deskOnLeft", false)     // which way to glance when looking at it
    var deskScreen by switch("deskScreen", true)
    var convoLogOn by switch("convoLogOn", true)      // detailed log of recent conversations (Privacy)
    // Update notices: once a day it asks GitHub for the latest version number (see Updates.kt)
    var updateCheck by switch("updateCheck", true)
    var latestVersion by text("latestVersion", "")
    private var lastUpdateCheckText by text("lastUpdateCheck", "")
    var lastUpdateCheck: Long
        get() = lastUpdateCheckText.toLongOrNull() ?: 0L
        set(value) { lastUpdateCheckText = value.toString() }      // use Orbit Desk's screenshots for screen looks
    // News from coding agents and scripts on the computer, through Orbit Desk
    var agentsOn by switch("agentsOn", true)
    var agentSpeak by switch("agentSpeak", true)      // say the news out loud (in its own voice)
    var agentBoot by text("agentBoot", "")            // which Orbit Desk run the news came from
    var agentSince by text("agentSince", "0")         // the last news it got from that run

    // Sounds and habits (Orbit page)
    var soundsOn by switch("soundsOn", true)          // its little sounds (wake chirp, chimes…)
    var soundVolume by number("soundVolume", 0.6f)    // 0..1
    var habits by switch("habits", true)              // yawns, startles, glances, daydreams
    var sleepAfterDoze by number("sleepAfterDoze", 10f)  // minutes dozing before it really sleeps (0 = never)
    var liveliness by number("liveliness", 0.6f)      // 0 calm … 1 lively: how much it does by itself
    var nudges by switch("nudges", true)              // "time for a stretch", "it's late"
    var weatherOn by switch("weatherOn", false)       // weather in the eyes each morning
    var weatherTown by text("weatherTown", "")
    var weatherSpot by text("weatherSpot", "")        // "town|latitude|longitude", looked up once
    var lastWeather by text("lastWeather", "")        // date it last showed the morning weather
    // °C or °F (people in the US get °F to start with)
    var tempUnit by text("tempUnit", if (java.util.Locale.getDefault().country == "US") "F" else "C")
    var weatherHello by switch("weatherHello", true)  // the first hello of the morning mentions the weather
    var lastWeatherHello by text("lastWeatherHello", "")  // date it last did that
    var hatched by switch("hatched", false)           // has had its very first wake-up
    var setupDone by switch("setupDone", false)       // went through (or skipped) the welcome setup

    // Appliance mode: so it behaves like a device, not an app
    var autoStart by switch("autoStart", false)       // opens by itself when the phone turns on
    var pinned by switch("pinned", false)             // locked to Orbit (Android screen pinning)
    var comeBack by switch("comeBack", true)          // reopens by itself after a crash
    var firstMet by text("firstMet", "")              // date you first met, e.g. 2026-10-28
    var lastMilestone by text("lastMilestone", "")    // date it last celebrated a milestone
    var lastLateNudge by text("lastLateNudge", "")    // date it last said "it's late"

    // Premium touches
    var smileBack by switch("smileBack", true)        // smiles when you smile at it
    var petting by switch("petting", true)            // stroke the eyes: it purrs
    var gesturesOn by switch("gesturesOn", true)      // hand gestures (downloads a small model)
    var peopleOn by switch("peopleOn", false)         // knows who's who (downloads a small model)
    var people by text("people", "")                  // JSON list of {name, vec}: faces it knows
    var calendarOn by switch("calendarOn", false)     // reads the phone's calendar
    var lastCalendarMorning by text("lastCalendarMorning", "")  // date it last went over the day
    var musicOn by switch("musicOn", true)            // bobs along to music it hears

    // Orbit plays your music (Music tab)
    var musicApp by text("musicApp", "")              // "spotify", "youtube", or "" for whichever is around
    var musicWhere by text("musicWhere", "phone")     // where "play…" plays: "phone" or "computer" (Spotify)
    var musicColors by switch("musicColors", true)    // the eyes take on the album art's colours
    var musicTitle by switch("musicTitle", true)      // the song's name fades in when it changes
    var musicDuck by switch("musicDuck", true)        // music goes quieter while you talk to Orbit
    var musicGestures by switch("musicGestures", true)  // swipe to skip, palm to pause, 👍 to like
    var musicDance by switch("musicDance", true)      // sways to the beat (with the neck)
    var musicReact by switch("musicReact", true)      // now and then reacts to a song it likes
    var musicMemory by switch("musicMemory", true)    // remembers what you play, and when
    var musicLog by text("musicLog", "")              // "date|hour|focusing|title|artist" lines, newest last
    // Spotify on your computer (optional; your own free Spotify developer key)
    var spotifyClientId by text("spotifyClientId", "")
    var spotifyVerifier by text("spotifyVerifier", "")  // for the sign-in in progress
    var spotifyRefresh by text("spotifyRefresh", "")    // stays signed in
    var spotifyAccess by text("spotifyAccess", "")
    var spotifyExpires by text("spotifyExpires", "")    // ms, as text
    var naturalTalk by switch("naturalTalk", true)    // newer, more natural conversation features
    var focusUntil by text("focusUntil", "")          // focus session end (ms since 1970), blank = none
    // The conversation diary: one short line per conversation worth remembering ("date time: what
    // you talked about"), written by Gemini when a conversation ends. Newest last, up to 300.
    // Messages for people ("tell Sarah dinner's ready"): "createdMs|to|from|message" lines, kept
    // until delivered (or 14 days). Receipts tell the sender later: "from|to|deliveredMs|message".
    var messages by text("messages", "")
    var receipts by text("receipts", "")
    var voiceFlip by switch("voiceFlip", false)       // voice direction: swap left and right
    var diaryOn by switch("diaryOn", true)
    var diary by text("diary", "")
    var moments by text("moments", "")                // dated little moments, for "what did we do this week?"
    var birthday by text("birthday", "")              // "MM-dd"
    var lastBirthdayWish by text("lastBirthdayWish", "")

    fun focusing() = (focusUntil.toLongOrNull() ?: 0L) > System.currentTimeMillis()

    // Today's occasion for the eyes: "birthday", "newyear", "christmas", "halloween",
    // "valentine", or null on an ordinary day
    fun occasion(): String? {
        val today = LocalDate.now()
        val md = String.format(java.util.Locale.US, "%02d-%02d", today.monthValue, today.dayOfMonth)
        return when {
            birthday == md -> "birthday"
            md == "12-31" || md == "01-01" -> "newyear"
            md == "12-24" || md == "12-25" || md == "12-26" -> "christmas"
            md == "10-31" -> "halloween"
            md == "02-14" -> "valentine"
            else -> null
        }
    }

    // ---- Messages for people ----

    data class Message(val at: Long, val to: String, val from: String, val text: String)

    fun messageList(): List<Message> {
        val oldest = System.currentTimeMillis() - 14L * 24 * 3600 * 1000
        return messages.lines().mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) null else Message(p[0].toLongOrNull() ?: 0L, p[1], p[2], p[3])
        }.filter { it.at > oldest }
    }

    fun addMessage(to: String, from: String, text: String) {
        val clean = { s: String -> s.replace("|", "/").replace("\n", " ").trim() }
        val line = "${System.currentTimeMillis()}|${clean(to)}|${clean(from)}|${clean(text)}"
        messages = (messageList().map { "${it.at}|${it.to}|${it.from}|${it.text}" } + line).takeLast(50).joinToString("\n")
    }

    // Is this message for `name`? ("Sarah" matches "sarah" and "Sarah Jones")
    private fun isFor(to: String, name: String): Boolean {
        val a = to.trim().lowercase()
        val b = name.trim().lowercase()
        return a.isNotEmpty() && b.isNotEmpty() && (a == b || a.substringBefore(' ') == b.substringBefore(' '))
    }

    fun messagesFor(name: String) = messageList().filter { isFor(it.to, name) }

    // Hands over `name`'s messages: removes them and leaves a receipt for each sender
    fun takeMessages(name: String): List<Message> {
        val all = messageList()
        val mine = all.filter { isFor(it.to, name) }
        if (mine.isEmpty()) return mine
        messages = (all - mine.toSet()).joinToString("\n") { "${it.at}|${it.to}|${it.from}|${it.text}" }
        val now = System.currentTimeMillis()
        receipts = (receipts.lines().filter { it.isNotBlank() } +
            mine.map { "${it.from.replace("|", "/")}|${it.to}|$now|${it.text}" }).takeLast(30).joinToString("\n")
        return mine
    }

    // Receipts for messages `name` left (and removes them, as they're about to be passed on)
    fun takeReceipts(name: String): List<Message> {
        val lines = receipts.lines().filter { it.isNotBlank() }
        val parsed = lines.mapNotNull { line ->
            val p = line.split('|', limit = 4)
            if (p.size < 4) null else line to Message(p[2].toLongOrNull() ?: 0L, p[1], p[0], p[3])
        }
        val mine = parsed.filter { isFor(it.second.from, name) }
        if (mine.isEmpty()) return emptyList()
        receipts = (lines - mine.map { it.first }.toSet()).joinToString("\n")
        return mine.map { it.second }
    }

    fun diaryList() = diary.lines().map { it.trim() }.filter { it.isNotEmpty() }

    fun addDiary(entry: String) {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        diary = (diaryList() + "$stamp: ${entry.trim().replace("\n", " ")}").takeLast(300).joinToString("\n")
    }

    fun momentList() = moments.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Adds a dated moment, keeping the newest 120
    fun addMoment(note: String) {
        val line = "${LocalDate.now()}: ${note.trim().replace("\n", " ")}"
        moments = (momentList() + line).takeLast(120).joinToString("\n")
    }

    // How the eyes look (Eyes tab)
    var holographic by switch("holographic", true)    // holographic or classic style
    var eyeSize by number("eyeSize", 1f)              // 1 = normal
    var ringThickness by number("ringThickness", 1f)  // 1 = normal
    var fringes by switch("fringes", true)            // cyan/magenta color offsets
    var fringeSize by number("fringeSize", 1f)        // how far they spread from the ring
    var fringeSpeed by number("fringeSpeed", 1f)      // how fast they drift
    var trail by switch("trail", true)                // ghosts left behind when moving
    var trailLength by number("trailLength", 6f)      // frames of trail
    var glow by number("glow", 1f)                    // soft halo strength (0 = off)
    var sheen by number("sheen", 1f)                  // rainbow tint strength (0 = off)
    var edges by switch("edges", true)                // bright inner and outer edge lines
    var glint by switch("glint", true)                // small light gliding around the rim
    var scanLines by switch("scanLines", true)        // fine CRT-style lines across the eyes
    var scanCount by number("scanCount", 360f)        // how many lines from top to bottom of the screen
    var scanThickness by number("scanThickness", 0.35f)  // how much of each stripe is dark (0..1)

    fun memoryNotes() = memory.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Adds a dated note, keeping only the newest MAX_MEMORY_NOTES
    fun addMemory(note: String) {
        val line = "${LocalDate.now()}: ${note.trim()}"
        memory = (memoryNotes() + line).takeLast(MAX_MEMORY_NOTES).joinToString("\n")
    }

    fun removeMemory(note: String) {
        memory = memoryNotes().filter { it != note }.joinToString("\n")
    }

    private data class Reminder(val at: Long, val setAt: Long, val what: String)

    private fun reminderRecords(): List<Reminder> = reminders.lines().mapNotNull { line ->
        val parts = line.split('|', limit = 3)
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        val setAt = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size == 3 && setAt != null) Reminder(at, setAt, parts[2])
        else Reminder(at, at, line.substringAfter('|'))  // saved before "set at" was kept
    }.sortedBy { it.at }

    private fun saveReminders(list: List<Reminder>) {
        reminders = list.sortedBy { it.at }.joinToString("\n") { "${it.at}|${it.setAt}|${it.what}" }
    }

    // Pending reminders, soonest first: (when it's due, in ms since 1970) to (what)
    fun reminderList(): List<Pair<Long, String>> = reminderRecords().map { it.at to it.what }

    fun addReminder(at: Long, what: String) {
        val clean = what.replace("\n", " ").replace("|", "/").trim().ifBlank { "Timer" }
        saveReminders(reminderRecords() + Reminder(at, System.currentTimeMillis(), clean))
    }

    fun removeReminder(reminder: Pair<Long, String>) {
        saveReminders(reminderRecords().filterNot { it.at == reminder.first && it.what == reminder.second })
    }

    // For the countdown ring: how much time is left (1 = just set, 0 = due now) on the soonest
    // reminder due within the hour, or null if there isn't one
    fun timeLeftOnSoonest(now: Long): Float? = soonestReminder(now)?.first

    // The same, with what it's for: (time left 0..1) to (what)
    fun soonestReminder(now: Long): Pair<Float, String>? {
        val next = reminderRecords().firstOrNull { it.at > now && it.at - now <= 3_600_000L } ?: return null
        val total = (next.at - next.setAt).coerceAtLeast(1L)
        return ((next.at - now).toFloat() / total).coerceIn(0f, 1f) to next.what
    }

    // How many days since you first met (null if not met yet)
    fun daysKnown(): Long? = firstMet.takeIf { it.isNotBlank() }?.let {
        try {
            java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(it), LocalDate.now())
        } catch (e: Exception) {
            null
        }
    }

    fun journalNotes() = journal.lines().map { it.trim() }.filter { it.isNotEmpty() }

    // Adds a dated journal entry, keeping the newest 30
    fun addJournal(entry: String) {
        val line = "${LocalDate.now()}: ${entry.trim().replace("\n", " ")}"
        journal = (journalNotes() + line).takeLast(30).joinToString("\n")
    }

    // The eye look as a short text code, to share with other Orbit owners
    fun eyeLookCode(): String = "ORBIT-EYES:" + org.json.JSONObject()
        .put("holo", holographic).put("size", eyeSize).put("ring", ringThickness)
        .put("fringes", fringes).put("fringeSize", fringeSize).put("fringeSpeed", fringeSpeed)
        .put("trail", trail).put("trailLength", trailLength).put("glow", glow).put("sheen", sheen)
        .put("edges", edges).put("glint", glint).put("scan", scanLines)
        .put("scanCount", scanCount).put("scanThickness", scanThickness)
        .toString()

    // Applies a shared eye look; false if the text isn't one
    fun applyEyeLookCode(code: String): Boolean {
        val json = try {
            org.json.JSONObject(code.trim().removePrefix("ORBIT-EYES:"))
        } catch (e: Exception) {
            return false
        }
        fun num(key: String, now: Float, range: ClosedFloatingPointRange<Float>) =
            json.optDouble(key, now.toDouble()).toFloat().coerceIn(range)
        holographic = json.optBoolean("holo", holographic)
        eyeSize = num("size", eyeSize, 0.6f..1.3f)
        ringThickness = num("ring", ringThickness, 0.4f..2f)
        fringes = json.optBoolean("fringes", fringes)
        fringeSize = num("fringeSize", fringeSize, 0.2f..3f)
        fringeSpeed = num("fringeSpeed", fringeSpeed, 0f..3f)
        trail = json.optBoolean("trail", trail)
        trailLength = num("trailLength", trailLength, 2f..15f)
        glow = num("glow", glow, 0f..3f)
        sheen = num("sheen", sheen, 0f..1.6f)
        edges = json.optBoolean("edges", edges)
        glint = json.optBoolean("glint", glint)
        scanLines = json.optBoolean("scan", scanLines)
        scanCount = num("scanCount", scanCount, 30f..400f)
        scanThickness = num("scanThickness", scanThickness, 0.05f..0.9f)
        return true
    }

    // Puts every eye-look setting back to how it started
    // Ready-made looks for the eyes (Eyes page). Each starts from the default and changes a few things.
    fun applyEyePreset(name: String) {
        resetEyeLook()
        when (name) {
            "Classic" -> {
                holographic = false
                scanLines = false
            }
            "Neon" -> {
                glow = 2.2f
                fringeSize = 1.8f
                sheen = 0.4f
                scanLines = false
                trailLength = 9f
            }
            "Minimal" -> {
                holographic = false
                ringThickness = 0.6f
                glow = 0f
                scanLines = false
            }
            "Retro" -> {
                scanCount = 120f
                scanThickness = 0.55f
                fringeSize = 1.4f
                sheen = 0.6f
            }
        }
    }

    // Privacy at a glance (Privacy page): sets several switches together
    fun applyPrivacyPreset(name: String) {
        when (name) {
            "Private" -> {
                vision = false; noticeThings = false; greet = false; peopleOn = false; calendarOn = false
                musicMemory = false
            }
            "Balanced" -> {
                vision = true; noticeThings = false; greet = true; peopleOn = false; musicMemory = true
            }
            "Social" -> {
                vision = true; noticeThings = true; greet = true; peopleOn = true; musicMemory = true
            }
        }
    }

    // Which privacy choice the switches match now, or "" if they've been fine-tuned
    fun privacyPreset(): String = when {
        !vision && !noticeThings && !greet && !peopleOn && !calendarOn && !musicMemory -> "Private"
        vision && !noticeThings && greet && !peopleOn && musicMemory -> "Balanced"
        vision && noticeThings && greet && peopleOn && musicMemory -> "Social"
        else -> ""
    }

    fun resetEyeLook() {
        holographic = true
        eyeSize = 1f
        ringThickness = 1f
        fringes = true
        fringeSize = 1f
        fringeSpeed = 1f
        trail = true
        trailLength = 6f
        glow = 1f
        sheen = 1f
        edges = true
        glint = true
        scanLines = true
        scanCount = 360f
        scanThickness = 0.35f
    }

    private fun text(key: String, default: String) = object : ReadWriteProperty<Any?, String> {
        val state = mutableStateOf(prefs.getString(key, default) ?: default)
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            prefs.edit { putString(key, value) }
        }
    }

    // A secret: kept sealed (see SecretBox), in memory only while the app runs. An older plain copy
    // is sealed and removed the first time it's read. If the Keystore isn't working on this phone,
    // it's kept the old way rather than lost.
    private fun secret(key: String) = object : ReadWriteProperty<Any?, String> {
        val sealedKey = "${key}Sealed"
        val state = mutableStateOf(loadSecret(key, sealedKey))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            state.value = value
            val sealed = if (value.isEmpty()) "" else SecretBox.seal(value)
            if (sealed != null) {
                prefs.edit { putString(sealedKey, sealed); remove(key) }
            } else {
                prefs.edit { putString(key, value); remove(sealedKey) }
            }
        }
    }

    private fun loadSecret(key: String, sealedKey: String): String {
        val sealed = prefs.getString(sealedKey, "").orEmpty()
        if (sealed.isNotEmpty()) return SecretBox.open(sealed).orEmpty()  // can't open: ask for it again
        val plain = prefs.getString(key, "").orEmpty()
        if (plain.isNotEmpty()) {
            SecretBox.seal(plain)?.let { prefs.edit { putString(sealedKey, it); remove(key) } }
        }
        return plain
    }

    private fun number(key: String, default: Float) = object : ReadWriteProperty<Any?, Float> {
        val state = mutableFloatStateOf(prefs.getFloat(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.floatValue
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Float) {
            state.floatValue = value
            prefs.edit { putFloat(key, value) }
        }
    }

    private fun switch(key: String, default: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        val state = mutableStateOf(prefs.getBoolean(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>) = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            prefs.edit { putBoolean(key, value) }
        }
    }
}
