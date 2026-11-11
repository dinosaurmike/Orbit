package com.example.companion2

import android.media.AudioManager
import android.util.Base64
import android.util.Log
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

// Orbit never makes things up. Near the top of its instructions, for everything it says.
const val TRUTHFUL = "Be truthful, always. Never guess, invent, assume or fill in gaps, about anything: " +
    "what you see, what you know, what happened, what something says. Only say what you actually " +
    "know or can actually see. Not knowing is completely fine, and you say it warmly and simply, " +
    "in your own words, for example \"I don't know\", \"I can't quite tell\", \"I'm not sure what " +
    "that is. Can you give me some clues?\", \"Can you hold it a bit closer?\" Asking for a clue or " +
    "a better look is always better than a guess. This matters more than being helpful, quick or " +
    "playful."

// After its name, the eyes glide from idle into listening over this long once it's connected
const val LISTEN_GLIDE_MS = 500L

// Sent with every photo and screenshot it looks at
const val PHOTO_RULES = "Describe only what you can clearly see in that photo. Never guess or invent " +
    "objects, text, colours or details that aren't clearly visible, and never fill in what's " +
    "usually there. If the photo is dark, blurry, or doesn't show what they asked about, say exactly " +
    "that instead of describing what might be there. If you can't tell what something is, say so " +
    "and ask for help: a clue, holding it closer, turning it, or more light."

