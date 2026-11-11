package com.example.companion2

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import java.net.URLEncoder
import org.json.JSONObject

// Where Orbit Desk (desk/orbit_desk.py, a small program on your computer) listens
const val DESK_PORT = 47800
const val DESK_DISCOVERY_PORT = 47801

// Talks to Orbit Desk on your computer, so Orbit can look at your screen when you ask
// ("what do you think of this?"). Everything stays on your home network; the computer only
// hands over a picture to a phone that knows its 6-digit pairing code, and lists each look.
//
// Plain sockets are used on purpose: it's a tiny local request, and Android blocks unencrypted
// HTTP libraries by default (which is right for the internet, but this never leaves the house).
class DeskLink(private val settings: Settings) {
    // How the connection for agent news is doing (shown in settings; set by AgentBridge)
    @Volatile var agentState = "Off"

    // Looks for computers running Orbit Desk on the network. Calls back (on a background
    // thread) with each one found: its name and address.
    fun find(done: (List<Pair<String, String>>) -> Unit) {
        thread(name = "desk-find") {
            val found = mutableListOf<Pair<String, String>>()
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val ask = "ORBIT-DESK?".toByteArray()
                    socket.send(
                        DatagramPacket(ask, ask.size, InetAddress.getByName("255.255.255.255"), DESK_DISCOVERY_PORT)
                    )
                    val buffer = ByteArray(512)
                    val until = System.currentTimeMillis() + 2000
                    while (System.currentTimeMillis() < until) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        // "ORBIT-DESK <port> <computer name>"
                        val parts = String(packet.data, 0, packet.length).trim().split(" ", limit = 3)
                        if (parts.size >= 2 && parts[0] == "ORBIT-DESK") {
                            val address = "${packet.address.hostAddress}:${parts[1]}"
                            if (found.none { it.second == address }) {
                                found.add(parts.getOrElse(2) { address } to address)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("Orbit", "Couldn't search for Orbit Desk", e)
            }
            done(found)
        }
    }

    // Checks the address and code. Calls back (on a background thread) with a message to show.
    fun test(done: (String) -> Unit) {
        thread(name = "desk-test") {
            val message = try {
                val (status, body) = request("/check")
                when (status) {
                    200 -> {
                        val info = JSONObject(String(body))
                        settings.deskName = info.optString("name")
                        "✓ Connected to ${settings.deskName}" +
                            if (info.optBoolean("paused")) " (sharing is paused there)" else ""
                    }
                    else -> "✗ " + problem(status, body)
                }
            } catch (e: Exception) {
                "✗ Couldn't reach it: ${e.message ?: e.javaClass.simpleName}. Is Orbit Desk running?"
            }
            done(message)
        }
    }

    // A picture of the computer's screen (JPEG), or why not. Call from a background thread.
    fun screenshot(): Result<ByteArray> = try {
        val (status, body) = request("/screen")
        if (status == 200) Result.success(body) else Result.failure(Exception(problem(status, body)))
    } catch (e: Exception) {
        Result.failure(Exception("couldn't reach the computer (is Orbit Desk running?)"))
    }

    // News from agents on the computer. Orbit Desk holds the request open (about 15 seconds)
    // until there's news, so it arrives the moment it's sent. Orbit says how it's doing in the
    // same request (is someone at the desk, is it asleep...), for agents that ask.
    // `quick`: answer straight away (when it first connects, to know it works)
    fun events(since: Long, boot: String, status: Map<String, String>, quick: Boolean = false): Result<AgentBatch> = try {
        val query = (mapOf("since" to since.toString(), "boot" to boot) + status +
            (if (quick) mapOf("wait" to "0") else emptyMap())).entries
            .joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val (code, body) = request("/events?$query", readTimeoutMs = 30_000)
        when (code) {
            200 -> {
                val json = JSONObject(String(body))
                val list = json.optJSONArray("events")
                val events = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    list?.optJSONObject(i)?.let {
                        AgentEvent(
                            it.optLong("id"), it.optString("kind"), it.optString("message").take(240),
                            it.optString("source").take(60), it.optString("move")
                        )
                    }
                }
                Result.success(AgentBatch(events, json.optLong("next", since), json.optString("boot")))
            }
            404 -> Result.failure(Exception("Orbit Desk on the computer is an older version: update it"))
            else -> Result.failure(Exception(problem(code, body)))
        }
    } catch (e: Exception) {
        Result.failure(Exception("couldn't reach the computer (is Orbit Desk running?)"))
    }

    private fun problem(status: Int, body: ByteArray): String = when (status) {
        401 -> "the pairing code is wrong"
        423 -> "sharing is paused on the computer"
        429 -> "too many wrong codes; wait a minute"
        else -> try {
            JSONObject(String(body)).optString("error").ifBlank { "the computer answered $status" }
        } catch (e: Exception) {
            "the computer answered $status"
        }
    }

    // One small HTTP request; returns the status code and the body
    private fun request(path: String, readTimeoutMs: Int = 10_000): Pair<Int, ByteArray> {
        val address = settings.deskAddress.trim()
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: DESK_PORT
        if (host.isBlank()) throw IllegalStateException("no computer address yet")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = readTimeoutMs
            val out = socket.getOutputStream()
            out.write(
                ("GET $path HTTP/1.0\r\nHost: $host\r\n" +
                    "X-Orbit-Code: ${settings.deskCode.trim()}\r\n\r\n").toByteArray()
            )
            out.flush()
            // HTTP/1.0: the computer closes the connection when it's done
            val all = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                all.write(buffer, 0, n)
                if (all.size() > 12_000_000) throw IllegalStateException("answer too big")
            }
            val bytes = all.toByteArray()
            val split = indexOf(bytes, "\r\n\r\n".toByteArray())
            if (split < 0) throw IllegalStateException("odd answer from the computer")
            val statusLine = String(bytes, 0, split).lineSequence().first()
            val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("odd answer from the computer")
            return status to bytes.copyOfRange(split + 4, bytes.size)
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
