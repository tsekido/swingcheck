package jp.co.updates.swingcheck.core

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot

/** 5章：各Pで表示する数値の項目。 */
enum class Metric(val kind: MetricKind) {
    HEAD_SWAY(MetricKind.LENGTH),
    HEAD_LIFT(MetricKind.LENGTH),
    SHOULDER_TILT(MetricKind.ANGLE),
    PELVIS_SIDE_BEND(MetricKind.ANGLE),
    PELVIS_SWAY(MetricKind.LENGTH),
    SPINE_TILT(MetricKind.ANGLE),
    /** 本人から見て左の膝（右打ちではリード側）。 */
    KNEE_FLEX_LEFT(MetricKind.ANGLE),
    KNEE_FLEX_RIGHT(MetricKind.ANGLE),
    ELBOW_FLEX_LEFT(MetricKind.ANGLE),
    ELBOW_FLEX_RIGHT(MetricKind.ANGLE),
}

enum class MetricKind { LENGTH, ANGLE }

/** 項目 → 値。LENGTH はピクセル、ANGLE は度。 */
typealias MetricValues = Map<Metric, Double>

object MetricsCalculator {
    private const val RAD_TO_DEG = 180.0 / PI

    /** 3 点 a-b-c の b での角度（度、0〜180）。 */
    fun angleAt(a: Point, b: Point, c: Point): Double {
        val ux = a.x - b.x
        val uy = a.y - b.y
        val vx = c.x - b.x
        val vy = c.y - b.y
        val nu = hypot(ux, uy)
        val nv = hypot(vx, vy)
        if (nu < 1e-9 || nv < 1e-9) return 180.0
        val cos = ((ux * vx + uy * vy) / (nu * nv)).coerceIn(-1.0, 1.0)
        return acos(cos) * RAD_TO_DEG
    }

    /**
     * トレイル側の点からリード側の点へのベクトルの、水平からの角度（度）。リード側が高いと正。
     * 右打ち正面撮影ではリード＝画面の右（+x）。画像座標は y が下向きなので符号を反転する。
     */
    fun tilt(trail: Point, lead: Point): Double =
        atan2(-(lead.y - trail.y), lead.x - trail.x) * RAD_TO_DEG

    /** 腰の中心から肩の中心へのベクトルの、鉛直からの角度（度）。肩の中心がトレイル側（-x）に傾くと正。 */
    fun spineTilt(hipCenter: Point, shoulderCenter: Point): Double =
        atan2(hipCenter.x - shoulderCenter.x, hipCenter.y - shoulderCenter.y) * RAD_TO_DEG

    /** 屈曲角：180° − ∠(a, b, c)。まっすぐで 0。 */
    fun flex(a: Point, b: Point, c: Point): Double = 180.0 - angleAt(a, b, c)

    /**
     * 1 コマ分の数値。baseline は P1 のコマ（Sway と Lift の基準）。
     * 移動量の符号は Sway：目標方向（+x）が正、Lift：上が正。
     */
    fun compute(pose: PixelPose, baseline: PixelPose): MetricValues {
        val head = Derived.head(pose)
        val head0 = Derived.head(baseline)
        val hip = Derived.hipCenter(pose)
        val hip0 = Derived.hipCenter(baseline)
        val sc = Derived.shoulderCenter(pose)
        return mapOf(
            Metric.HEAD_SWAY to head.x - head0.x,
            Metric.HEAD_LIFT to -(head.y - head0.y),
            Metric.SHOULDER_TILT to tilt(pose.p(Joint.RIGHT_SHOULDER), pose.p(Joint.LEFT_SHOULDER)),
            Metric.PELVIS_SIDE_BEND to tilt(pose.p(Joint.RIGHT_HIP), pose.p(Joint.LEFT_HIP)),
            Metric.PELVIS_SWAY to hip.x - hip0.x,
            Metric.SPINE_TILT to spineTilt(hip, sc),
            Metric.KNEE_FLEX_LEFT to flex(pose.p(Joint.LEFT_HIP), pose.p(Joint.LEFT_KNEE), pose.p(Joint.LEFT_ANKLE)),
            Metric.KNEE_FLEX_RIGHT to flex(pose.p(Joint.RIGHT_HIP), pose.p(Joint.RIGHT_KNEE), pose.p(Joint.RIGHT_ANKLE)),
            Metric.ELBOW_FLEX_LEFT to flex(pose.p(Joint.LEFT_SHOULDER), pose.p(Joint.LEFT_ELBOW), pose.p(Joint.LEFT_WRIST)),
            Metric.ELBOW_FLEX_RIGHT to flex(pose.p(Joint.RIGHT_SHOULDER), pose.p(Joint.RIGHT_ELBOW), pose.p(Joint.RIGHT_WRIST)),
        )
    }
}

/** 単位の換算。表示のときに使う（内部はピクセルと度）。 */
object Units {
    /** 身長に対する肩幅（両肩峰間の幅）の比率。実データで調整する。 */
    const val SHOULDER_WIDTH_TO_HEIGHT = 0.259
    const val CM_PER_INCH = 2.54
    const val INCHES_PER_FOOT = 12

    /** 1 ピクセルが何 cm か。heightCm × 0.259 ÷ S。 */
    fun cmPerPx(heightCm: Double, shoulderWidthPx: Double): Double =
        heightCm * SHOULDER_WIDTH_TO_HEIGHT / shoulderWidthPx

    /** 比率表示（%）：移動量 ÷ S × 100。「肩幅の◯%」。 */
    fun toPercentOfShoulderWidth(px: Double, shoulderWidthPx: Double): Double = px / shoulderWidthPx * 100.0

    fun pxToCm(px: Double, heightCm: Double, shoulderWidthPx: Double): Double =
        px * cmPerPx(heightCm, shoulderWidthPx)

    fun cmToInch(cm: Double): Double = cm / CM_PER_INCH

    fun inchToCm(inch: Double): Double = inch * CM_PER_INCH

    /** フィート・インチ入力から cm（内部で持つ値）へ。 */
    fun feetInchesToCm(feet: Int, inches: Double): Double = inchToCm(feet * INCHES_PER_FOOT + inches)

    /** cm からフィート・インチへ（インチは小数）。 */
    fun cmToFeetInches(cm: Double): Pair<Int, Double> {
        val totalInches = cmToInch(cm)
        val feet = (totalInches / INCHES_PER_FOOT).toInt()
        return feet to (totalInches - feet * INCHES_PER_FOOT)
    }
}
