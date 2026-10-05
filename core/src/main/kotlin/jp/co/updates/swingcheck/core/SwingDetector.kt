package jp.co.updates.swingcheck.core

/** 8章の状態。 */
enum class SwingState { IDLE, READY, ADDRESS, MOVING, SWING }

/**
 * 検出されたスイングの切り出し範囲（フレームのタイムスタンプと同じ時刻軸、ミリ秒）。
 * endMs は検出した時刻より未来（今 + 0.3 秒）なので、呼び出し側はその時刻まで録画を続けてから書き出す。
 * timedOut は、フィニッシュで止まらず時間切れで検出した場合に true。
 */
data class SwingClip(val startMs: Long, val endMs: Long, val timedOut: Boolean)

data class SwingDetectorUpdate(val state: SwingState, val detected: SwingClip? = null)

/** 8章の閾値。速さは S（肩幅）／秒で正規化した値。 */
data class SwingDetectorConfig(
    /** 「全身が映っている」とみなすために必要な関節と、その最低 visibility。 */
    val requiredJoints: List<Int> = listOf(
        Joint.NOSE, Joint.LEFT_SHOULDER, Joint.RIGHT_SHOULDER, Joint.LEFT_WRIST, Joint.RIGHT_WRIST,
        Joint.LEFT_HIP, Joint.RIGHT_HIP, Joint.LEFT_KNEE, Joint.RIGHT_KNEE,
        Joint.LEFT_ANKLE, Joint.RIGHT_ANKLE,
    ),
    val minVisibility: Double = 0.5,
    /** 手の速さをこの秒数前の位置との差から求める（15fps で約 2 コマ）。 */
    val speedLagSec: Double = 0.15,
    /** READY → ADDRESS：この速さ未満が続く。 */
    val quietSpeed: Double = 0.3,
    val quietHoldSec: Double = 0.4,
    /** ADDRESS → MOVING：この速さを超える。 */
    val moveStartSpeed: Double = 0.6,
    /** MOVING：この秒数以内に手が肩の中心より上に上がらなければ、構え直しとみなす。 */
    val movingTimeoutSec: Double = 1.0,
    /** MOVING → ADDRESS：この速さ未満がこの秒数続く（ワッグルや構え直しで止まった）。 */
    val movingStopSpeed: Double = 0.3,
    val movingStopHoldSec: Double = 0.2,
    /** SWING → 検出：この速さ未満がこの秒数続く（フィニッシュで静止）。 */
    val finishSpeed: Double = 0.3,
    val finishHoldSec: Double = 0.4,
    /** SWING：この秒数たっても止まらなければ検出として扱う。 */
    val swingTimeoutSec: Double = 3.0,
    /** この秒数以上全身が映らなければ IDLE に戻る。 */
    val lostSec: Double = 1.0,
    /** 切り出し範囲：ADDRESS の開始の何秒前から、検出時刻の何秒後まで。 */
    val clipPreSec: Double = 0.5,
    val clipPostSec: Double = 0.3,
    /** 手が肩の中心より上：手の y が (肩の中心の y − 余裕 × S) より小さい。 */
    val aboveShoulderMargin: Double = 0.0,
    /** 手の位置の平滑化（One Euro Filter）。 */
    val handMinCutoff: Double = 1.0,
    val handBeta: Double = 0.3,
    val handDCutoff: Double = 1.0,
    /** S（肩幅）の追従の速さ（指数移動平均の係数）。MOVING／SWING 中は固定する。 */
    val shoulderWidthAlpha: Double = 0.2,
)

/**
 * 8章：スイング検出の状態機械。毎秒 15 コマ程度の骨格を 1 コマずつ [update] に与える。
 * width, height は骨格を推定した画像のピクセル数（正規化座標をピクセルに直すのに使う）。
 */
