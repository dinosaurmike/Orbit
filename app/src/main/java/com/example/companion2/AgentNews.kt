package com.example.companion2

import android.util.Log
import kotlin.concurrent.thread

// News from coding agents and scripts on your computer ("the build finished", "I need your
// decision"), through Orbit Desk. Any agent can send it: through MCP (Antigravity, Cursor,
// Codex, Claude Code…), a command, or a local web request. See desk/README.md.
//
// Orbit keeps one request open to Orbit Desk ("anything new?"), which answers the moment news
// arrives. If you're at your desk, Orbit looks at you and says it; if you're away, it keeps it
// and tells you when it next sees you.
class AgentEvent(
    val id: Long,
    val kind: String,     // "done", "needs_input", "failed", "info", or "express" (a reaction only)
    val message: String,  // short, already cleaned up by Orbit Desk
    val source: String,   // who it's from, e.g. "Codex" (may be blank)
    val move: String      // for "express": which reaction
)

class AgentBatch(val events: List<AgentEvent>, val next: Long, val boot: String)

// News waiting to be told, and whether an agent is waiting on you (the eyes' edge glows blue)
object AgentNews {
    private val pending = mutableListOf<AgentEvent>()
    @Volatile var needsYouSince = 0L
    @Volatile var firstWaitingSince = 0L  // when the oldest news still waiting arrived

    fun add(event: AgentEvent) = synchronized(pending) {
        if (pending.isEmpty()) firstWaitingSince = System.currentTimeMillis()
        pending.add(event)
        if (pending.size > 20) pending.removeAt(0)
        if (event.kind == "needs_input") needsYouSince = System.currentTimeMillis()
    }

    fun count() = synchronized(pending) { pending.size }

    fun takeAll(): List<AgentEvent> = synchronized(pending) {
        pending.toList().also {
            pending.clear()
            needsYouSince = 0L
            firstWaitingSince = 0L
        }
    }

    fun needsYou() = needsYouSince != 0L

    // What Orbit says, in one or two short sentences
    fun spoken(news: List<AgentEvent>, away: Boolean): String {
        fun who(e: AgentEvent) = e.source.ifBlank { "Your agent" }
        fun one(e: AgentEvent, withMessage: Boolean): String {
            val m = if (withMessage) e.message.trim() else ""
            // Ends with the message's own ? or !, or a full stop
            val said = if (m.isEmpty() || m.last() in ".?!…") m else "$m."
            return when (e.kind) {
                "done" -> if (m.isNotEmpty()) "${who(e)} is done: $said" else "${who(e)} is done."
                "needs_input" -> if (m.isNotEmpty()) "${who(e)} needs you: $said" else "${who(e)} needs you."
                "failed" -> if (m.isNotEmpty()) "${who(e)} hit a problem: $said" else "${who(e)} hit a problem."
                else -> if (m.isNotEmpty()) "${who(e)} says: $said" else "News from ${who(e)}."
            }
        }
        if (news.size == 1) return (if (away) "While you were away: " else "") + one(news[0], true)
        // Several: what matters most first, without the details (except one that needs you)
        val order = listOf("needs_input", "failed", "done", "info")
        val sorted = news.sortedBy { order.indexOf(it.kind).let { i -> if (i < 0) 9 else i } }
        val first = one(sorted[0], sorted[0].kind == "needs_input")
        val rest = sorted.drop(1).take(2).joinToString(" ") { one(it, false) }
        val more = sorted.size - 3
        return (if (away) "While you were away: " else "") + "$first $rest" +
            if (more > 0) " And $more more." else ""
    }
}

// The connection to Orbit Desk for agent news. Runs while Orbit Desk is paired and agent news
// is on; waits and tries again (up to every 30 seconds) when the computer can't be reached.
class AgentBridge(
    private val settings: Settings,
    private val desk: DeskLink,
    private val status: () -> Map<String, String>,  // is someone at the desk, is it asleep…
    private val onNews: (List<AgentEvent>) -> Unit
) {
    @Volatile private var running = false

    // Paired (a secret), or paired the old way (a code), which then shows "tap Pair again"
    private fun wanted() = settings.deskOn && settings.agentsOn && settings.deskAddress.isNotBlank() &&
        (settings.deskSecret.isNotBlank() || settings.deskCode.isNotBlank())

    fun start() {
        if (running) return
        running = true
        thread(name = "agent-news", isDaemon = true) {
            var wait = 2000L
            var connected = false
            while (running) {
                if (!wanted()) {
                    desk.agentState = "Off"
                    connected = false
                    Thread.sleep(3000)
                    continue
                }
                if (!connected) desk.agentState = "Connecting…"
                val since = settings.agentSince.toLongOrNull() ?: 0L
                desk.events(since, settings.agentBoot, status(), quick = !connected).fold(
                    onSuccess = { batch ->
                        if (batch.boot != settings.agentBoot) settings.agentBoot = batch.boot
                        if (batch.next.toString() != settings.agentSince) settings.agentSince = batch.next.toString()
                        desk.agentState = "✓ Connected: agents on ${settings.deskName.ifBlank { "the computer" }} can reach it"
                        connected = true
                        wait = 2000L
                        if (batch.events.isNotEmpty()) {
                            try {
                                onNews(batch.events)
                            } catch (e: Exception) {
                                Log.e("Orbit", "Agent news failed", e)
                            }
                        }
                    },
                    onFailure = { e ->
                        desk.agentState = "Not connected: ${e.message}"
                        connected = false
                        Thread.sleep(wait)
                        wait = (wait * 2).coerceAtMost(30_000L)
                    }
                )
            }
        }
    }

    fun stop() {
        running = false
    }
}
