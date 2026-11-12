package com.example.companion2

import android.util.Log
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import org.json.JSONObject

// Where Orbit Desk (desk/orbit_desk.py, a small program on your computer) listens
const val DESK_PORT = 47800
const val DESK_DISCOVERY_PORT = 47801

// A problem already in plain words, shown to the person as it is
class DeskProblem(message: String) : Exception(message)

// Talks to Orbit Desk on your computer, so Orbit can look at your screen when you ask
// ("what do you think of this?") and hear news from coding agents. Everything stays on your home
// network.
//
// Pairing happens once, with the 6-digit code the computer shows: the phone and the computer
// swap keys and each proves it knows the code, which gives them a shared secret nobody else on
// the network can work out. After that the code is never sent: each request is signed with the
// secret, and the computer's answers (screen pictures, news) are encrypted with it.
//
// Plain sockets are used on purpose: it's a tiny local request, and Android blocks unencrypted
// HTTP libraries by default (which is right for the internet; here the content is encrypted by
// us instead, and it never leaves the house).
class DeskLink(private val settings: Settings) {
    // How the connection for agent news is doing (shown in settings; set by AgentBridge)
    @Volatile var agentState = "Off"

    private class Answer(val status: Int, val headers: Map<String, String>, val body: ByteArray)

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

    // Pairs with the computer at the address, using the code it shows. Calls back (on a
    // background thread) with a message to show.
    fun pair(code: String, done: (String) -> Unit) {
        thread(name = "desk-pair") {
            val message = try {
                "✓ Paired with ${pairNow(code.trim())}"
            } catch (e: DeskProblem) {
                "✗ ${e.message}"
            } catch (e: Exception) {
                "✗ Couldn't reach it: ${e.message ?: e.javaClass.simpleName}. Is Orbit Desk running?"
            }
            done(message)
        }
    }

    private fun pairNow(code: String): String {
        if (!code.matches(Regex("\\d{6}"))) throw DeskProblem("type the 6-digit code Orbit Desk shows")
        // A random name for this phone, so the computer can tell paired phones apart
        val deviceId = settings.deskDeviceId.ifBlank {
            DeskCrypto.hex(DeskCrypto.random(16)).also { settings.deskDeviceId = it }
        }
        // 1) Swap public keys (a new key pair each time; only the shared secret is kept)
        val keys = DeskCrypto.newKeyPair()
        val phonePub = DeskCrypto.rawPublicKey(keys.public as ECPublicKey)
        val phoneNonce = DeskCrypto.random(16)
        val first = request(
            "POST", "/pair", JSONObject()
                .put("device_id", deviceId)
                .put("phone_pub", b64(phonePub))
                .put("phone_nonce", b64(phoneNonce))
                .toString().toByteArray()
        )
        when (first.status) {
            200 -> {}
            404 -> throw DeskProblem("Orbit Desk on the computer is an older version: update Orbit Desk on the computer")
            else -> throw DeskProblem(problem(first))
        }
        val answer = JSONObject(String(first.body))
        val deskPub = unb64(answer.getString("desk_pub"))
        val deskNonce = unb64(answer.getString("desk_nonce"))
        val transcript = DeskCrypto.transcript(deviceId.toByteArray(), phonePub, phoneNonce, deskPub, deskNonce)
        val shared = DeskCrypto.sharedSecret(keys, deskPub)
        val secret = DeskCrypto.hkdf(shared, DeskCrypto.sha256(transcript), "orbit-desk v3".toByteArray())

        // 2) Prove we know the code (tied to this swap, so it can't be reused). The computer only
        // proves it knows the code after we have, so nobody can ask it for something to guess
        // the code against.
        val codeKey = code.toByteArray()
        val second = request(
            "POST", "/pair/confirm", JSONObject()
                .put("device_id", deviceId)
                .put("phone_proof", b64(DeskCrypto.hmac(codeKey, "phone".toByteArray() + transcript)))
                .toString().toByteArray()
        )
        when (second.status) {
            200 -> {}
            401 -> throw DeskProblem("the pairing code is wrong")
            else -> throw DeskProblem(problem(second))
        }
        val confirmed = JSONObject(String(second.body))
        val deskProof = unb64(confirmed.optString("desk_proof"))
        // 3) Check the computer knows the code too: otherwise something else on the network
        // answered, pretending to be it
        if (!MessageDigest.isEqual(deskProof, DeskCrypto.hmac(codeKey, "desk".toByteArray() + transcript))) {
            throw DeskProblem(
                "the computer couldn't prove it knows the code, so it may not be your computer. " +
                    "Not paired; try again on your home network"
            )
        }
        settings.deskSecret = b64(secret)
        settings.deskName = confirmed.optString("name").ifBlank { settings.deskName }
        settings.deskCode = ""  // not needed any more (and older versions kept it here)
        return settings.deskName.ifBlank { "the computer" }
    }

