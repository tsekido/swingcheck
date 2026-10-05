package jp.co.updates.swingcheck.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import kotlinx.coroutines.flow.map

/**
 * 撮影画面（起動時の画面）。カメラ本体は次のタスクで作るので、プレビュー部分は枠で代用している。
 */
@Composable
fun CaptureScreen(container: AppContainer, onOpenList: () -> Unit, onOpenSettings: () -> Unit) {
    val countFlow = remember(container) { container.swingRepository.observeSwings().map { it.size } }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // カメラのプレビューの代わりの枠
        Box(
            Modifier
                .fillMaxSize()
                .padding(24.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.capture_camera_placeholder), color = Color.White.copy(alpha = 0.7f))
        }

        GuideOverlay()

        // 画面の端に保存済みのスイング数
        Text(
            pluralStringResource(R.plurals.capture_saved_count, count, count),
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 32.dp, top = 32.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalButton(onClick = onOpenList) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null)
                Text(stringResource(R.string.capture_open_list), Modifier.padding(start = 8.dp))
            }
            FilledTonalButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text(stringResource(R.string.capture_open_settings), Modifier.padding(start = 8.dp))
            }
        }
    }
}
