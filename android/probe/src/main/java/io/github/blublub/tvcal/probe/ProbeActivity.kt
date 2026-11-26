package io.github.blublub.tvcal.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.BlackLevelPattern
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.util.SizeF
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Diagnostic probe: dumps every Camera2 characteristic of every camera and takes a few
 * manual-exposure RAW_SENSOR frames from each back camera to prove that a third-party app
 * gets usable raw sensor data. The report is written to logcat (tag TVCAL) and to
 * files/probe.txt (read with `adb exec-out run-as <pkg> cat files/probe.txt`).
 * It contains device/camera characteristics only, no personal data.
 */
class ProbeActivity : Activity() {

    private lateinit var view: TextView
    private val report = StringBuilder()
    private val thread = HandlerThread("probe").apply { start() }
    private val handler = Handler(thread.looper)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 9f
            setPadding(16, 16, 16, 16)
        }
        setContentView(ScrollView(this).apply { addView(view) })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            begin()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin() else log("Camera permission denied")
    }

    private fun log(s: String) {
        s.lines().forEach { Log.i(TAG, it) }
        synchronized(report) { report.append(s).append('\n') }
        runOnUiThread { view.text = synchronized(report) { report.toString() } }
    }

    private fun begin() {
        handler.post {
            try {
                log("=== TV Cal Probe ===")
                log("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}")
                val cm = getSystemService(CameraManager::class.java)
                val ids = cm.cameraIdList.toList()
                log("cameraIdList=$ids")
                val all = LinkedHashSet<String>(ids)
                ids.forEach { all.addAll(cm.getCameraCharacteristics(it).physicalCameraIds) }
                for (id in all) dump(cm, id, inList = id in ids)
                for (id in ids) {
                    val c = cm.getCameraCharacteristics(id)
                    if (c.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) continue
                    val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW !in caps) {
                        log("\n### RAW test camera $id: skipped (no RAW capability)")
                        continue
                    }
                    try { rawTest(cm, id, c) } catch (t: Throwable) { log("RAW test $id FAILED: $t") }
                }
                log("\n=== done ===")
            } catch (t: Throwable) {
                log("FATAL: ${Log.getStackTraceString(t)}")
            } finally {
                File(filesDir, "probe.txt").writeText(synchronized(report) { report.toString() })
                Log.i(TAG, "PROBE_FINISHED")
            }
        }
    }

    // ---------- characteristics dump ----------

    private fun dump(cm: CameraManager, id: String, inList: Boolean) {
        val c = cm.getCameraCharacteristics(id)
        log("\n### camera $id ${if (inList) "" else "(physical only)"}")
        for (key in c.keys.sortedBy { it.name }) {
            val v = try { fmt(c.get(key)) } catch (t: Throwable) { "<err $t>" }
            log("${key.name} = $v")
        }
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> "null"
        is IntArray -> v.joinToString(",", "[", "]")
        is FloatArray -> v.joinToString(",", "[", "]")
        is LongArray -> v.joinToString(",", "[", "]")
        is BooleanArray -> v.joinToString(",", "[", "]")
        is ByteArray -> "byte[${v.size}]"
        is Array<*> -> v.joinToString(",", "[", "]") { fmt(it) }
        is Rational -> "${v.numerator}/${v.denominator}"
        is ColorSpaceTransform -> (0 until 3).joinToString(" ; ", "[", "]") { r ->
            (0 until 3).joinToString(" ") { col -> "%.5f".format(v.getElement(col, r).toDouble()) }
        }
        is BlackLevelPattern -> (0 until 4).joinToString(",", "[", "]") { v.getOffsetForIndex(it % 2, it / 2).toString() }
        is RggbChannelVector -> "R=${v.red} Ge=${v.greenEven} Go=${v.greenOdd} B=${v.blue}"
        is StreamConfigurationMap -> {
            val raw = v.getOutputSizes(ImageFormat.RAW_SENSOR)?.joinToString { it.toString() } ?: "-"
            val raw10 = v.getOutputSizes(ImageFormat.RAW10)?.joinToString { it.toString() } ?: "-"
            val yuv = v.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }
            "RAW_SENSOR{$raw} RAW10{$raw10} YUVmax=$yuv"
        }
        is Range<*>, is Size, is SizeF, is Rect -> v.toString()
        else -> v.toString()
    }

    // ---------- manual RAW capture test ----------

    private fun rawTest(cm: CameraManager, id: String, c: CameraCharacteristics) {
        log("\n### RAW test camera $id")
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxByOrNull { it.width * it.height }!!
        val isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val expRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val cfa = c.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT) ?: -1
        val white = c.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 0
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val manual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        log("size=$size cfa=$cfa white=$white iso=$isoRange exp=$expRange manual=$manual")

        val devQ = LinkedBlockingQueue<Any>()
        cm.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) { devQ.put(d) }
            override fun onDisconnected(d: CameraDevice) { d.close(); devQ.put("disconnected") }
            override fun onError(d: CameraDevice, e: Int) { d.close(); devQ.put("error $e") }
        }, handlerForCallbacks)
        val dev = devQ.poll(5, TimeUnit.SECONDS) as? CameraDevice ?: run { log("open failed"); return }

        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 4)
        val images = LinkedBlockingQueue<Image>()
        reader.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { images.put(it) } }, handlerForCallbacks)

        val sesQ = LinkedBlockingQueue<Any>()
        @Suppress("DEPRECATION")
        dev.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) { sesQ.put(s) }
            override fun onConfigureFailed(s: CameraCaptureSession) { sesQ.put("configure failed") }
        }, handlerForCallbacks)
        val ses = sesQ.poll(5, TimeUnit.SECONDS) as? CameraCaptureSession
        if (ses == null) { log("session failed"); reader.close(); dev.close(); return }

        try {
            // Same idea as the iPhone app: lowest ISO, fixed shutter, everything automatic off.
            val iso = isoRange?.lower ?: 100
            val exposures = listOf(1_000_000_000L / 60, 1_000_000_000L / 250)
            for (expNs in exposures) {
                val e = expRange?.clamp(expNs) ?: expNs
                val req = dev.createCaptureRequest(
                    if (manual) CameraDevice.TEMPLATE_MANUAL else CameraDevice.TEMPLATE_STILL_CAPTURE
                ).apply {
                    addTarget(reader.surface)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                    set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(e, 33_333_333L))
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 1.0f) // ~1 m
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF)
                }.build()
                repeat(3) { n ->
                    val resQ = LinkedBlockingQueue<TotalCaptureResult>()
                    ses.capture(req, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) { resQ.put(res) }
                    }, handlerForCallbacks)
                    val res = resQ.poll(5, TimeUnit.SECONDS)
                    val img = images.poll(5, TimeUnit.SECONDS)
                    if (res == null || img == null) { log("frame $n: timeout (result=${res != null} image=${img != null})"); img?.close(); return@repeat }
                    log("req exp=${e / 1000}us iso=$iso -> " + describe(res) + " | " + analyse(img, cfa, white))
                    img.close()
                }
            }
        } finally {
            ses.close(); reader.close(); dev.close()
        }
    }

    private val handlerForCallbacks by lazy { Handler(HandlerThread("cb").apply { start() }.looper) }

    private fun describe(r: TotalCaptureResult): String {
        val exp = r.get(android.hardware.camera2.CaptureResult.SENSOR_EXPOSURE_TIME)
        val iso = r.get(android.hardware.camera2.CaptureResult.SENSOR_SENSITIVITY)
        val bl = r.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val wl = r.get(android.hardware.camera2.CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL)
        val neutral = r.get(android.hardware.camera2.CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)
        val ap = r.get(android.hardware.camera2.CaptureResult.LENS_APERTURE)
        val fd = r.get(android.hardware.camera2.CaptureResult.LENS_FOCUS_DISTANCE)
        return "got exp=${exp?.div(1000)}us iso=$iso f/$ap focus=$fd black=${fmt(bl)} whiteDyn=$wl neutral=${fmt(neutral)}"
    }

    /** Mean of each CFA channel in the centre 10% box, plus the raw maximum. */
    private fun analyse(img: Image, cfa: Int, white: Int): String {
        val p = img.planes[0]
        val buf = p.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = p.rowStride
        val px = p.pixelStride
        val w = img.width; val h = img.height
        val bw = (w / 10) and 1.inv(); val bh = (h / 10) and 1.inv()
        val x0 = ((w - bw) / 2) and 1.inv(); val y0 = ((h - bh) / 2) and 1.inv()
        val sum = DoubleArray(4); val cnt = IntArray(4); var max = 0
        for (y in y0 until y0 + bh) {
            val row = y * rowStride
            for (x in x0 until x0 + bw) {
                val v = buf.getShort(row + x * px).toInt() and 0xFFFF
                val i = (y and 1) * 2 + (x and 1)
                sum[i] += v; cnt[i]++
                if (v > max) max = v
            }
        }
        // Position index 0..3 = (0,0),(0,1),(1,0),(1,1) mapped to colour by CFA layout.
        val names = when (cfa) {
            0 -> listOf("R", "Gr", "Gb", "B")
            1 -> listOf("Gr", "R", "B", "Gb")
            2 -> listOf("Gb", "B", "R", "Gr")
            3 -> listOf("B", "Gb", "Gr", "R")
            else -> listOf("c0", "c1", "c2", "c3")
        }
        val means = (0 until 4).joinToString(" ") { "${names[it]}=%.1f".format(sum[it] / cnt[it]) }
        return "${w}x$h stride=$rowStride px=$px centre[$means] max=$max/$white"
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    companion object { const val TAG = "TVCAL" }
}
