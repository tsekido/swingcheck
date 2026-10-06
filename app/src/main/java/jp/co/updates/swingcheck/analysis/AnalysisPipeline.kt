package jp.co.updates.swingcheck.analysis

import android.util.Log
import jp.co.updates.swingcheck.core.BallDetector
import jp.co.updates.swingcheck.core.BallResult
import jp.co.updates.swingcheck.core.GrayImage
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.core.PoseFrame
import jp.co.updates.swingcheck.core.PoseInterpolation
import jp.co.updates.swingcheck.core.PoseSequence
import jp.co.updates.swingcheck.core.SwingAnalyzer
import jp.co.updates.swingcheck.data.AnalysisStatus
import jp.co.updates.swingcheck.data.AnalysisVersion
import jp.co.updates.swingcheck.data.SwingDao
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.data.SwingFiles
import jp.co.updates.swingcheck.data.SwingRepository
import jp.co.updates.swingcheck.pose.PoseEstimator
import jp.co.updates.swingcheck.pose.PoseFileFormat
import jp.co.updates.swingcheck.pose.PoseFrames
import jp.co.updates.swingcheck.settings.SettingsRepository
import jp.co.updates.swingcheck.video.VideoFrameReader
import jp.co.updates.swingcheck.video.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 解析の流れ（design.md 2章）：全コマ読み → 骨格推定 → 骨格の列を保存 → Pの判定と数値 →
 * ボール判定（設定でオンのとき。素振りなら Swing ごと削除）→ position_mark に保存 → 状態を DONE。
 *
 * ボール判定は P1 のコマが要るので、Pの判定のあとに行う（仕様書の流れと結果は同じ）。
 * 失敗したら状態を FAILED にする。
 */
