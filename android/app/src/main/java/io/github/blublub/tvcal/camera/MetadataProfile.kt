package io.github.blublub.tvcal.camera

import io.github.blublub.tvcal.engine.CameraProfile
import io.github.blublub.tvcal.engine.CameraSettings
import io.github.blublub.tvcal.engine.ColourMath
import io.github.blublub.tvcal.engine.Mat3
import io.github.blublub.tvcal.engine.ProfileStats
import io.github.blublub.tvcal.engine.Vec3

/**
 * The colour data a phone camera reports about itself (Camera2 / DNG): enough to build an approximate
 * camera profile when no profile trained against a colorimeter exists for this phone.
 *
 * Matrices are row-major. `colorTransform` maps XYZ to the reference camera space under its illuminant
 * (DNG ColorMatrix), `calibration` maps the reference camera space to this unit's (DNG CameraCalibration).
 */
data class CameraColorInfo(
    val colorTransform1: Mat3?,
    val colorTransform2: Mat3?,
    val calibration1: Mat3?,
    val calibration2: Mat3?,
    /** Camera2 / EXIF light source codes, e.g. 21 = D65, 17 = Standard light A. */
    val illuminant1: Int?,
    val illuminant2: Int?,
    /** Sensor black level (the mean of the 2x2 pattern) and white level, raw counts. */
    val black: Double,
    val white: Double,
    /** f-number of the lens and the locked ISO, for the brightness estimate. */
    val aperture: Double,
    val iso: Double,
)

/**
 * Builds a camera profile from the camera's own colour matrices.
 *
 * The phone maker tuned these matrices for photos under daylight and tungsten, not for the narrow-band
 * light of TV panels, and the absolute brightness comes from the exposure equation, not from a
 * colorimeter. So the profile is approximate: the direction of white-balance and gamma errors is right,
 * their exact size isn't. It is labelled as such everywhere it's used.
 */
object MetadataProfile {
    const val D65 = 21

    /**
     * Luminance (cd/m²) that just saturates the sensor in one second at f/1 and ISO 1, from the
     * saturation-based ISO definition (ISO 12232: H_sat = 78 / S lux·s) and the usual lens factor
     * H = 0.65 · L · t / N². Phones calibrate ISO loosely, so this is good to perhaps a factor of 1.5.
     */
    const val SATURATION_CONSTANT = 78.0 / 0.65

    fun matmul(a: Mat3, b: Mat3): Mat3 = List(3) { r -> List(3) { c -> (0 until 3).sumOf { a[r][it] * b[it][c] } } }

    private val identity: Mat3 = List(3) { r -> List(3) { c -> if (r == c) 1.0 else 0.0 } }

    /** XYZ -> this camera's native RGB under D65 (the DNG "XYZ to camera" chain), or null without data. */
    fun xyzToCamera(info: CameraColorInfo): Mat3? {
        val (cm, cc) = when {
            info.illuminant1 == D65 && info.colorTransform1 != null -> info.colorTransform1 to info.calibration1
            info.illuminant2 == D65 && info.colorTransform2 != null -> info.colorTransform2 to info.calibration2
            // No D65 slot: the first one is usually the daylight one.
            info.colorTransform1 != null -> info.colorTransform1 to info.calibration1
            else -> return null
        }
        return matmul(cc ?: identity, cm)
    }

    /**
     * Camera RGB (dark-corrected raw counts per second, as the camera meter reports them) -> XYZ in cd/m²,
     * at the given locked settings. Null if the camera reports no usable colour data.
     */
    fun matrix(info: CameraColorInfo): Mat3? {
        val toCam = xyzToCamera(info) ?: return null
        val inv = ColourMath.inverse(toCam)
        if (inv.any { row -> row.any { !it.isFinite() } }) return null
        // DNG matrices are normalised so that the illuminant's white at Y = 1 gives the camera neutral with
        // its largest channel at 1: the inverse turns "fraction of the sensor's range" into relative XYZ.
        val range = info.white - info.black
        if (range <= 0 || info.iso <= 0 || info.aperture <= 0) return null
        // The normalisation is only approximate in practice: make it exact, so a D65 grey whose brightest
        // channel fills the sensor's range in one second reads as the saturation luminance.
        val neutral = ColourMath.matvec(toCam, ColourMath.xyYToXYZ(ColourMath.d65, 1.0))
        val peak = maxOf(neutral.x, neutral.y, neutral.z)
        if (peak <= 0) return null
        val nitsAtFullScalePerSecond = SATURATION_CONSTANT * info.aperture * info.aperture / info.iso
        val scale = nitsAtFullScalePerSecond * peak / range
        val m = inv.map { row -> row.map { it * scale } }
        // Sanity: a neutral grey must come out with positive brightness.
        if (ColourMath.matvec(m, neutral).y <= 0) return null
        return m
    }

    /** The approximate profile for this phone, or null if its camera reports no colour data. */
    fun profile(info: CameraColorInfo, model: String, name: String, settings: CameraSettings): CameraProfile? {
        val m = matrix(info) ?: return null
        return CameraProfile(
            model = model, name = name, version = 0, matrix = m, camera = settings,
            stats = ProfileStats(), trained = "camera metadata (approximate)",
        )
    }

    /** The camera neutral (relative R, G, B) the matrices predict for D65 white; handy for checks. */
    fun d65Neutral(info: CameraColorInfo): Vec3? {
        val toCam = xyzToCamera(info) ?: return null
        val n = ColourMath.matvec(toCam, ColourMath.xyYToXYZ(ColourMath.d65, 1.0))
        return n / maxOf(n.x, n.y, n.z)
    }
}
