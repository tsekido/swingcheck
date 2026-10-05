package jp.co.updates.swingcheck.capture

import android.graphics.Bitmap

/** GL スレッドから推定スレッドへ縮小フレームを渡す口。推定が追いつかないときはコマを捨てる。 */
interface PoseSink {
    /** true の間は推定中。GL スレッドは、これが true なら縮小フレームの読み出し自体を省く。 */
    fun isBusy(): Boolean

    /** @param timestampMs [TimeOrigin] で 0 始まりにしたミリ秒。bitmap の recycle は受け取った側が行う。 */
    fun submit(bitmap: Bitmap, timestampMs: Long)
}
