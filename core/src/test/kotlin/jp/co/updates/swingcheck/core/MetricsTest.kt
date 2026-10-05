package jp.co.updates.swingcheck.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.atan2

class MetricsTest {
    /** ピクセル座標（y 下向き）で関節を指定して PixelPose を作る。指定のない関節は (0,0)。 */
    private fun pose(vararg joints: Pair<Int, Point>, visible: Boolean = true): PixelPose {
        val xs = DoubleArray(Joint.COUNT)
        val ys = DoubleArray(Joint.COUNT)
        for ((j, pt) in joints) {
            xs[j] = pt.x
            ys[j] = pt.y
        }
        return PixelPose(0, xs, ys, DoubleArray(Joint.COUNT) { if (visible) 1.0 else 0.0 })
    }

    private fun deg(rad: Double) = Math.toDegrees(rad)

    @Test
    fun shoulderTiltIsPositiveWhenLeadShoulderIsHigher() {
        // リード肩（左、画面右）が 10 px 高い。画像は y が下向きなので y は小さい
        val up = MetricsCalculator.tilt(Point(100.0, 110.0), Point(200.0, 100.0))
        assertEquals(deg(atan2(10.0, 100.0)), up, 1e-9)
        assertTrue(up > 0)
        val down = MetricsCalculator.tilt(Point(100.0, 100.0), Point(200.0, 110.0))
        assertEquals(-up, down, 1e-9)
    }

    @Test
    fun spineTiltIsPositiveWhenShouldersLeanAwayFromTarget() {
        // 腰の中心 (500, 600)、肩の中心が 100 px 上・トレイル側（左）に 20 px
        val away = MetricsCalculator.spineTilt(Point(500.0, 600.0), Point(480.0, 500.0))
        assertEquals(deg(atan2(20.0, 100.0)), away, 1e-9)
        val toward = MetricsCalculator.spineTilt(Point(500.0, 600.0), Point(520.0, 500.0))
        assertEquals(-away, toward, 1e-9)
        assertEquals(0.0, MetricsCalculator.spineTilt(Point(500.0, 600.0), Point(500.0, 500.0)), 1e-9)
    }

    @Test
    fun flexIsZeroWhenStraightAndGrowsWithBend() {
        assertEquals(0.0, MetricsCalculator.flex(Point(0.0, 0.0), Point(0.0, 100.0), Point(0.0, 200.0)), 1e-9)
        assertEquals(90.0, MetricsCalculator.flex(Point(0.0, 0.0), Point(0.0, 100.0), Point(100.0, 100.0)), 1e-9)
        assertEquals(45.0, MetricsCalculator.flex(Point(0.0, 0.0), Point(0.0, 100.0), Point(100.0, 200.0)), 1e-9)
    }

    @Test
    fun computeGivesSignedSwayAndLiftRelativeToAddress() {
        val base = pose(
            Joint.NOSE to Point(500.0, 300.0), Joint.LEFT_EAR to Point(500.0, 300.0), Joint.RIGHT_EAR to Point(500.0, 300.0),
            Joint.LEFT_HIP to Point(550.0, 800.0), Joint.RIGHT_HIP to Point(450.0, 800.0),
            Joint.LEFT_SHOULDER to Point(550.0, 500.0), Joint.RIGHT_SHOULDER to Point(450.0, 500.0),
        )
        // 頭が目標方向へ 20 px・上へ 5 px、腰が目標方向へ 30 px
        val now = pose(
            Joint.NOSE to Point(520.0, 295.0), Joint.LEFT_EAR to Point(520.0, 295.0), Joint.RIGHT_EAR to Point(520.0, 295.0),
            Joint.LEFT_HIP to Point(580.0, 800.0), Joint.RIGHT_HIP to Point(480.0, 800.0),
            Joint.LEFT_SHOULDER to Point(550.0, 500.0), Joint.RIGHT_SHOULDER to Point(450.0, 500.0),
        )
        val m = MetricsCalculator.compute(now, base)
        assertEquals(20.0, m.getValue(Metric.HEAD_SWAY), 1e-9)
        assertEquals(5.0, m.getValue(Metric.HEAD_LIFT), 1e-9)
        assertEquals(30.0, m.getValue(Metric.PELVIS_SWAY), 1e-9)
        val m0 = MetricsCalculator.compute(base, base)
        assertEquals(0.0, m0.getValue(Metric.HEAD_SWAY), 1e-9)
        assertEquals(0.0, m0.getValue(Metric.PELVIS_SWAY), 1e-9)
        assertEquals(Metric.entries.toSet(), m.keys)
    }

