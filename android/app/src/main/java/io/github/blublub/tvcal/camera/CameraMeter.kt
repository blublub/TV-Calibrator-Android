// Android port of CameraMeter and CameraXYZMeter in "TV Calibrator/Camera/CameraMeter.swift" and
// "CameraXYZMeter.swift" (TV Calibrator by Slidebean, https://github.com/slidebean/tv-calibrator,
// CC BY-NC 4.0). Changed: translated to Kotlin; thresholds scale with the sensor's white level.
package io.github.blublub.tvcal.camera

import android.content.Context
import io.github.blublub.tvcal.engine.CameraProfile
import io.github.blublub.tvcal.engine.CameraSettings
import io.github.blublub.tvcal.engine.MeasureError
import io.github.blublub.tvcal.engine.Meter
import io.github.blublub.tvcal.engine.MeterClock
import io.github.blublub.tvcal.engine.MeterReading
import io.github.blublub.tvcal.engine.Vec3
import io.github.blublub.tvcal.engine.engineJson
import io.github.blublub.tvcal.engine.fixed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One camera reading, as the app sees it. */
data class CameraReading(
    /** Dark-corrected sensor signal per second of exposure. */
    val rgb: DoubleArray,
    val duration: Double,
    val uneven: Double,
    /** Brightest channel as a share of full scale. */
    val peak: Double,
    val settings: CameraSettings,
    /** When the light was captured (meter clock, seconds). */
    val capturedAt: Double,
)

/** Sensor baseline (black) measured with the lens covered at a short and a long shutter time. */
@Serializable
data class DarkLevel(
    /** At 1/30 s. */
    val short: List<Double>,
    /** At 1/4 s. */
    val long: List<Double>,
    /** The camera and RAW format it was measured with: measured again if they change. */
    val camera: String? = null,
) {
    /** Baseline for any shutter time (straight line through the two measurements). */
    fun at(t: Double): DoubleArray {
        val f = (t - T_SHORT) / (T_LONG - T_SHORT)
        return DoubleArray(3) { short[it] + (long[it] - short[it]) * f }
    }

    companion object {
        const val T_SHORT = 1.0 / 30
        const val T_LONG = 0.25
    }
}

/** The camera meter: dark measurement, auto-exposure ladder, one reading at a time. */
class CameraMeter(context: Context) {
    val engine = CameraEngine(context)
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _message = MutableStateFlow("The camera is off.")
    val message: StateFlow<String> = _message.asStateFlow()
    private val _dark = MutableStateFlow<DarkLevel?>(null)
    val dark: StateFlow<DarkLevel?> = _dark.asStateFlow()
    private val _lastReading = MutableStateFlow<CameraReading?>(null)
    val lastReading: StateFlow<CameraReading?> = _lastReading.asStateFlow()
    private val _logLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = _logLines.asStateFlow()

    private var duration = 1.0 / 250
    /** Only one measurement uses the camera at a time (two would mix up which photo had which shutter time). */
    private val inUse = Mutex()

    init {
        engine.logger = { log(it) }
        prefs.getString("darkLevel", null)?.let { s ->
            runCatching { engineJson.decodeFromString(DarkLevel.serializer(), s) }.getOrNull()?.let { _dark.value = it }
        }
    }

    fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date())
        _logLines.value = (_logLines.value + "$stamp  $line").takeLast(200)
    }

    val ready: Boolean get() = _running.value && _dark.value != null
    val settings: CameraSettings get() = engine.settings

    /** The thresholds below were written for 14-bit iPhone data. */
    private val bitScale: Double get() = engine.whiteLevel / 16383.0

    /** Starts the camera. The caller has already been granted the camera permission. */
    suspend fun start() {
        if (_running.value) return
        _message.value = "Starting the camera…"
        val error = engine.start()
        if (error != null) {
            _message.value = error
            return
        }
        // A darkness measured with another camera or RAW format doesn't apply.
        val d = _dark.value
        if (d != null && d.camera != engine.settings.format + " " + engine.settings.lens) {
            log("The saved darkness was measured with another camera setup: measure it again.")
            _dark.value = null
        }
        _dark.value?.let { engine.floorLevel = it.short.max() }
        _running.value = true
        _message.value = if (_dark.value == null) "Cover the lens and measure the darkness once." else "Ready to measure."
    }

    fun stop() {
        engine.stop()
        _running.value = false
        _message.value = "The camera is off."
    }

    private suspend fun shot(seconds: Double, dark: Boolean = false) = engine.capture(seconds, learnsWhite = !dark)

    /**
     * With the lens covered: the sensor's baseline at a short and a long shutter time, subtracted from
     * every reading. Kept between launches; measure again after a system update.
     */
    suspend fun measureDark() = inUse.withLock {
        _busy.value = true
        try {
            _message.value = "Measuring with the lens covered…"
            // Three shutter times: the shortest, 1/30 s and 1/4 s. In real darkness all three read the same.
            // A little light leaking in shows between 1/30 s and 1/4 s; a lot can fill the sensor at both, and
            // shows against the shortest.
            val c = shot(engine.minDuration, dark = true)
            val a = shot(DarkLevel.T_SHORT, dark = true)
            val b = shot(DarkLevel.T_LONG, dark = true)
            val cs = c.stats; val st = a.stats; val lt = b.stats
            if (cs == null || st == null || lt == null) {
                _message.value = "Couldn't take a photo. Try again."
                return@withLock
            }
            val leak = (0 until 3).maxOf { lt.mean[it] - st.mean[it] }
            val flood = (0 until 3).maxOf { st.mean[it] - cs.mean[it] }
            val limit = 120 * bitScale
            if (leak > limit || flood > limit) {
                log("Dark rejected: brightest colour reads ${fixed(cs.mean.max(), 1)} at 1/${fixed(1 / c.used, 0)} s, " +
                    "${fixed(st.mean.max(), 1)} at 1/${fixed(1 / a.used, 0)} s, ${fixed(lt.mean.max(), 1)} at 1/${fixed(1 / b.used, 0)} s")
                _message.value = if (maxOf(leak, flood) > 2000 * bitScale)
                    "Still seeing light. Lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
                else "Some light is still getting in. A fingertip isn't enough: lay the phone screen-up on a table, so the camera on the back is pressed flat against it, and try again."
                return@withLock
            }
            val d = DarkLevel(st.mean.toList(), lt.mean.toList(), engine.settings.format + " " + engine.settings.lens)
            _dark.value = d
            engine.floorLevel = d.short.max()
            prefs.edit().putString("darkLevel", engineJson.encodeToString(DarkLevel.serializer(), d)).apply()
            log("Dark at 1/${fixed(1 / a.used, 0)} s: ${st.mean.joinToString(" ") { fixed(it, 2) }}; " +
                "at 1/${fixed(1 / b.used, 0)} s: ${lt.mean.joinToString(" ") { fixed(it, 2) }}")
            _message.value = "Ready. Point the box at the centre of the TV."
        } finally {
            _busy.value = false
        }
    }

    /** One reading: finds a shutter time that exposes the patch well, then reports the signal. */
    suspend fun measure(): Pair<CameraReading?, String?> = inUse.withLock {
        val dark = _dark.value
        if (!ready || dark == null) return@withLock null to "The camera isn't ready."
        _busy.value = true
        try {
            var seconds = duration
            repeat(7) {
                val s = shot(seconds)
                val stats = s.stats
                if (stats == null) {
                    log("Measurement failed: no usable photo")
                    return@withLock null to "The phone couldn't take a photo."
                }
                val base = dark.at(s.used)
                val net = DoubleArray(3) { maxOf(stats.mean[it] - base[it], 0.0) }
                val peak = net.max() / maxOf(engine.whiteLevel - base.max(), 1000 * bitScale)
                if (stats.clipped || peak > 0.75) {
                    if (s.used <= engine.minDuration * 1.01) {
                        return@withLock null to "Too bright for the camera. Move the phone a little further from the TV."
                    }
                    seconds = s.used / 4
                    return@repeat
                }
                if (peak < 0.15 && s.used < engine.maxDuration * 0.99) {
                    seconds = minOf(s.used * minOf(0.4 / maxOf(peak, 0.002), 16.0), engine.maxDuration)
                    return@repeat
                }
                duration = s.used
                val reading = CameraReading(DoubleArray(3) { net[it] / s.used }, s.used, stats.uneven, peak, engine.settings, s.at)
                _lastReading.value = reading
                return@withLock reading to null
            }
            null to "Couldn't find a good exposure for this patch."
        } finally {
            _busy.value = false
        }
    }
}

/** The camera as a Meter for the engine: each reading is the RAW camera signal turned into XYZ with a profile. */
class CameraXYZMeter(
    private val camera: CameraMeter,
    private val profile: CameraProfile,
    /** Refuse to measure if the camera's locked settings differ from the profile's. */
    private val checkSettings: Boolean = true,
    /** The kind of screen being measured (a key in the profile's `panels`); null uses the general conversion. */
    private val panel: String? = null,
) : Meter {
    /** The light in a reading is captured a little before the reading comes back (RAW processing). */
    override val integration: Double get() = 0.25
    override fun now(): Double = MeterClock.now()
    override suspend fun sleep(seconds: Double) = MeterClock.sleep(seconds)

    override suspend fun read(): MeterReading {
        val t0 = now()
        val (reading, problem) = camera.measure()
        val t1 = now()
        if (reading == null) {
            if (problem != null && problem.startsWith("Too bright")) throw MeasureError.Message(problem)
            // No photo (the camera was interrupted, or isn't ready): a missed reading, after a short pause so
            // callers that keep trying don't spin.
            MeterClock.sleep(0.3)
            return MeterReading(t0, now(), null)
        }
        val diffs = if (checkSettings) profile.camera.mismatches(reading.settings) else emptyList()
        if (diffs.isNotEmpty()) {
            throw MeasureError.Message(
                "The camera is running with different settings than its profile was made for (" +
                    diffs.joinToString("; ") + "). This can follow a system update.",
            )
        }
        return MeterReading(t0, t1, profile.xyz(Vec3(reading.rgb[0], reading.rgb[1], reading.rgb[2]), panel))
    }
}
