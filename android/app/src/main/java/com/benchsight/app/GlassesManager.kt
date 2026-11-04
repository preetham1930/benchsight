package com.benchsight.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.meta.wearable.dat.camera.Camera
import com.meta.wearable.dat.camera.addCamera
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import com.meta.wearable.dat.mockdevice.api.GlassesModel
import com.meta.wearable.dat.mockdevice.api.MockGlasses
import com.meta.wearable.dat.mockdevice.api.camera.CameraFacing
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Connects to Meta AI glasses through the Wearables Device Access Toolkit.
 *
 * With mock mode on, Mock Device Kit simulates Ray-Ban Meta glasses and uses the phone's back
 * camera as the glasses' camera, so the exact same session / camera / capture code runs without
 * hardware. With real glasses, only the registration step differs.
 */
class GlassesManager(private val context: Context, private val scope: CoroutineScope) {

    private val _status = MutableStateFlow("Glasses: not initialized")
    val status: StateFlow<String> = _status

    private val _ready = MutableStateFlow(false) // camera stream active, photos can be captured
    val ready: StateFlow<Boolean> = _ready

    // Live viewfinder built from the glasses video stream (luma channel, downscaled, ~8 fps).
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview
    private var lastPreviewMs = 0L

    private var initialized = false
    private var session: DeviceSession? = null
    private var camera: Camera? = null
    private var mockGlasses: MockGlasses? = null

    fun initialize() {
        if (initialized) return
        Wearables.initialize(context)
            .onSuccess { initialized = true; _status.value = "Glasses toolkit ready" }
            .onFailure { error, _ -> _status.value = "Toolkit init failed: ${error.description}" }
    }

    /** Simulated Ray-Ban Meta glasses, worn and powered, streaming from the phone's back camera. */
    fun enableMockGlasses() {
        scope.launch {
            try {
                val kit = MockDeviceKit.getInstance(context)
                kit.enable() // registration is simulated as REGISTERED by default
                var paired: MockGlasses? = null
                kit.pairGlasses(GlassesModel.RAYBAN_META).fold(
                    onSuccess = { paired = it },
                    onFailure = { error, _ -> _status.value = "Mock pairing failed: ${error.description}" },
                )
                val glasses = paired ?: return@launch
                glasses.powerOn()
                glasses.unfold()
                glasses.don()
                glasses.services.camera.setCameraFeed(CameraFacing.BACK)
                mockGlasses = glasses
                _status.value = "Mock Ray-Ban Meta paired and worn (phone back camera)"
            } catch (e: Exception) {
                _status.value = "Mock glasses error: ${e.message}"
            }
        }
    }

    /** Real glasses: hands off to the Meta AI app to connect this app. Not needed in mock mode. */
    fun register(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    fun startCamera() {
        if (session != null) {
            _status.value = "Session already running"
            return
        }
        Wearables.createSession(AutoDeviceSelector()).fold(
            onSuccess = { s ->
                session = s
                scope.launch { s.errors.collect { e -> _status.value = "Session error: ${e.description}" } }
                scope.launch {
                    s.state.collect { state ->
                        _status.value = "Session: $state"
                        if (state == DeviceSessionState.STARTED && camera == null) attachCamera(s)
                        if (state == DeviceSessionState.STOPPED) {
                            _ready.value = false
                            camera = null
                            session = null
                        }
                    }
                }
                s.start() // fire-and-forget; camera is attached once STARTED
            },
            onFailure = { error, _ -> _status.value = "Could not create session: ${error.description}" },
        )
    }

    private fun attachCamera(s: DeviceSession) {
        s.addCamera(StreamConfiguration(videoQuality = VideoQuality.HIGH, frameRate = 15)).fold(
            onSuccess = { cam ->
                camera = cam
                scope.launch {
                    cam.stream.state.collect { st ->
                        _ready.value = st == StreamState.STREAMING
                        _status.value = "Glasses camera: $st"
                    }
                }
                scope.launch(Dispatchers.Default) {
                    cam.stream.videoStream.collect { frame ->
                        val now = System.currentTimeMillis()
                        if (now - lastPreviewMs < 120) return@collect
                        lastPreviewMs = now
                        try {
                            lumaPreview(frame.buffer, frame.width, frame.height)?.let { _preview.value = it }
                        } catch (_: Exception) { /* skip a malformed frame */ }
                    }
                }
                cam.stream.start().onFailure { error, _ -> _status.value = "Stream failed: ${error.description}" }
            },
            onFailure = { error, _ -> _status.value = "Camera failed: ${error.description}" },
        )
    }

    /** Full-resolution still from the glasses camera (stream must be active). */
    suspend fun capturePhoto(): Bitmap? {
        val cam = camera ?: run { _status.value = "Start the glasses camera first"; return null }
        var photo: PhotoData? = null
        cam.stream.capturePhoto()
            .onSuccess { photo = it }
            .onFailure { error, _ -> _status.value = "Capture failed: ${error.description}" }
        return when (val p = photo) {
            is PhotoData.Bitmap -> p.bitmap
            is PhotoData.HEIC -> withContext(Dispatchers.Default) { decodeHeic(p.data) }
            null -> null
        }
    }

    /**
     * The SDK delivers decoded frames as YUV 4:2:0 pixel data. The first plane is luma (brightness) in every
     * 4:2:0 layout, so a grayscale preview from it is always correct, whatever the chroma layout.
     */
    private fun lumaPreview(data: ByteBuffer, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val buf = data.duplicate().apply { rewind() }
        val total = buf.remaining()
        val stride = maxOf(w, total / (h * 3 / 2))
        if (stride.toLong() * h > total) return null
        val step = maxOf(1, w / 480)
        val ow = w / step
        val oh = h / step
        val px = IntArray(ow * oh)
        var i = 0
        for (y in 0 until oh) {
            val row = y * step * stride
            for (x in 0 until ow) {
                val v = buf.get(row + x * step).toInt() and 0xFF
                px[i++] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        return Bitmap.createBitmap(px, ow, oh, Bitmap.Config.ARGB_8888)
    }

    fun stop() {
        camera?.stop()
        session?.stop()
        camera = null
        session = null
        _ready.value = false
        _preview.value = null
    }

    // Glasses store orientation in EXIF, which BitmapFactory does not apply.
    private fun decodeHeic(data: ByteBuffer): Bitmap? {
        val buf = data.duplicate().apply { rewind() }
        val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val rotation = when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotation == 0f) return bmp
        val m = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
}
