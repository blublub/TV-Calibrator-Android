// Port of "TV Calibrator/Measure/Runners.swift" (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin coroutines;
// state is Compose snapshot state.
package io.github.blublub.tvcal.measure

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.blublub.tvcal.engine.Guided
import io.github.blublub.tvcal.engine.LiveAnalysis
import io.github.blublub.tvcal.engine.LiveReading
import io.github.blublub.tvcal.engine.MeasureError
import io.github.blublub.tvcal.engine.Measurements
import io.github.blublub.tvcal.engine.Meter
import io.github.blublub.tvcal.engine.MeterClock
import io.github.blublub.tvcal.engine.Patch
import io.github.blublub.tvcal.engine.PatchKind
import io.github.blublub.tvcal.engine.PatternSequence
import io.github.blublub.tvcal.engine.Sweep
import io.github.blublub.tvcal.engine.SweepEvent
import io.github.blublub.tvcal.engine.Vec3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The main-thread scope the measuring loops run in (state changes happen on the main thread). */
internal fun uiScope() = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

/**
 * Follows a pattern video with the camera: waits for the sync flashes, then measures each patch.
 * Publishes progress for the measuring card.
 */
class SweepRunner {
    sealed class Phase {
        object Idle : Phase()
        object Waiting : Phase()
        object Measuring : Phase()
        object Done : Phase()
        object Cancelled : Phase()
        data class Failed(val message: String) : Phase()
    }

    data class PatchProgress(val id: String, val name: String, val kind: PatchKind, val Y: Double? = null, val done: Boolean = false)

    var phase by mutableStateOf<Phase>(Phase.Idle); private set
    val patches = mutableStateListOf<PatchProgress>()
    var index by mutableStateOf(-1); private set
    /** Recent brightness readings, for the little live graph. */
    val recent = mutableStateListOf<Double>()
    var latestY by mutableStateOf<Double?>(null); private set

    private val scope = uiScope()
    private var job: Job? = null

    val running: Boolean get() = phase == Phase.Waiting || phase == Phase.Measuring

    /** Measures a video. Returns null if cancelled or failed (the phase says why). */
    suspend fun run(meter: Meter, sequence: PatternSequence, checkOrder: Boolean = false): Measurements? {
        cancel()
        phase = Phase.Waiting
        index = -1
        recent.clear()
        latestY = null
        patches.clear()
        patches.addAll(sequence.patches.map { PatchProgress(it.id, it.name, it.kind) })
        val work = scope.async {
            try {
                val meas = Sweep.sweep(meter, sequence) { e -> handle(e) }
                if (checkOrder && Sweep.outOfOrder(meas, sequence.hdr)) {
                    phase = Phase.Failed(MeasureError.OutOfOrder.message ?: "")
                    null
                } else {
                    phase = Phase.Done
                    meas
                }
            } catch (e: CancellationException) {
                phase = Phase.Cancelled
                null
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: e.toString())
                null
            }
        }
        job = work
        return try {
            work.await()
        } catch (e: CancellationException) {
            phase = Phase.Cancelled
            null
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun reset() {
        cancel()
        phase = Phase.Idle
    }

    private fun handle(e: SweepEvent) {
        when (e) {
            SweepEvent.Waiting -> phase = Phase.Waiting
            is SweepEvent.Reading -> {
                latestY = e.y
                e.y?.let {
                    recent.add(it)
                    while (recent.size > 40) recent.removeAt(0)
                }
            }
            SweepEvent.Synced -> phase = Phase.Measuring
            is SweepEvent.PatchStart -> index = e.index
            is SweepEvent.PatchDone -> if (e.index < patches.size) {
                patches[e.index] = patches[e.index].copy(done = true, Y = e.xyz?.y)
            }
        }
    }
}

/** Reads one pattern continuously for the live guided steps, and says how far off it is. */
class LiveSession {
    var reading by mutableStateOf<LiveReading?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var patch by mutableStateOf<Patch?>(null); private set

    private val readings = ArrayList<Vec3>()
    private var since = 0.0
    private var hdr = false
    private var whiteY: Double? = null
    private var basis: List<Vec3>? = null
    private var meter: Meter? = null
    private val scope = uiScope()
    private var loop: Job? = null

    /** Starts (or switches to) reading a pattern. */
    fun show(p: Patch, hdr: Boolean, whiteY: Double?, basis: List<Vec3>?, meter: Meter) {
        patch = p
        this.hdr = hdr
        this.whiteY = whiteY
        this.basis = basis
        this.meter = meter
        readings.clear()
        reading = null
        error = null
        since = MeterClock.now()
        if (loop == null) loop = scope.launch { run() }
    }

    /** Use after changing a setting, to see its effect sooner. */
    fun restartAverage() {
        readings.clear()
        since = MeterClock.now()
    }

    fun stop() {
        loop?.cancel()
        loop = null
        patch = null
        reading = null
    }

    private suspend fun run() {
        while (scope.isActive) {
            val p = patch
            val m = meter
            if (p == null || m == null) {
                delay(200)
                continue
            }
            val started = MeterClock.now()
            try {
                val r = m.read()
                error = null
                // ignore readings that began before this pattern was asked for
                val xyz = r.xyz
                if (xyz != null && patch?.id == p.id && started >= since + 0.6) {
                    readings.add(xyz)
                    while (readings.size > 14) readings.removeAt(0)
                    reading = Guided.evaluate(p, readings.toList(), hdr, whiteY, basis)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.toString()
                delay(1000)
            }
        }
    }
}

/** The free-running live meter (no pattern needed). */
class FreeMeter {
    var analysis by mutableStateOf<LiveAnalysis?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    private val scope = uiScope()
    private var loop: Job? = null

    fun start(meter: Meter, hdr: Boolean) {
        stop()
        loop = scope.launch {
            while (isActive) {
                try {
                    val r = meter.read()
                    r.xyz?.let { analysis = LiveAnalysis.of(it, hdr) }
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = e.message ?: e.toString()
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }
}
