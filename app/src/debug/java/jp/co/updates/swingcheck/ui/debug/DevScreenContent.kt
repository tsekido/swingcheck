package jp.co.updates.swingcheck.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.capture.CameraCapabilitiesReader
import jp.co.updates.swingcheck.capture.CaptureConfigSelector
import jp.co.updates.swingcheck.settings.FpsMode
import jp.co.updates.swingcheck.ui.dev.CameraReport
import jp.co.updates.swingcheck.ui.dev.DeviceReport
import jp.co.updates.swingcheck.ui.dev.HighSpeedSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevScreenContent(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraText by remember { mutableStateOf<String?>(null) }
    var selectionText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        cameraText = withContext(Dispatchers.IO) {
            runCatching { CameraReportReader.read(context).toText() }
                .getOrElse { "failed to read camera characteristics: $it" }
        }
        val mode = container.settingsRepository.current().fpsMode
        selectionText = withContext(Dispatchers.IO) {
            runCatching { selectionReport(context, mode) }.getOrElse { "failed to select capture config: $it" }
        }
    }
    val lastInfo by container.captureController.lastInfo.collectAsStateWithLifecycle()
    val debug by container.captureController.debug.collectAsStateWithLifecycle()
    // 撮影画面で最後に動いた設定（撮影画面を一度も開いていなければ空）
    val text = if (cameraText == null || selectionText == null) null else buildString {
        appendLine("== capture pipeline (selected for fpsMode, per back camera) ==")
        appendLine(selectionText)
        appendLine("== capture pipeline (last run on the capture screen) ==")
        appendLine(lastInfo?.toText() ?: "(not run yet)")
        if (lastInfo != null) appendLine(debug.toText())
        appendLine()
        append(cameraText)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.dev_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.dev_capture_title))
            Button(
                enabled = text != null,
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("camera report", text.orEmpty()))
                    Toast.makeText(context, R.string.dev_copied, Toast.LENGTH_SHORT).show()
                },
            ) { Text(stringResource(R.string.dev_copy)) }
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(text ?: stringResource(R.string.dev_loading), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

/** 設定の fpsMode で、背面カメラごとに実際に選ばれる撮影設定（カメラは開かない）。 */
private fun selectionReport(context: Context, mode: FpsMode): String {
    val cameras = CameraCapabilitiesReader.readBackCameras(context)
    if (cameras.isEmpty()) return "(no back cameras)"
    return buildString {
        appendLine("fpsMode: $mode")
        for (camera in cameras) {
            val config = CaptureConfigSelector.select(
                camera.caps, mode, CameraCapabilitiesReader::encoderSupports,
            )
            append("camera ${camera.caps.cameraId}: ")
            appendLine(config?.let { "${it.sessionType} ${it.size} ${it.fps}fps AE ${it.fpsRange}" } ?: "(none)")
        }
        CameraCapabilitiesReader.findEncoder()?.let { appendLine("encoder: ${it.name}") }
    }
}

/** Camera2 の CameraCharacteristics から、高速撮影の対応状況を読む（カメラの権限は要らない）。 */
private object CameraReportReader {
    fun read(context: Context): DeviceReport {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameras = manager.cameraIdList.map { id ->
            val ch = manager.getCameraCharacteristics(id)
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val highSpeed = map?.highSpeedVideoSizes.orEmpty().map { size ->
                HighSpeedSize(
                    size.width,
                    size.height,
                    map!!.getHighSpeedVideoFpsRangesFor(size).map { it.lower to it.upper },
                )
            }
            CameraReport(
                id = id,
                facing = facingName(ch.get(CameraCharacteristics.LENS_FACING)),
                hardwareLevel = hardwareLevelName(ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
                capabilities = caps.map(::capabilityName),
                supportsConstrainedHighSpeed = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO in caps,
                highSpeedSizes = highSpeed,
                aeFpsRanges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { it.lower to it.upper }.orEmpty(),
                physicalIds = ch.physicalCameraIds.sorted(),
            )
        }
        return DeviceReport(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cameras = cameras,
        )
    }

    private fun facingName(v: Int?): String = when (v) {
        CameraMetadata.LENS_FACING_BACK -> "BACK"
        CameraMetadata.LENS_FACING_FRONT -> "FRONT"
        CameraMetadata.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($v)"
    }

    private fun hardwareLevelName(v: Int?): String = when (v) {
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN($v)"
    }

    private fun capabilityName(v: Int): String = when (v) {
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_PRIVATE_REPROCESSING -> "PRIVATE_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MOTION_TRACKING -> "MOTION_TRACKING"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> "MONOCHROME"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> "SECURE_IMAGE_DATA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> "SYSTEM_CAMERA"
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_OFFLINE_PROCESSING -> "OFFLINE_PROCESSING"
        else -> "UNKNOWN($v)"
    }
}
