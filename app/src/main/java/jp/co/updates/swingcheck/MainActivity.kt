package jp.co.updates.swingcheck

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as SwingcheckApp).container
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            MaterialTheme {
                // 画面の実装は次のタスク。今は動作確認用の最小限（debug ビルドだけ動画の取り込みを出す）
                val swings by container.swingRepository.observeSwings().collectAsState(initial = emptyList())
                val scope = rememberCoroutineScope()
                val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    if (uri != null) scope.launch { container.videoImporter.import(uri) }
                }
                Column(Modifier.fillMaxSize().padding(16.dp)) {
                    if (debuggable) {
                        Button(onClick = { picker.launch("video/*") }) { Text("Import video (debug)") }
                    }
                    swings.forEach { s ->
                        Text("#${s.id} ${s.analysisStatus} ball=${s.ballResult} frames=${s.frameCount} ${s.fps}fps ${s.width}x${s.height}")
                    }
                }
            }
        }
    }
}
