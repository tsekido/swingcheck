package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.settings.FpsMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Zenfone 9（ASUS_AI2202）の背面カメラ構成を再現したテスト。 */
class CapturePlannerTest {
    private val fhd = CaptureSize(1920, 1080)
    private val hd = CaptureSize(1280, 720)
    private val uhd = CaptureSize(3840, 2160)

    private val normalSizes = listOf(NormalSize(uhd, 30), NormalSize(fhd, 60), NormalSize(hd, 60))
    private val normalRanges = listOf(FpsRange(15, 30), FpsRange(30, 30), FpsRange(30, 60), FpsRange(60, 60))
    private val hs120and240 = mapOf(
        fhd to listOf(FpsRange(120, 120), FpsRange(240, 240)),
        hd to listOf(FpsRange(120, 120), FpsRange(240, 240)),
    )

    private fun cam(id: String, focal: Float?, hs: Map<CaptureSize, List<FpsRange>> = emptyMap()) =
        CameraCaps(id, hs, normalSizes, normalRanges, focalLengthMm = focal)

    private val id0 = cam("0", 5.53f)
    private val id2 = cam("2", 2.75f, hs120and240)
    private val id3 = cam("3", 5.53f, hs120and240 + (uhd to listOf(FpsRange(120, 120))))
    private val zenfone = listOf(id0, id2, id3)

    private fun plan(cameras: List<CameraCaps>, mode: FpsMode = FpsMode.AUTO, failed: Set<FailedSetting> = emptySet()) =
        CapturePlanner.plan(cameras, mode, { _, _ -> true }, failed)

    private fun summary(list: List<CaptureConfig>) = list.map { "${it.cameraId}:${it.sessionType}:${it.fps}" }

    @Test
    fun mainCamerasExcludeUltraWide() {
        assertEquals(listOf("0", "3"), CapturePlanner.mainCameras(zenfone).map { it.cameraId })
    }

    @Test
    fun focalToleranceAndUnknownFocal() {
        val near = cam("1", 5.6f) // 約 1.3% 違い
        val far = cam("2", 6.5f)
        val unknown = cam("3", null)
        assertEquals(listOf("0", "1"), CapturePlanner.mainCameras(listOf(id0, near, far, unknown)).map { it.cameraId })
        // 先頭の焦点距離が不明なら、先頭だけ
        assertEquals(listOf("0"), CapturePlanner.mainCameras(listOf(cam("0", null), id3)).map { it.cameraId })
        assertEquals(emptyList<CameraCaps>(), CapturePlanner.mainCameras(emptyList()))
    }

    @Test
    fun autoPicksId3At240NotUltraWideId2() {
        val p = plan(zenfone)
        val first = p.first()
        assertEquals("3", first.cameraId)
        assertEquals(SessionType.HIGH_SPEED, first.sessionType)
        assertEquals(240, first.fps)
        assertEquals(fhd, first.size)
        assertTrue(p.none { it.cameraId == "2" })
    }

    @Test
    fun retryOrderIs240then120then60then30() {
        assertEquals(
            listOf("3:HIGH_SPEED:240", "3:HIGH_SPEED:120", "0:NORMAL:60", "3:NORMAL:60", "0:NORMAL:30", "3:NORMAL:30"),
            summary(plan(zenfone)),
        )
    }

    @Test
    fun failed240OnId3FallsTo120() {
        val p = plan(zenfone, failed = setOf(FailedSetting("3", SessionType.HIGH_SPEED, 240)))
        assertEquals("3:HIGH_SPEED:120", summary(p).first())
    }

    @Test
    fun failedHighSpeedFallsToNormal60OnMainCamera() {
        val failed = setOf(FailedSetting("3", SessionType.HIGH_SPEED, 240), FailedSetting("3", SessionType.HIGH_SPEED, 120))
        val first = plan(zenfone, failed = failed).first()
        assertEquals("0", first.cameraId)
        assertEquals(SessionType.NORMAL, first.sessionType)
        assertEquals(60, first.fps)
    }

    @Test
    fun ultraWideOnlyHighSpeedStillUsesMainNormalSession() {
        val p = plan(listOf(id0, id2))
        assertEquals(listOf("0:NORMAL:60", "0:NORMAL:30"), summary(p))
    }