    @Test
    fun pelvisSideBendUsesSameDefinitionAsShoulderTilt() {
        val p = pose(
            Joint.LEFT_HIP to Point(560.0, 790.0), Joint.RIGHT_HIP to Point(460.0, 800.0),
        )
        val m = MetricsCalculator.compute(p, p)
        assertEquals(deg(kotlin.math.atan(0.1)), m.getValue(Metric.PELVIS_SIDE_BEND), 1e-9)
    }

    @Test
    fun headIsVisibilityWeightedAverage() {
        val xs = DoubleArray(Joint.COUNT)
        val ys = DoubleArray(Joint.COUNT)
        val vis = DoubleArray(Joint.COUNT)
        xs[Joint.NOSE] = 100.0; vis[Joint.NOSE] = 1.0
        xs[Joint.LEFT_EAR] = 200.0; vis[Joint.LEFT_EAR] = 1.0
        xs[Joint.RIGHT_EAR] = 1000.0; vis[Joint.RIGHT_EAR] = 0.0 // 重み 0 は無視される
        val head = Derived.head(PixelPose(0, xs, ys, vis))
        assertEquals(150.0, head.x, 1e-9)
    }

    @Test
    fun unitConversions() {
        // 身長 175 cm、S = 100 px → 1 px = 0.45325 cm
        assertEquals(175 * 0.259 / 100, Units.cmPerPx(175.0, 100.0), 1e-12)
        assertEquals(25.0, Units.toPercentOfShoulderWidth(20.0, 80.0), 1e-9)
        assertEquals(9.065, Units.pxToCm(20.0, 175.0, 100.0), 1e-9)
        assertEquals(1.0, Units.cmToInch(2.54), 1e-12)
        assertEquals(177.8, Units.feetInchesToCm(5, 10.0), 1e-9)
        val (ft, inch) = Units.cmToFeetInches(177.8)
        assertEquals(5, ft)
        assertEquals(10.0, inch, 1e-9)
    }

    @Test
    fun shaftEstimatorExtendsForearmFromHand() {
        val p = pose(
            Joint.LEFT_ELBOW to Point(200.0, 100.0), Joint.LEFT_WRIST to Point(200.0, 200.0),
            Joint.RIGHT_ELBOW to Point(100.0, 100.0), Joint.RIGHT_WRIST to Point(200.0, 100.0),
            Joint.LEFT_INDEX to Point(200.0, 200.0), Joint.RIGHT_INDEX to Point(200.0, 200.0),
        )
        val est = ForearmShaftEstimator()
        val lead = est.estimate(p, 100.0, ShaftPhase.ADDRESS_TO_DOWNSWING)
        assertEquals(Derived.hand(p).x, lead.grip.x, 1e-9)
        assertEquals(0.0, lead.tip.x - lead.grip.x, 1e-9)
        assertEquals(150.0, lead.tip.y - lead.grip.y, 1e-9) // 長さ 1.5 S、向きは下
        val trail = est.estimate(p, 100.0, ShaftPhase.FOLLOW_THROUGH)
        assertEquals(150.0, trail.tip.x - trail.grip.x, 1e-9) // 右肘→右手首は +x
        assertEquals(0.0, trail.tip.y - trail.grip.y, 1e-9)
    }
}