    // Checks the connection to the paired computer. Calls back (on a background thread) with a
    // message to show.
    fun test(done: (String) -> Unit) {
        thread(name = "desk-test") {
            val message = try {
                val answer = signedGet("/check")
                when (answer.status) {
                    200 -> {
                        val info = JSONObject(String(answer.body))
                        settings.deskName = info.optString("name")
                        "✓ Connected to ${settings.deskName}" +
                            if (info.optBoolean("paused")) " (sharing is paused there)" else ""
                    }
                    else -> "✗ " + problem(answer)
                }
            } catch (e: DeskProblem) {
                "✗ ${e.message}"
            } catch (e: Exception) {
                "✗ Couldn't reach it: ${e.message ?: e.javaClass.simpleName}. Is Orbit Desk running?"
            }
            done(message)
        }
    }

    // A picture of the computer's screen (JPEG), or why not. Call from a background thread.
    fun screenshot(): Result<ByteArray> = try {
        val answer = signedGet("/screen")
        if (answer.status == 200) Result.success(answer.body) else Result.failure(Exception(problem(answer)))
    } catch (e: DeskProblem) {
        Result.failure(e)
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
        val answer = signedGet("/events?$query", readTimeoutMs = 30_000)
        when (answer.status) {
            200 -> {
                val json = JSONObject(String(answer.body))
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
            else -> Result.failure(Exception(problem(answer)))
        }
    } catch (e: DeskProblem) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(Exception("couldn't reach the computer (is Orbit Desk running?)"))
    }

    private fun problem(answer: Answer): String {
        val error = try {
            JSONObject(String(answer.body)).optString("error")
        } catch (e: Exception) {
            ""
        }
        return when {
            // What Orbit Desk before pairing (version 2) says to a request without its code
            answer.status == 401 && error == "wrong pairing code" ->
                "Orbit Desk on the computer is an older version: update Orbit Desk on the computer"
            answer.status == 401 -> error.ifBlank { "not paired: tap Pair again" }
            answer.status == 423 -> "sharing is paused on the computer"
            answer.status == 429 -> "too many wrong codes; wait a minute"
            else -> error.ifBlank { "the computer answered ${answer.status}" }
        }
    }

    // A request only this paired phone can make: signed with the shared secret, with the time and
    // a one-off random nonce, so a copy of it is useless to anyone else. The answer is decrypted;
    // one that was changed on the way (or isn't encrypted) is refused.
    private fun signedGet(path: String, readTimeoutMs: Int = 10_000): Answer {
        val secret = settings.deskSecret
        if (secret.isBlank()) {
            // A code but no secret: paired the old way, before Orbit Desk version 3
            throw DeskProblem(
                if (settings.deskCode.isNotBlank()) "Orbit Desk was updated: tap Pair again"
                else "not paired yet: type the code Orbit Desk shows and tap Pair"
            )
        }
        val key = unb64(secret)
        val authKey = DeskCrypto.hkdf(key, null, "auth".toByteArray())
        val encKey = DeskCrypto.hkdf(key, null, "enc".toByteArray())
        val time = (System.currentTimeMillis() / 1000).toString()
        val nonce = DeskCrypto.hex(DeskCrypto.random(16))
        val noBody = DeskCrypto.hex(DeskCrypto.sha256(ByteArray(0)))
        val sig = DeskCrypto.hex(DeskCrypto.hmac(authKey, "GET\n$path\n$time\n$nonce\n$noBody".toByteArray()))
        val answer = request(
            "GET", path, null,
            mapOf(
                "X-Orbit-Device" to settings.deskDeviceId, "X-Orbit-Time" to time,
                "X-Orbit-Nonce" to nonce, "X-Orbit-Sig" to sig
            ),
            readTimeoutMs
        )
        if (answer.status != 200) return answer
        val notTrusted = "the computer's answer didn't check out (changed on the way?), so it was ignored"
        if (answer.headers["x-orbit-enc"] != "1") throw DeskProblem(notTrusted)
        val plain = try {
            DeskCrypto.open(encKey, answer.body, sig.toByteArray())
        } catch (e: GeneralSecurityException) {
            throw DeskProblem(notTrusted)
        } catch (e: IllegalArgumentException) {
            throw DeskProblem(notTrusted)
        }
        return Answer(200, answer.headers, plain)
    }

