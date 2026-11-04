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

    fun stop() {
        camera?.stop()
        session?.stop()
        camera = null
        session = null
        _ready.value = false
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
