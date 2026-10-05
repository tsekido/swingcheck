package jp.co.updates.swingcheck.capture

import jp.co.updates.swingcheck.core.SwingClip

/**
 * 検出イベント（[SwingClip]）を受けて、endMs までのコマが届くまで待ち、届いたら切り出し範囲を返す。
 * 時刻は [TimeOrigin] で 0 始まりのミリ秒に直したもの（検出側とエンコーダー側で同じ原点）。
 * スレッドセーフ：検出は推定スレッド、コマの追加はエンコーダーのスレッドから呼ばれる。
 */
class ClipCoordinator(private val buffer: SampleRingBuffer, private val origin: TimeOrigin) {
    private val pending = ArrayList<SwingClip>()

    /** 検出イベントを受け付ける。すでに endMs まで届いていれば、すぐ切り出せるので返す。 */
    fun request(clip: SwingClip): List<ClipSelection> {
        synchronized(pending) { pending += clip }
        return collectReady(force = false)
    }

    /** コマを buffer に入れたあとに呼ぶ。endMs まで届いた分を返す。 */
    fun onSampleAdded(): List<ClipSelection> = collectReady(force = false)

    /** 撮影を止めるとき：待っている分を、あるところまでで切り出す。 */
    fun flush(): List<ClipSelection> = collectReady(force = true)

    private fun collectReady(force: Boolean): List<ClipSelection> {
        if (!force && synchronized(pending) { pending.isEmpty() }) return emptyList() // コマごとに呼ばれるので、待ちがなければすぐ戻る
        val latest = buffer.latestPtsUs
        if (latest == null) {
            if (force) synchronized(pending) { pending.clear() }
            return emptyList()
        }
        val ready = synchronized(pending) {
            val r = pending.filter { force || origin.toUs(it.endMs) <= latest }
            pending.removeAll(r.toSet())
            r
        }
        return ready.mapNotNull { buffer.select(origin.toUs(it.startMs), origin.toUs(it.endMs)) }
    }
}

/**
 * 時刻の原点。最初に見えたタイムスタンプ（マイクロ秒）を 0 とし、検出側のミリ秒とエンコーダー側のマイクロ秒を
 * 同じ軸にそろえる。カメラの時刻はそのまま増える値なので、原点を引くだけでよい。
 */
class TimeOrigin {
    private var originUs: Long? = null

    @Synchronized
    fun ensure(ptsUs: Long): Long = originUs ?: ptsUs.also { originUs = it }

    /** 0 始まりのミリ秒。原点が決まっていなければ、この値を原点にする。 */
    fun toMs(ptsUs: Long): Long = Math.floorDiv(ptsUs - ensure(ptsUs), 1000L)

    /** 0 始まりのミリ秒 → 元のマイクロ秒。原点がまだなければ 0 を原点として扱う。 */
    @Synchronized
    fun toUs(ms: Long): Long = (originUs ?: 0L) + ms * 1000L
}