    // One small HTTP request; returns the status code, headers (lower-case names) and body
    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = 10_000
    ): Answer {
        val address = settings.deskAddress.trim()
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "").toIntOrNull() ?: DESK_PORT
        if (host.isBlank()) throw DeskProblem("no computer address yet: tap Find my computer")
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = readTimeoutMs
            val head = StringBuilder("$method $path HTTP/1.0\r\nHost: $host\r\n")
            headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
            if (body != null) {
                head.append("Content-Type: application/json\r\nContent-Length: ${body.size}\r\n")
            }
            head.append("\r\n")
            val out = socket.getOutputStream()
            out.write(head.toString().toByteArray())
            if (body != null) out.write(body)
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
            val lines = String(bytes, 0, split).lines()
            val status = lines.first().split(" ").getOrNull(1)?.toIntOrNull()
                ?: throw IllegalStateException("odd answer from the computer")
            val answerHeaders = lines.drop(1).filter { ':' in it }.associate {
                it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
            }
            return Answer(status, answerHeaders, bytes.copyOfRange(split + 4, bytes.size))
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw DeskProblem("odd answer from the computer")
    }
}

// The cryptography for Orbit Desk, from Android's built-in java.security and javax.crypto (no
// extra libraries). Kept apart from DeskLink so it can be tested on its own.
object DeskCrypto {
    private val randomness = SecureRandom()

    fun random(size: Int) = ByteArray(size).also { randomness.nextBytes(it) }

    // A new P-256 key pair (the curve also called secp256r1), used for one pairing only
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    // Java hands out public keys wrapped in a longer format (X.509); Orbit Desk swaps just the
    // point itself: 0x04, then x and y as 32 bytes each ("uncompressed", 65 bytes in all)
    fun rawPublicKey(key: ECPublicKey): ByteArray =
        byteArrayOf(4) + fixed32(key.w.affineX) + fixed32(key.w.affineY)

    // The other way, after checking the point really is on the curve (a bad point is a known
    // way to trick a key swap into leaking secrets)
    fun publicKeyFromRaw(raw: ByteArray, params: ECParameterSpec): PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "not an uncompressed P-256 point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val p = (params.curve.field as ECFieldFp).p
        val onCurve = x < p && y < p &&
            y.multiply(y).mod(p) == x.pow(3).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        require(onCurve) { "the computer's key isn't on the curve" }
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    // The secret both sides get from the key swap (ECDH): 32 bytes
    fun sharedSecret(mine: KeyPair, theirRaw: ByteArray): ByteArray {
        val theirs = publicKeyFromRaw(theirRaw, (mine.public as ECPublicKey).params)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(mine.private)
        agreement.doPhase(theirs, true)
        return leftPad(agreement.generateSecret(), 32)
    }

    // Everything swapped while pairing, in a fixed order, each part after its length (4 bytes),
    // so two different swaps can never produce the same bytes. Must match orbit_desk.py.
    fun transcript(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (part in parts) {
            out.write(byteArrayOf((part.size ushr 24).toByte(), (part.size ushr 16).toByte(),
                (part.size ushr 8).toByte(), part.size.toByte()))
            out.write(part)
        }
        return out.toByteArray()
    }

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(data)
    }

    // HKDF with SHA-256 (RFC 5869), which turns one secret into separate keys for separate
    // jobs. Android has no built-in HKDF, but it's just two steps of HMAC. A missing salt means
    // 32 zero bytes, as the standard says.
    fun hkdf(key: ByteArray, salt: ByteArray?, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt ?: ByteArray(32), key)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    // AES-256-GCM: a random 12-byte IV, then the encrypted bytes and their 16-byte tag. `aad`
    // ties the answer to the request it answers (it's that request's signature).
    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = random(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(plain)
    }

    // Throws if anything was changed, or it was made with another key or for another request
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        require(sealed.size >= 12 + 16) { "too short to be encrypted" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, 12, sealed.size - 12)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    // BigInteger gives as few bytes as it needs (sometimes with an extra 0 in front): make it 32
    private fun fixed32(n: BigInteger): ByteArray {
        val bytes = n.toByteArray()
        return if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else leftPad(bytes, 32)
    }

    private fun leftPad(bytes: ByteArray, size: Int): ByteArray =
        if (bytes.size >= size) bytes else ByteArray(size - bytes.size) + bytes
}
