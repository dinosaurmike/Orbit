package com.example.companion2

import android.util.Base64
import android.util.Log
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// One-off questions to a Gemini text model (not the live voice conversation): Orbit's daily
// homework, and picking out which photo shows your computer screen. Call from a background thread.
object GeminiText {
    private val client = OkHttpClient.Builder()
        .readTimeout(90, TimeUnit.SECONDS)  // searching and writing can take a while
        .build()
    @Volatile private var model: String? = null

    // Every model this key can call generateContent on. Looked up once.
    @Volatile private var names: List<String>? = null
    fun modelNames(key: String): List<String> {
        names?.let { return it }
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200")
            .header("x-goog-api-key", key)
            .build()
        val found = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("model list error ${response.code}")
            val models = JSONObject(response.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
            (0 until models.length()).map { models.getJSONObject(it) }
                .filter { m ->
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: JSONArray()
                    (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                }
                .map { it.getString("name") }
        }
        names = found
        return found
    }

    // A current text model this key can use: a "flash" model (fast, and on the free tier),
    // newest first, skipping audio/image/live/embedding ones. Looked up once.
    fun model(key: String): String {
        model?.let { return it }
        val skip = listOf("image", "tts", "audio", "live", "embedding", "vision", "lite")
        val usable = modelNames(key).filter { name -> skip.none { name.contains(it) } }
        val chosen = (usable.filter { it.contains("flash") }.ifEmpty { usable }).maxOrNull()
            ?: throw IllegalStateException("no text model available for this key")
        model = chosen
        return chosen
    }

    // A text question; returns the answer, or null if Google refused the request
    fun ask(key: String, prompt: String, withSearch: Boolean = false): String? =
        generate(key, JSONArray().put(JSONObject().put("text", prompt)), withSearch)

    // A question about some photos (JPEGs, shown to the model numbered in order)
    fun askAboutPhotos(key: String, prompt: String, photos: List<ByteArray>): String? {
        val parts = JSONArray()
        photos.forEachIndexed { i, photo ->
            parts.put(JSONObject().put("text", "Photo ${i + 1}:"))
            parts.put(
                JSONObject().put(
                    "inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(photo, Base64.NO_WRAP))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))
        return generate(key, parts, withSearch = false)
    }

    private fun generate(key: String, parts: JSONArray, withSearch: Boolean): String? {
        val body = JSONObject().put(
            "contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
        )
        if (withSearch) body.put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/${model(key)}:generateContent")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w("Orbit", "Gemini request refused (${response.code}), search=$withSearch")
                return null
            }
            val answer = JSONObject(response.body?.string().orEmpty())
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
            return (0 until answer.length()).joinToString("") { answer.getJSONObject(it).optString("text") }
        }
    }
}
