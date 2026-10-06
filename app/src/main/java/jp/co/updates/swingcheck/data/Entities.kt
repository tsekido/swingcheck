package jp.co.updates.swingcheck.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import jp.co.updates.swingcheck.core.BallResult
import jp.co.updates.swingcheck.core.Handedness

enum class CameraView { FACE_ON } // 将来 DOWN_THE_LINE

/** Swing の取り込み元。 */
enum class SwingSource { CAPTURED, IMPORTED }

enum class AnalysisStatus { PENDING, RUNNING, DONE, FAILED }

/** design.md 10章 `swing`。enum は名前の文字列で保存される。 */
@Entity(tableName = "swing")
data class SwingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 撮影日時（エポックミリ秒） */
    val createdAt: Long,
    /** アプリ専用領域（filesDir）からの相対パス */
    val videoPath: String,
    val fps: Float,
    val frameCount: Int,
    /** 回転メタデータを反映した、表示する向きの大きさ（骨格の座標はこの大きさに対する比率） */
    val width: Int,
    val height: Int,
    val cameraView: CameraView = CameraView.FACE_ON,
    val handedness: Handedness = Handedness.RIGHT,
    val heightCm: Float? = null,
    val memo: String = "",
    val analysisStatus: AnalysisStatus = AnalysisStatus.PENDING,
    val ballResult: BallResult? = null,
    /** 解析に使った判定ロジックの版。[AnalysisVersion.CURRENT] より小さければ再解析の対象。0 は未解析 */
    val analysisVersion: Int = 0,
    /** アプリ内で撮影したか、保存済みの動画を読み込んだか。読み込んだ動画にはボール判定（素振りの除外）をしない */
    @ColumnInfo(defaultValue = "'CAPTURED'") val source: SwingSource = SwingSource.CAPTURED,
    /**
     * 動画の時刻から実時間への補正倍率（実時間 = 動画の時刻 ÷ timeScale）。1 なら補正なし。
     * 引き延ばされたスロー動画（時刻が 30fps などに延ばされている）を読み込んだときだけ 1 より大きい。
     * 骨格ファイルの時刻と再生の位置は動画の時刻のまま持ち、解析（速さ・平滑化）に使うときだけ補正する。
     * fps は補正後（実時間）の値で持つ。
     */
    @ColumnInfo(defaultValue = "1.0") val timeScale: Float = 1f,
)

/** design.md 10章 `position_mark`。スイングごとに P1〜P8 の 8 行。 */
@Entity(
    tableName = "position_mark",
    primaryKeys = ["swingId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = SwingEntity::class,
            parentColumns = ["id"],
            childColumns = ["swingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PositionMarkEntity(
    val swingId: Long,
    /** 1〜8（P1〜P8） */
    val position: Int,
    val autoFrame: Int,
    /** 手で直したコマ。null なら自動 */
    val manualFrame: Int?,
    val autoUncertain: Boolean,
    /** [MetricsJson] 形式 */
    val metricsJson: String,
) {
    /** 実際に使うコマ（手で直していればそちら） */
    val frame: Int get() = manualFrame ?: autoFrame
}

object AnalysisVersion {
    /** 判定ロジックや数値の計算を変えたら上げる。 */
    const val CURRENT = 1
}