class SwingDetector(
    private val config: SwingDetectorConfig = SwingDetectorConfig(),
    private val width: Int,
    private val height: Int,
) {
    var state: SwingState = SwingState.IDLE
        private set

    private var filterX = newFilter()
    private var filterY = newFilter()
    private val history = ArrayDeque<Triple<Double, Double, Double>>() // t(秒), x, y
    private var shoulderWidth = 0.0
    private var lastValidSec = Double.NaN
    private var firstValidSec = Double.NaN

    private var quietStartSec: Double? = null
    private var addressStartSec = 0.0
    private var movingStartSec = 0.0
    private var stopStartSec: Double? = null
    private var swingStartSec = 0.0
    private var stillStartSec: Double? = null

    private fun newFilter() = OneEuroFilter(config.handMinCutoff, config.handBeta, config.handDCutoff)

    fun reset() {
        state = SwingState.IDLE
        filterX = newFilter()
        filterY = newFilter()
        history.clear()
        shoulderWidth = 0.0
        lastValidSec = Double.NaN
        firstValidSec = Double.NaN
        quietStartSec = null
        stopStartSec = null
        stillStartSec = null
    }

    private fun isFullBody(frame: PoseFrame?): Boolean {
        if (frame == null) return false
        return config.requiredJoints.all { frame.landmarks[it].visibility >= config.minVisibility }
    }

    /** frame が null のとき、または全身が映っていないときは「映っていない」コマとして扱う。 */
    fun update(timestampMs: Long, frame: PoseFrame?): SwingDetectorUpdate {
        val t = timestampMs / 1000.0
        if (!isFullBody(frame)) {
            if (!lastValidSec.isNaN() && t - lastValidSec >= config.lostSec && state != SwingState.IDLE) reset()
            return SwingDetectorUpdate(state)
        }
        frame!!
        if (firstValidSec.isNaN()) firstValidSec = t
        lastValidSec = t

        val pose = toPose(frame)
        val hand = Derived.hand(pose)
        val hx = filterX.filter(t, hand.x)
        val hy = filterY.filter(t, hand.y)
        val sw = Derived.shoulderWidth(pose)
        if (state == SwingState.IDLE || state == SwingState.READY || state == SwingState.ADDRESS || shoulderWidth <= 0.0) {
            shoulderWidth = if (shoulderWidth <= 0.0) sw else shoulderWidth + config.shoulderWidthAlpha * (sw - shoulderWidth)
        }
        val speed = speed(t, hx, hy)

        var detected: SwingClip? = null
        when (state) {
            SwingState.IDLE -> {
                state = SwingState.READY
                quietStartSec = null
            }

            SwingState.READY -> {
                if (trackQuiet(t, speed, config.quietSpeed)) {
                    enterAddress(quietStartSec!!)
                }
            }

            SwingState.ADDRESS -> {
                if (speed != null && speed > config.moveStartSpeed) {
                    state = SwingState.MOVING
                    movingStartSec = t
                    stopStartSec = null
                }
            }

            SwingState.MOVING -> {
                val sc = Derived.shoulderCenter(pose)
                if (hy < sc.y - config.aboveShoulderMargin * shoulderWidth) {
                    state = SwingState.SWING
                    swingStartSec = t
                    stillStartSec = null
                } else {
                    if (speed != null && speed < config.movingStopSpeed) {
                        if (stopStartSec == null) stopStartSec = maxOf(t - config.speedLagSec, firstValidSec)
                        if (t - stopStartSec!! >= config.movingStopHoldSec) enterAddress(stopStartSec!!)
                    } else {
                        stopStartSec = null
                    }
                    if (state == SwingState.MOVING && t - movingStartSec > config.movingTimeoutSec) {
                        // 止まらないまま上にも上がらない：ADDRESS には戻さず、静止を待ち直す
                        state = SwingState.READY
                        quietStartSec = null
                    }
                }
            }

            SwingState.SWING -> {
                if (speed != null && speed < config.finishSpeed) {
                    if (stillStartSec == null) stillStartSec = t - config.speedLagSec
                } else {
                    stillStartSec = null
                }
                val stillDone = stillStartSec != null && t - stillStartSec!! >= config.finishHoldSec
                val timedOut = t - swingStartSec >= config.swingTimeoutSec
                if (stillDone || timedOut) {
                    detected = clip(t, timedOut = !stillDone)
                    state = SwingState.READY
                    quietStartSec = null
                }
            }
        }
        return SwingDetectorUpdate(state, detected)
    }

    private fun enterAddress(startSec: Double) {
        state = SwingState.ADDRESS
        addressStartSec = startSec
        stopStartSec = null
    }

    private fun clip(nowSec: Double, timedOut: Boolean): SwingClip = SwingClip(
        startMs = Math.round((addressStartSec - config.clipPreSec) * 1000),
        endMs = Math.round((nowSec + config.clipPostSec) * 1000),
        timedOut = timedOut,
    )

    /** 静止が続いているか。続き始めた時刻を quietStartSec に入れ、holdSec たったら true。 */
    private fun trackQuiet(t: Double, speed: Double?, threshold: Double): Boolean {
        if (speed != null && speed < threshold) {
            if (quietStartSec == null) quietStartSec = maxOf(t - config.speedLagSec, firstValidSec)
            return t - quietStartSec!! >= config.quietHoldSec
        }
        quietStartSec = null
        return false
    }

    /** 手の速さ（S／秒）。speedLagSec 以上前の位置がまだ無いときは null。 */
    private fun speed(t: Double, x: Double, y: Double): Double? {
        history.addLast(Triple(t, x, y))
        while (history.size > 2 && history[1].first <= t - config.speedLagSec - 0.5) history.removeFirst()
        var ref: Triple<Double, Double, Double>? = null
        for (h in history) {
            if (h.first <= t - config.speedLagSec + 1e-9) ref = h else break
        }
        if (ref == null || shoulderWidth <= 0.0) return null
        val dt = t - ref.first
        if (dt <= 0.0) return null
        return Point(ref.second, ref.third).distanceTo(Point(x, y)) / dt / shoulderWidth
    }

    private fun toPose(frame: PoseFrame): PixelPose = PixelPose(
        frame.timestampMs,
        DoubleArray(Joint.COUNT) { frame.landmarks[it].x.toDouble() * width },
        DoubleArray(Joint.COUNT) { frame.landmarks[it].y.toDouble() * height },
        DoubleArray(Joint.COUNT) { frame.landmarks[it].visibility.toDouble() },
    )
}
