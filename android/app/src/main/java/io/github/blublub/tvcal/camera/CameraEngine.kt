// Android counterpart of CameraEngine in "TV Calibrator/Camera/CameraMeter.swift" (TV Calibrator by
// Slidebean, https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: rewritten for Camera2.
package io.github.blublub.tvcal.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import io.github.blublub.tvcal.engine.CameraSettings
import io.github.blublub.tvcal.engine.Mat3
import io.github.blublub.tvcal.engine.MeterClock
import io.github.blublub.tvcal.engine.fixed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor

/**
 * The phone camera as a light meter, at the Camera2 level.
 *
 * It takes RAW_SENSOR photos (straight from the sensor: no tone curve, no white balance, no processing)
 * from the back main camera and averages the red, green and blue photosites in the centre box.
 * Everything that affects the numbers is locked: lens, focus (closest, so the TV's pixel grid blurs
 * out), ISO (lowest) and the RAW format. Only the shutter time changes, and readings are divided by it.
 */
class CameraEngine(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("tvcal-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private val lock = Mutex()

    var logger: ((String) -> Unit)? = null
    private fun log(s: String) = logger?.invoke(s)

    /** A shot: the centre-box numbers, the shutter time really used, and when the light was captured. */
    data class Shot(val stats: RawStats?, val used: Double, val at: Double)

    // What was chosen and locked (valid once started).
    var cameraId: String? = null; private set
    var rawSize: Size? = null; private set
    var cfa = -1; private set
    var whiteLevel = 1023.0; private set
    var blackLevel = 64.0; private set
    var iso = 100; private set
    var minDuration = 1.0 / 8000; private set
    var maxDuration = 0.25; private set
    var aperture = 0.0; private set
    var focalLength = 0.0; private set
    var colorInfo: CameraColorInfo? = null; private set
    private var focusDistance = 0f
    private var minFrameNs = 0L
    private var manualTemplate = true

    /** Lowest analog-to-digital floor the camera has measured with the lens covered (0 until then). */
    @Volatile var floorLevel = 0.0

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var pending: CompletableDeferred<Pair<android.media.Image?, TotalCaptureResult?>>? = null

    val running: Boolean get() = session != null

    val settings: CameraSettings
        get() = CameraSettings(
            iso = iso.toDouble(),
            format = rawSize?.let { "${it.width}x${it.height} cfa$cfa" },
            lens = cameraId?.let { "camera $it ${fixed(focalLength, 2)}mm f/${fixed(aperture, 2)}" },
            aperture = null,
        )

    /** The camera the meter uses: a back camera with RAW and manual control, the biggest sensor first. */
    private fun choose(): String? {
        data class Cand(val id: String, val area: Float, val logical: Boolean)
        val cands = manager.cameraIdList.mapNotNull { id ->
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) return@mapNotNull null
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return@mapNotNull null
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) return@mapNotNull null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return@mapNotNull null
            Cand(id, size.width * size.height, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps)
        }
        // A physical camera, when the phone lists it, can't switch lenses behind our back.
        return (cands.filter { !it.logical }.maxByOrNull { it.area } ?: cands.maxByOrNull { it.area })?.id
    }

    private fun mat(t: ColorSpaceTransform?): Mat3? =
        t?.let { m -> List(3) { r -> List(3) { c -> m.getElement(c, r).toDouble() } } }

    /** Why `inspect()` found nothing usable, in words. */
    var inspectProblem: String? = null; private set

    /**
     * Picks the camera and reads its locked settings and colour data without opening it. Returns the
     * colour data, or null (and `inspectProblem` says why).
     */
    fun inspect(): CameraColorInfo? {
        inspectProblem = try {
            load()
        } catch (e: Exception) {
            "Couldn't read the camera's details (${e.message})."
        }
        return if (inspectProblem == null) colorInfo else null
    }

    /** Reads everything about the chosen camera. Null when it can be used, else what's missing. */
    private fun load(): String? {
        val id = choose() ?: return "This phone's camera doesn't give apps RAW photos, which the camera meter needs."
        val c = manager.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualTemplate = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        if (!manualTemplate) return "This phone's camera doesn't allow manual exposure, which the camera meter needs."
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
            ?: return "This phone's camera has no RAW format."
        cameraId = id
        rawSize = size
        cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        whiteLevel = (c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023).toDouble()
        val bl = c.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        blackLevel = bl?.let { p -> (0 until 4).map { p.getOffsetForIndex(it % 2, it / 2) }.average() } ?: 0.0
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        iso = isoRange?.lower ?: 100
        val exp = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        minDuration = maxOf((exp?.lower ?: 125_000L) / 1e9, 1.0 / 8000)
        maxDuration = minOf((exp?.upper ?: 250_000_000L) / 1e9, 0.25)
        aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.toDouble() ?: 0.0
        focalLength = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()?.toDouble() ?: 0.0
        // Closest focus: at TV distance this softens the picture so the pixel grid averages out.
        focusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        minFrameNs = map.getOutputMinFrameDuration(ImageFormat.RAW_SENSOR, size)
        colorInfo = CameraColorInfo(
            colorTransform1 = mat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)),
            colorTransform2 = mat(c.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)),
            calibration1 = mat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)),
            calibration2 = mat(c.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)),
            illuminant1 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1),
            illuminant2 = c.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt(),
            black = blackLevel, white = whiteLevel, aperture = aperture, iso = iso.toDouble(),
        )
        if (colorInfo?.colorTransform1 == null && colorInfo?.colorTransform2 == null) colorInfo = null
        return null
    }

    /** Sets up the back main camera for RAW capture with focus, ISO and exposure locked. Null when ready. */
    @SuppressLint("MissingPermission")
    suspend fun start(): String? = lock.withLock {
        if (device != null) return@withLock null
        load()?.let { return@withLock it }
        val id = cameraId!!
        val size = rawSize!!
        log("Camera $id: RAW ${size.width}x${size.height}, CFA $cfa, white ${whiteLevel.toInt()}, black ${fixed(blackLevel, 1)}, ISO $iso, f/${fixed(aperture, 2)}, ${fixed(focalLength, 2)} mm")

        val opened = CompletableDeferred<Any>()
        manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { opened.complete(d) }
            override fun onDisconnected(d: CameraDevice) {
                d.close(); if (!opened.isCompleted) opened.complete("disconnected") else onLost("The camera was taken by another app.")
            }
            override fun onError(d: CameraDevice, error: Int) {
                d.close(); if (!opened.isCompleted) opened.complete("error $error") else onLost("The camera stopped (error $error).")
            }
        })
        val d = withTimeoutOrNull(5000) { opened.await() }
        if (d !is CameraDevice) return@withLock "Couldn't open the camera (${d ?: "timeout"})."
        device = d
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 3)
        r.setOnImageAvailableListener({ rd ->
            val img = rd.acquireNextImage() ?: return@setOnImageAvailableListener
            val p = pending
            if (p == null || p.isCompleted) img.close() else p.complete(img to null)
        }, handler)
        reader = r
        configure()
    }

    private fun onLost(why: String) {
        log(why)
        session = null
        device = null
        pending?.complete(null to null)
    }

    /** (Re)creates the capture session for the RAW reader and, when there is one, the preview. */
    private suspend fun configure(): String? {
        val d = device ?: return "The camera isn't open."
        val r = reader ?: return "The camera isn't open."
        session?.close()
        session = null
        val outs = mutableListOf(OutputConfiguration(r.surface))
        preview?.let { outs.add(OutputConfiguration(it)) }
        val done = CompletableDeferred<CameraCaptureSession?>()
        d.createCaptureSession(
            SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs, executor, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { done.complete(s) }
                override fun onConfigureFailed(s: CameraCaptureSession) { done.complete(null) }
            }),
        )
        val s = withTimeoutOrNull(5000) { done.await() } ?: return "Couldn't set up the camera."
        session = s
        preview?.let { surf ->
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surf)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            }.build()
            s.setRepeatingRequest(req, null, handler)
        }
        return null
    }

    /** Shows the live picture on a surface (null: no preview). */
    suspend fun setPreview(surface: Surface?) = lock.withLock {
        if (surface == preview) return@withLock
        preview = surface
        if (device != null) configure()?.let { log(it) }
    }

    /** A surface size for the preview: the sensor's 4:3, not too large. */
    fun previewSize(texture: SurfaceTexture? = null): Size {
        val id = cameraId ?: choose() ?: return Size(1440, 1080)
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val sizes = map?.getOutputSizes(SurfaceTexture::class.java) ?: return Size(1440, 1080)
        return sizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width } ?: sizes.first()
    }

    fun stop() {
        session?.close(); session = null
        device?.close(); device = null
        reader?.close(); reader = null
    }

    /**
     * Takes one RAW photo at the given shutter time (seconds). Answers exactly once, also when the camera
     * never delivers (a missing photo comes back as null stats).
     */
    suspend fun capture(duration: Double, learnsWhite: Boolean = true): Shot = lock.withLock {
        val d = device; val s = session
        val started = MeterClock.now()
        if (d == null || s == null) return@withLock Shot(null, duration, started)
        val t = duration.coerceIn(minDuration, maxDuration)
        val ns = (t * 1e9).toLong()
        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
            addTarget(reader!!.surface)
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(ns, minFrameNs))
            set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
            set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
            set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
        }.build()
        val imageWait = CompletableDeferred<Pair<android.media.Image?, TotalCaptureResult?>>()
        val resultWait = CompletableDeferred<TotalCaptureResult?>()
        pending = imageWait
        s.capture(req, object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { resultWait.complete(res) }
            override fun onCaptureFailed(cs: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                log("Photo failed (reason ${f.reason})"); resultWait.complete(null); imageWait.complete(null to null)
            }
        }, handler)
        val timeoutMs = ((t + 8) * 1000).toLong()
        val img = withTimeoutOrNull(timeoutMs) { imageWait.await() }?.first
        val res = withTimeoutOrNull(2000) { resultWait.await() }
        pending = null
        if (img == null) {
            log("The camera didn't answer in time")
            return@withLock Shot(null, t, started + t / 2)
        }
        val used = res?.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1e9 } ?: t
        val gotIso = res?.get(CaptureResult.SENSOR_SENSITIVITY)
        if (gotIso != null && gotIso != iso) log("The camera used ISO $gotIso instead of $iso")
        if (kotlin.math.abs(used - t) > t * 0.02) log("The camera used ${fixed(used * 1000, 2)} ms instead of ${fixed(t * 1000, 2)} ms")
        val stats = try {
            val p = img.planes[0]
            RawAnalysis.measure(p.buffer, img.width, img.height, p.rowStride, p.pixelStride, cfa, whiteLevel, floorLevel)
        } finally {
            img.close()
        }
        if (stats == null) log("RAW photo arrived but couldn't be read (CFA $cfa)")
        // A clipped photo shows where the sensor really tops out, if that's lower than its data says.
        if (learnsWhite && stats != null && stats.plateau && stats.maxRaw < whiteLevel * 0.98) {
            whiteLevel = stats.maxRaw
            log("Sensor white level ${whiteLevel.toInt()} (learned from a clipped photo)")
        }
        Shot(stats, used, started + used / 2)
    }
}
