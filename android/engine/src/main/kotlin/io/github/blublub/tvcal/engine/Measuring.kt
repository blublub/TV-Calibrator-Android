// Port of CalibrationKit/Sources/CalibrationKit/Measuring.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin; async/await
// became coroutines.
package io.github.blublub.tvcal.engine

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

// Measurements

/** A patch that got no reading (too dark for the meter, or the meter too slow). */
@Serializable
data class MissedPatch(
    val name: String,
    /** How long each reading attempt took, in seconds. */
    @SerialName("readings_s") val readings: List<Double>,
    /** Seconds the patch was on screen. */
    @SerialName("hold_s") val hold: Double,
)

/**
 * One round's readings: XYZ per patch id. Encodes like the Mac app's history files:
 * {"W0": [X, Y, Z], "G5": null, ..., "_missed": {...}}.
 */
@Serializable(with = MeasurementsSerializer::class)
data class Measurements(
    val values: MutableMap<String, Vec3> = LinkedHashMap(),
    val missed: MutableMap<String, MissedPatch> = LinkedHashMap(),
) {
    operator fun get(id: String): Vec3? = values[id]
    operator fun set(id: String, v: Vec3?) {
        if (v == null) values.remove(id) else values[id] = v
    }

    fun toJson(): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        for ((k, v) in values) m[k] = JsonArray(v.toList().map { JsonPrimitive(it) })
        for (k in missed.keys) if (values[k] == null) m[k] = JsonNull
        if (missed.isNotEmpty()) {
            m["_missed"] = JsonObject(missed.mapValues { engineJson.encodeToJsonElement(MissedPatch.serializer(), it.value) })
        }
        return JsonObject(m)
    }

    companion object {
        fun fromJson(e: JsonElement): Measurements {
            val out = Measurements()
            for ((k, v) in e.jsonObject) {
                if (k == "_missed") {
                    for ((mk, mv) in v.jsonObject) out.missed[mk] = engineJson.decodeFromJsonElement(MissedPatch.serializer(), mv)
                } else if (v is JsonArray && v.size == 3) {
                    val n = v.map { (it as? JsonPrimitive)?.doubleOrNull }
                    if (n.all { it != null }) out.values[k] = Vec3(n[0]!!, n[1]!!, n[2]!!)
                }
            }
            return out
        }
    }
}

object MeasurementsSerializer : KSerializer<Measurements> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Measurements) {
        (encoder as JsonEncoder).encodeJsonElement(value.toJson())
    }
    override fun deserialize(decoder: Decoder): Measurements =
        Measurements.fromJson((decoder as JsonDecoder).decodeJsonElement())
}

/** The engine's JSON settings: tolerant of unknown keys (newer files), no pretty printing. */
val engineJson = kotlinx.serialization.json.Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

// Meters

/** One reading: when it started and ended (on the meter's own clock, seconds) and what it saw. */
data class MeterReading(val start: Double, val end: Double, val xyz: Vec3?)

/** Anything that measures the TV: the phone camera (with its profile), or a simulation. */
interface Meter {
    /** Seconds on the meter's clock (monotonic). Simulations use a virtual clock. */
    fun now(): Double
    suspend fun sleep(seconds: Double)
    suspend fun read(): MeterReading
    /** How long before a reading's end the light it reports was captured (the sync uses this). */
    val integration: Double
}

/** Real-time clock helpers for meters that measure real light. */
object MeterClock {
    fun now(): Double = System.nanoTime() / 1e9
    suspend fun sleep(seconds: Double) {
        if (seconds > 0) delay((seconds * 1000).toLong())
    }
}

sealed class MeasureError(message: String) : Exception(message) {
    object NoSync : MeasureError("no sync") {
        override val message: String get() = EngineTexts.current.noSync
    }
    object OutOfOrder : MeasureError("out of order") {
        override val message: String get() = EngineTexts.current.outOfOrder
    }
    class Message(text: String) : MeasureError(text)
}

/** Progress while a video is being measured. */
sealed class SweepEvent {
    object Waiting : SweepEvent()
    data class Reading(val y: Double?) : SweepEvent()
    object Synced : SweepEvent()
    data class PatchStart(val index: Int, val id: String, val name: String) : SweepEvent()
    data class PatchDone(val index: Int, val id: String, val name: String, val xyz: Vec3?) : SweepEvent()
}

// Following a pattern video

object Sweep {
    internal fun median(v: List<Double>): Double {
        val s = v.sorted()
        return s[s.size / 2]
    }

    internal fun neutral(v: Vec3, limit: Double = 0.05): Boolean =
        v.y > 0 && ColourMath.polar(ColourMath.xyzToXY(v)).r < limit

    private class Run(val ys: MutableList<Double>, val ts: MutableList<Double>, val xyz: MutableList<Vec3>)

