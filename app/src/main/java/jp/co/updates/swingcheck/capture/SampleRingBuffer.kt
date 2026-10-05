package jp.co.updates.swingcheck.capture

/** エンコーダーが出した圧縮済みの 1 コマ（ByteBuffer の内容のコピー ＋ BufferInfo の要点）。 */
class EncodedSample(
    val data: ByteArray,
    val ptsUs: Long,
    val isKeyFrame: Boolean,
)

/**
 * [SampleRingBuffer.select] の結果。samples は先頭がキーフレーム。時刻は元のまま（0 始まりへの変換は [outputPtsUs]）。
 */
class ClipSelection(val samples: List<EncodedSample>, val startClamped: Boolean, val coversEnd: Boolean) {
    val firstPtsUs: Long get() = samples.first().ptsUs

    /** 書き出す動画での時刻（先頭のコマが 0）。 */
    fun outputPtsUs(sample: EncodedSample): Long = sample.ptsUs - firstPtsUs

    /** 実測の fps（コマ数 − 1）÷ 経過時間。コマが 2 つ未満なら null。 */
    fun measuredFps(): Float? {
        if (samples.size < 2) return null
        val span = samples.last().ptsUs - firstPtsUs
        if (span <= 0) return null
        return ((samples.size - 1) * 1_000_000.0 / span).toFloat()
    }
}

/**
 * 圧縮済みのコマを直近 [maxDurationUs] 分だけ持つリングバッファ。スレッドセーフ。
 *
 * - 先頭は常にキーフレーム。古いものは GOP（キーフレームから次のキーフレームの直前まで）単位で捨てる
 * - 残り時間が [maxDurationUs] を下回らない範囲で捨てるので、保持する長さは maxDurationUs 〜 maxDurationUs + GOP の長さ
 * - 合計が [maxBytes] を超えたら、時間にかかわらず古いものから捨てる
 * - B フレームなし（時刻が単調増加）が前提
 */
class SampleRingBuffer(private val maxDurationUs: Long, private val maxBytes: Long) {
    private val samples = ArrayDeque<EncodedSample>()
    private var bytes = 0L

    /** キーフレームが来るまでの間は、先頭に置けるものがないので捨てる。 */
    @Synchronized
    fun add(sample: EncodedSample) {
        if (samples.isEmpty() && !sample.isKeyFrame) return
        samples.addLast(sample)
        bytes += sample.data.size
        evict()
    }

    private fun evict() {
        while (samples.isNotEmpty()) {
            val nextKey = indexOfNextKeyFrame()
            val overBytes = bytes > maxBytes
            val overDuration = nextKey != -1 && samples.last().ptsUs - samples[nextKey].ptsUs >= maxDurationUs
            if (!overBytes && !overDuration) return
            // 先頭の GOP を捨てる。次のキーフレームがなければ全部
            repeat(if (nextKey == -1) samples.size else nextKey) { bytes -= samples.removeFirst().data.size }
        }
    }

    /** 先頭（index 0）の次のキーフレームの位置。なければ -1。 */
    private fun indexOfNextKeyFrame(): Int {
        for (i in 1 until samples.size) if (samples[i].isKeyFrame) return i
        return -1
    }

    @get:Synchronized
    val latestPtsUs: Long? get() = samples.lastOrNull()?.ptsUs

    @get:Synchronized
    val oldestPtsUs: Long? get() = samples.firstOrNull()?.ptsUs

    @get:Synchronized
    val byteCount: Long get() = bytes

    @get:Synchronized
    val sampleCount: Int get() = samples.size

    @Synchronized
    fun clear() {
        samples.clear()
        bytes = 0
    }

    /**
     * 切り出す範囲を求める。startUs 以前で最も近いキーフレームから、endUs 以前の最後のコマまで。
     * startUs がバッファの先頭より前なら先頭（キーフレーム）にそろえる（startClamped）。
     * endUs にバッファが届いていなければ、あるところまで（coversEnd = false）。空なら null。
     */
    @Synchronized
    fun select(startUs: Long, endUs: Long): ClipSelection? {
        if (samples.isEmpty()) return null
        var from = 0
        for (i in samples.indices) {
            if (samples[i].ptsUs > startUs) break
            if (samples[i].isKeyFrame) from = i
        }
        var to = from
        for (i in from until samples.size) {
            if (samples[i].ptsUs > endUs) break
            to = i
        }
        return ClipSelection(
            samples = samples.subList(from, to + 1).toList(),
            startClamped = startUs < samples.first().ptsUs,
            coversEnd = samples.last().ptsUs >= endUs,
        )
    }
}
