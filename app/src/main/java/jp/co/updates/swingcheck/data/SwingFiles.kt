package jp.co.updates.swingcheck.data

import java.io.File
import java.util.UUID

/** アプリ専用領域（filesDir）の中の、動画と骨格の列のファイルの置き場所。 */
class SwingFiles(private val root: File) {
    /** DB に入れる相対パスから実ファイルを引く。 */
    fun resolve(relativePath: String): File = File(root, relativePath)

    /** 新しい動画の相対パス（`videos/<uuid>.mp4`）。 */
    fun newVideoPath(): String = "videos/${UUID.randomUUID()}.mp4"

    /** 骨格の列（`pose/<id>.bin`）。 */
    fun poseFile(swingId: Long): File = File(root, "pose/$swingId.bin")

    /** そのスイングの動画と骨格の列を消す（無ければ何もしない）。 */
    fun deleteFor(swing: SwingEntity) {
        resolve(swing.videoPath).delete()
        poseFile(swing.id).delete()
    }
}
