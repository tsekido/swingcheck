package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SwingDetectorTest {
    private class Run(val clips: List<SwingClip>, val states: List<SwingState>)

    private fun shift(frames: List<PoseFrame>, offsetMs: Long) = frames.map { PoseFrame(it.timestampMs + offsetMs, it.landmarks) }

    /** 15fps に間引いた骨格を 1 コマずつ検出器に与える。null は「映っていない」コマ。 */
    private fun run(
        stream: List<PoseFrame?>,
        times: List<Long>,
        config: SwingDetectorConfig = SwingDetectorConfig(),
    ): Run {
        val det = SwingDetector(config, SyntheticSwing.WIDTH, SyntheticSwing.HEIGHT)
        val clips = mutableListOf<SwingClip>()
        val states = mutableListOf<SwingState>()
        for (i in stream.indices) {
            val u = det.update(times[i], stream[i])
            states += u.state
            u.detected?.let { clips += it }
        }
        return Run(clips, states)
    }

    private fun runFrames(frames: List<PoseFrame>, config: SwingDetectorConfig = SwingDetectorConfig()) =
        run(frames, frames.map { it.timestampMs }, config)

    @ParameterizedTest
    @ValueSource(doubles = [60.0, 120.0, 240.0])
    fun swingIsDetectedOnceWithClipRange(fps: Double) {
        // 最初の 1 秒はワッグル、その後静止（アドレス）、スイング
        val r = SyntheticSwing.generate(
            fps = fps, preHoldSec = 2.0, waggle = SyntheticSwing.Waggle(-2.0, -1.0),
        )
        val frames = SyntheticSwing.decimate(r.sequence.frames, fps)
        val res = runFrames(frames)
        assertEquals(1, res.clips.size, "clips=${res.clips}")
        val c = res.clips[0]
        val ms = { sec: Double -> ((sec - r.startSec) * 1000).toLong() }
        // 切り出しは、アドレスの静止の始まり（ワッグルのあと t ≒ -1.0）の 0.5 秒前から
        assertTrue(kotlin.math.abs(c.startMs - (ms(-1.0) - 500)) < 400, "start=${c.startMs}")
        // 終わりは、フィニッシュ（t = 1.7）で静止してから +0.4 秒のあと +0.3 秒あたり
        assertTrue(c.endMs > ms(1.7) + 300 && c.endMs < ms(1.7) + 1300, "end=${c.endMs}")
        assertTrue(!c.timedOut)
        // 検出後は READY に戻る
        assertTrue(res.states.contains(SwingState.SWING))
    }

    @Test
    fun statesProgressInOrder() {
        val r = SyntheticSwing.generate(fps = 60.0, preHoldSec = 1.5)
        val res = runFrames(SyntheticSwing.decimate(r.sequence.frames, 60.0))
        val firstOf = { s: SwingState -> res.states.indexOf(s) }
        assertTrue(firstOf(SwingState.READY) in 0 until firstOf(SwingState.ADDRESS))
        assertTrue(firstOf(SwingState.ADDRESS) < firstOf(SwingState.MOVING))
        assertTrue(firstOf(SwingState.MOVING) < firstOf(SwingState.SWING))
    }

    @ParameterizedTest
    @ValueSource(doubles = [60.0, 240.0])
    fun waggleDoesNotTrigger(fps: Double) {
        for (amp in listOf(0.15, 0.25, 0.5)) {
            for (hz in listOf(1.0, 2.0, 3.0)) {
                val r = SyntheticSwing.generate(
                    fps = fps, includeSwing = false, preHoldSec = 1.0, postHoldSec = 4.0,
                    waggle = SyntheticSwing.Waggle(0.0, 2.5, amplitudeS = amp, hz = hz),
                )
                // includeSwing=false のときの時間軸は -pre .. post
                val res = runFrames(SyntheticSwing.decimate(r.sequence.frames, fps))
                assertEquals(0, res.clips.size, "amp=$amp hz=$hz fps=$fps clips=${res.clips}")
                // 動き出し（MOVING）までは進むこと（検出器が手の動きを見ていることの確認）
                assertTrue(res.states.contains(SwingState.MOVING), "amp=$amp hz=$hz never moved")
                assertTrue(!res.states.contains(SwingState.SWING))
            }
        }
    }

    @Test
    fun waggleThenSwingGivesExactlyOneClip() {
        val r = SyntheticSwing.generate(fps = 60.0, preHoldSec = 3.0, waggle = SyntheticSwing.Waggle(-3.0, -1.2, amplitudeS = 0.4))
        val res = runFrames(SyntheticSwing.decimate(r.sequence.frames, 60.0))
        assertEquals(1, res.clips.size)
    }

    @Test
    fun twoSwingsInARowGiveTwoClips() {
        val a = SyntheticSwing.generate(fps = 60.0, preHoldSec = 1.5, postHoldSec = 2.0)
        val b = SyntheticSwing.generate(fps = 60.0, preHoldSec = 1.5, postHoldSec = 2.0)
        val offset = a.sequence.frames.last().timestampMs + 17
        val frames = SyntheticSwing.decimate(a.sequence.frames + shift(b.sequence.frames, offset), 60.0)
        val res = runFrames(frames)
        assertEquals(2, res.clips.size, "clips=${res.clips}")
        assertTrue(res.clips[1].startMs > res.clips[0].endMs - 1500)
    }

    @Test
    fun personNotInFrameStaysIdleAndReturnsToIdleAfterLoss() {
        val r = SyntheticSwing.generate(fps = 60.0, includeSwing = false, preHoldSec = 1.0, postHoldSec = 2.0)
        val frames = SyntheticSwing.decimate(r.sequence.frames, 60.0)
        val times = frames.map { it.timestampMs }
        // 最初の 1 秒は映っていない
        val stream = frames.mapIndexed { i, f -> if (times[i] < 1000) null else f }
        val det = SwingDetector(SwingDetectorConfig(), SyntheticSwing.WIDTH, SyntheticSwing.HEIGHT)
        var last = SwingState.IDLE
        for (i in stream.indices) {
            last = det.update(times[i], stream[i]).state
            if (times[i] < 1000) assertEquals(SwingState.IDLE, last)
        }
        assertEquals(SwingState.ADDRESS, last)
        // その後 1.0 秒以上映らなくなると IDLE に戻る
        var t = times.last()
        repeat(20) {
            t += 66
            last = det.update(t, null).state
        }
        assertEquals(SwingState.IDLE, last)
    }

    @Test
    fun lowVisibilityBodyIsTreatedAsNotInFrame() {
        val r = SyntheticSwing.generate(fps = 60.0, includeSwing = false, preHoldSec = 0.5, postHoldSec = 0.5)
        val f = r.sequence.frames[0]
        val hidden = PoseFrame(0, f.landmarks.mapIndexed { i, l -> if (i == Joint.LEFT_ANKLE) l.copy(visibility = 0.2f) else l })
        val det = SwingDetector(SwingDetectorConfig(), SyntheticSwing.WIDTH, SyntheticSwing.HEIGHT)
        assertEquals(SwingState.IDLE, det.update(0, hidden).state)
        assertEquals(SwingState.READY, det.update(66, f).state)
    }

    @Test
    fun swingThatNeverStopsIsDetectedByTimeout() {
        // フィニッシュで止まらない人：停止判定を事実上無効（速さの閾値を負）にして、時間切れで検出させる
        val r = SyntheticSwing.generate(fps = 60.0, preHoldSec = 1.5, postHoldSec = 4.0)
        val cfg = SwingDetectorConfig(finishSpeed = -1.0, swingTimeoutSec = 3.0)
        val res = runFrames(SyntheticSwing.decimate(r.sequence.frames, 60.0), cfg)
        assertEquals(1, res.clips.size)
        assertTrue(res.clips[0].timedOut)
    }
}
