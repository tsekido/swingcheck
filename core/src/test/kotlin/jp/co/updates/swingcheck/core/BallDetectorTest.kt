package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.abs

class BallDetectorTest {
    private val w = 1080
    private val h = 1920

    /** P1 の骨格：肩幅 S = 250 px、足首は y = 1700、x = 540 ± 137.5。 */
    private fun addressPose(): PoseFrame = SyntheticSwing.generate(fps = 30.0, includeSwing = false).sequence.frames.first()

    private class Scene(val w: Int, val h: Int, val bg: Int, noise: Int, seed: Long) {
        val px = IntArray(w * h)

        init {
            val rnd = Random(seed)
            for (i in px.indices) px[i] = bg + if (noise > 0) rnd.nextInt(2 * noise + 1) - noise else 0
        }

        fun disk(cx: Double, cy: Double, r: Double, v: Int) {
            for (y in (cy - r - 1).toInt()..(cy + r + 1).toInt()) for (x in (cx - r - 1).toInt()..(cx + r + 1).toInt()) {
                if (x in 0 until w && y in 0 until h && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) px[y * w + x] = v
            }
        }

        fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) {
            for (y in y0..y1) for (x in x0..x1) px[y * w + x] = v
        }

        fun image(): GrayImage = GrayImage.ofInts(w, h, px)
    }

    // ボール：直径 0.11 S ≒ 27.5 px、足首の少し下・両足の間
    private val ballX = 540.0
    private val ballY = 1730.0
    private val ballR = 13.75

    private fun grass(noise: Int = 4, seed: Long = 1) = Scene(w, h, 90, noise, seed)

    @Test
    fun ballRemovedIsHit() {
        val a = grass(seed = 1).apply { disk(ballX, ballY, ballR, 235) }
        val e = grass(seed = 2)
        val d = BallDetector().analyze(a.image(), e.image(), addressPose())
        assertEquals(BallResult.HIT, d.result)
        assertNotNull(d.ball)
        val b = d.ball!!
        assertTrue(abs(b.x - ballX) < 3 && abs(b.y - ballY) < 3, "ball at ${b.x},${b.y}")
    }

    @Test
    fun ballStillThereIsPractice() {
        val a = grass(seed = 1).apply { disk(ballX, ballY, ballR, 235) }
        val e = grass(seed = 2).apply { disk(ballX, ballY, ballR, 235) }
        assertEquals(BallResult.PRACTICE, BallDetector().detect(a.image(), e.image(), addressPose()))
    }

    @Test
    fun noBallIsUnknown() {
        val a = grass(seed = 1)
        val e = grass(seed = 2)
        val d = BallDetector().analyze(a.image(), e.image(), addressPose())
        assertEquals(BallResult.UNKNOWN, d.result)
        assertNull(d.ball)
    }

    @Test
    fun darkImageIsUnknown() {
        val a = Scene(w, h, 20, 3, 1).apply { disk(ballX, ballY, ballR, 70) }
        val e = Scene(w, h, 20, 3, 2)
        assertEquals(BallResult.UNKNOWN, BallDetector().detect(a.image(), e.image(), addressPose()))
    }

    @Test
    fun elongatedBrightShapeIsNotTreatedAsBall() {
        // 明るいが細長い（靴や線のような）ものは、丸さの条件で除外される
        val a = grass().apply { rect(500, 1728, 530, 1733, 235) }
        val e = grass(seed = 2)
        assertEquals(BallResult.UNKNOWN, BallDetector().detect(a.image(), e.image(), addressPose()))
    }

    @Test
    fun brightestRoundBlobIsChosen() {
        val a = grass().apply {
            disk(ballX, ballY, ballR, 235)
            disk(ballX + 60, ballY - 10, ballR, 170) // もう少し暗い丸
        }
        val e = grass(seed = 2).apply { disk(ballX + 60, ballY - 10, ballR, 170) }
        val d = BallDetector().analyze(a.image(), e.image(), addressPose())
        assertEquals(BallResult.HIT, d.result)
        assertTrue(abs(d.ball!!.x - ballX) < 3)
    }

    @Test
    fun ballOutsideSearchAreaIsIgnored() {
        // 足元から遠い（上方）の明るい丸は探す範囲の外
        val a = grass().apply { disk(540.0, 1300.0, ballR, 235) }
        val e = grass(seed = 2)
        assertEquals(BallResult.UNKNOWN, BallDetector().detect(a.image(), e.image(), addressPose()))
    }

    @Test
    fun partiallyFadedBallCountsAsGoneOnlyBelowThreshold() {
        val a = grass().apply { disk(ballX, ballY, ballR, 235) }
        // 最後のコマで明るさが 70% 残っている（P1 の差の 40% 以上）→ 素振り
        val e70 = grass(seed = 2).apply { disk(ballX, ballY, ballR, 90 + (145 * 0.7).toInt()) }
        assertEquals(BallResult.PRACTICE, BallDetector().detect(a.image(), e70.image(), addressPose()))
        val e20 = grass(seed = 2).apply { disk(ballX, ballY, ballR, 90 + (145 * 0.2).toInt()) }
        assertEquals(BallResult.HIT, BallDetector().detect(a.image(), e20.image(), addressPose()))
    }

    @Test
    fun byteArrayAndIntArrayInputsAreEquivalent() {
        val a = grass().apply { disk(ballX, ballY, ballR, 235) }
        val e = grass(seed = 2)
        val ab = GrayImage.ofBytes(w, h, ByteArray(a.px.size) { a.px[it].toByte() })
        val eb = GrayImage.ofBytes(w, h, ByteArray(e.px.size) { e.px[it].toByte() })
        assertEquals(BallResult.HIT, BallDetector().detect(ab, eb, addressPose()))
    }
}
