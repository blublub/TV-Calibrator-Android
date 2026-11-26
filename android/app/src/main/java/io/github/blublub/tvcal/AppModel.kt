// Port of "TV Calibrator/App/AppModel.swift" (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin; state is
// Compose snapshot state, so screens recompose when it changes (like @Published).
package io.github.blublub.tvcal

import io.github.blublub.tvcal.ui.L10n

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.blublub.tvcal.camera.CameraMeter
import io.github.blublub.tvcal.camera.CameraXYZMeter
import io.github.blublub.tvcal.engine.Meter
import io.github.blublub.tvcal.engine.PatternSequence
import io.github.blublub.tvcal.engine.ShownPatch
import io.github.blublub.tvcal.engine.SimLiveMeter
import io.github.blublub.tvcal.engine.SimMeter
import io.github.blublub.tvcal.engine.SimTV
import io.github.blublub.tvcal.profiles.LinksService
import io.github.blublub.tvcal.profiles.ProfileService
import io.github.blublub.tvcal.storage.Brand
import io.github.blublub.tvcal.storage.ColourMenu
import io.github.blublub.tvcal.storage.Multipoint
import io.github.blublub.tvcal.storage.TVRecord
import io.github.blublub.tvcal.storage.TVStore
import java.util.UUID

/** Everything the screens share: the saved TVs, the camera, its profile and the pattern links. */
class AppModel(context: Context) {
    val appContext: Context = context.applicationContext
    val store = TVStore(appContext)
    val camera = CameraMeter(appContext)
    val profiles = ProfileService()
    val links = LinksService(appContext)
    private val prefs = appContext.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** The saved TVs, oldest first. */
    val tvs = mutableStateListOf<TVRecord>().apply { addAll(store.loadTVs()) }
    private val sims = HashMap<String, SimTV>()

    /** Set once the user has seen "How it works". */
    var welcomeSeen by mutableStateOf(prefs.getBoolean("welcome.seen", false))
        private set

    fun markWelcomeSeen() {
        welcomeSeen = true
        prefs.edit().putBoolean("welcome.seen", true).apply()
    }

    init {
        profiles.refresh(camera.engine)
    }

    fun tv(id: UUID): TVRecord? = tvs.firstOrNull { it.id == id }

    // TVs

    fun add(tv: TVRecord) {
        store.save(tv)
        tvs.add(tv)
    }

    fun update(tv: TVRecord) {
        store.save(tv)
        val i = tvs.indexOfFirst { it.id == tv.id }
        if (i >= 0) tvs[i] = tv
    }

    fun delete(tv: TVRecord) {
        // TODO: cancel the recheck reminder once notifications are ported.
        store.delete(tv)
        tvs.removeAll { it.id == tv.id }
    }

    fun addDemoTV(): TVRecord {
        val n = tvs.count { it.isDemo }
        val tv = TVRecord(
            name = L10n.tr("Demo Screen", "Demo-Fernseher").let { if (n == 0) it else "$it ${n + 1}" }, brand = Brand.SAMSUNG,
            multipoint = Multipoint.TWENTY, colourMenu = ColourMenu.RGB_SLIDERS, isDemo = true,
        )
        add(tv)
        return tv
    }

    // demo TVs

    fun simTV(tv: TVRecord, hdr: Boolean): SimTV {
        val key = "${tv.id}-$hdr"
        sims[key]?.let { return it }
        val seed = (tv.id.mostSignificantBits ushr 40) and 0xFFFFFF
        val saved = store.simState(tv, hdr)
        val s = SimTV(hdr, saved, seed)
        if (saved == null) {
            s.s.p20Shift = 0.10          // demo: the multipoint controls act 10% above their label
            store.saveSim(s.s, tv, hdr)
        }
        sims[key] = s
        return s
    }

    /** Demo: "press the remote". */
    fun demoApply(tv: TVRecord, hdr: Boolean, changes: Map<String, Int>) {
        val s = simTV(tv, hdr)
        s.apply(changes)
        store.saveSim(s.s, tv, hdr)
    }

    // meters

    /** Why the camera can't measure yet, or null when it's ready. */
    val cameraBlocker: String?
        get() {
            if (profiles.profile == null) return profiles.summary
            if (!camera.running.value) return L10n.tr("Start the camera (Camera setup).", "Starte die Kamera (Kamera einrichten).")
            if (camera.dark.value == null) return L10n.tr("Measure the camera's darkness once (Camera setup).", "Miss einmal die Dunkelheit der Kamera (Kamera einrichten).")
            return null
        }

    /** Starts the camera (the camera permission has been granted). */
    suspend fun startCamera() {
        camera.start()
        profiles.refresh(camera.engine)
    }

    /** A meter that follows a pattern video. */
    fun videoMeter(tv: TVRecord, hdr: Boolean, sequence: PatternSequence): Meter? {
        if (tv.isDemo) {
            return SimMeter(simTV(tv, hdr), sequence, playAt = 2.0, realTime = 0.1, seed = System.currentTimeMillis() % 100_000)
        }
        val p = profiles.profile
        if (cameraBlocker != null || p == null) return null
        return CameraXYZMeter(camera, p, checkSettings = !profiles.isStandard, panel = tv.panel.profileKey)
    }

    /** A meter for the live guided steps. In the demo it reads whatever pattern is being asked for. */
    fun liveMeter(tv: TVRecord, hdr: Boolean, shown: ShownPatch): Meter? {
        if (tv.isDemo) return SimLiveMeter(simTV(tv, hdr), shown)
        val p = profiles.profile
        if (cameraBlocker != null || p == null) return null
        return CameraXYZMeter(camera, p, checkSettings = !profiles.isStandard, panel = tv.panel.profileKey)
    }

    /** A meter for the free-running live meter (no pattern): the camera only. */
    fun freeMeter(): Meter? {
        val p = profiles.profile
        if (cameraBlocker != null || p == null) return null
        return CameraXYZMeter(camera, p, checkSettings = false)
    }

    /** The short success sound at the end of a calibration (success.wav from the original app). */
    fun chime() {
        if (!prefs.getBoolean("feedback.sounds", true)) return
        runCatching {
            val fd = appContext.assets.openFd("success.wav")
            MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
            fd.close()
        }
    }
}