class AnalysisPipeline(
    private val dao: SwingDao,
    private val repository: SwingRepository,
    private val files: SwingFiles,
    private val settings: SettingsRepository,
    private val estimatorFactory: () -> PoseEstimator,
) {
    suspend fun run(swingId: Long) {
        val swing = dao.get(swingId) ?: return // 解析待ちの間に削除された
        dao.updateStatus(swingId, AnalysisStatus.RUNNING)
        try {
            analyze(swing)
        } catch (e: CancellationException) {
            // 途中で止められた。再開されるときに最初からやり直す
            withContext(NonCancellable) { dao.updateStatus(swingId, AnalysisStatus.PENDING) }
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "analysis failed: swing=$swingId", e)
            dao.updateStatus(swingId, AnalysisStatus.FAILED)
        }
    }

    /** 解析の時間の内訳（ミリ秒）。ログ用。 */
    private class Timing {
        var poseMs = 0L
        var copyMs = 0L
        var convertMs = 0L
        var inferMs = 0L
        var frames = 0
        var estimated = 0
    }

    private suspend fun analyze(swing: SwingEntity) {
        val start = System.nanoTime()
        val timing = Timing()
        var estimateMs = 0L
        var outcome = "ok"
        try {
            analyze(swing, timing) { estimateMs = it }
        } catch (e: Throwable) {
            outcome = if (e is CancellationException) "cancelled" else "failed(${e.javaClass.simpleName})"
            throw e
        } finally {
            val totalMs = (System.nanoTime() - start) / 1_000_000
            // 読み込み＋骨格推定の合計から骨格推定を引いたものがデコード（と色変換）の時間
            Log.i(
                TAG,
                "timing swing=${swing.id} result=$outcome frames=${timing.frames} " +
                    "estimated=${timing.estimated} " +
                    "decode_ms=${estimateMs - timing.poseMs} pose_ms=${timing.poseMs} " +
                    "copy_ms=${timing.copyMs} convert_ms=${timing.convertMs} infer_ms=${timing.inferMs} total_ms=$totalMs",
            )
        }
    }

    private suspend fun analyze(swing: SwingEntity, timing: Timing, onEstimated: (Long) -> Unit) {
        val reader = VideoFrameReader(files.resolve(swing.videoPath))
        val info = reader.probe()

        // 1. 全コマ読み → 骨格推定
        val estimateStart = System.nanoTime()
        val (frames, size) = try {
            estimateAll(reader, info, timing)
        } finally {
            onEstimated((System.nanoTime() - estimateStart) / 1_000_000)
        }
        if (frames.none { it.second }) error("no person detected in any frame")
        val poseFrames = frames.map { it.first }

        // 2. 骨格の列を保存
        withContext(Dispatchers.IO) { PoseFileFormat.write(files.poseFile(swing.id), poseFrames) }
        dao.updateVideoInfo(swing.id, info.fps, poseFrames.size, size.first, size.second)

        // 3. Pの判定と数値
        val sequence = PoseSequence(poseFrames, size.first, size.second)
        val analysis = withContext(Dispatchers.Default) {
            SwingAnalyzer(handedness = swing.handedness).analyze(sequence)
        }

        // 4. ボール判定（設定でオンのときだけ）
        var ball: BallResult? = null
        if (settings.current().practiceFilterEnabled) {
            ball = detectBall(reader, info, analysis.phases[Phase.P1].frame, poseFrames.size - 1) { p1 ->
                AnalysisMapper.toPoseFrame(analysis.smoothed.poses[p1], analysis.smoothed)
            }
            if (ball == BallResult.PRACTICE) {
                repository.delete(swing.id) // 素振りは保存しない
                return
            }
        }

        // 5. 保存して DONE
        dao.saveAnalysisResult(swing.id, AnalysisMapper.toMarks(swing.id, analysis), ball, AnalysisVersion.CURRENT)
    }

    /** 戻り値：(骨格, 検出できたか) のコマ列と、画像の (幅, 高さ)。 */
    private suspend fun estimateAll(
        reader: VideoFrameReader,
        info: VideoInfo,
        timing: Timing,
    ): Pair<List<Pair<PoseFrame, Boolean>>, Pair<Int, Int>> = withContext(Dispatchers.Default) {
        val out = ArrayList<Pair<PoseFrame, Boolean>>(info.frameCount)
        val estimatedFlags = ArrayList<Boolean>(info.frameCount)
        var size = info.width to info.height
        var lastTs = -1L
        // 高 fps の動画は、推定を POSE_TARGET_FPS 前後まで間引く（残りは後で補間する）
        val step = poseStep(info.fps)
        estimatorFactory().use { estimator ->
            reader.decode(info) { frame ->
                ensureActive()
                val estimate = frame.index % step == 0 || frame.index == info.frameCount - 1
                if (!estimate) {
                    // 補間するので時刻だけ入れておく。YUV のコピーも変換もしない
                    out += PoseFrames.undetected(frame.timestampMs) to false
                    estimatedFlags += false
                    timing.frames++
                    return@decode true
                }
                val ts = Timestamps.nextAfter(lastTs, frame.timestampMs)
                lastTs = ts
                // 骨格の座標は正規化座標なので、縮小しても結果の意味は変わらない。保存する動画の大きさは元のまま
                val poseStart = System.nanoTime()
                frame.displaySize() // YUV のコピーはここで行われる（初回の呼び出しで）
                val t1 = System.nanoTime()
                if (out.isEmpty()) size = frame.displaySize()
                val bitmap = frame.toBitmap(POSE_INPUT_MAX_LONG_SIDE)
                val t2 = System.nanoTime()
                try {
                    val pose = estimator.estimate(bitmap, ts)
                    out += if (pose != null) pose to true else PoseFrames.undetected(ts) to false
                    estimatedFlags += true
                } finally {
                    bitmap.recycle()
                    val t3 = System.nanoTime()
                    timing.copyMs += (t1 - poseStart) / 1_000_000
                    timing.convertMs += (t2 - t1) / 1_000_000
                    timing.inferMs += (t3 - t2) / 1_000_000
                    timing.poseMs += (t3 - poseStart) / 1_000_000
                    timing.frames++
                    timing.estimated++
                }
                true
            }
        }
        if (step > 1) {
            // 推定したコマの検出フラグは本物の結果。補間で埋めたコマは、前後がともに検出できていれば検出済みとする
            val poses = out.mapTo(ArrayList(out.size)) { it.first }
            val flags = estimatedFlags.toBooleanArray()
            PoseInterpolation.fill(poses, flags) { PoseFrames.undetected(it) }
            for (i in poses.indices) {
                val detected = if (flags[i]) out[i].second else
                    poses[i].landmarks.any { it.visibility > 0f }
                out[i] = poses[i] to detected
            }
        }
        out to size
    }

    /** P1 のコマと最後のコマのグレースケールを読み直して判定する。画像が取れなければ UNKNOWN。 */
    private suspend fun detectBall(
        reader: VideoFrameReader,
        info: VideoInfo,
        addressFrame: Int,
        lastFrame: Int,
        addressPose: (Int) -> PoseFrame,
    ): BallResult = withContext(Dispatchers.Default) {
        val wanted = setOf(addressFrame, lastFrame)
        val grays = HashMap<Int, GrayImage>()
        reader.decode(info) { frame ->
            ensureActive()
            if (frame.index in wanted) grays[frame.index] = frame.toGray()
            frame.index < lastFrame
        }
        val address = grays[addressFrame]
        val end = grays[lastFrame]
        if (address == null || end == null) BallResult.UNKNOWN
        else BallDetector().detect(address, end, addressPose(addressFrame))
    }

    private companion object {
        const val TAG = "AnalysisPipeline"

        /** MediaPipe に渡す画像の長辺（ピクセル）。モデルの入力は 256 前後なので、これで十分。 */
        const val POSE_INPUT_MAX_LONG_SIDE = 640

        /**
         * 骨格推定する fps の目標。これより高い fps の動画は、整数コマおきに間引いて推定し、
         * 間のコマは前後から線形補間する（240fps なら 2 コマに 1 コマ）。
         */
        const val POSE_TARGET_FPS = 120f

        /** 何コマに 1 コマ推定するか。目標以下の fps なら 1（間引かない）。 */
        fun poseStep(fps: Float): Int = maxOf(1, Math.round(fps / POSE_TARGET_FPS))
    }
}

object Timestamps {
    /** MediaPipe の VIDEO モードは時刻が増え続けることを求める。同じ（または戻った）時刻なら 1ms 進める。 */
    fun nextAfter(previous: Long, candidate: Long): Long = maxOf(candidate, previous + 1)
}
