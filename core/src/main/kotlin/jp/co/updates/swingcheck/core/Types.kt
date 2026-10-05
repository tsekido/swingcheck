package jp.co.updates.swingcheck.core

import kotlin.math.hypot

/** MediaPipe Pose Landmarker の 1 関節。x, y は画像座標（0〜1、y は下向き）。 */
data class Landmark(val x: Float, val y: Float, val z: Float, val visibility: Float)

/** 1 コマ分の骨格。landmarks は 33 個。 */
class PoseFrame(val timestampMs: Long, val landmarks: List<Landmark>) {
    init {
        require(landmarks.size == Joint.COUNT) { "landmarks must have ${Joint.COUNT} items" }
    }
}

/** 骨格のコマ列と、その元になった動画（画像）の幅・高さ（ピクセル）。 */
class PoseSequence(val frames: List<PoseFrame>, val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "width and height must be positive" }
    }

    val size: Int get() = frames.size
}

/** MediaPipe Pose の関節番号。left／right は本人から見た左右。 */
object Joint {
    const val COUNT = 33

    const val NOSE = 0
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28

    /** 左右を入れ替えるときの対応表（偶数／奇数ペア）。 */
    private val SWAP_PAIRS = listOf(
        1 to 4, 2 to 5, 3 to 6, 7 to 8, 9 to 10, 11 to 12, 13 to 14, 15 to 16,
        17 to 18, 19 to 20, 21 to 22, 23 to 24, 25 to 26, 27 to 28, 29 to 30, 31 to 32,
    )

    /** 左右入れ替え後の関節番号。 */
    fun mirrored(index: Int): Int {
        for ((a, b) in SWAP_PAIRS) {
            if (index == a) return b
            if (index == b) return a
        }
        return index
    }
}

/**
 * 打席。内部の計算はすべて「右打ち・目標方向が画面の右（+x）・リード側が本人の左」で行う。
 * 左打ちのときは、入力を [toRightHanded] で鏡像に直してから計算する（ここが左右反転の唯一の入口）。
 * 初期版では RIGHT だけを使う。LEFT は未検証。
 */
enum class Handedness {
    RIGHT,
    LEFT;

    /** 右打ち基準の骨格列に直す。RIGHT ならそのまま返す。LEFT は x を反転し、左右の関節を入れ替える。 */
    fun toRightHanded(sequence: PoseSequence): PoseSequence = when (this) {
        RIGHT -> sequence
        LEFT -> PoseSequence(
            sequence.frames.map { f ->
                PoseFrame(
                    f.timestampMs,
                    List(Joint.COUNT) { i ->
                        val l = f.landmarks[Joint.mirrored(i)]
                        Landmark(1f - l.x, l.y, l.z, l.visibility)
                    },
                )
            },
            sequence.width,
            sequence.height,
        )
    }
}

data class Point(val x: Double, val y: Double) {
    fun distanceTo(o: Point): Double = hypot(x - o.x, y - o.y)
}

/** 正規化座標（0〜1）をピクセル座標に直す。縦横比で角度がゆがまないよう、計算の前に必ず通す。 */
fun Landmark.toPixel(width: Int, height: Int): Point = Point(x.toDouble() * width, y.toDouble() * height)

/**
 * ピクセル座標に直した 1 コマ分の骨格。計算（平滑化・数値・Pの判定）はすべてこの型で行う。
 * xs, ys はピクセル、visibility は元の値（補間前）。
 */
class PixelPose(
    val timestampMs: Long,
    val xs: DoubleArray,
    val ys: DoubleArray,
    val visibility: DoubleArray,
) {
    fun p(joint: Int): Point = Point(xs[joint], ys[joint])

    fun mid(a: Int, b: Int): Point = Point((xs[a] + xs[b]) / 2, (ys[a] + ys[b]) / 2)
}

/** 平滑化まで済んだ骨格列（ピクセル座標）。 */
class PixelSequence(val poses: List<PixelPose>, val width: Int, val height: Int) {
    val size: Int get() = poses.size
    fun timeSec(i: Int): Double = poses[i].timestampMs / 1000.0
}
