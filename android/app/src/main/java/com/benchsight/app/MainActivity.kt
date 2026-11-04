package com.benchsight.app

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val Green = Color(0xFF00E676)
private val Bg = Color(0xFF0F1412)
private val Panel = Color(0xFF18201C)

class MainActivity : ComponentActivity() {

    private lateinit var glasses: GlassesManager
    private lateinit var speaker: Speaker
    private val recorder = VoiceRecorder()
    private lateinit var client: BenchClient

    // UI state
    private var sessionId by mutableStateOf<String?>(null)
    private var phase by mutableStateOf("-")
    private var sayEn by mutableStateOf("Start the glasses camera, then begin a repair.")
    private var sayLocal by mutableStateOf("")
    private var heard by mutableStateOf("")
    private var pending by mutableStateOf("")
    private var busy by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var image by mutableStateOf<Bitmap?>(null)
    private var serverUrl by mutableStateOf("")

    private val permissions = registerForActivityResult(RequestMultiplePermissions()) {
        glasses.initialize()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("benchsight", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server", "http://192.168.1.10:8000")!!
        client = BenchClient(serverUrl)
        glasses = GlassesManager(applicationContext, lifecycleScope)
        speaker = Speaker(this)
        permissions.launch(arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, background = Bg, surface = Panel)) {
                Surface(Modifier.fillMaxSize(), color = Bg) { Screen(prefs) }
            }
        }
    }

    override fun onDestroy() {
        glasses.stop()
        speaker.shutdown()
        super.onDestroy()
    }

    // ---------- actions ----------
    private fun call(label: String, block: suspend () -> JSONObject?) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            try {
                val r = withContext(Dispatchers.IO) { block() }
                if (r != null) handle(r)
            } catch (e: Exception) {
                sayEn = "$label failed: ${e.message}"
                sayLocal = ""
            } finally {
                busy = false
            }
        }
    }

    private suspend fun handle(r: JSONObject) {
        if (r.has("error")) { sayEn = r.getString("error"); sayLocal = ""; return }
        sayEn = r.optString("say_en")
        sayLocal = r.optString("say_local").takeIf { it.isNotBlank() && it != sayEn } ?: ""
        phase = r.optString("phase", phase)
        heard = r.optString("heard").takeIf { it.isNotBlank() && it != "null" } ?: heard
        val p = r.optJSONObject("pending")
        pending = if (p != null && p.optString("action") == "request_measurement") {
            val lo = p.opt("expected_min").takeUnless { it == JSONObject.NULL } ?: ""
            val hi = p.opt("expected_max").takeUnless { it == JSONObject.NULL } ?: ""
            "Measure: ${p.optString("test_point")}\nMeter: ${p.optString("meter_mode")} · " +
                "Power: ${p.optString("power_state")} · Expected: $lo–$hi ${p.optString("expected_unit")}"
        } else ""
        val hl = r.optString("highlight")
        val sid = sessionId
        if (hl.isNotBlank() && hl != "null" && sid != null) {
            withContext(Dispatchers.IO) { client.fetchImage(sid, hl) }?.let { image = it }
        }
        speaker.speak(sayEn, sayLocal, r.optString("language"))
    }

    private fun newRepair() = call("New session") {
        val r = client.newSession()
        sessionId = r.optString("session").takeIf { it.isNotBlank() }
        image = null; heard = ""; pending = ""
        r
    }

    private fun capture(kind: String) {
        val sid = sessionId ?: run { sayEn = "Start a new repair first."; return }
        call("Capture") {
            val bmp = glasses.capturePhoto() ?: return@call null
            image = bmp
            client.sendImage(sid, bmp, kind)
        }
    }

    private fun startTalk() {
        if (busy || sessionId == null) return
        talking = true
        recorder.start()
    }

    private fun stopTalk() {
        if (!talking) return
        talking = false
        val (wav, seconds) = recorder.stop()
        val sid = sessionId ?: return
        if (seconds < 0.6) { sayEn = "Hold the button while you speak."; return }
        call("Voice") { client.sendAudio(sid, wav) }
    }

    // ---------- UI ----------
    @Composable
    private fun Screen(prefs: android.content.SharedPreferences) {
        val status by glasses.status.collectAsStateWithLifecycle()
        val ready by glasses.ready.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("BenchSight", color = Green, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(status, color = Color(0xFF93A39A), fontSize = 13.sp)
            Text("Session: ${sessionId ?: "none"} · ${phase}", color = Color(0xFF93A39A), fontSize = 13.sp)

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(serverUrl, { serverUrl = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("Server (PC) address") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    prefs.edit().putString("server", serverUrl).apply()
                    client.baseUrl = serverUrl
                    call("Server check") { client.health().also { sayEn = "Server reachable." } }
                }) { Text("Save") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { glasses.enableMockGlasses() }) { Text("Mock glasses") }
                OutlinedButton(onClick = { glasses.register(this@MainActivity) }) { Text("Real glasses") }
                Button(onClick = { glasses.startCamera() }) { Text("Start camera") }
            }
            Button(onClick = { newRepair() }, Modifier.fillMaxWidth()) { Text("New repair") }

            Box(
                Modifier.fillMaxWidth().height(300.dp).background(Panel, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val img = image
                if (img != null) Image(img.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Text(if (ready) "Glasses camera live. Tap Show board." else "Glasses camera not started",
                    color = Color(0xFF93A39A))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { capture("overview") }, enabled = ready && !busy, modifier = Modifier.weight(1f)) {
                    Text("Show board")
                }
                Button(onClick = { capture("closeup") }, enabled = ready && !busy, modifier = Modifier.weight(1f)) {
                    Text("Close-up")
                }
            }

            Box(
                Modifier.fillMaxWidth().height(72.dp)
                    .background(if (talking) Color(0xFFFF5252) else Green, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            startTalk()
                            tryAwaitRelease()
                            stopTalk()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(if (talking) "Listening… release to send" else if (busy) "Thinking…" else "HOLD TO TALK",
                    color = Color(0xFF002B12), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }

            if (heard.isNotBlank()) Text("Heard: $heard", color = Color(0xFFFFB300))
            Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                Column(Modifier.padding(12.dp)) {
                    Text(sayEn, color = Color.White, fontSize = 17.sp)
                    if (sayLocal.isNotBlank()) Text(sayLocal, color = Color(0xFFB9F6CA), fontSize = 16.sp)
                }
            }
            if (pending.isNotBlank()) Text(pending, color = Green, fontSize = 14.sp)
        }
    }
}
