package io.github.blublub.tvcal.camera

import io.github.blublub.tvcal.engine.CameraSettings
import io.github.blublub.tvcal.engine.ColourMath
import io.github.blublub.tvcal.engine.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawAnalysisTest {
    /**
     * A synthetic frame: each photosite of a colour reads its value plus Gaussian noise (`sigma` counts),
     * like a real sensor. (A noise-free frame would rightly look like a clipped plateau.)
     */
    private fun frame(
        w: Int, h: Int, r: Int, g: Int, b: Int, cfa: Int = 3, stride: Int = w * 2, sigma: Double = 0.0,
    ): ByteBuffer {
        val cell = RawAnalysis.cell(cfa)!!
        val rnd = java.util.Random(42)
        val buf = ByteBuffer.allocate(stride * h).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) {
            val v = when (cell[(y and 1) * 2 + (x and 1)]) { 0 -> r; 1 -> g; else -> b }
            val n = (v + rnd.nextGaussian() * sigma).toInt().coerceIn(0, 1023)
            buf.putShort(y * stride + x * 2, n.toShort())
        }
        return buf
    }

    @Test
    fun readsEachColourFromItsPhotosites() {
        for (cfa in 0..3) {
            val s = RawAnalysis.measure(frame(400, 300, 300, 500, 200, cfa, sigma = 6.0), 400, 300, 800, 2, cfa, 1023.0)!!
            assertEquals("cfa $cfa R", 300.0, s.mean[0], 1.0)
            assertEquals("cfa $cfa G", 500.0, s.mean[1], 1.0)
            assertEquals("cfa $cfa B", 200.0, s.mean[2], 1.0)
            assertFalse("cfa $cfa clipped", s.clipped)
            assertTrue(s.uneven < 0.01)
        }
    }

    @Test
    fun noiseFreeBrightFrameLooksLikeAPlateau() {
        val s = RawAnalysis.measure(frame(400, 300, 300, 500, 200), 400, 300, 800, 2, 3, 1023.0)!!
        assertTrue(s.plateau)
    }

    @Test
    fun honoursRowPadding() {
        val s = RawAnalysis.measure(frame(400, 300, 100, 200, 300, stride = 1024), 400, 300, 1024, 2, 3, 1023.0)!!
        assertEquals(100.0, s.mean[0], 1e-9)
        assertEquals(300.0, s.mean[2], 1e-9)
    }

    @Test
    fun flagsClippingAtTheSensorCeiling() {
        val s = RawAnalysis.measure(frame(400, 300, 1023, 1023, 1023), 400, 300, 800, 2, 3, 1023.0)!!
        assertTrue(s.clipped)
        assertTrue(s.plateau)
    }

    @Test
    fun darkFrameIsNotAPlateau() {
        val s = RawAnalysis.measure(frame(400, 300, 64, 64, 64), 400, 300, 800, 2, 3, 1023.0, floor = 64.0)!!
        assertFalse(s.clipped)
        assertFalse(s.plateau)
    }

    @Test
    fun unknownLayoutIsRefused() {
        assertNull(RawAnalysis.measure(frame(40, 30, 1, 1, 1), 40, 30, 80, 2, 5, 1023.0))
    }
}

class MetadataProfileTest {
    // The OPPO Find X8 main camera's matrices as its Camera2 metadata reports them (camera id 2).
    private val x8 = CameraColorInfo(
        colorTransform1 = listOf(
            listOf(0.78050, -0.25560, -0.07840), listOf(-0.42330, 1.16570, 0.21690), listOf(-0.10430, 0.22860, 0.53410),
        ),
        colorTransform2 = listOf(
            listOf(1.05570, -0.49200, -0.02680), listOf(-0.44580, 1.37370, 0.32610), listOf(-0.05100, 0.18570, 0.83130),
        ),
        calibration1 = null, calibration2 = null, illuminant1 = 21, illuminant2 = 17,
        black = 64.0, white = 1023.0, aperture = 1.8, iso = 100.0,
    )

    @Test
    fun d65WhiteComesBackAsD65() {
        val m = MetadataProfile.matrix(x8)!!
        val toCam = MetadataProfile.xyzToCamera(x8)!!
        // camera signal for D65 white, as counts per second at some brightness
        val cam = ColourMath.matvec(toCam, ColourMath.xyYToXYZ(ColourMath.d65, 1.0)) * 500.0
        val xy = ColourMath.xyzToXY(ColourMath.matvec(m, cam))
        assertEquals(0.3127, xy.x, 1e-9)
        assertEquals(0.3290, xy.y, 1e-9)
    }

    @Test
    fun brightnessFollowsTheExposureEquation() {
        val m = MetadataProfile.matrix(x8)!!
        val toCam = MetadataProfile.xyzToCamera(x8)!!
        val n = ColourMath.matvec(toCam, ColourMath.xyYToXYZ(ColourMath.d65, 1.0))
        // full scale (959 counts) on the brightest channel within one second
        val cam = n / maxOf(n.x, n.y, n.z) * (1023.0 - 64.0)
        val y = ColourMath.matvec(m, cam).y
        assertEquals(MetadataProfile.SATURATION_CONSTANT * 1.8 * 1.8 / 100, y, 1e-6)
    }

    @Test
    fun x8NeutralIsGreenHeavyLikeEveryBayerSensor() {
        val n = MetadataProfile.d65Neutral(x8)!!
        assertEquals(1.0, n.y, 1e-12)
        assertTrue(n.x in 0.3..0.7)
        assertTrue(n.z in 0.5..0.9)
    }

    @Test
    fun usesTheD65SlotWhereverItIs() {
        val swapped = x8.copy(
            colorTransform1 = x8.colorTransform2, colorTransform2 = x8.colorTransform1, illuminant1 = 17, illuminant2 = 21,
        )
        assertEquals(MetadataProfile.xyzToCamera(x8), MetadataProfile.xyzToCamera(swapped))
    }

    @Test
    fun noColourDataNoProfile() {
        val none = x8.copy(colorTransform1 = null, colorTransform2 = null)
        assertNull(MetadataProfile.profile(none, "m", "n", CameraSettings()))
        val p = MetadataProfile.profile(x8, "OPPO CPH2651", "OPPO Find X8", CameraSettings(iso = 100.0))
        assertNotNull(p)
        assertTrue(p!!.isValid)
        assertEquals("Not rated", p.accuracyWord)
        assertTrue(p.xyz(Vec3(100.0, 200.0, 120.0)).y > 0)
    }
}
