package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.settings.FpsMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CaptureConfigSelectorTest {
    private val fhd = CaptureSize(1920, 1080)
    private val hd = CaptureSize(1280, 720)
    private val uhd = CaptureSize(3840, 2160)

    private val normalSizes = listOf(NormalSize(uhd, 30), NormalSize(fhd, 60), NormalSize(hd, 60))
    private val normalRanges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(30, 60), FpsRange(60, 60))

    private fun caps(highSpeed: Map<CaptureSize, List<FpsRange>> = emptyMap()) =
        CameraCaps("0", highSpeed, normalSizes, normalRanges)

    private val highSpeed = mapOf(
        fhd to listOf(FpsRange(30, 120), FpsRange(120, 120), FpsRange(30, 240), FpsRange(240, 240)),
        hd to listOf(FpsRange(30, 120), FpsRange(120, 120), FpsRange(30, 240), FpsRange(240, 240), FpsRange(30, 480)),
    )

    @Test
    fun autoUsesMaxHighSpeedFpsCappedAt240() {
        val c = CaptureConfigSelector.select(caps(highSpeed), FpsMode.AUTO)!!
        assertEquals(SessionType.HIGH_SPEED, c.sessionType)
        assertEquals(240, c.fps)
        assertEquals(fhd, c.size) // 480fps は上限超え。240fps は両方にあるので 1080p を優先
        assertEquals(FpsRange(240, 240), c.fpsRange)
    }

    @Test
    fun autoFallsBackTo720pWhen1080pLacksTheFps() {
        val hs = mapOf(fhd to listOf(FpsRange(120, 120)), hd to listOf(FpsRange(240, 240)))
        val c = CaptureConfigSelector.select(caps(hs), FpsMode.AUTO)!!
        assertEquals(240, c.fps)
        assertEquals(hd, c.size)
    }

    @Test
    fun autoWithoutHighSpeedUsesNormalMax60At1080p() {
        val c = CaptureConfigSelector.select(caps(), FpsMode.AUTO)!!
        assertEquals(SessionType.NORMAL, c.sessionType)
        assertEquals(60, c.fps)
        assertEquals(fhd, c.size)
        assertEquals(FpsRange(60, 60), c.fpsRange)
    }

    @Test
    fun autoWithoutHighSpeedAndOnly30() {
        val c = CameraCaps("0", emptyMap(), normalSizes, listOf(FpsRange(15, 30), FpsRange(30, 30)))
        val sel = CaptureConfigSelector.select(c, FpsMode.AUTO)!!
        assertEquals(30, sel.fps)
        assertEquals(FpsRange(30, 30), sel.fpsRange)
    }

    @Test
    fun normalSizeLimitedByMinFrameDuration() {
        // 4K は 30fps まで。1080p は 60fps まで。
        val c = CaptureConfigSelector.select(CameraCaps("0", emptyMap(), listOf(NormalSize(uhd, 30)), normalRanges), FpsMode.AUTO)!!
        assertEquals(30, c.fps)
        assertEquals(uhd, c.size)
    }

    @Test
    fun explicitFpsUsedWhenSupported() {
        val c = CaptureConfigSelector.select(caps(highSpeed), FpsMode.FPS_120)!!
        assertEquals(SessionType.HIGH_SPEED, c.sessionType)
        assertEquals(120, c.fps)
        assertEquals(FpsRange(120, 120), c.fpsRange)
    }

    @Test
    fun explicit60UsesNormalSessionEvenIfHighSpeedListsIt() {
        val hs = mapOf(fhd to listOf(FpsRange(60, 60), FpsRange(120, 120)))
        val c = CaptureConfigSelector.select(caps(hs), FpsMode.FPS_60)!!
        assertEquals(SessionType.NORMAL, c.sessionType)
        assertEquals(60, c.fps)
    }

    @Test
    fun unsupportedFpsDropsToNearestLower() {
        // 240 を指定したが、高速撮影は 120 まで
        val hs = mapOf(fhd to listOf(FpsRange(120, 120)))
        val c = CaptureConfigSelector.select(caps(hs), FpsMode.FPS_240)!!
        assertEquals(120, c.fps)
        // 120 を指定したが高速撮影に非対応：通常の最大 60
        val n = CaptureConfigSelector.select(caps(), FpsMode.FPS_120)!!
        assertEquals(60, n.fps)
        assertEquals(SessionType.NORMAL, n.sessionType)
    }

    @Test
    fun explicitBetweenValuesPicksLower() {
        val c = CaptureConfigSelector.select(caps(highSpeed), FpsMode.FPS_60)!!
        assertEquals(60, c.fps) // 通常の 60
        val ranges = listOf(FpsRange(30, 30), FpsRange(24, 24))
        val d = CaptureConfigSelector.select(CameraCaps("0", emptyMap(), normalSizes, ranges), FpsMode.FPS_60)!!
        assertEquals(30, d.fps)
    }

    @Test
    fun encoderLimitsAreRespected() {
        // エンコーダーが 1080p で 120fps 以上に対応しない
        val supports = { s: CaptureSize, fps: Int -> !(s == fhd && fps > 60) }
        val c = CaptureConfigSelector.select(caps(highSpeed), FpsMode.AUTO, supports)!!
        assertEquals(240, c.fps)
        assertEquals(hd, c.size)
        val noHd = { s: CaptureSize, fps: Int -> s == fhd && fps <= 60 }
        val d = CaptureConfigSelector.select(caps(highSpeed), FpsMode.AUTO, noHd)!!
        assertEquals(60, d.fps)
        assertEquals(fhd, d.size)
    }

    @Test
    fun noSizeAmong1080pOr720pPicksLargestWithin1080p() {
        val sizes = listOf(NormalSize(CaptureSize(1600, 900), 60), NormalSize(CaptureSize(640, 480), 60))
        val c = CaptureConfigSelector.select(CameraCaps("0", emptyMap(), sizes, normalRanges), FpsMode.AUTO)!!
        assertEquals(CaptureSize(1600, 900), c.size)
    }

    @Test
    fun noCandidatesReturnsNull() {
        assertNull(CaptureConfigSelector.select(CameraCaps("0", emptyMap(), emptyList(), emptyList()), FpsMode.AUTO))
        assertNull(CaptureConfigSelector.select(caps(), FpsMode.AUTO) { _, _ -> false })
    }

    @Test
    fun targetBelowEverythingTakesSmallestAvailable() {
        val c = CaptureConfigSelector.select(
            CameraCaps("0", emptyMap(), normalSizes, listOf(FpsRange(60, 60))), FpsMode.FPS_30,
        )!!
        assertEquals(60, c.fps)
    }

    @Test
    fun bestRangePrefersHighestLower() {
        assertEquals(FpsRange(120, 120), CaptureConfigSelector.bestRange(listOf(FpsRange(30, 120), FpsRange(120, 120)), 120))
        assertEquals(FpsRange(30, 120), CaptureConfigSelector.bestRange(listOf(FpsRange(30, 120)), 120))
        assertNull(CaptureConfigSelector.bestRange(listOf(FpsRange(30, 120)), 60))
    }
}