    @Test
    fun explicitModeStartsAtTheRequestedFps() {
        assertEquals("3:HIGH_SPEED:120", summary(plan(zenfone, FpsMode.FPS_120)).first())
        assertEquals("0:NORMAL:60", summary(plan(zenfone, FpsMode.FPS_60)).first())
        // 60 の次は 30、そのあとに要求より上（120, 240）
        assertEquals(
            listOf("0:NORMAL:60", "3:NORMAL:60", "0:NORMAL:30", "3:NORMAL:30", "3:HIGH_SPEED:120", "3:HIGH_SPEED:240"),
            summary(plan(zenfone, FpsMode.FPS_60)),
        )
    }

    @Test
    fun failuresOnOtherCamerasDoNotExclude() {
        // ID 2 の 240 が記録されていても、ID 3 の 240 には影響しない
        val p = plan(zenfone, failed = setOf(FailedSetting("2", SessionType.HIGH_SPEED, 240)))
        assertEquals("3:HIGH_SPEED:240", summary(p).first())
    }

    @Test
    fun everythingFailedFallsBackToUnfilteredPlan() {
        val failed = plan(zenfone).map(FailedSetting::of).toSet()
        assertEquals(summary(plan(zenfone)), summary(plan(zenfone, failed = failed)))
    }

    @Test
    fun noCandidatesGivesEmptyPlan() {
        assertEquals(emptyList<CaptureConfig>(), plan(emptyList()))
        assertEquals(emptyList<CaptureConfig>(), CapturePlanner.plan(zenfone, FpsMode.AUTO, { _, _ -> false }, emptySet()))
    }

    @Test
    fun failedSettingRoundTrips() {
        val f = FailedSetting("3", SessionType.HIGH_SPEED, 240)
        assertEquals(f, FailedSetting.decode(f.encode()))
        assertNotNull(FailedSetting.decode("0|NORMAL|60"))
        assertNull(FailedSetting.decode("garbage"))
        assertNull(FailedSetting.decode("0|BOGUS|60"))
        assertNull(FailedSetting.decode("0|NORMAL|x"))
    }

    @Test
    fun forcedReturnsOnlyThatSetting() {
        // 超広角（id2）の 120fps を強制。焦点距離で除外されず、失敗記録があっても除外されず、その 1 件だけ
        val forced = ForcedCapture("2", SessionType.HIGH_SPEED, fhd, 120)
        val failed = setOf(FailedSetting("2", SessionType.HIGH_SPEED, 120))
        val planned = CapturePlanner.plan(zenfone, FpsMode.AUTO, { _, _ -> true }, failed, forced)
        assertEquals(listOf(CaptureConfig("2", SessionType.HIGH_SPEED, fhd, 120, FpsRange(120, 120))), planned)
        // 通常セッションの強制
        val normal = CapturePlanner.plan(zenfone, FpsMode.AUTO, { _, _ -> true }, emptySet(), ForcedCapture("2", SessionType.NORMAL, hd, 60))
        assertEquals(listOf("2:NORMAL:60"), summary(normal))
    }

    @Test
    fun forcedThatTheCameraDoesNotHaveGivesEmpty() {
        fun planForced(f: ForcedCapture) = CapturePlanner.plan(zenfone, FpsMode.AUTO, { _, _ -> true }, emptySet(), f)
        assertTrue(planForced(ForcedCapture("9", SessionType.NORMAL, fhd, 30)).isEmpty())
        assertTrue(planForced(ForcedCapture("0", SessionType.HIGH_SPEED, fhd, 120)).isEmpty()) // id0 は高速撮影なし
        assertTrue(planForced(ForcedCapture("2", SessionType.HIGH_SPEED, uhd, 120)).isEmpty())
        assertTrue(planForced(ForcedCapture("2", SessionType.HIGH_SPEED, fhd, 480)).isEmpty())
    }

    @Test
    fun forcedOptionsComeFromTheCameraCapabilities() {
        assertEquals(listOf(fhd, hd), ForcedCapture.sizeOptions(id2, SessionType.HIGH_SPEED))
        assertEquals(listOf(240, 120), ForcedCapture.fpsOptions(id2, SessionType.HIGH_SPEED, fhd))
        assertEquals(listOf(60, 30), ForcedCapture.fpsOptions(id2, SessionType.NORMAL, fhd))
    }
}
