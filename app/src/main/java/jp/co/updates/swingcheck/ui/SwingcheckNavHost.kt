package jp.co.updates.swingcheck.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.ui.capture.CaptureScreen
import jp.co.updates.swingcheck.ui.debug.DEBUG_TOOLS_ENABLED
import jp.co.updates.swingcheck.ui.debug.DevScreen
import jp.co.updates.swingcheck.ui.list.SwingListScreen
import jp.co.updates.swingcheck.ui.result.ResultScreen
import jp.co.updates.swingcheck.ui.settings.SettingsScreen

private object Routes {
    const val CAPTURE = "capture"
    const val LIST = "list"
    const val RESULT = "result/{swingId}"
    const val SETTINGS = "settings"
    const val DEV = "dev"

    fun result(swingId: Long) = "result/$swingId"
}

/** 画面の流れ：撮影（起動時）→ 一覧 → 解析結果。撮影と一覧から設定へ。 */
@Composable
fun SwingcheckNavHost(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.CAPTURE) {
        composable(Routes.CAPTURE) {
            CaptureScreen(
                container,
                onOpenList = { nav.navigate(Routes.LIST) },
                onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.LIST) {
            SwingListScreen(
                container,
                onBack = { nav.popBackStack() },
                onOpenSwing = { nav.navigate(Routes.result(it)) },
            )
        }
        composable(
            Routes.RESULT,
            arguments = listOf(navArgument("swingId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("swingId") ?: return@composable
            ResultScreen(container, id, onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                container,
                onBack = { nav.popBackStack() },
                onOpenDev = if (DEBUG_TOOLS_ENABLED) ({ nav.navigate(Routes.DEV) }) else null,
            )
        }
        if (DEBUG_TOOLS_ENABLED) {
            composable(Routes.DEV) { DevScreen(container, onBack = { nav.popBackStack() }) }
        }
    }
}
