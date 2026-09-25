package com.tonyisup.poseguidesnap.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameVisualStatisticsTest {
    @Test
    fun blackAndWhiteSamplesExposeMidpointMeanAndFullRange() {
        val statistics = FrameVisualStatistics.fromArgbSamples(
            intArrayOf(0xff000000.toInt(), 0xffffffff.toInt()),
        )

        assertEquals(0.5, statistics.meanLuminance, 0.0)
        assertEquals(1.0, statistics.luminanceRange, 0.0)
    }

    @Test
    fun rgbLuminanceIgnoresAlphaAndUsesAllColorChannels() {
        val statistics = FrameVisualStatistics.fromArgbSamples(
            intArrayOf(0x00ff0000, 0x0000ff00, 0x000000ff),
        )

        assertEquals(1.0 / 3.0, statistics.meanLuminance, 0.000_001)
        assertEquals(0.643, statistics.luminanceRange, 0.000_001)
    }

    @Test
    fun invalidEvidenceFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            FrameVisualStatistics.fromArgbSamples(intArrayOf())
        }
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, -0.1, 1.1).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                FrameVisualStatistics(meanLuminance = invalid, luminanceRange = 0.0)
            }
            assertThrows(IllegalArgumentException::class.java) {
                FrameVisualStatistics(meanLuminance = 0.0, luminanceRange = invalid)
            }
        }
    }
}
