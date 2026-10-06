package jp.co.updates.swingcheck.core

/** 推定を間引いたコマの骨格を、前後の推定したコマから埋める。 */
object PoseInterpolation {
    /**
     * [from] と [to] の間を [fraction]（0〜1）の位置で線形補間した骨格。
     * 補間したコマは実測ではないので、visibility は前後の小さいほうにする
     * （どちらかが欠損なら補間したコマも欠損として、[Preprocessor] で改めて補間される）。
     */
    fun between(from: PoseFrame, to: PoseFrame, fraction: Double, timestampMs: Long): PoseFrame {
        val f = fraction.coerceIn(0.0, 1.0).toFloat()
        val landmarks = List(Joint.COUNT) { j ->
            val a = from.landmarks[j]
            val b = to.landmarks[j]
            Landmark(
                a.x + (b.x - a.x) * f,
                a.y + (b.y - a.y) * f,
                a.z + (b.z - a.z) * f,
                minOf(a.visibility, b.visibility),
            )
        }
        return PoseFrame(timestampMs, landmarks)
    }

    /**
     * 推定したコマ（[estimated] が true）だけが本物の値を持つ [frames] の、残りのコマを埋める（先頭から [frames] を書き換える）。
     * 補間の重みはコマの位置（番号）で決める。最初の推定より前、最後の推定より後のコマは、
     * 補間できないので visibility 0 の欠損（[undetected]）にする。
     */
    fun fill(frames: MutableList<PoseFrame>, estimated: BooleanArray, undetected: (Long) -> PoseFrame) {
        require(frames.size == estimated.size) { "frames and estimated must have the same size" }
        var prev = -1
        for (i in frames.indices) {
            if (!estimated[i]) continue
            if (prev < 0) {
                for (k in 0 until i) frames[k] = undetected(frames[k].timestampMs)
            } else {
                for (k in prev + 1 until i) {
                    frames[k] = between(frames[prev], frames[i], (k - prev).toDouble() / (i - prev), frames[k].timestampMs)
                }
            }
            prev = i
        }
        val from = if (prev < 0) 0 else prev + 1
        for (k in from until frames.size) frames[k] = undetected(frames[k].timestampMs)
    }
}
