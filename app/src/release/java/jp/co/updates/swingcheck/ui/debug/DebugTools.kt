package jp.co.updates.swingcheck.ui.debug

import androidx.compose.runtime.Composable
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.VideoImporter

/** 開発用の機能（リリース版では出さない）。debug 版の同名ファイルと対になる。 */
const val DEBUG_TOOLS_ENABLED = false

@Composable
fun DevScreen(container: AppContainer, onBack: () -> Unit) {
}

/** 端末内の動画を選んで取り込むランチャー。リリース版では何もしない。 */
@Composable
fun rememberVideoImportLauncher(
    importer: VideoImporter,
    onStarted: () -> Unit,
    onFinished: (success: Boolean) -> Unit,
): () -> Unit = {}
