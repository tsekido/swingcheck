package jp.co.updates.swingcheck.analysis

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * 撮影中は新しい解析を始めない（spec 3章：負荷が高い場合は撮影を終えてから解析）ための一時停止フラグ。
 * 撮影が動いている間は [awaitIdle] が戻らず、止まると戻る。すでに走っている解析は止めない。
 */
class AnalysisGate {
    private val capturing = MutableStateFlow(false)

    fun setCapturing(value: Boolean) {
        capturing.value = value
    }

    val isCapturing: Boolean get() = capturing.value

    /** 撮影中でなくなるまで待つ。撮影中でなければすぐ戻る。 */
    suspend fun awaitIdle() {
        capturing.first { !it }
    }
}