    /**
     * Waits for the sync and returns the time (meter clock) the patch sequence starts.
     *
     * The video goes: black, grey, WHITE, grey, then the first (bright) patch. This looks for
     * bright -> grey (~6 s) -> bright, all three neutral (not a colour). Readings that straddle a
     * change are skipped, and anything shown before PLAY is ignored.
     */
    suspend fun findSync(meter: Meter, timeout: Double = 240.0, onEvent: ((SweepEvent) -> Unit)? = null): Double {
        onEvent?.invoke(SweepEvent.Waiting)
        val tStart = meter.now()
        val runs = ArrayList<Run>()
        while (meter.now() - tStart < timeout) {
            currentCoroutineContext().ensureActive()
            val r = meter.read()
            val xyz = r.xyz
            if (xyz == null) {
                onEvent?.invoke(SweepEvent.Reading(null))
                continue
            }
            val t = r.end - meter.integration
            val y = maxOf(xyz.y, 1e-4)
            onEvent?.invoke(SweepEvent.Reading(xyz.y))
            val last = runs.lastOrNull()
            if (last != null && 1 / 1.6 < y / median(last.ys) && y / median(last.ys) < 1.6) {
                last.ys.add(y); last.ts.add(t); last.xyz.add(xyz)
            } else {
                runs.add(Run(mutableListOf(y), mutableListOf(t), mutableListOf(xyz)))
            }
            while (runs.size > 10) runs.removeAt(0)
            // drop single-reading runs that sit between their neighbours (a reading caught mid-change)
            val clean = ArrayList<Run>()
            for ((i, run) in runs.withIndex()) {
                if (i > 0 && i < runs.size - 1 && run.ys.size == 1) {
                    val a = median(runs[i - 1].ys); val b = run.ys[0]; val c = median(runs[i + 1].ys)
                    if (minOf(a, c) < b && b < maxOf(a, c)) continue
                }
                clean.add(run)
            }
            if (clean.size < 3) continue
            val h1Run = clean[clean.size - 3]; val lRun = clean[clean.size - 2]; val h2Run = clean[clean.size - 1]
            val h1 = median(h1Run.ys); val l = median(lRun.ys); val h2 = median(h2Run.ys)
            // the sync's grey is a mid grey, not black: bright/grey ratio ~4-20, never ~1000
            if (!(2.0 <= h1 / l && h1 / l <= 40 && 1.6 <= h2 / l && h2 / l <= 40 && 0.3 < h1 / h2 && h1 / h2 < 3.5)) continue
            // all three must be greys/whites, not colour patches
            if (!listOf(h1Run, lRun, h2Run).all { neutral(it.xyz[it.xyz.size / 2]) }) continue
            // The grey started between the last bright reading and the first grey one, and ended
            // between the last grey reading and the first bright one: work with ranges.
            val fallLo = h1Run.ts.last(); val fallHi = lRun.ts.first()
            val riseLo = lRun.ts.last(); val riseHi = h2Run.ts.first()
            val tol = 0.6
            if (!(riseLo - fallHi - tol <= Patterns.SYNC_HOLD && Patterns.SYNC_HOLD <= riseHi - fallLo + tol)) continue
            val lo = maxOf(riseLo, fallLo + Patterns.SYNC_HOLD)
            val hi = minOf(riseHi, fallHi + Patterns.SYNC_HOLD)
            val est = if (lo <= hi) (lo + hi) / 2 else (riseLo + riseHi) / 2
            onEvent?.invoke(SweepEvent.Synced)
            return est
        }
        throw MeasureError.NoSync
    }

    /** Measures every patch of a pattern video, following its timeline from the sync. */
    suspend fun sweep(meter: Meter, sequence: PatternSequence, onEvent: ((SweepEvent) -> Unit)? = null): Measurements {
        val tSeq = findSync(meter, onEvent = onEvent)
        val offsets = sequence.offsets
        val meas = Measurements()
        for ((i, p) in sequence.patches.withIndex()) {
            val start = tSeq + offsets[i]
            val end = start + p.hold
            onEvent?.invoke(SweepEvent.PatchStart(i, p.id, p.name))
            while (meter.now() < start + 1.2) {
                currentCoroutineContext().ensureActive()
                meter.sleep(minOf(0.25, start + 1.2 - meter.now()))
            }
            val vals = ArrayList<Vec3>()
            val tries = ArrayList<Double>()
            while (meter.now() < end - 1.0) {
                currentCoroutineContext().ensureActive()
                val r = meter.read()
                tries.add(pyRound(r.end - r.start, 1))
                onEvent?.invoke(SweepEvent.Reading(r.xyz?.y))
                val xyz = r.xyz
                if (xyz == null || r.start < start + 0.5) continue
                if (r.end - meter.integration <= end - 0.15) vals.add(xyz)
                // bright patches: one more reading if there's clearly time
                if (r.end + (r.end - r.start) > end - 0.6) break
            }
            if (vals.isEmpty()) {
                meas.missed[p.id] = MissedPatch(p.name, tries, p.hold)
            } else {
                meas[p.id] = vals.fold(Vec3.ZERO) { a, b -> a + b } / vals.size.toDouble()
            }
            onEvent?.invoke(SweepEvent.PatchDone(i, p.id, p.name, meas[p.id]))
        }
        return meas
    }

    /** Readings in the wrong order mean the video and the meter got out of step. */
    fun outOfOrder(meas: Measurements, hdr: Boolean): Boolean {
        val ys = Patterns.sequence(hdr).patches.filter { it.kind == PatchKind.grey }.mapNotNull { meas[it.id]?.y }
        return ys.zipWithNext().any { (a, b) -> b < a * 0.8 }
    }
}
