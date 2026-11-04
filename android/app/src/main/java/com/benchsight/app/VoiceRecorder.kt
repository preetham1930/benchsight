package com.benchsight.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Push-to-talk recorder: 24 kHz mono 16-bit PCM, returned as a WAV file for Muse Voice Transcribe. */
class VoiceRecorder {
    private val rate = 24_000
    @Volatile private var recording = false
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream()

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested in MainActivity before use
    fun start() {
        if (recording) return
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4)
        pcm.reset()
        recording = true
        rec.startRecording()
        worker = thread(name = "voice") {
            val buf = ByteArray(minBuf)
            while (recording) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) }
            }
            rec.stop()
            rec.release()
        }
    }

    /** Stops recording and returns (wavBytes, seconds). */
    fun stop(): Pair<ByteArray, Double> {
        recording = false
        worker?.join(1500)
        val data = synchronized(pcm) { pcm.toByteArray() }
        return wav(data) to data.size / 2.0 / rate
    }

    private fun wav(data: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + data.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(data.size)
        return h.array() + data
    }
}
