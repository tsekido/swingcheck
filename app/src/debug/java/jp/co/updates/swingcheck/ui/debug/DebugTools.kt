package jp.co.updates.swingcheck.ui.debug

import androidx.compose.runtime.Composable
import jp.co.updates.swingcheck.AppContainer

/** 開発用の機能（debug 版だけ）。release 版には同名の空実装が入る。 */
const val DEBUG_TOOLS_ENABLED = true

@Composable
fun DevScreen(container: AppContainer, onBack: () -> Unit) = DevScreenContent(container, onBack)
