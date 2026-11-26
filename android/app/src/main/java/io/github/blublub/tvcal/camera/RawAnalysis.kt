// Port of the RawAnalysis part of "TV Calibrator/Camera/CameraMeter.swift" (TV Calibrator by Slidebean,
// https://github.com/slidebean/tv-calibrator, CC BY-NC 4.0). Changed: translated to Kotlin for Android
// RAW_SENSOR buffers (16-bit little-endian Bayer); the clipping thresholds scale with the sensor's own
// white level instead of assuming 14 bits.
package io.github.blublub.tvcal.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One RAW photo, read in the centre box. */
data class RawStats(
    /** Average R, G, B photosite value in the centre box (raw counts). */
    val mean: DoubleArray,
    /** Photosites at the sensor's ceiling. */
    val clipped: Boolean,
    /** Brightness spread across the box's four quarters (0 = even). */
    val uneven: Double,
    /** The brightest photosite in the box. */
    val maxRaw: Double,
    /** Many photosites pile up at that brightest value: the sensor's real ceiling. */
    val plateau: Boolean,
)

object RawAnalysis {
    /** The iPhone code's thresholds were written for 14-bit data; they are scaled by white / this. */
    private const val IPHONE_FULL_SCALE = 16383.0

    /**
     * Colour of each photosite in a 2x2 cell, indexed (y % 2) * 2 + (x % 2): 0 red, 1 green, 2 blue,
     * for Camera2's SENSOR_INFO_COLOR_FILTER_ARRANGEMENT (0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR).
     */
    fun cell(cfa: Int): IntArray? = when (cfa) {
        0 -> intArrayOf(0, 1, 1, 2)
        1 -> intArrayOf(1, 0, 2, 1)
        2 -> intArrayOf(1, 2, 0, 1)
        3 -> intArrayOf(2, 1, 1, 0)
        else -> null
    }

    /**
     * Averages the red, green and blue photosites in a box covering the centre 10% of the frame.
     * `white`: the level at which this sensor's photosites saturate. `floor`: the level a covered lens
     * reads (0 if not known yet); on a black patch every photosite sits there, which must not be taken
     * for a ceiling.
     */
    fun measure(
        buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int,
        cfa: Int, white: Double, floor: Double = 0.0,
    ): RawStats? {
        val cell = cell(cfa) ?: return null
        val buf = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // Centre box: 10% of the width and height, aligned to whole 2x2 cells. (Changed from the iPhone
        // code: the box is a multiple of 4, so each quarter holds whole cells too. With an odd number of
        // rows per half, the quarters held different shares of red and blue rows, and a coloured patch
        // looked uneven when it wasn't.)
        val boxW = (width / 10) and 3.inv()
        val boxH = (height / 10) and 3.inv()
        val x0 = (width / 2 - boxW / 2) and 1.inv()
        val y0 = (height / 2 - boxH / 2) and 1.inv()
        if (boxW <= 0 || boxH <= 0) return null

        val sum = DoubleArray(3)
        val count = DoubleArray(3)
        val quarter = DoubleArray(4)
        val quarterN = DoubleArray(4)
        var clipped = false
        val clipLevel = (white * 0.98).toInt()
        var maxRaw = 0
        val histogram = IntArray(65536)

        for (y in y0 until y0 + boxH) {
            val row = y * rowStride
            val qy = if (y < y0 + boxH / 2) 0 else 2
            val parity = (y and 1) * 2
            for (x in x0 until x0 + boxW) {
                val raw = buf.getShort(row + x * pixelStride).toInt() and 0xFFFF
                if (raw >= clipLevel) clipped = true
                if (raw > maxRaw) maxRaw = raw
                histogram[raw]++
                val v = raw.toDouble()
                val c = cell[parity + (x and 1)]
                sum[c] += v
                count[c] += 1.0
                val q = qy + if (x < x0 + boxW / 2) 0 else 1
                quarter[q] += v
                quarterN[q] += 1.0
            }
        }
        val mean = DoubleArray(3) { if (count[it] > 0) sum[it] / count[it] else 0.0 }
        val qMeans = DoubleArray(4) { if (quarterN[it] > 0) quarter[it] / quarterN[it] else 0.0 }
        val qAvg = qMeans.sum() / 4
        val uneven = if (qAvg > 0) (qMeans.max() - qMeans.min()) / qAvg else 0.0
        // A real patch's brightest photosites are a thin tail of the noise; at the sensor's ceiling they
        // pile up. More than 1% of the box within a hair of the top value means it's clipped.
        val top = maxRaw
        val band = maxOf(2, top * 3 / 1000)
        var nearTop = 0
        for (v in maxOf(0, top - band)..top) nearTop += histogram[v]
        val total = boxW * boxH
        val minPlateau = maxOf(2000.0 * white / IPHONE_FULL_SCALE, floor * 2)
        val plateau = top > minPlateau && total > 0 && nearTop.toDouble() / total > 0.01
        if (plateau) clipped = true
        return RawStats(mean, clipped, uneven, maxRaw.toDouble(), plateau)
    }
}
