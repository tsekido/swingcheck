package jp.co.updates.swingcheck.data

import jp.co.updates.swingcheck.analysis.MetricsJson
import jp.co.updates.swingcheck.analysis.PhaseCorrection
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.PoseSequence
import jp.co.updates.swingcheck.core.Preprocessor
import jp.co.updates.swingcheck.core.SmoothingConfig
import jp.co.updates.swingcheck.pose.PoseFileFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SwingRepository(
    private val dao: SwingDao,
    private val files: SwingFiles,
    private val smoothing: SmoothingConfig = SmoothingConfig(),
) {
    private val correctionLock = Mutex()

    /** 撮影日時の新しい順。 */
    fun observeSwings(): Flow<List<SwingEntity>> = dao.observeAll()

    fun observeSwing(id: Long): Flow<SwingEntity?> = dao.observe(id)

    fun observeMarks(swingId: Long): Flow<List<PositionMarkEntity>> = dao.observeMarks(swingId)

    suspend fun getSwing(id: Long): SwingEntity? = dao.get(id)

    suspend fun getMarks(swingId: Long): List<PositionMarkEntity> = dao.getMarks(swingId)

    suspend fun insert(swing: SwingEntity): Long = dao.insert(swing)

    /** 動画・骨格のファイルも消す。マークは外部キーの連鎖で消える。 */
    suspend fun delete(id: Long) = deleteAll(listOf(id))

    /** まとめて削除。存在しない id は無視する。 */
    suspend fun deleteAll(ids: List<Long>) {
        if (ids.isEmpty()) return
        val swings = dao.getAll(ids)
        dao.deleteAll(ids)
        withContext(Dispatchers.IO) { swings.forEach(files::deleteFor) }
    }

    suspend fun updateMemo(id: Long, memo: String) = dao.updateMemo(id, memo)

    /** 身長（cm）の更新。数値はピクセルで保存しているので、cm の計算し直しは表示側で行う。 */
    suspend fun updateHeightCm(id: Long, heightCm: Float?) = dao.updateHeight(id, heightCm)

    /** 保存してある骨格の列。解析が終わっていなければ null。 */
    suspend fun loadPoseSequence(swingId: Long): PoseSequence? {
        val swing = dao.get(swingId) ?: return null
        val file = files.poseFile(swingId)
        if (!file.exists()) return null
        return withContext(Dispatchers.IO) { PoseSequence(PoseFileFormat.read(file), swing.width, swing.height) }
    }

    /** Pのコマを手で直し、数値を計算し直して保存する。P1 を直したときは全Pの数値が変わる。 */
    suspend fun setManualFrame(swingId: Long, phase: Phase, frame: Int) {
        correct(swingId, phase, frame)
    }

    /** そのPを自動判定のコマに戻し、数値を計算し直して保存する。 */
    suspend fun resetToAuto(swingId: Long, phase: Phase) {
        correct(swingId, phase, null)
    }

    private suspend fun correct(swingId: Long, phase: Phase, manualFrame: Int?) = correctionLock.withLock {
        val swing = dao.get(swingId) ?: throw IllegalArgumentException("swing not found: $swingId")
        val marks = dao.getMarks(swingId)
        check(marks.size == Phase.entries.size) { "swing $swingId has no analysis result" }
        val sequence = loadPoseSequence(swingId) ?: throw IllegalStateException("pose file missing: $swingId")

        val target = marks[phase.ordinal]
        val newFrame = manualFrame ?: target.autoFrame
        val result = withContext(Dispatchers.Default) {
            val smoothed = Preprocessor.preprocess(sequence, swing.handedness, smoothing)
            PhaseCorrection.apply(smoothed, marks.map { it.frame }, phase, newFrame)
        }
        val json = result.metrics.mapKeys { it.key.number }
            .mapValues { MetricsJson.encode(it.value, result.referenceLengthPx) }
        dao.saveCorrection(swingId, phase.number, manualFrame, json)
    }
}
