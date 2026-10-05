package jp.co.updates.swingcheck.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureMathTest {
    @Test
    fun bitrateScalesWithFpsAndIsClamped() {
        val at30 = Bitrate.forVideo(1920, 1080, 30)
        val at240 = Bitrate.forVideo(1920, 1080, 240)
        assertEquals(8.0, at240.toDouble() / at30, 0.01)
        assertEquals(Bitrate.MIN_BPS, Bitrate.forVideo(640, 360, 30))
        assertEquals(Bitrate.MAX_BPS, Bitrate.forVideo(3840, 2160, 240))
        assertTrue(at240 in 30_000_000..40_000_000)
    }

    @Test
    fun rotationHintForPortraitBackCamera() {
        assertEquals(90, CaptureOrientation.rotationHint(90, 0))
        assertEquals(0, CaptureOrientation.rotationHint(90, 90))
        assertEquals(270, CaptureOrientation.rotationHint(90, 180))
        assertEquals(180, CaptureOrientation.rotationHint(270, 90))
    }

    @Test
    fun displaySizeSwapsForQuarterTurns() {
        val s = CaptureSize(1920, 1080)
        assertEquals(CaptureSize(1080, 1920), CaptureOrientation.displaySize(s, 90))
        assertEquals(CaptureSize(1080, 1920), CaptureOrientation.displaySize(s, 270))
        assertEquals(s, CaptureOrientation.displaySize(s, 0))
        assertEquals(s, CaptureOrientation.displaySize(s, 180))
    }

    @Test
    fun decimatorSamplesAboutTargetRateFromHighRateFrames() {
        val d = FrameDecimator(15, 1_000_000_000L)
        var taken = 0
        // 240fps で 10 秒
        for (i in 0 until 2400) if (d.shouldSample(i * 1_000_000_000L / 240)) taken++
        assertTrue(taken in 148..152, "taken=$taken")
    }

    @Test
    fun decimatorDoesNotDriftWithJitter() {
        val d = FrameDecimator(15, 1000)
        var taken = 0
        // 30fps、ばらつきあり（33 / 34 ms 交互）で 10 秒
        var t = 0L
        var i = 0
        while (t < 10_000) {
            if (d.shouldSample(t)) taken++
            t += if (i++ % 2 == 0) 33 else 34
        }
        assertTrue(taken in 148..152, "taken=$taken")
    }

    @Test
    fun decimatorRestartsAfterLongGap() {
        val d = FrameDecimator(15, 1000)
        assertTrue(d.shouldSample(0))
        assertFalse(d.shouldSample(30))
        assertTrue(d.shouldSample(5000))
        assertFalse(d.shouldSample(5010))
        assertTrue(d.shouldSample(5070))
    }

    @Test
    fun rateMeterCountsPerSecond() {
        val m = RateMeter(2000)
        for (i in 0..30) m.tick(i * 100L) // 10 回/秒
        assertEquals(10f, m.perSecond(3000), 0.1f)
        assertEquals(0f, m.perSecond(100_000))
    }

    @Test
    fun letterboxFitsPortraitImageIntoWiderSurface() {
        // 1080x1920 の映像を 2000x1000 の画面に：高さに合わせる
        val r = Letterbox.fit(2000, 1000, 1080, 1920)
        assertEquals(ViewportRect(718, 0, 563, 1000), r)
        // 縦長の画面に横長の映像：幅に合わせる
        val r2 = Letterbox.fit(1000, 2000, 1920, 1080)
        assertEquals(ViewportRect(0, 718, 1000, 563), r2)
        // ぴったり
        assertEquals(ViewportRect(0, 0, 1080, 1920), Letterbox.fit(1080, 1920, 1080, 1920))
    }
}
