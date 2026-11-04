package com.benchsight.app

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// iOS-style dark palette
private val Bg = Color(0xFF000000)
private val Card = Color(0xFF1C1C1E)
private val Elevated = Color(0xFF2C2C2E)
private val Separator = Color(0xFF38383A)
private val Label = Color(0xFFFFFFFF)
private val Secondary = Color(0xFF8E8E93)
private val Accent = Color(0xFF0A84FF)
private val Success = Color(0xFF30D158)
private val Warning = Color(0xFFFF9F0A)
private val Danger = Color(0xFFFF453A)

class MainActivity : ComponentActivity() {

    private lateinit var glasses: GlassesManager
    private lateinit var speaker: Speaker
    private val recorder = VoiceRecorder()
    private lateinit var client: BenchClient

    private var sessionId by mutableStateOf<String?>(null)
    private var phase by mutableStateOf("-")
    private var sayEn by mutableStateOf("Connect the glasses, then start a repair.")
    private var sayLocal by mutableStateOf("")
    private var heard by mutableStateOf("")
    private var measure by mutableStateOf<List<Pair<String, String>>>(emptyList())
    private var busy by mutableStateOf(false)
    private var talking by mutableStateOf(false)
    private var image by mutableStateOf<Bitmap?>(null)
    private var serverUrl by mutableStateOf("")
    private var serverOk by mutableStateOf(false)
    private var mockMode by mutableStateOf(true)
    private var glassesChosen by mutableStateOf(false)

