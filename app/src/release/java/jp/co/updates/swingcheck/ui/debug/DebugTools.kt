package jp.co.updates.swingcheck.ui.debug

import androidx.compose.runtime.Composable
import jp.co.updates.swingcheck.AppContainer

/** 開発用の機能（リリース版では出さない）。debug 版の同名ファイルと対になる。 */
const val DEBUG_TOOLS_ENABLED = false

@Composable
fun DevScreen(container: AppContainer, onBack: () -> Unit) {
}
