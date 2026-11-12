package com.example.companion2

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// "What just happened?": a detailed log of the last few conversations, kept on the phone only.
// Why each one started (its name, a tap, an arrival hello…), what it heard, what it said, which
// tools Gemini used (with what), photos it looked at, and how it ended. When Orbit does something
// odd, this shows the real cause instead of a guess. Settings → Privacy → Recent conversations.
object ConvoLog {
    const val KEEP = 10          // conversations
    const val MAX_LINES = 300    // lines per conversation

    class Line(val at: Long, val kind: String, var text: String)
    class Conversation(val startedAt: Long, val reason: String) {
        val lines = mutableListOf<Line>()
    }

    private val lock = Any()
    private val conversations = ArrayDeque<Conversation>()
    private var file: File? = null
    @Volatile var enabled = true

    // Called once at start-up with where to keep the log
    fun init(dir: File) = synchronized(lock) {
        file = File(dir, "conversation_log.json")
        load()
    }

    // A conversation starts. `reason` in plain words: "You said its name", "You tapped it"…
    fun begin(reason: String, at: Long = System.currentTimeMillis()) = synchronized(lock) {
        if (!enabled) return@synchronized
        conversations.addLast(Conversation(at, reason))
        while (conversations.size > KEEP) conversations.removeFirst()
    }

    // Words arrive in pieces; consecutive pieces of the same kind join into one line
    fun heard(words: String) = addWords("heard", words)
    fun said(words: String) = addWords("said", words)

    fun tool(name: String, args: String) = add("tool", if (args.isBlank() || args == "{}") name else "$name $args")
    fun photo(caption: String) = add("photo", caption)
    fun note(text: String) = add("note", text)

    fun end(why: String) {
        add("end", why)
        save()
    }

    private fun addWords(kind: String, words: String) = synchronized(lock) {
        if (!enabled || words.isEmpty()) return@synchronized
        val convo = conversations.lastOrNull() ?: return@synchronized
        val last = convo.lines.lastOrNull()
        if (last != null && last.kind == kind) last.text += words
        else if (words.isNotBlank()) addLine(convo, Line(System.currentTimeMillis(), kind, words.trimStart()))
    }

    private fun add(kind: String, text: String) = synchronized(lock) {
        if (!enabled) return@synchronized
        val convo = conversations.lastOrNull() ?: return@synchronized
        addLine(convo, Line(System.currentTimeMillis(), kind, text))
    }

    private fun addLine(convo: Conversation, line: Line) {
        if (convo.lines.size < MAX_LINES) convo.lines.add(line)
    }

    // Newest first, for the settings screen
    fun recent(): List<Conversation> = synchronized(lock) { conversations.toList().reversed() }

    fun clear() = synchronized(lock) {
        conversations.clear()
        file?.delete()
    }

    // One conversation as plain text, to read or copy (and send to whoever's helping you)
    fun asText(c: Conversation): String {
        val day = SimpleDateFormat("EEE d MMM, h:mm:ss a", Locale.getDefault())
        val time = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
        return buildString {
            append("${day.format(Date(c.startedAt))}: ${c.reason}\n")
            for (l in c.lines) {
                val label = when (l.kind) {
                    "heard" -> "Heard"
                    "said" -> "Said"
                    "tool" -> "Tool"
                    "photo" -> "Photo"
                    "end" -> "Ended"
                    else -> "Note"
                }
                append("${time.format(Date(l.at))}  $label: ${l.text.trim()}\n")
            }
        }
    }

    fun save() = synchronized(lock) {
        val f = file ?: return@synchronized
        try {
            if (!enabled) {
                f.delete()
                return@synchronized
            }
            val all = JSONArray()
            for (c in conversations) {
                val lines = JSONArray()
                c.lines.forEach { lines.put(JSONObject().put("at", it.at).put("kind", it.kind).put("text", it.text)) }
                all.put(JSONObject().put("startedAt", c.startedAt).put("reason", c.reason).put("lines", lines))
            }
            f.writeText(all.toString())
        } catch (_: Exception) {
            // A log that can't be saved isn't worth a crash
        }
    }

    private fun load() {
        val f = file ?: return
        conversations.clear()
        try {
            if (!f.exists()) return
            val all = JSONArray(f.readText())
            for (i in 0 until all.length()) {
                val o = all.getJSONObject(i)
                val c = Conversation(o.getLong("startedAt"), o.getString("reason"))
                val lines = o.getJSONArray("lines")
                for (j in 0 until lines.length()) {
                    val l = lines.getJSONObject(j)
                    c.lines.add(Line(l.getLong("at"), l.getString("kind"), l.getString("text")))
                }
                conversations.addLast(c)
            }
            while (conversations.size > KEEP) conversations.removeFirst()
        } catch (_: Exception) {
            conversations.clear()  // a damaged file: start a fresh log
        }
    }
}
