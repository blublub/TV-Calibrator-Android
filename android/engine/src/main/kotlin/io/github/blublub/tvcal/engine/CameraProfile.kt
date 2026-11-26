// Port of CalibrationKit/Sources/CalibrationKit/CameraProfile.swift (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin.
package io.github.blublub.tvcal.engine

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

// Camera profiles: what turns a phone model's raw sensor numbers into real colour (XYZ). A profile only
// holds for the camera settings it was trained with (or, on Android without a trained profile, worked
// out from the camera's own colour metadata).

/** The locked camera settings a profile was trained with. */
@Serializable
data class CameraSettings(
    val iso: Double? = null,
    /** RAW format, e.g. "4032x3024 grb4". */
    val format: String? = null,
    /** e.g. "Back Camera". */
    val lens: String? = null,
    /** f-number. Only on lenses with a variable aperture; null on fixed-aperture lenses. */
    val aperture: Double? = null,
) {
    /** Which settings differ from what the profile expects. Empty if they match. */
    fun mismatches(got: CameraSettings): List<String> {
        val diffs = ArrayList<String>()
        if (iso != null && got.iso != null && abs(iso - got.iso) > 0.5) diffs.add("ISO: profile ${fixed(iso, 0)}, now ${fixed(got.iso, 0)}")
        if (format != null && got.format != null && format != got.format) diffs.add("RAW format: profile $format, now ${got.format}")
        if (lens != null && got.lens != null && lens != got.lens) diffs.add("lens: profile $lens, now ${got.lens}")
        if (aperture != null && got.aperture != null && abs(aperture - got.aperture) > 0.05) {
            diffs.add("aperture: profile f/${fixed(aperture, 2)}, now f/${fixed(got.aperture, 2)}")
        }
        return diffs
    }
}

/** How well a profile matched the reference colorimeter in training. */
@Serializable
data class ProfileStats(
    /** Average dE against the colorimeter on the training TVs. */
    val deAvg: Double? = null,
    /** Estimated average dE on a TV it has never seen (leave-one-TV-out). */
    val unseen: Double? = null,
    /** TVs it was trained on. */
    val tvs: List<String> = emptyList(),
)

private fun isMat3(m: Mat3?): Boolean = m != null && m.size == 3 && m.all { it.size == 3 }

/**
 * The conversion for one type of screen (panel technology, e.g. "woled", "qdlcd", "rgbled"). Screens make
 * white from different mixes of light, and a camera isn't a perfect colorimeter, so one conversion can't
 * serve them all.
 */
@Serializable
data class PanelMatrix(
    /** Camera RGB -> XYZ for this type of screen. */
    val matrix: Mat3,
    /**
     * Camera RGB -> XYZ for greys and near-greys, when they need their own. Blended with `matrix`
     * between 0.02 and 0.04 from D65 in x, y.
     */
    val neutral: Mat3? = null,
    /** Average dE against the colorimeter on this type's training TVs. */
    val deAvg: Double? = null,
    /** True when worked out from another phone model trained on this type, not measured with this one. */
    val derived: Boolean? = null,
)

/** One phone model's profile. */
@Serializable
data class CameraProfile(
    val format: Int = 1,
    /** Hardware model identifier, e.g. "iPhone18,1". */
    val model: String,
    /** Marketing name, e.g. "iPhone 18 Pro". */
    val name: String,
    /** Increases each time the profile is retrained. */
    val version: Int,
    /** Camera RGB (dark-corrected sensor signal per second) -> XYZ. */
    val matrix: Mat3,
    val camera: CameraSettings,
    val stats: ProfileStats,
    val trained: String? = null,
    /** One conversion per type of screen this model knows; `matrix` is the general one for any other. */
    val panels: Map<String, PanelMatrix>? = null,
) {
    /** The conversion for a type of screen, when this profile has one. */
    fun panel(key: String?): PanelMatrix? {
        if (key == null) return null
        val p = panels?.get(key) ?: return null
        return if (isMat3(p.matrix)) p else null
    }

    /** Camera RGB -> XYZ on a given type of screen (the general conversion when it isn't known). */
    fun xyz(cameraRGB: Vec3, panel: String?): Vec3 {
        val p = panel(panel) ?: return ColourMath.matvec(matrix, cameraRGB)
        val colour = ColourMath.matvec(p.matrix, cameraRGB)
        val n = p.neutral
        if (!isMat3(n)) return colour
        val s = colour.x + colour.y + colour.z
        if (!(colour.y > 0 && s > 0)) return colour
        val dx = colour.x / s - 0.3127
        val dy = colour.y / s - 0.3290
        val d = sqrt(dx * dx + dy * dy)
        val w = minOf(1.0, maxOf(0.0, (d - 0.02) / 0.02))          // 0 near grey (neutral), 1 for colours
        val neutral = ColourMath.matvec(n!!, cameraRGB)
        return neutral * (1 - w) + colour * w
    }

    val isValid: Boolean get() = isMat3(matrix) && matrix.all { row -> row.all { it.isFinite() } }

    /** Camera RGB -> XYZ. */
    fun xyz(cameraRGB: Vec3): Vec3 = ColourMath.matvec(matrix, cameraRGB)

    /** "Excellent" ... for the unseen-TV estimate (or the training average). */
    val accuracyWord: String
        get() {
            val de = stats.unseen ?: stats.deAvg ?: return "Not rated"
            return if (de < 1) "Excellent" else if (de < 2) "Very good" else if (de < 3) "Good" else "Approximate"
        }
}

/** The profile server's index: which phone models are supported. */
@Serializable
data class ProfileCatalog(
    val format: Int,
    val updated: String,
    /** The oldest app catalog format that can read this one. */
    val minAppFormat: Int? = null,
    val profiles: List<Entry>,
) {
    @Serializable
    data class Entry(
        val model: String,
        val name: String,
        val version: Int,
        /** Path of the profile file, relative to the catalog. */
        val file: String,
        /** SHA-256 of the profile file, hex. */
        val sha256: String,
        val deAvg: Double? = null,
        val unseen: Double? = null,
        val tvs: Int? = null,
    )

    fun entry(model: String): Entry? = profiles.firstOrNull { it.model == model }
}

/** The profiles built into the app. `standard` names the profile used on models that have none of their own. */
@Serializable
data class BuiltInProfiles(
    val format: Int = 1,
    /** Model identifier of the standard (fallback) profile. */
    val standard: String? = null,
    val profiles: List<CameraProfile> = emptyList(),
) {
    /** This model's own profile, if one is built in. */
    fun profile(model: String): CameraProfile? =
        profiles.filter { it.model == model && it.isValid }.maxByOrNull { it.version }

    /** The profile to use when a model has none of its own. */
    val standardProfile: CameraProfile? get() = standard?.let { profile(it) }
}
