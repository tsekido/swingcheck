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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import jp.co.updates.swingcheck.capture.CameraCaps
import jp.co.updates.swingcheck.capture.CapturePlanner
import jp.co.updates.swingcheck.capture.CaptureSize
import jp.co.updates.swingcheck.capture.DebugForcedCapture
import jp.co.updates.swingcheck.capture.ForcedCapture
import jp.co.updates.swingcheck.capture.SessionType
import jp.co.updates.swingcheck.capture.FailedSetting
import jp.co.updates.swingcheck.settings.FpsMode
import jp.co.updates.swingcheck.ui.dev.CameraReport
import jp.co.updates.swingcheck.ui.dev.DeviceReport
import jp.co.updates.swingcheck.ui.dev.HighSpeedSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevScreenContent(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var cameraText by remember { mutableStateOf<String?>(null) }
    var selectionText by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val failedEncoded by container.settingsRepository.failedCaptureSettings.collectAsStateWithLifecycle(initialValue = emptySet())
    LaunchedEffect(failedEncoded) {
        cameraText = withContext(Dispatchers.IO) {
            runCatching { CameraReportReader.read(context).toText() }
                .getOrElse { "failed to read camera characteristics: $it" }
        }
        val mode = container.settingsRepository.current().fpsMode
        selectionText = withContext(Dispatchers.IO) {
            runCatching { selectionReport(context, mode, failedEncoded.mapNotNull(FailedSetting::decode).toSet()) }.getOrElse { "failed to select capture config: $it" }
        }
    }
    val lastInfo by container.captureController.lastInfo.collectAsStateWithLifecycle()
    val debug by container.captureController.debug.collectAsStateWithLifecycle()
    // 撮影画面で最後に動いた設定（撮影画面を一度も開いていなければ空）
    val text = if (cameraText == null || selectionText == null) null else buildString {
        appendLine("== failed capture settings (excluded from selection) ==")
        if (failedEncoded.isEmpty()) appendLine("(none)") else failedEncoded.sorted().forEach { appendLine(FailedSetting.decode(it)?.toString() ?: it) }
        appendLine()
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
            OutlinedButton(
                enabled = failedEncoded.isNotEmpty(),
                onClick = { scope.launch { container.settingsRepository.clearFailedCaptureSettings() } },
            ) { Text(stringResource(R.string.dev_reset_failed)) }
            ForcedCaptureSection(context)
            SelectionContainer(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(text ?: stringResource(R.string.dev_loading), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            }
        }
    }
}

/** 設定の fpsMode での撮影設定の選び方（カメラは開かない）：メインカメラの候補と、試す順番。 */
private fun selectionReport(context: Context, mode: FpsMode, failed: Set<FailedSetting>): String {
    val cameras = CameraCapabilitiesReader.readBackCameras(context)
    if (cameras.isEmpty()) return "(no back cameras)"
    val caps = cameras.map { it.caps }
    val main = CapturePlanner.mainCameras(caps).map { it.cameraId }
    val plan = CapturePlanner.plan(caps, mode, CameraCapabilitiesReader::encoderSupports, failed)
    return buildString {
        appendLine("fpsMode: $mode")
        for (camera in cameras) {
            val c = camera.caps
            val role = if (c.cameraId in main) "main" else "excluded (focal length differs)"
            val physical = if (c.physicalIds.isEmpty()) "" else ", physical ${c.physicalIds.joinToString(",")}"
            appendLine("camera ${c.cameraId}: focal ${c.focalLengthMm ?: "?"}mm$physical -> $role")
        }
        appendLine("attempt order (failed settings excluded):")
        if (plan.isEmpty()) appendLine("  (none)")
        plan.forEachIndexed { i, it ->
            appendLine("  ${i + 1}. camera ${it.cameraId}: ${it.sessionType} ${it.size} ${it.fps}fps AE ${it.fpsRange}")
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
                physicalIds = ch.physicalCameraIds.sorted().map { pid -> "$pid${physicalFocalText(manager, pid)}" },
                focalLengths = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
            )
        }
        return DeviceReport(
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            cameras = cameras,
        )
    }

    /** 物理カメラの焦点距離（読めなければ空文字）。物理カメラはアプリから見えないことがある。 */
    private fun physicalFocalText(manager: CameraManager, id: String): String = try {
        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.let { " (${it}mm)" }.orEmpty()
    } catch (e: Exception) {
        " (not accessible)"
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

/**
 * 撮影設定の強制指定。カメラ ID・セッション種別・サイズ・fps を、そのカメラの CameraCharacteristics から読んだ候補の中から選んで保存する。
 * 焦点距離による除外はしない（超広角も出る）。保存した設定は、次に撮影画面を開いたときから使われる。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ForcedCaptureSection(context: Context) {
    var cameras by remember { mutableStateOf<List<CameraCaps>>(emptyList()) }
    var saved by remember { mutableStateOf(DebugForcedCapture.read(context)) }
    var cameraId by remember { mutableStateOf(saved?.cameraId) }
    var session by remember { mutableStateOf(saved?.sessionType) }
    var size by remember { mutableStateOf(saved?.size) }
    var fps by remember { mutableStateOf(saved?.fps) }
    LaunchedEffect(Unit) {
        cameras = withContext(Dispatchers.IO) {
            runCatching { CameraCapabilitiesReader.readBackCameras(context).map { it.caps } }.getOrDefault(emptyList())
        }
    }
    val caps = cameras.firstOrNull { it.cameraId == cameraId }
    val sizes = if (caps != null && session != null) ForcedCapture.sizeOptions(caps, session!!) else emptyList()
    val fpsList = if (caps != null && session != null && size != null) ForcedCapture.fpsOptions(caps, session!!, size!!) else emptyList()
    val candidate = if (cameraId != null && session != null && size != null && fps != null) ForcedCapture(cameraId!!, session!!, size!!, fps!!) else null

    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Force capture setting (debug only): ${saved ?: "off (automatic)"}", fontSize = 12.sp)
        Text("camera", fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cameras.forEach { c ->
                FilterChip(
                    selected = cameraId == c.cameraId,
                    onClick = { cameraId = c.cameraId; session = null; size = null; fps = null },
                    label = { Text("${c.cameraId} (${c.focalLengthMm ?: "?"}mm)") },
                )
            }
        }
        Text("session", fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SessionType.entries.forEach { t ->
                FilterChip(
                    selected = session == t,
                    enabled = caps != null && ForcedCapture.sizeOptions(caps, t).isNotEmpty(),
                    onClick = { session = t; size = null; fps = null },
                    label = { Text(t.name) },
                )
            }
        }
        Text("size", fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sizes.forEach { s ->
                FilterChip(selected = size == s, onClick = { size = s; fps = null }, label = { Text(s.toString()) })
            }
        }
        Text("fps", fontSize = 11.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            fpsList.forEach { f ->
                FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text("$f") })
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = candidate != null,
                onClick = {
                    DebugForcedCapture.write(context, candidate)
                    saved = candidate
                    Toast.makeText(context, "saved (applies when the capture screen is opened)", Toast.LENGTH_SHORT).show()
                },
            ) { Text("Save") }
            OutlinedButton(
                enabled = saved != null,
                onClick = {
                    DebugForcedCapture.write(context, null)
                    saved = null
                    cameraId = null; session = null; size = null; fps = null
                },
            ) { Text("Clear (automatic)") }
        }
    }
}
