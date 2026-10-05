package jp.co.updates.swingcheck.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import jp.co.updates.swingcheck.core.BallResult
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SwingDao {
    @Insert
    abstract suspend fun insert(swing: SwingEntity): Long

    @Query("SELECT * FROM swing ORDER BY createdAt DESC, id DESC")
    abstract fun observeAll(): Flow<List<SwingEntity>>

    @Query("SELECT * FROM swing WHERE id = :id")
    abstract fun observe(id: Long): Flow<SwingEntity?>

    @Query("SELECT * FROM swing WHERE id = :id")
    abstract suspend fun get(id: Long): SwingEntity?

    @Query("SELECT * FROM swing WHERE id IN (:ids)")
    abstract suspend fun getAll(ids: List<Long>): List<SwingEntity>

    @Query("UPDATE swing SET analysisStatus = :status WHERE id = :id")
    abstract suspend fun updateStatus(id: Long, status: AnalysisStatus)

    @Query("UPDATE swing SET memo = :memo WHERE id = :id")
    abstract suspend fun updateMemo(id: Long, memo: String)

    @Query("UPDATE swing SET heightCm = :heightCm WHERE id = :id")
    abstract suspend fun updateHeight(id: Long, heightCm: Float?)

    /** 骨格の保存が済んだあとに、実際に読めた動画の情報で更新する。 */
    @Query("UPDATE swing SET fps = :fps, frameCount = :frameCount, width = :width, height = :height WHERE id = :id")
    abstract suspend fun updateVideoInfo(id: Long, fps: Float, frameCount: Int, width: Int, height: Int)

    @Query("DELETE FROM swing WHERE id IN (:ids)")
    abstract suspend fun deleteAll(ids: List<Long>)

    // --- position_mark ---

    @Query("SELECT * FROM position_mark WHERE swingId = :swingId ORDER BY position")
    abstract fun observeMarks(swingId: Long): Flow<List<PositionMarkEntity>>

    @Query("SELECT * FROM position_mark WHERE swingId = :swingId ORDER BY position")
    abstract suspend fun getMarks(swingId: Long): List<PositionMarkEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun upsertMarks(marks: List<PositionMarkEntity>)

    @Query("DELETE FROM position_mark WHERE swingId = :swingId")
    abstract suspend fun deleteMarks(swingId: Long)

    @Query("UPDATE position_mark SET manualFrame = :manualFrame, metricsJson = :metricsJson WHERE swingId = :swingId AND position = :position")
    abstract suspend fun updateManualFrame(swingId: Long, position: Int, manualFrame: Int?, metricsJson: String)

    @Query("UPDATE position_mark SET metricsJson = :metricsJson WHERE swingId = :swingId AND position = :position")
    abstract suspend fun updateMetrics(swingId: Long, position: Int, metricsJson: String)

    @Query("UPDATE swing SET analysisStatus = :status, ballResult = :ballResult, analysisVersion = :version WHERE id = :id")
    abstract suspend fun updateAnalysisResult(id: Long, status: AnalysisStatus, ballResult: BallResult?, version: Int)

    /** 解析結果（8 つのPと状態）を 1 つのトランザクションで保存する。再解析のときは古い行を置き換える。 */
    @Transaction
    open suspend fun saveAnalysisResult(
        swingId: Long,
        marks: List<PositionMarkEntity>,
        ballResult: BallResult?,
        version: Int,
    ) {
        deleteMarks(swingId)
        upsertMarks(marks)
        updateAnalysisResult(swingId, AnalysisStatus.DONE, ballResult, version)
    }

    /**
     * Pの手動修正の保存。Pを直すと P1 が変わった場合に全Pの数値が変わるので、複数行をまとめて更新する。
     * [metricsByPosition] は position → metricsJson。
     */
    @Transaction
    open suspend fun saveCorrection(
        swingId: Long,
        position: Int,
        manualFrame: Int?,
        metricsByPosition: Map<Int, String>,
    ) {
        for ((pos, json) in metricsByPosition) {
            if (pos == position) updateManualFrame(swingId, pos, manualFrame, json) else updateMetrics(swingId, pos, json)
        }
    }
}