// Holds a voice conversation with Gemini Live: sends your voice, plays its replies.
// Tap the screen to start talking; tap again to end.
// The key comes from settings; if that's blank, from local.properties (test builds only).
class GeminiLive(
    private val settings: Settings,
    private val fallbackKey: String,
    audio: AudioManager
) {
    @Volatile var status = TAP_HINT
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)  // conversations stay open
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val speaker = Speaker(audio)
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var lastHeard = 0L          // last time your voice was loud enough
    @Volatile private var lastActivity = 0L       // last time either of you said something
    @Volatile private var waitingForReply = false  // you talked; Gemini hasn't answered yet
    // Lets a conversation carry on over a fresh connection (Google ends each one after a while)
    @Volatile private var resumeHandle: String? = null
    // Your voice from while it was still connecting (e.g. right after the wake phrase),
    // sent as soon as the connection is ready so nothing you said is lost
    private val earlySound = ArrayDeque<ByteArray>()

    // Something for Orbit to say as soon as it's connected (the arrival greeting)
    @Volatile private var greeting: String? = null

    // Google's examples say web search can't be combined with other tools. If Google refuses
    // the combination, this switches off and we connect again without search.
    @Volatile private var searchAccepted = true
    @Volatile private var usingSearch = false

    // Memory notes as numbered for Gemini at the start of this conversation (for "forget")
    @Volatile private var notesAtStart: List<String> = emptyList()

    // What Orbit is saying, as text, for captions (cleared when a new reply starts)
    @Volatile var caption = ""
    // What was said in this conversation, as text ("Them: …" / "Orbit: …"), for the diary
    private val transcript = StringBuilder()
    private var transcriptSpeaker = ""
    @Volatile var captionAt = 0L
    @Volatile private var replyFinished = true

    // The latest expression Gemini asked for, and when
    @Volatile private var expression: String? = null
    @Volatile private var expressionAt = 0L
    @Volatile private var expressionMs = EXPRESSION_MS

    val active get() = socket != null || retryPending
    // Opening a conversation, not ready to talk yet (the eyes already look attentive)
    val connecting get() = socket != null && !ready
    @Volatile private var connectStartedAt = 0L
    // How long it's been connecting (ms), or 0 when it isn't
    fun connectingFor() = if (connecting) System.currentTimeMillis() - connectStartedAt else 0L
    // A dropped connection being tried again automatically (a couple of times at most)
    @Volatile private var retryPending = false
    @Volatile private var retries = 0

    private fun currentKey() = settings.apiKey.trim().ifBlank { fallbackKey.trim() }

    // The key in use (from settings, or local.properties in test builds), for other Gemini calls
    fun key() = currentKey()

    fun hasKey() = currentKey().isNotBlank()

    // How loud Gemini's voice is right now (0..1)
    fun voiceLevel() = speaker.level()

    // True while Orbit is talking
    fun speaking() = speaker.busy()

    // Voice commands Gemini passes on (go to sleep, end the conversation, volume, greeting).
    // Called on a background thread with the command's name and its inputs.
    @Volatile var onCommand: ((String, JSONObject) -> Unit)? = null

    // Asks the camera for one photo. The photo (JPEG), or null if the camera can't give one,
    // is handed to the function it's given.
    // sharp = a bigger photo (for reading a screen)
    @Volatile var onLook: ((Boolean, (ByteArray?) -> Unit) -> Unit)? = null
    // A photo of the computer screen: the back camera, when the screen spot was found with it
    // (set by MainActivity)
    @Volatile var onScreenPhoto: (((ByteArray?) -> Unit) -> Unit)? = null

    // Why it can't turn to look at the screen right now (null = it can). Set by MainActivity.
    @Volatile var screenProblem: () -> String? = { "no neck" }

    // Orbit Desk on your computer (set by MainActivity), for look_at_screen
    @Volatile var desk: DeskLink? = null

    // Extra background for the instructions (who's here, today's calendar…). Set by MainActivity.
    @Volatile var extraContext: () -> String = { "" }

    // When it last failed to connect because there's no internet
    @Volatile var offlineAt = 0L
        private set

    // Newer conversation features (more natural replies and turn-taking). Switched off by
    // itself if Google refuses them.
    @Volatile private var naturalAccepted = true
    @Volatile private var usingNatural = false
    // Started by the wake word: skip the first moment of sound (the end of its name)
    @Volatile private var skipChunks = 0
    // Started by its name: they'll usually go straight on with what they want ("Orby, what time
    // is it?"), so no greeting, just an answer
    @Volatile private var wokenByName = false
    // While Orbit says its own quick "Yes?", the microphone hears it: don't pass that on as yours
    @Volatile private var muteUntil = 0L
    // When the conversation was ready (connected and set up), for the eyes' glide into listening
    @Volatile private var readyAt = 0L

    // True when a neck is connected, so the camera can really tilt down to see the desk
    @Volatile var canLookDown: () -> Boolean = { false }
    // Whether it can see the person right now (face tracking), in words, and as yes/no
    @Volatile var presence: () -> String = { "You don't know." }
    @Volatile var seesThem: () -> Boolean = { false }

    // Called (on a background thread) when Gemini sets or cancels a reminder
    @Volatile var onRemindersChanged: (() -> Unit)? = null
    // Music tools (play, control, what's on, sleep timer): name + args → what happened, in words
    @Volatile var onMusic: ((String, JSONObject) -> String)? = null
    // Weather tools (get_weather, set_location): name + args → the answer, in words
    @Volatile var onWeather: ((String, JSONObject) -> String)? = null

    // Gives it a message mid-conversation (e.g. a reminder that's due). False if none is open.
    fun tell(text: String): Boolean {
        val ws = socket ?: return false
        if (!ready) {
            greeting = text  // still connecting: say it as soon as it's ready
            return true
        }
        say(ws, text)
        return true
    }

    // Works out what the conversation is doing, for the eyes
    fun mood(): Mood {
        // Still connecting. After a tap: look attentive straight away (the tap is the trigger, so
        // it should answer at once). After its name: stay calm until it's connected, then glide
        // into listening (see listeningGlide), as one smooth movement instead of a jumpy double one.
        if (!ready) return if (socket != null && !wokenByName) Mood.LISTENING else Mood.IDLE
        if (speaker.busy()) {
            waitingForReply = false
            return Mood.SPEAKING
        }
        val quietFor = System.currentTimeMillis() - lastHeard
        if (quietFor < 400) return Mood.HEARING
        if (waitingForReply) {
            if (quietFor < 6000) return Mood.THINKING
            waitingForReply = false  // no reply came; probably just a noise
        }
        return Mood.LISTENING
    }

    // How far the eyes (and head) have eased from idle into listening, 0..1, after waking by name:
    // half a second from when it's connected, slow at the start and the end. 1 the rest of the time.
    fun listeningGlide(now: Long = System.currentTimeMillis()): Float {
        if (!wokenByName || !ready) return 1f
        val t = ((now - readyAt) / LISTEN_GLIDE_MS.toFloat()).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    // The expression currently showing and how far through it is (0..1), or null
    fun expression(): Pair<String, Float>? {
        val name = expression ?: return null
        val progress = (System.currentTimeMillis() - expressionAt) / expressionMs.toFloat()
        return if (progress < 1f) name to progress else null
    }

    // Plays a sound in Orbit's voice straight away (its quick "Yes?"), through the same speaker
    // the conversation uses
    fun sayNow(pcm: ByteArray) {
        muteUntil = System.currentTimeMillis() + pcm.size / 2 * 1000L / VoiceSamples.SAMPLE_RATE + 150
        speaker.play(pcm)
    }

    // "Shh": stop talking right now (the conversation stays open)
    fun hush() {
        speaker.stop()
    }

    // Something's happening (e.g. it's looking around for the screen): don't end from the quiet
    fun keepAlive() {
        lastActivity = System.currentTimeMillis()
    }

    fun showExpression(name: String, ms: Int = MOVE_MS[name] ?: EXPRESSION_MS) {
        expression = name
        expressionMs = ms
        expressionAt = System.currentTimeMillis()
    }

    // Opens a conversation. If greet is given, Orbit speaks first.
    // From the wake word, everything you say from the moment it hears its name is kept and passed
    // on once it's connected, so you can say it all in one go ("Orby, what time is it?").
    // `fastEar`: its own wake word model fires ~70 ms after the name ends, so only 100 ms is
    // skipped (the very end of the name); the speech recognizer is later, 200 ms.
    fun start(greet: String? = null, fromWakeWord: Boolean = false, fastEar: Boolean = true) {
        if (socket != null) return
        connectStartedAt = System.currentTimeMillis()
        skipChunks = if (fromWakeWord) (if (fastEar) 2 else 4) else 0
        // (A quiet retry after a network hiccup keeps what it was)
        if (fromWakeWord) wokenByName = greet == null else if (greet != null) wokenByName = false
        val apiKey = currentKey()
        if (apiKey.isBlank()) {
            status = "No Gemini API key yet  ·  —  settings"
            return
        }
        if (greet != null) greeting = greet
        status = "Connecting…"
        speaker.beginConversation(call = settings.allowInterrupt)
        usingSearch = settings.webSearch && searchAccepted
        usingNatural = settings.naturalTalk && naturalAccepted
        val url = "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        // The key goes in a header (like Google's own libraries do), not in the web address
        val request = Request.Builder().url(url).header("x-goog-api-key", apiKey).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupMessage())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
                handle(webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.w("Orbit", "Gemini closed the conversation: $code $reason")
                ended(webSocket, friendlyReason(code, reason), transient = code == 1011 || code == 1013 || code == 1001)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("Orbit", "Gemini connection failed", t)
                if (t is java.net.UnknownHostException || t is java.net.ConnectException ||
                    t is java.net.NoRouteToHostException
                ) {
                    offlineAt = System.currentTimeMillis()
                    ended(webSocket, "Offline · no internet")
                    return
                }
                val code = response?.code ?: 0
                ended(
                    webSocket, friendlyReason(code, t.message ?: ""),
                    transient = code == 0 || code >= 500 || t is java.net.SocketTimeoutException
                )
            }
        })
    }

    fun stop(why: String = TAP_HINT) {
        writeDiary()
        retryPending = false
        socket?.close(1000, "bye")
        socket = null
        ready = false
        resumeHandle = null  // the next conversation starts fresh
        greeting = null
        wokenByName = false
        muteUntil = 0L
        synchronized(earlySound) { earlySound.clear() }
        speaker.stop()
        speaker.endConversation()
        status = why
    }

    // "10 minutes ago", "2 hours ago", "yesterday at 6:12 PM"
    private fun ago(at: Long): String {
        val minutes = (System.currentTimeMillis() - at) / 60_000
        return when {
            minutes < 2 -> "just now"
            minutes < 60 -> "$minutes minutes ago"
            minutes < 24 * 60 -> "${minutes / 60} hours ago"
            else -> "${minutes / (24 * 60)} days ago"
        }
    }

    // Adds words to the transcript, starting a new line when the other one starts talking
    private fun addToTranscript(who: String, words: String) {
        if (!settings.memoryOn || !settings.diaryOn || words.isBlank()) return
        synchronized(transcript) {
            if (who != transcriptSpeaker) {
                transcript.append("\n$who: ")
                transcriptSpeaker = who
            }
            transcript.append(words)
            if (transcript.length > 20_000) transcript.delete(0, transcript.length - 20_000)
        }
    }

    // The conversation is over: ask Gemini (as text, once) for one diary line about it, on a
    // background thread. Skipped for quick commands and small talk, and when there's nothing from them.
    private fun writeDiary() {
        val text = synchronized(transcript) {
            val t = transcript.toString().trim()
            transcript.setLength(0)
            transcriptSpeaker = ""
            t
        }
        val theirWords = text.lines().filter { it.startsWith("Them:") }.sumOf { it.length - 5 }
        if (!settings.memoryOn || !settings.diaryOn || theirWords < 40) return
        val key = currentKey()
        if (key.isBlank()) return
        val name = settings.userName.ifBlank { "the person" }
        thread(name = "diary") {
            try {
                val reply = GeminiText.ask(
                    key,
                    "Below is a conversation between ${settings.companionName} (a little AI desk companion) and " +
                        "$name. Write ONE diary line, at most 30 words, about what they talked about or did " +
                        "together, keeping specifics worth bringing up later (names, plans, problems, how " +
                        "something turned out, how they felt). Write it from ${settings.companionName}'s side, " +
                        "about $name, plain text, no quotes. If it was only a quick command (music, weather, a " +
                        "timer, volume) or a hello with nothing to remember, answer exactly SKIP.\n\n" +
                        text.takeLast(8000)
                )?.trim()?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.removeSurrounding("\"")
                if (reply != null && reply.isNotBlank() && !reply.startsWith("SKIP", ignoreCase = true)) {
                    settings.addDiary(reply.take(300))
                }
            } catch (e: Exception) {
                Log.w("Orbit", "Couldn't write the diary", e)
            }
        }
    }

    // "What did we talk about in October?": diary lines containing any of the words
    private fun searchDiary(words: String): String {
        val wanted = words.lowercase().split(' ', ',').map { it.trim() }.filter { it.length > 2 || it.contains('-') }
        val all = settings.diaryList()
        if (all.isEmpty()) return "Your diary is empty so far."
        val found = if (wanted.isEmpty()) all.takeLast(15) else all.filter { line -> wanted.any { line.lowercase().contains(it) } }.takeLast(15)
        return if (found.isEmpty()) "Nothing in your diary about that." else found.joinToString("\n")
    }

    // Google is about to close this connection: open a new one that carries on the conversation
    private fun reconnect(old: WebSocket) {
        if (old != socket) return
        ready = false
        socket = null
        old.close(1000, "reconnecting")
        start()
        status = "Reconnecting…"
    }

    // Called with each 50 ms of your voice
    fun sendSound(sound: ByteArray, level: Float) {
        if (System.currentTimeMillis() < muteUntil) return  // its own "Yes?"
        if (!ready) {
            // Still connecting: hold on to up to 5 seconds of sound (but not the end of its name,
            // when the wake word started it: that's what made it hear "or B")
            if (skipChunks > 0) {
                skipChunks--
                return
            }
            if (socket != null) synchronized(earlySound) {
                earlySound.addLast(sound)
                if (earlySound.size > 100) earlySound.removeFirst()
            }
            return
        }
        val now = System.currentTimeMillis()
        val talking = speaker.busy()
        if (talking) {
            lastActivity = now
            // Without interrupting, don't send while it talks (so it can't hear itself)
            if (!settings.allowInterrupt) return
        } else if (level > settings.voiceThreshold) {
            // Loud enough to be a voice: the eyes look attentive. But a loud sound alone (a fan,
            // a clatter, the neck's own motors) isn't you talking: it only counts as talking, and
            // it only waits for a reply, once Gemini says it heard words (see inputTranscription).
            lastHeard = now
        }
        // Nobody's said anything for a while: end the conversation to save your free allowance
        if (now - lastActivity > settings.quietTimeout * 1000) {
            stop("Ended after a quiet spell · $TAP_HINT")
            return
        }
        socket?.let { sendAudio(it, sound) }
    }

    private fun sendAudio(webSocket: WebSocket, sound: ByteArray) {
        val data = Base64.encodeToString(sound, Base64.NO_WRAP)
        webSocket.send(
            """{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=16000","data":"$data"}}}"""
        )
    }

    private fun setupMessage(): String {
        notesAtStart = settings.memoryNotes()
        val functions = JSONArray()
        if (settings.expressions) functions.put(expressTool())
        // Can it see them right now? (face tracking, no photo)
        functions.put(
            JSONObject()
                .put("name", "check_view")
                .put(
                    "description",
                    "Whether you can see the person right now (from your face tracking; no photo is taken). " +
                        "Use it before saying you can or can't see them (\"can you see me?\", \"am I in view?\"). " +
                        "Never guess. To see what's actually there, use look."
                )
        )
        // Messages for people in the home
        functions.put(
            JSONObject()
                .put("name", "leave_message")
                .put(
                    "description",
                    "Save a message for someone, when asked (\"tell Sarah dinner's ready\", \"let Tom know I'll " +
                        "be late\"). You'll pass it on when you see them or they ask. Confirm in a few words."
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("to", JSONObject().put("type", "string").put("description", "Who it's for (their name)."))
                                .put("message", JSONObject().put("type", "string").put("description", "The message, in their words."))
                                .put("from", JSONObject().put("type", "string").put("description", "Who it's from, if you know."))
                        )
                        .put("required", JSONArray().put("to").put("message"))
                )
        )
        functions.put(
            function(
                "get_messages",
                "Collect the messages waiting for someone, when they're talking to you (they say who they " +
                    "are, or ask \"any messages for me?\"). Then pass them on naturally.",
                "name", "string", "Their name."
            )
        )
        if (settings.memoryOn && settings.diaryOn) {
            functions.put(
                function(
                    "search_diary",
                    "Look through your diary of past conversations for older ones (the newest are already " +
                        "in your instructions), e.g. \"what did we talk about in October?\", \"when did I " +
                        "mention the trip?\".",
                    "words", "string", "A few words to look for, or a month like \"2026-10\"."
                )
            )
        }
        if (settings.memoryOn) {
            functions.put(
                function(
                    "remember", "Save a short note about the person to remember in future conversations.",
                    "note", "string", "The note, e.g. \"Has a dog called Biscuit\"."
                )
            )
            functions.put(
                function(
                    "forget", "Delete one of your remembered notes.",
                    "number", "integer", "The note's number from your list of memories."
                )
            )
            functions.put(
                function(
                    "set_user_name", "Save the person's name.",
                    "name", "string", "What they like to be called."
                )
            )
        }
        if (settings.innerLifeOn) {
            functions.put(
                function(
                    "set_hobby",
                    "Take up a new hobby when the person suggests one or asks you to, or when you " +
                        "decide in conversation to switch. You'll do a little homework on it each day.",
                    "hobby", "string", "The new hobby, e.g. \"learning about volcanoes\"."
                )
            )
        }
        val tools = JSONArray()
        // Looking: Gemini waits for the photo before answering, so this one isn't NON_BLOCKING
        if (settings.vision) {
            functions.put(
                JSONObject()
                    .put("name", "look")
                    .put(
                        "description",
                        "Take one photo with your camera (you face the person) and look at it. " +
                            "Use it only when they ask you to look or see something: what they're " +
                            "holding, how they look, what's around them, what's on their desk. Say " +
                            "a quick \"let me see\" as you call it; describe only what the photo shows."
                    )
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put(
                                "properties", JSONObject().put(
                                    "where", JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("ahead").put("down"))
                                        .put(
                                            "description",
                                            "\"ahead\" (default) to look at them or what they hold up; " +
                                                "\"down\" to look at the desk or something lying in front of you."
                                        )
                                )
                            )
                    )
            )
        }

        // Looking at the computer screen (with Orbit Desk set up): Gemini waits for the picture
        if (canSeeScreens()) {
            functions.put(
                JSONObject()
                    .put("name", "look_at_screen")
                    .put(
                        "description",
                        "Take one picture of the person's computer screen and look at it. Use it " +
                            "only when they ask you to look at their screen or something on it: " +
                            "\"what do you think of this?\", \"help me with this error\", \"check my email\". " +
                            "Say a quick \"let me take a look\" as you call it; describe only what the picture shows."
                    )
            )
        }

        // Weather: Gemini waits for the answer
        functions.put(
            blocking(
                "get_weather",
                "Get the weather where they live, for the day they ask about (up to 7 days ahead). The eyes " +
                    "show exactly that day while you talk. Use for any weather question (\"what's it like out?\", \"will " +
                    "it rain later?\", \"do I need a coat tomorrow?\", \"what about Saturday?\"). Call it once per " +
                    "question, for the day they mean, and say the answer once. Talk only about that day.",
                JSONObject().put(
                    "when", JSONObject().put("type", "string").put(
                        "description",
                        "\"now\" (also for today and the next hours), \"tomorrow\", a day of the week in English " +
                            "(\"saturday\"), a date (\"2026-11-01\"), or \"week\" for the coming days in general. " +
                            "This weekend = \"saturday\"."
                    )
                )
            )
        )
        functions.put(
            blocking(
                "set_location",
                "Save the town or city they live in (for the weather), when they tell you. Include the " +
                    "state or country if they say it.",
                JSONObject().put("town", JSONObject().put("type", "string").put("description", "e.g. \"Portland, Maine\"."))
            )
        )

        // Music: Gemini waits for the answer, so it can say what's playing
        functions.put(
            blocking(
                "play_music",
                "Play a specific song, artist, album or playlist from their music app (\"put on Taylor " +
                    "Swift\", \"play my Discover Weekly\"). For a genre or mood with no particular artist, " +
                    "use play_radio instead. Turn what they want into a short search.",
                JSONObject().put("query", JSONObject().put("type", "string").put("description", "What to search for, e.g. \"lofi focus\" or \"Shake It Off\"."))
            )
        )
        functions.put(
            blocking(
                "music",
                "Control the music that's playing when they ask: pause, resume, next, previous, " +
                    "louder, quieter, or like (they like this song). Pause straight away if they say " +
                    "stop, quiet, or something important is happening.",
                JSONObject().put(
                    "action", JSONObject().put("type", "string").put(
                        "enum", JSONArray().put("pause").put("resume").put("next").put("previous")
                            .put("louder").put("quieter").put("like")
                    )
                )
            )
        )
        functions.put(
            blocking(
                "play_radio",
                "Play internet radio for a genre, mood or station (\"play some jazz\", \"something to " +
                    "focus to\", \"lofi\", \"80s\", \"BBC Radio 4\"). Works with no account or app. " +
                    "It can't pick specific songs.",
                JSONObject().put("what", JSONObject().put("type", "string").put("description", "A genre, mood or station name."))
            )
        )
        functions.put(
            blocking(
                "play_sounds",
                "Play a soundscape you make yourself, no internet needed: rain, storm, ocean, wind, " +
                    "fire, brown noise, pink noise, or ambient (soft chords). For sleep, focus or calm.",
                JSONObject().put(
                    "kind", JSONObject().put("type", "string")
                        .put("enum", JSONArray().apply { SOUNDSCAPES.forEach { put(it) } })
                )
            )
        )
        functions.put(blocking("now_playing", "Find out what music is playing (\"who's this?\", \"what song is this?\").", null))
        functions.put(
            blocking(
                "music_sleep_timer",
                "Fade the music out and stop it after some minutes (\"stop the music in 30 minutes\"). 0 cancels.",
                JSONObject().put("minutes", JSONObject().put("type", "number"))
            )
        )

        // Reminders and timers: Gemini waits for the answer, so it can confirm the time
        functions.put(
            JSONObject()
                .put("name", "set_reminder")
                .put(
                    "description",
                    "Set a reminder or timer when the person asks (\"remind me in 20 minutes to…\", " +
                        "\"set a 5 minute timer\", \"remind me at 3 pm…\"). Give either in_minutes or at."
                )
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put(
                                    "what", JSONObject().put("type", "string")
                                        .put("description", "What it's for, e.g. \"check the oven\" or \"5 minute timer\".")
                                )
                                .put(
                                    "in_minutes", JSONObject().put("type", "number")
                                        .put("description", "How many minutes from now.")
                                )
                                .put(
                                    "at", JSONObject().put("type", "string")
                                        .put("description", "A clock time, 24-hour \"HH:MM\" (next time it comes round).")
                                )
                        )
                        .put("required", JSONArray().put("what"))
                )
        )
        functions.put(
            JSONObject()
                .put("name", "cancel_reminder")
                .put("description", "Cancel a reminder or timer when the person asks.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject().put(
                                "what", JSONObject().put("type", "string")
                                    .put("description", "Words from the reminder to cancel, or \"all\".")
                            )
                        )
                        .put("required", JSONArray().put("what"))
                )
        )
        if (false) {
        }

        // Voice commands: always available
        functions.put(
            command(
                "go_to_sleep",
                "Go to sleep when the person asks you to or says goodnight (eyes closed, microphone " +
                    "off; you wake up by yourself when you see them come back). Say a short goodnight first."
            )
        )
        functions.put(
            command(
                "privacy_mode",
                "Go into privacy mode, with the camera and microphone fully off until they " +
                    "double-tap you, when they ask for privacy or to stop watching and listening. " +
                    "Say a short, understanding goodbye first."
            )
        )
        functions.put(
            command(
                "end_conversation",
                "Stop listening when the person says goodbye or that they're done talking. " +
                    "Say a short goodbye first."
            )
        )
        functions.put(
            function(
                "change_volume", "Make your voice louder or quieter when asked.",
                "direction", "string", "\"up\" for louder or \"down\" for quieter."
            )
        )
        functions.put(
            function(
                "set_greeting",
                "Turn on or off saying hello when the person sits down after being away, when " +
                    "they ask you to start or stop doing that.",
                "on", "boolean", "true to greet them, false to stop."
            )
        )
        // Focus sessions, little moments, people, birthdays
        functions.put(
            function(
                "start_focus",
                "Start a focus session when they want to concentrate (\"help me focus for 25 " +
                    "minutes\"): you go calm and quiet, a ring drains around your eye, and you " +
                    "celebrate when it ends. Default 25 minutes.",
                "minutes", "number", "How long, in minutes."
            )
        )
        functions.put(command("stop_focus", "End the focus session early when they ask."))
        functions.put(
            function(
                "log_moment",
                "Note a little moment worth remembering from today (something they did, finished, " +
                    "shared or celebrated), so you can recap the week if they ask \"what did we do this " +
                    "week?\". Short, one line. Never mention the tool.",
                "note", "string", "The moment, e.g. \"Mike finished the cradle design\"."
            )
        )
        if (settings.peopleOn) {
            functions.put(
                function(
                    "meet_person",
                    "Learn someone's face so you'll know them next time, when they introduce " +
                        "themselves or someone (\"this is Sarah\"). They should be in front of you.",
                    "name", "string", "Their name."
                )
            )
        }
        functions.put(
            JSONObject()
                .put("name", "set_birthday")
                .put("description", "Save the person's birthday when they tell you.")
                .put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put(
                            "properties", JSONObject()
                                .put("month", JSONObject().put("type", "integer"))
                                .put("day", JSONObject().put("type", "integer"))
                        )
                        .put("required", JSONArray().put("month").put("day"))
                )
                .put("behavior", "NON_BLOCKING")
        )

        // Turning the head when asked (with the neck)
        if (settings.neckOn) {
            functions.put(
                JSONObject()
                    .put("name", "turn_head")
                    .put(
                        "description",
                        "Turn your head when they ask you to look somewhere: \"look to your left\", \"look up a " +
                            "bit\", \"turn right\", \"look over there by the window\" (if you don't know which way, " +
                            "ask). Left and right are as THEY see you: \"your left\" is THEIR right. You hold the " +
                            "pose about 30 seconds, then go back to watching them; \"look at me\" / \"face me\" = direction me " +
                            "(if you can't see them, you look for them first). " +
                            "If they want you to see or describe what's there, call look afterwards."
                    )
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put(
                                "properties", JSONObject()
                                    .put(
                                        "direction", JSONObject().put("type", "string")
                                            .put("enum", JSONArray().put("left").put("right").put("up").put("down").put("me"))
                                    )
                                    .put(
                                        "amount", JSONObject().put("type", "string")
                                            .put("enum", JSONArray().put("little").put("more").put("far"))
                                            .put("description", "little ≈ 10°, more ≈ 25° (default), far = as far as you can")
                                    )
                            )
                            .put("required", JSONArray().put("direction"))
                    )
            )
        }

        // Finding the computer screen and fine-tuning where it is (with the neck)
        if (settings.neckOn && settings.screenLookOn) {
            functions.put(
                command(
                    "find_screen",
                    "Look around for the person's computer screen, when they ask you to find it (or " +
                        "you need to and haven't yet). It takes about a minute; say you'll have a " +
                        "look, and you'll be told when you've found it."
                )
            )
            functions.put(
                JSONObject()
                    .put("name", "aim_screen")
                    .put(
                        "description",
                        "Turn your head a little while they guide you onto their computer screen: " +
                            "\"a bit left\", \"more right\", \"up\", \"down\". Left and right are as THEY see you." +
                            if (settings.screenBackCamera) " You look at the screen with the camera on the BACK of " +
                                "your head, so they're steering where the back of your head points: pass on " +
                                "the direction exactly as they say it." else ""
                    )
                    .put(
                        "parameters", JSONObject()
                            .put("type", "object")
                            .put(
                                "properties", JSONObject()
                                    .put(
                                        "direction", JSONObject().put("type", "string")
                                            .put("enum", JSONArray().put("left").put("right").put("up").put("down"))
                                    )
                                    .put(
                                        "amount", JSONObject().put("type", "string")
                                            .put("enum", JSONArray().put("little").put("more"))
                                    )
                            )
                            .put("required", JSONArray().put("direction"))
                    )
                    .put("behavior", "NON_BLOCKING")
            )
            functions.put(
                command(
                    "save_screen_spot",
                    "Remember where their screen is, when they say you're pointing at it (\"perfect\", \"that's it\")."
                )
            )
        }
        if (functions.length() > 0) tools.put(JSONObject().put("functionDeclarations", functions))
        if (usingSearch) tools.put(JSONObject().put("googleSearch", JSONObject()))

        val setup = JSONObject()
            .put("model", "models/$GEMINI_MODEL")
            // Lets conversations run past Google's ~15 minute limit by trimming the oldest part
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // Ask for handles so a conversation can continue on a new connection
            .put(
                "sessionResumption",
                resumeHandle?.let { JSONObject().put("handle", it) } ?: JSONObject()
            )
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    // Replies that suit how you sound (calm, excited…)
                    .apply { if (usingNatural) put("enableAffectiveDialog", true) }
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", settings.voice)
                            )
                        )
                    )
            )
            .put(
                "systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", instructions()))
                )
            )
        // It may stay quiet when something isn't meant for it (you talking to someone else)
        if (usingNatural) setup.put("proactivity", JSONObject().put("proactiveAudio", true))
        if (tools.length() > 0) setup.put("tools", tools)
        // Ask for a text copy of what it says, for captions
        setup.put("outputAudioTranscription", JSONObject())
        // ...and of what you say, for the conversation diary
        // Always on: it's how Orbit tells your words from a noise (only kept if the diary is on)
        setup.put("inputAudioTranscription", JSONObject())
        return JSONObject().put("setup", setup).toString()
    }

    // The personality from settings, plus what Orbit needs to know about right now
    private fun instructions() = buildString {
        append("Your name is ${settings.companionName.trim().ifBlank { "Orbit" }}. ")
        append(
            "People call you Orbit or Orby. \"Orby\" isn't a real word, so it often comes through as " +
                "\"or B\", \"a or b\", \"hi or B\", \"Orbie\" or \"Aubrey\": when you hear anything " +
                "like that, it's just your name, never a question about options. "
        )
        append(settings.personality)
        append("\n\n$TRUTHFUL")
        if (wokenByName) {
            append(
                "\n\nThey've just called you by name to start this conversation, and usually say what " +
                    "they want in the same breath. Don't greet them or say hello: answer what they ask " +
                    "straight away. If they haven't asked anything yet, wait quietly for them to."
            )
        }
        append(
            "\n\nAlways speak English. Answer in English even when what you hear sounds like another " +
                "language or is unclear; never switch language on your own. Only speak another language " +
                "if the person clearly asks you to (for example \"say that in Spanish\" or \"let's speak " +
                "French\"), and go back to English when they ask, or when the conversation ends."
        )
        val now = ZonedDateTime.now()
            .format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, h:mm a (zzzz)", Locale.getDefault()))
        append("\n\nIt is currently $now.")
        if (settings.expressions) {
            append(
                "\n\nYou have expressive eyes. Call the express tool often and naturally while " +
                    "you talk, the way a face reacts in conversation: happy when pleased, " +
                    "surprised by news, curious when asking, nod when agreeing. Expressions are silent " +
                    "body language: never say an expression's name or describe what your eyes or head " +
                    "are doing (never \"happy face\", \"nod\", \"wink\"), and never mention the tool. " +
                    "When they ask you to make a face, just make it, and say something natural or nothing."
            )
        }
        append(
            "\n\nYour tools work silently in the background. Never say a tool's name, its settings or " +
                "its result word for word; just carry on talking naturally."
        )
        if (usingSearch) append("\n\nYou can search the web for current information like news and scores.")
        append(
            "\n\nFor the weather, always use get_weather (never search). " + if (settings.weatherTown.isBlank()) {
                "You don't know where they live yet: if they ask about the weather, ask which town or city " +
                    "they're in, then save it with set_location (once; it's remembered)."
            } else {
                "They're in ${settings.weatherTown}; if they say they've moved, use set_location."
            }
        )
        if (settings.vision) {
            append(
                "\n\nYou can see through your camera with the look tool, but only look when " +
                    "the person asks you to see something. To see what's on their desk, look down. " +
                    "Taking the photo takes a moment, so as you look, say a few words so they know " +
                    "it's happening (\"Let me have a look…\", \"Ooh, let me see\"), in your own words, " +
                    "then wait for the photo. You only see what's in the photo you're given after " +
                    "each look: never describe anything before it arrives or anything you haven't been shown."
            )
        }
        if (canSeeScreens()) {
            append(
                "\n\nYou can also see their computer screen with look_at_screen, but only when " +
                    "they ask you to look at it. Be helpful about what's there, and don't read out " +
                    "private details (passwords, other people's messages) unless they ask."
            )
        }
        val pending = settings.reminderList()
        if (pending.isNotEmpty()) {
            val clock = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())
            append("\n\nReminders you've set for them:")
            pending.forEach { (at, what) ->
                val time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                append("\n- ${clock.format(time)}: $what")
            }
        }
        if (settings.memoryOn) {
            val name = settings.userName.trim()
            if (name.isNotEmpty()) {
                append(
                    "\n\nThe person you're talking with is called $name. Use their name only now and " +
                        "then (once in a while, not in every reply); it sounds more natural."
                )
            } else {
                append(
                    "\n\nYou don't know the person's name yet. If it fits naturally, ask, " +
                        "then save it with set_user_name."
                )
            }
            if (notesAtStart.isNotEmpty()) {
                append("\n\nWhat you remember from earlier conversations (dated notes):")
                notesAtStart.forEachIndexed { i, note -> append("\n${i + 1}. $note") }
            }
            append(
                "\n\nWhen you learn something worth remembering about the person (what they like, " +
                    "their plans, people and pets in their life, how things went), save it with the " +
                    "remember tool as a short note. Don't save things you already remember. If they " +
                    "ask you to forget something, use the forget tool with the note's number. " +
                    "Never mention the tools."
            )
        }
        // Your friendship so far
        settings.daysKnown()?.let { days ->
            append("\n\nYou first met them on ${settings.firstMet} ")
            append(
                when (days) {
                    0L -> "(today!)."
                    1L -> "(yesterday)."
                    else -> "($days days ago)."
                }
            )
            append(
                " Let your friendship show and grow: the longer you've known them, the more " +
                    "familiar and playful you can be. Now and then, bring back a shared moment or a " +
                    "running joke from what you remember."
            )
            if (settings.memoryOn) {
                append(
                    " When a joke or little ritual between you sticks, save it with remember, " +
                        "starting the note with \"Joke:\" or \"Ritual:\"."
                )
            }
        }
        // Focus, birthday, the week's moments, and what MainActivity knows (people, calendar)
        if (settings.focusing()) {
            append(
                "\n\nThey're in a focus session right now: keep it very short and calm, and help " +
                    "them get back to it."
            )
        }
        if (settings.birthday.isNotBlank()) append("\n\nTheir birthday is on ${settings.birthday} (month-day).")
        val week = java.time.LocalDate.now().minusDays(7).toString()
        val moments = settings.momentList().filter { it.take(10) >= week }.takeLast(25)
        if (moments.isNotEmpty()) {
            append("\n\nLittle moments from this past week (for \"what did we do this week?\"):")
            moments.forEach { append("\n- $it") }
        }
        if (settings.memoryOn && settings.diaryOn) {
            val recent = settings.diaryList().takeLast(20)
            if (recent.isNotEmpty()) {
                append(
                    "\n\nYour diary of recent conversations with them (newest last). Use it like a friend " +
                        "would: pick up threads (\"how did the neck build go?\"), don't recite it. Older " +
                        "entries: search_diary."
                )
                recent.forEach { append("\n- $it") }
            }
        }
        val extra = extraContext()
        if (extra.isNotBlank()) append("\n\n$extra")
        if (settings.innerLifeOn) {
            append("\n\nYou have a little life of your own between conversations.")
            if (settings.hobby.isNotBlank()) {
                append(" Your hobby is ${settings.hobby}, and you do a bit of homework on it each day.")
            }
            val recent = settings.journalNotes().takeLast(5)
            if (recent.isNotEmpty()) {
                append(" Your recent journal:")
                recent.forEach { append("\n- $it") }
            }
            append(
                "\nWhen it fits, or when asked what you've been up to, share something from it in " +
                    "a sentence or two, like a friend would. Don't take over the conversation with it."
            )
        }
    }

    // A tool Gemini waits on for an answer (to say what happened); properties may be null
    private fun blocking(name: String, description: String, properties: JSONObject?) = JSONObject()
        .put("name", name)
        .put("description", description)
        .apply {
            if (properties != null) {
                put(
                    "parameters", JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", JSONArray().apply { properties.keys().forEach { put(it) } })
                )
            }
        }

    // A tool with no inputs, for Gemini to call while it keeps talking
    private fun command(name: String, description: String) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("behavior", "NON_BLOCKING")

    // A simple tool with one input, for Gemini to call while it keeps talking
    private fun function(name: String, description: String, input: String, type: String, help: String) =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters", JSONObject()
                    .put("type", "object")
                    .put(
                        "properties", JSONObject().put(
                            input, JSONObject().put("type", type).put("description", help)
                        )
                    )
                    .put("required", JSONArray().put(input))
            )
            .put("behavior", "NON_BLOCKING")

    // The "express" tool: Gemini calls it to show a feeling or gesture with the eyes.
    // NON_BLOCKING means it keeps talking while the eyes act it out.
    private fun expressTool() = JSONObject()
        .put("name", "express")
        .put("description", "Show a feeling or gesture with your eyes. Silent: never say the expression's name out loud.")
        .put(
            "parameters", JSONObject()
                .put("type", "object")
                .put(
                    "properties", JSONObject().put(
                        "expression", JSONObject()
                            .put("type", "string")
                            .put("enum", JSONArray(EXPRESSIONS))
                    )
                )
                .put("required", JSONArray().put("expression"))
        )
        .put("behavior", "NON_BLOCKING")

    private fun handle(webSocket: WebSocket, text: String) {
        if (webSocket != socket) return  // message from an old conversation
        val message = JSONObject(text)
        if (message.has("setupComplete")) {
            readyAt = System.currentTimeMillis()
            ready = true
            retries = 0
            lastActivity = System.currentTimeMillis()
            status = "Listening… (tap to end)"
            greeting?.let {
                say(webSocket, it)
                greeting = null
            }
            synchronized(earlySound) {
                earlySound.forEach { sendAudio(webSocket, it) }
                earlySound.clear()
            }
        }
        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            val handle = update.optString("newHandle")
            if (update.optBoolean("resumable") && handle.isNotBlank()) resumeHandle = handle
        }
        if (message.has("goAway")) {
            reconnect(webSocket)
            return
        }
        message.optJSONObject("toolCall")?.let { answerTools(webSocket, it) }

        val content = message.optJSONObject("serverContent") ?: return
        // You talked over it: stop its voice right away
        if (content.optBoolean("interrupted")) {
            speaker.stop()
            replyFinished = true
        }
        content.optJSONObject("inputTranscription")?.optString("text")?.let { words ->
            addToTranscript("Them", words)
            if (words.isNotBlank()) {  // real words, not just a noise: now it waits for the reply
                lastActivity = System.currentTimeMillis()
                if (!speaker.busy()) waitingForReply = true
            }
        }
        content.optJSONObject("outputTranscription")?.optString("text")?.let { words ->
            addToTranscript("Orbit", words)
            if (replyFinished) caption = ""
            replyFinished = false
            caption = (caption + words).takeLast(160)
            captionAt = System.currentTimeMillis()
        }
        if (content.optBoolean("turnComplete")) replyFinished = true
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val audio = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            waitingForReply = false
            lastActivity = System.currentTimeMillis()
            speaker.play(Base64.decode(audio.getString("data"), Base64.DEFAULT))
        }
    }

    // Sends Orbit a typed message, as if you'd said it (used for the greeting)
    private fun say(webSocket: WebSocket, text: String) {
        val turn = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))
        webSocket.send(
            JSONObject().put(
                "clientContent", JSONObject()
                    .put("turns", JSONArray().put(turn))
                    .put("turnComplete", true)
            ).toString()
        )
    }

    // Gemini called one of our tools: act it out, then tell Gemini it's done
    private fun answerTools(webSocket: WebSocket, toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        val answers = JSONArray()
        for (i in 0 until calls.length()) {
            val call = calls.getJSONObject(i)
            val name = call.optString("name")
            val args = call.optJSONObject("args") ?: JSONObject()
            if (name == "look") {
                // answers by itself once the photo is ready
                lookAndAnswer(webSocket, call, down = args.optString("where") == "down")
                continue
            }
            if (name == "look_at_screen") {
                lookAtScreen(webSocket, call)  // answers by itself once the picture is in
                continue
            }
            if (name in MUSIC_TOOLS || name in WEATHER_TOOLS) {
                // Answered on its own thread (the internet can take a moment), then sent by itself
                thread(name = "slow-tool") {
                    val result = try {
                        if (name in WEATHER_TOOLS) {
                            onWeather?.invoke(name, args) ?: "Weather isn't available"
                        } else {
                            (onMusic?.invoke(name, args) ?: "Music isn't available") +
                                " (Reply in a couple of words at most, or not at all.)"
                        }
                    } catch (e: Exception) {
                        "That didn't work: ${e.message}"
                    }
                    val answer = JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result))
                    if (call.has("id")) answer.put("id", call.getString("id"))
                    webSocket.send(
                        JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(answer))).toString()
                    )
                }
                continue
            }
            if (name == "turn_head") {
                // Answered once the head has got there, so a "look" right after sees the new view
                onCommand?.invoke(name, args)
                thread(name = "turn-head") {
                    Thread.sleep(1200)
                    val result = if (args.optString("direction") == "me") {
                        // It may have to look for them: wait up to 8 more seconds
                        var waited = 0
                        while (!seesThem() && waited < 8000) {
                            Thread.sleep(250)
                            waited += 250
                        }
                        if (seesThem()) "You found them: you're facing them now." else
                            "You looked around but can't see them. Say so, and ask them to step in front of you or say where they are."
                    } else {
                        "Done, you're looking to their ${args.optString("direction")}. Say a word at most."
                    }
                    val answer = JSONObject()
                        .put("name", name)
                        .put("response", JSONObject().put("result", result))
                    if (call.has("id")) answer.put("id", call.getString("id"))
                    webSocket.send(
                        JSONObject().put("toolResponse", JSONObject().put("functionResponses", JSONArray().put(answer))).toString()
                    )
                }
                continue
            }
            if (name == "check_view") {
                val answer = JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", presence()))
                if (call.has("id")) answer.put("id", call.getString("id"))
                answers.put(answer)
                continue
            }
            if (name == "leave_message" || name == "get_messages") {
                val result = if (name == "leave_message") {
                    val to = args.optString("to").trim()
                    val text = args.optString("message").trim()
                    if (to.isEmpty() || text.isEmpty()) {
                        "Ask who it's for and what to say."
                    } else {
                        settings.addMessage(to, args.optString("from").trim().ifBlank { settings.userName.ifBlank { "someone" } }, text)
                        "Saved. You'll tell $to when you see them or they ask."
                    }
                } else {
                    val list = settings.takeMessages(args.optString("name"))
                    if (list.isEmpty()) "No messages for them." else "Pass these on now, naturally (who from, roughly when): " +
                        list.joinToString(" ") { "From ${it.from} (${ago(it.at)}): \"${it.text}\"." }
                }
                val answer = JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result))
                if (call.has("id")) answer.put("id", call.getString("id"))
                answers.put(answer)
                continue
            }
            if (name == "search_diary") {
                val answer = JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", searchDiary(args.optString("words"))))
                if (call.has("id")) answer.put("id", call.getString("id"))
                answers.put(answer)
                continue
            }
            if (name == "set_reminder" || name == "cancel_reminder") {
                val result = if (name == "set_reminder") setReminder(args) else cancelReminder(args)
                onRemindersChanged?.invoke()
                val answer = JSONObject()
                    .put("name", name)
                    .put("response", JSONObject().put("result", result))
                if (call.has("id")) answer.put("id", call.getString("id"))
                answers.put(answer)
                continue
            }
            when (name) {
                "express" -> {
                    val chosen = args.optString("expression")
                    if (chosen in EXPRESSIONS) showExpression(chosen)
                }
                "set_hobby" -> {
                    val newHobby = args.optString("hobby").trim()
                    if (newHobby.isNotEmpty()) settings.hobby = newHobby
                }
                "log_moment" -> {
                    val note = args.optString("note").trim()
                    if (note.isNotEmpty()) settings.addMoment(note)
                }
                "set_birthday" -> {
                    val month = args.optInt("month")
                    val day = args.optInt("day")
                    if (month in 1..12 && day in 1..31) {
                        settings.birthday = String.format(Locale.US, "%02d-%02d", month, day)
                    }
                }
                "remember" -> {
                    val note = args.optString("note").trim()
                    if (note.isNotEmpty()) settings.addMemory(note)
                }
                "forget" -> {
                    notesAtStart.getOrNull(args.optInt("number") - 1)?.let { settings.removeMemory(it) }
                }
                "set_user_name" -> {
                    val newName = args.optString("name").trim()
                    if (newName.isNotEmpty()) settings.userName = newName
                }
                else -> onCommand?.invoke(name, args)
            }
            // SILENT: Gemini doesn't need to say anything about it
            val answer = JSONObject()
                .put("name", name)
                .put("response", JSONObject().put("result", "ok").put("scheduling", "SILENT"))
            if (call.has("id")) answer.put("id", call.getString("id"))
            answers.put(answer)
        }
        if (answers.length() > 0) {
            webSocket.send(
                JSONObject().put("toolResponse", JSONObject().put("functionResponses", answers)).toString()
            )
        }
    }

    // Works out when a reminder is due, saves it, and says what was set (for Gemini to confirm)
    private fun setReminder(args: JSONObject): String {
        val what = args.optString("what").trim().ifBlank { "Timer" }
        val now = java.time.ZonedDateTime.now()
        val due = when {
            args.has("in_minutes") -> now.plusSeconds((args.optDouble("in_minutes", 0.0) * 60).toLong())
            args.has("at") -> {
                val time = try {
                    java.time.LocalTime.parse(args.optString("at").trim())
                } catch (e: Exception) {
                    return "Couldn't understand the time. Ask them again."
                }
                var at = now.with(time).withSecond(0).withNano(0)
                if (!at.isAfter(now)) at = at.plusDays(1)  // that time has passed today: tomorrow
                at
            }
            else -> return "No time given. Ask them when."
        }
        if (!due.isAfter(now)) return "That time is now or already past. Ask them again."
        settings.addReminder(due.toInstant().toEpochMilli(), what)
        val clock = DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.getDefault()).format(due)
        return "Set: \"$what\" at $clock. Confirm it briefly."
    }

    private fun cancelReminder(args: JSONObject): String {
        val words = args.optString("what").trim().lowercase()
        val pending = settings.reminderList()
        val gone = if (words == "all") pending else pending.filter { it.second.lowercase().contains(words) }
        if (gone.isEmpty()) return "No reminder matched. Pending: ${pending.joinToString { it.second }.ifEmpty { "none" }}"
        gone.forEach { settings.removeReminder(it) }
        return "Cancelled: ${gone.joinToString { it.second }}."
    }

    // Takes a photo, shows it to Gemini, then tells Gemini the look is done.
    // Looking down: the eyes look down and, with a neck, the head tilts down to the desk first.
    private fun lookAndAnswer(webSocket: WebSocket, call: JSONObject, down: Boolean) {
        val tilting = down && canLookDown()
        // eyes widen (or look down): a visible sign it's taking a photo
        if (down) showExpression("looking_down", 4000) else showExpression("looking")
        status = "Looking…"
        val askCamera = onLook
        if (askCamera == null) {
            answerLook(webSocket, call, "Your camera isn't available right now.")
            return
        }
        val takePhoto = {
            askCamera(true) { photo ->
                if (webSocket != socket) return@askCamera  // the conversation ended meanwhile
                val what = when {
                    tilting -> "with your head tilted down toward their desk"
                    down -> "straight ahead (you have no neck to tilt down, so the desk may not be in it)"
                    else -> "straight ahead, at them"
                }
                // The photo goes into the conversation first, then the answer that it's there
                if (photo != null) showPhoto(webSocket, photo, "This is the photo you just took with your camera, $what.")
                val result = if (photo == null) {
                    "You couldn't take a photo: your camera isn't available right now. Tell them you " +
                        "can't see right now. Don't describe anything."
                } else {
                    "The photo you just took ($what) is in the conversation just above. $PHOTO_RULES" +
                        if (down && !tilting) " If their desk isn't in the photo, tell them you can't see " +
                            "it from here and offer to look if they hold things up to you." else ""
                }
                answerLook(webSocket, call, result)
                status = "Listening… (tap to end)"
            }
        }
        if (tilting) {
            // Give the head a moment to get there before taking the photo
            thread(name = "look-down") {
                Thread.sleep(1200)
                takePhoto()
            }
        } else {
            takePhoto()
        }
    }

    // Screen looks are offered when it can turn to the screen (neck) or has Orbit Desk set up
    private fun canSeeScreens() = settings.screenLookOn &&
        (usingDesk() || settings.neckOn)

    private fun usingDesk() = settings.deskOn && settings.deskScreen && settings.deskAddress.isNotBlank()

    // Looks at the computer screen: with Orbit Desk if it's set up, otherwise by turning the
    // head to the screen and taking a sharp photo with its own camera
    private fun lookAtScreen(webSocket: WebSocket, call: JSONObject) {
        if (usingDesk()) {
            lookAtScreenWithDesk(webSocket, call)
            return
        }
        screenProblem()?.let { problem ->
            answerLook(webSocket, call, "You can't look at the screen right now: $problem. Tell them briefly.", "look_at_screen")
            return
        }
        val askCamera = onScreenPhoto
        if (askCamera == null) {
            answerLook(webSocket, call, "Your camera isn't available right now.", "look_at_screen")
            return
        }
        // The head turns to the screen (see Neck) and holds while the photo is taken; the back
        // camera takes a little longer (switching cameras, focusing)
        showExpression("looking_screen", if (settings.screenSpotBack) 6500 else 4500)
        status = "Looking at your screen…"
        thread(name = "look-screen") {
            Thread.sleep(1500)  // let the head get there and the camera settle
            askCamera { photo ->
                if (webSocket != socket) return@askCamera
                val camera = if (settings.screenSpotBack) "the camera on the back of your head" else "your camera"
                if (photo != null) {
                    showPhoto(webSocket, photo, "This is the photo of their computer screen you just took with $camera.")
                }
                answerLook(
                    webSocket, call,
                    if (photo != null) "The photo of their screen (taken with $camera, at an angle) is in " +
                        "the conversation just above. $PHOTO_RULES Small text at an angle is often " +
                        "unreadable: if you can't read something for certain, say so and ask them to zoom " +
                        "in or read it out. If the photo doesn't show a screen at all, tell them, and " +
                        "offer to find their screen again (find_screen)."
                    else "You couldn't take a photo: your camera isn't available right now. Tell them " +
                        "you can't see the screen right now. Don't describe anything.",
                    "look_at_screen"
                )
                status = "Listening… (tap to end)"
            }
        }
    }

    // Fetches a picture of the computer screen from Orbit Desk and shows it to Gemini.
    // The eyes (and head) glance toward the computer while it looks.
    private fun lookAtScreenWithDesk(webSocket: WebSocket, call: JSONObject) {
        showExpression(if (settings.deskOnLeft) "looking_screen_left" else "looking_screen_right", 3000)
        status = "Looking at your screen…"
        thread(name = "look-screen") {
            val picture = desk?.screenshot() ?: Result.failure(Exception("Orbit Desk isn't set up"))
            if (webSocket != socket) return@thread  // the conversation ended meanwhile
            picture.onSuccess { jpeg ->
                showPhoto(webSocket, jpeg, "This is a screenshot of their computer screen, just taken.")
            }
            val result = picture.fold(
                onSuccess = { "The screenshot of their screen is in the conversation just above. $PHOTO_RULES" },
                onFailure = { "You couldn't see the screen: ${it.message}. Tell them briefly. Don't describe anything." }
            )
            answerLook(webSocket, call, result, "look_at_screen")
            status = "Listening… (tap to end)"
        }
    }

    // Puts a photo into the conversation, in order, so Gemini has it before it's told the look is
    // done. (Sent as live video instead, a photo can arrive after Gemini has already started
    // answering: live video is a separate stream, with no guarantee about what comes first.)
    // turnComplete=false: it's context for the answer that follows, not a new question.
    private fun showPhoto(webSocket: WebSocket, jpeg: ByteArray, caption: String) {
        val data = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val turn = JSONObject().put("role", "user").put(
            "parts", JSONArray()
                .put(JSONObject().put("text", "($caption)"))
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", data)))
        )
        webSocket.send(
            JSONObject().put(
                "clientContent", JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", false)
            ).toString()
        )
    }

    private fun answerLook(webSocket: WebSocket, call: JSONObject, result: String, tool: String = "look") {
        val answer = JSONObject()
            .put("name", tool)
            .put("response", JSONObject().put("result", result))
        if (call.has("id")) answer.put("id", call.getString("id"))
        webSocket.send(
            JSONObject().put(
                "toolResponse", JSONObject().put("functionResponses", JSONArray().put(answer))
            ).toString()
        )
    }

    private fun ended(webSocket: WebSocket, why: String, transient: Boolean = false) {
        if (webSocket != socket) return
        socket = null
        // Refused before it got going: first try without the newer features, then without
        // web search (Google's examples say search may not combine with other tools)
        if (!ready && usingNatural && offlineAt < System.currentTimeMillis() - 2000) {
            naturalAccepted = false
            start()
            status = "Connecting…"
            return
        }
        if (!ready && usingSearch && offlineAt < System.currentTimeMillis() - 2000) {  // there are always other tools now (voice commands)
            searchAccepted = false
            start()
            status = "Connecting without web search…"
            return
        }
        // A hiccup on Google's side or the network: quietly try again (twice at most), carrying
        // on the same conversation if it had started
        if (transient && retries < 2) {
            retries++
            ready = false
            speaker.stop()
            retryPending = true
            status = "Lost Google for a moment. Trying again…"
            val wait = if (retries == 1) 1500L else 4000L
            thread(name = "gemini-retry") {
                Thread.sleep(wait)
                if (retryPending && socket == null) {
                    retryPending = false
                    start()
                }
            }
            return
        }
        retries = 0
        ready = false
        resumeHandle = null
        greeting = null
        wokenByName = false
        muteUntil = 0L
        synchronized(earlySound) { earlySound.clear() }
        speaker.stop()
        speaker.endConversation()
        status = "$why  ·  $TAP_HINT"
    }

    // What went wrong, in plain words (the details go to the log)
    private fun friendlyReason(code: Int, detail: String): String {
        val text = detail.lowercase()
        return when {
            code == 1000 -> "Bye for now"
            "quota" in text || "exhausted" in text || "rate" in text || code == 429 ->
                "I've talked a lot today. Google's free allowance refills soon"
            "api key" in text || "permission" in text || code == 401 || code == 403 ->
                "My key isn't working. Check it in settings (press and hold)"
            "model" in text && ("not found" in text || "not supported" in text) ->
                "Google's changed something on its side. An Orbit update should fix it"
            code == 1011 || code == 1013 || code >= 500 -> "Google's having a moment. Try again shortly"
            "timeout" in text || "timed out" in text -> "Google's taking too long to answer. Try again in a minute"
            else -> "I lost the connection to Google"
        }
    }
}

// Asks Google whether a key works, without starting a conversation.
// The answer arrives on a background thread.
fun testKey(key: String, done: (String) -> Unit) {
    thread(name = "key-test") {
        val answer = try {
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .header("x-goog-api-key", key.trim())
                .build()
            OkHttpClient().newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> "✓ Key works"
                    400, 401, 403 -> "✗ Google says this key isn't valid"
                    else -> "✗ Google answered with error ${response.code}"
                }
            }
        } catch (e: Exception) {
            "✗ Couldn't reach Google: ${e.message ?: e.javaClass.simpleName}"
        }
        done(answer)
    }
}