    private val permissions = registerForActivityResult(RequestMultiplePermissions()) { glasses.initialize() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("benchsight", Context.MODE_PRIVATE)
        serverUrl = prefs.getString("server", "")!!
        client = BenchClient(serverUrl)
        glasses = GlassesManager(applicationContext, lifecycleScope)
        speaker = Speaker(this)
        permissions.launch(arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
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
        r.optString("heard").takeIf { it.isNotBlank() && it != "null" }?.let { heard = it }
        val p = r.optJSONObject("pending")
        measure = if (p != null && p.optString("action") == "request_measurement") {
            fun v(k: String) = p.opt(k).takeUnless { it == null || it == JSONObject.NULL }?.toString() ?: ""
            val range = listOf(v("expected_min"), v("expected_max")).joinToString(" – ").trim(' ', '–')
            listOf(
                "Point" to v("test_point"),
                "Meter" to v("meter_mode").replace('_', ' '),
                "Power" to v("power_state").replace('_', ' '),
                "Expected" to "$range ${v("expected_unit")}".trim(),
            )
        } else emptyList()
        val hl = r.optString("highlight")
        val sid = sessionId
        if (hl.isNotBlank() && hl != "null" && sid != null) {
            withContext(Dispatchers.IO) { client.fetchImage(sid, hl) }?.let { image = it }
        }
        speaker.speak(sayEn, sayLocal, r.optString("language"))
    }

    private fun normalizeServer(input: String): String {
        var u = input.trim().trimEnd('/')
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        val hostPart = u.substringAfter("://")
        if (!hostPart.contains(':')) u = "$u:8000"
        return u
    }

    private fun connectServer(prefs: SharedPreferences) {
        serverUrl = normalizeServer(serverUrl)
        prefs.edit().putString("server", serverUrl).apply()
        client.baseUrl = serverUrl
        call("Connection") {
            val h = client.health()
            if (h.has("error")) { serverOk = false; h } else { serverOk = true; sayEn = "Connected to the server."; sayLocal = ""; null }
        }
    }

    private fun newRepair() = call("New repair") {
        val r = client.newSession()
        sessionId = r.optString("session").takeIf { it.isNotBlank() }
        image = null; heard = ""; measure = emptyList()
        r
    }

    private fun capture(kind: String) {
        val sid = sessionId ?: run { sayEn = "Start a repair first."; return }
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
    private fun Screen(prefs: SharedPreferences) {
        val ready by glasses.ready.collectAsStateWithLifecycle()
        val status by glasses.status.collectAsStateWithLifecycle()
        val live by glasses.preview.collectAsStateWithLifecycle()
        var editing by remember { mutableStateOf(true) }
        LaunchedEffect(ready, serverOk) { if (ready && serverOk) editing = false }

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // Large title
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("BenchSight", color = Label, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp)
                    Text("Repair copilot for Meta AI glasses", color = Secondary, fontSize = 15.sp)
                }
                StatusCapsule(if (busy) "Thinking" else phaseLabel(phase, sessionId), if (busy) Warning else if (ready) Success else Secondary)
            }

            Spacer(Modifier.height(24.dp))

            // Setup (grouped list)
            if (editing) {
                SectionHeader("Setup")
                Grouped {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Server", color = Label, fontSize = 17.sp, modifier = Modifier.width(76.dp))
                        Box(Modifier.weight(1f).padding(end = 8.dp)) {
                            if (serverUrl.isEmpty()) Text("192.168.x.x", color = Secondary, fontSize = 17.sp, maxLines = 1)
                            BasicTextField(serverUrl.removePrefix("http://"), { serverUrl = it.trim(); serverOk = false },
                                singleLine = true, textStyle = TextStyle(color = Label, fontSize = 17.sp),
                                cursorBrush = SolidColor(Accent), modifier = Modifier.fillMaxWidth())
                        }
                        TextAction(if (serverOk) "Connected" else "Connect", if (serverOk) Success else Accent) { connectServer(prefs) }
                    }
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Glasses", color = Label, fontSize = 17.sp, modifier = Modifier.width(76.dp))
                        Spacer(Modifier.weight(1f))
                        Segmented(listOf("Mock", "Ray-Ban Meta"), if (!glassesChosen) -1 else if (mockMode) 0 else 1) { i ->
                            mockMode = i == 0
                            glassesChosen = true
                            if (mockMode) glasses.enableMockGlasses() else glasses.register(this@MainActivity)
                        }
                    }
                    RowDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Camera", color = Label, fontSize = 17.sp, modifier = Modifier.width(76.dp))
                        Text(if (ready) "Live" else "Off", color = if (ready) Success else Secondary, fontSize = 17.sp,
                            modifier = Modifier.weight(1f))
                        TextAction(if (ready) "Running" else "Start", if (ready) Secondary else Accent) { glasses.startCamera() }
                    }
                }
                Text(status, color = Secondary, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
            } else {
                Grouped {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(Success, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(if (mockMode) "Mock Ray-Ban Meta · Camera live" else "Ray-Ban Meta · Camera live",
                            color = Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        TextAction("Edit", Accent) { editing = true }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            PrimaryButton(if (sessionId == null) "Start Repair" else "New Repair", enabled = !busy) { newRepair() }

            // Viewfinder
            Spacer(Modifier.height(24.dp))
            SectionHeader("Glasses view")
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(20.dp)).background(Card),
                contentAlignment = Alignment.Center,
            ) {
                val lv = live
                if (ready && lv != null) {
                    Image(lv.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Row(
                        Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50))
                            .background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(7.dp).background(Danger, CircleShape))
                        Spacer(Modifier.width(6.dp))
                        Text("LIVE", color = Label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Visibility, null, tint = Secondary, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(if (ready) "Starting live view…" else "Camera is off",
                            color = Secondary, fontSize = 15.sp, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TintedButton("Board", Icons.Rounded.CameraAlt, ready && !busy && sessionId != null, Modifier.weight(1f)) { capture("overview") }
                TintedButton("Close-up", Icons.Rounded.CenterFocusStrong, ready && !busy && sessionId != null, Modifier.weight(1f)) { capture("closeup") }
            }

            // Assistant
            Spacer(Modifier.height(24.dp))
            SectionHeader("BenchSight")
            Grouped {
                Column(Modifier.padding(16.dp)) {
                    Text(sayEn, color = Label, fontSize = 17.sp, lineHeight = 23.sp)
                    if (sayLocal.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(sayLocal, color = Secondary, fontSize = 16.sp, lineHeight = 22.sp)
                    }
                }
            }

            val shot = image
            if (shot != null) {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Last capture")
                Box(Modifier.fillMaxWidth().aspectRatio(shot.width.toFloat() / shot.height.coerceAtLeast(1))
                    .clip(RoundedCornerShape(16.dp)).background(Card)) {
                    Image(shot.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }

            if (measure.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionHeader("Next measurement")
                Grouped {
                    measure.forEachIndexed { i, (k, v) ->
                        if (i > 0) RowDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(k, color = Secondary, fontSize = 15.sp, modifier = Modifier.width(84.dp))
                            Text(v.ifBlank { "—" }, color = Label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            // Push to talk
            Spacer(Modifier.height(28.dp))
            MicButton()
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    talking -> "Listening…"
                    busy -> "Thinking…"
                    sessionId == null -> "Start a repair to talk"
                    else -> "Hold to talk"
                },
                color = Secondary, fontSize = 15.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            if (heard.isNotBlank()) {
                Text("“$heard”", color = Label, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    @Composable
    private fun MicButton() {
        val enabled = !busy && sessionId != null
        val pulse = rememberInfiniteTransition(label = "pulse")
        val ring by pulse.animateFloat(1f, 1.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (talking) Box(Modifier.size(92.dp).scale(ring).background(Danger.copy(alpha = 0.25f), CircleShape))
            Box(
                Modifier.size(84.dp).background(if (talking) Danger else if (enabled) Accent else Elevated, CircleShape)
                    .pointerInput(enabled) {
                        detectTapGestures(onPress = {
                            startTalk()
                            tryAwaitRelease()
                            stopTalk()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Mic, "Hold to talk", tint = if (enabled || talking) Label else Secondary, modifier = Modifier.size(38.dp)) }
        }
    }

    private fun phaseLabel(p: String, sid: String?) = when {
        sid == null -> "Ready"
        p == "need_overview" -> "Show board"
        p == "confirm_board" -> "Confirm board"
        p == "need_symptom" -> "Describe fault"
        p == "diagnosing" -> "Diagnosing"
        p == "awaiting_signoff" -> "Confirm fix"
        p == "done" -> "Done"
        p == "escalated" -> "Escalated"
        else -> "Ready"
    }
}

// ---------- iOS-style building blocks ----------
@Composable
private fun SectionHeader(text: String) {
    Text(text.uppercase(), color = Secondary, fontSize = 13.sp, letterSpacing = 0.3.sp,
        modifier = Modifier.padding(start = 16.dp, bottom = 6.dp))
}

@Composable
private fun Grouped(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card), content = content)
}

@Composable
private fun RowDivider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Separator))
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(text, color = color, fontSize = 17.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp))
        .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp))
}

@Composable
private fun StatusCapsule(text: String, dot: Color) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Card).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent else Elevated).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Label else Secondary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TintedButton(text: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) Accent.copy(alpha = 0.16f) else Card).clickable(enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Accent else Secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (enabled) Accent else Secondary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(Elevated).padding(2.dp)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.clip(RoundedCornerShape(7.dp)).background(if (i == selected) Color(0xFF636366) else Color.Transparent)
                    .clickable { onSelect(i) }.padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = Label, fontSize = 13.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}
