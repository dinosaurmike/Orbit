package com.example.companion2

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import okhttp3.OkHttpClient
import okhttp3.Request

// Small on-phone models (hand gestures, faces) are downloaded once, the first time a feature
// is switched on, and kept in the app's own storage. Call from a background thread.
object Models {
    private val client = OkHttpClient()

    // The model file, downloading it first if needed. `progress` gets 0..100.
    fun get(dir: File, name: String, url: String, progress: (Int) -> Unit): File {
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return file
        dir.mkdirs()
        val part = File(dir, "$name.part")
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download failed (error ${response.code})")
            val body = response.body ?: throw IOException("download was empty")
            val total = body.contentLength()
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) progress((done * 100 / total).toInt())
                    }
                }
            }
        }
        if (!part.renameTo(file)) throw IOException("couldn't save the model")
        return file
    }

    // The model, mapped into memory the way MediaPipe wants it
    fun map(file: File): MappedByteBuffer = FileInputStream(file).channel.use { channel ->
        channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }
}
