package jp.co.updates.swingcheck.ui.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import jp.co.updates.swingcheck.VideoImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 開発用の機能（debug 版だけ）。release 版には同名の空実装が入る。 */
const val DEBUG_TOOLS_ENABLED = true

@Composable
fun DevScreen(onBack: () -> Unit) = DevScreenContent(onBack)

/** 端末内の動画を選んで取り込む（Photo Picker）。選ばれたら [onStarted]、終わったら [onFinished]。 */
@Composable
fun rememberVideoImportLauncher(
    importer: VideoImporter,
    onStarted: () -> Unit,
    onFinished: (success: Boolean) -> Unit,
): () -> Unit {
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            onStarted()
            scope.launch {
                val ok = try {
                    importer.import(uri)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
                onFinished(ok)
            }
        }
    }
    return { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
}
