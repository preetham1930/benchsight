package com.benchsight.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import org.json.JSONObject

/** Talks to the BenchSight server running on the PC (same Wi-Fi). Call from a background thread. */
class BenchClient(var baseUrl: String) {

    private fun open(path: String, method: String = "GET"): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 180_000 // Muse Spark reasoning + retries can take a while
        }

    private fun readJson(c: HttpURLConnection): JSONObject {
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        return try { JSONObject(text) } catch (e: Exception) { JSONObject().put("error", "HTTP ${c.responseCode}: $text") }
    }

    fun health(): JSONObject = readJson(open("/api/health"))

    fun newSession(): JSONObject = readJson(open("/api/session", "POST").apply { doOutput = true; outputStream.close() })

    fun sendImage(sid: String, bitmap: Bitmap, kind: String): JSONObject {
        val jpg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }.toByteArray()
        return multipart("/api/session/$sid/image", "file", "glasses.jpg", "image/jpeg", jpg, mapOf("kind" to kind))
    }

    fun sendAudio(sid: String, wav: ByteArray): JSONObject =
        multipart("/api/session/$sid/audio", "file", "voice.wav", "audio/wav", wav, emptyMap())

    fun sendText(sid: String, text: String): JSONObject {
        val c = open("/api/session/$sid/text", "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        c.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        return readJson(c)
    }

    fun fetchImage(sid: String, name: String): Bitmap? = try {
        open("/api/session/$sid/file/$name").inputStream.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) { null }

    private fun multipart(path: String, field: String, filename: String, mime: String, bytes: ByteArray,
                          fields: Map<String, String>): JSONObject {
        val boundary = "----benchsight" + UUID.randomUUID().toString().replace("-", "")
        val c = open(path, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(c.outputStream).use { out ->
            for ((k, v) in fields) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
            }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$field\"; filename=\"$filename\"\r\n")
            out.writeBytes("Content-Type: $mime\r\n\r\n")
            out.write(bytes)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return readJson(c)
    }
}
