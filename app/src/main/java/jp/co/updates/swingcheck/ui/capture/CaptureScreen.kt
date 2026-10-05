package jp.co.updates.swingcheck.ui.capture

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.capture.CaptureStatus
import jp.co.updates.swingcheck.ui.debug.DEBUG_TOOLS_ENABLED
import kotlinx.coroutines.flow.map

/**
 * 撮影画面（起動時の画面）。プレビューは SurfaceView で、描画は [AppContainer.captureController] の GL が行う。
 * カメラは「権限あり ・ 画面が前面（RESUMED）・ Surface あり」の間だけ動かし、そうでなくなったら止める。
 */
@Composable
fun CaptureScreen(container: AppContainer, onOpenList: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val controller = container.captureController
    val countFlow = remember(container) { container.swingRepository.observeSwings().map { it.size } }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val status by controller.status.collectAsStateWithLifecycle()
    val debug by controller.debug.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    var asked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        // 起動したらすぐカメラを立ち上げたいので、最初の 1 回は自動で権限を求める
        if (!granted && !asked) {
            asked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            // 設定アプリで権限が変わっているかもしれない
            if (resumed) granted = hasCameraPermission(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var surface by remember { mutableStateOf<Surface?>(null) }
    val displayRotation = (LocalView.current.display?.rotation ?: Surface.ROTATION_0) * 90
    DisposableEffect(granted, resumed, surface) {
        val s = surface
        if (granted && resumed && s != null) controller.start(s, displayRotation)
        onDispose { controller.stop() } // 画面が隠れたら、カメラ・エンコーダー・GL を止める
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            CameraSurface(onSurface = { surface = it }, onDestroyed = { controller.stop() })
        } else {
            PermissionExplanation(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenAppSettings = { openAppSettings(context) },
            )
        }

        if (granted) GuideOverlay()

        Column(
            Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 32.dp, top = 32.dp, end = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 画面の端に保存済みのスイング数
            OverlayText(pluralStringResource(R.plurals.capture_saved_count, count, count))
            if (granted) {
                val info = status.info
                when {
                    info != null -> OverlayText(
                        stringResource(R.string.capture_fps_info, info.config.fps, info.config.size.width, info.config.size.height),
                    )
                    status.state == CaptureStatus.State.ERROR ->
                        OverlayText(stringResource(R.string.capture_error, status.error.orEmpty()))
                    else -> OverlayText(stringResource(R.string.capture_starting))
                }
                if (DEBUG_TOOLS_ENABLED && info != null) {
                    Text(
                        debug.toText(),
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }

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

@Composable
private fun OverlayText(text: String) {
    Text(
        text,
        color = Color.White,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** プレビュー用の SurfaceView。Surface ができたら [onSurface]、壊れる前に [onDestroyed]（ここで GL を手放す）。 */
@Composable
private fun CameraSurface(onSurface: (Surface?) -> Unit, onDestroyed: () -> Unit) {
    val currentOnSurface by rememberUpdatedState(onSurface)
    val currentOnDestroyed by rememberUpdatedState(onDestroyed)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = currentOnSurface(holder.surface)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        currentOnDestroyed()
                        currentOnSurface(null)
                    }
                })
            }
        },
    )
}

@Composable
private fun PermissionExplanation(onRequest: () -> Unit, onOpenAppSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.capture_permission_message),
            color = Color.White,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringResource(R.string.capture_permission_request))
        }
        TextButton(onClick = onOpenAppSettings) {
            Text(stringResource(R.string.capture_permission_open_settings))
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
