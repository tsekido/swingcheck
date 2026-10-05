package jp.co.updates.swingcheck

import jp.co.updates.swingcheck.analysis.AnalysisScheduler
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.data.SwingRepository
import jp.co.updates.swingcheck.settings.SettingsRepository

/**
 * 保存済みの動画ファイルから Swing を作り、解析の順番待ちに入れる。
 * 動画の取り込み（[VideoImporter]）と、撮影した動画の保存（capture パッケージ）で共通。
 */
class SwingRegistrar(
    private val repository: SwingRepository,
    private val settings: SettingsRepository,
    private val scheduler: AnalysisScheduler,
) {
    /**
     * @param videoPath [jp.co.updates.swingcheck.data.SwingFiles.newVideoPath] で作った相対パス（ファイルは書き終えていること）
     * @param width, height 回転メタデータを反映した、表示する向きの大きさ
     * @return 作った Swing の id
     */
    suspend fun register(videoPath: String, fps: Float, frameCount: Int, width: Int, height: Int): Long {
        val id = repository.insert(
            SwingEntity(
                createdAt = System.currentTimeMillis(),
                videoPath = videoPath,
                fps = fps,
                frameCount = frameCount,
                width = width,
                height = height,
                heightCm = settings.current().defaultHeightCm,
            ),
        )
        scheduler.enqueue(id)
        return id
    }
}
