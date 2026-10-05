package jp.co.updates.swingcheck.ui.result

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import jp.co.updates.swingcheck.AppContainer
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.core.Derived
import jp.co.updates.swingcheck.core.Phase
import jp.co.updates.swingcheck.data.AnalysisStatus
import jp.co.updates.swingcheck.data.PositionMarkEntity
import jp.co.updates.swingcheck.data.SwingEntity
import jp.co.updates.swingcheck.display.FrameMetricsResolver
import jp.co.updates.swingcheck.display.PhaseButtonState
import jp.co.updates.swingcheck.display.SeekMath
import jp.co.updates.swingcheck.display.ShaftPhases
import jp.co.updates.swingcheck.settings.AppSettings
import jp.co.updates.swingcheck.ui.common.BackButton
import jp.co.updates.swingcheck.ui.common.HeightField
import jp.co.updates.swingcheck.ui.common.rememberSwingDateFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(container: AppContainer, swingId: Long, onBack: () -> Unit) {
    val vm: ResultViewModel = viewModel(
        key = "result-$swingId",
        factory = viewModelFactory { initializer { ResultViewModel(container, swingId) } },
    )
    val swing by vm.swing.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val dateFormat = rememberSwingDateFormat()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(swing?.let { dateFormat.format(it.createdAt) } ?: stringResource(R.string.result_title)) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        val s = swing
        val pose = vm.pose
        val cfg = settings
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                s != null && s.analysisStatus != AnalysisStatus.DONE ->
                    CenterText(stringResource(R.string.result_not_ready))
                s != null && vm.loadFailed -> CenterText(stringResource(R.string.list_status_failed))
                s == null || pose == null || cfg == null || marks.size != Phase.entries.size ->
                    CenterText(stringResource(R.string.result_loading))
                else -> ResultContent(container, vm, s, pose, marks, cfg)
            }
        }
    }
}

@Composable
private fun CenterText(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultContent(
    container: AppContainer,
    vm: ResultViewModel,
    swing: SwingEntity,
    pose: LoadedPose,
    marks: List<PositionMarkEntity>,
    settings: AppSettings,
) {
    val context = LocalContext.current
    val videoFile = remember(swing.videoPath) { container.files.resolve(swing.videoPath) }
    val player = remember(pose) { FramePlayer(context, videoFile, pose.timestampsMs) }
    DisposableEffect(player) { onDispose { player.release() } }

    // 画面から離れたら再生を止める
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 最初は P1 のコマを出す
    LaunchedEffect(player) { player.seekToFrame(vm.currentFrame) }

    // 再生中は、プレーヤーの位置から表示中のコマを追いかける。止まったときにも 1 回合わせる
    LaunchedEffect(player) {
        snapshotFlow { player.isPlaying }.collectLatest { playing ->
            if (playing) {
                try {
                    while (true) {
                        withFrameNanos { }
                        vm.currentFrame = player.currentFrame()
                    }
                } finally {
                    vm.currentFrame = player.currentFrame()
                }
            }
        }
    }

    val frame = vm.currentFrame
    val frameCount = pose.frameCount
    fun goTo(f: Int) {
        val target = f.coerceIn(0, frameCount - 1)
        player.pause()
        vm.currentFrame = target
        player.seekToFrame(target)
    }

    val imeVisible = WindowInsets.isImeVisible

    Column(Modifier.fillMaxSize().imePadding()) {
        // キーボードを出している間（身長・メモの入力中）は、動画まわりをたたんで入力欄を見えるようにする
        if (!imeVisible) {
            val density = LocalDensity.current
            val maxVideoHeight = with(density) { (LocalWindowInfo.current.containerSize.height * 0.4f).toDp() }
            val p1Frame = marks.first { it.position == 1 }.frame
            val shoulderWidth = remember(pose, p1Frame) { Derived.shoulderWidth(pose.smoothed.poses[p1Frame]) }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                VideoWithOverlay(
                    player = player,
                    pose = pose.smoothed.poses[frame.coerceIn(0, frameCount - 1)],
                    imageWidth = pose.smoothed.width,
                    imageHeight = pose.smoothed.height,
                    shoulderWidthPx = shoulderWidth,
                    shaftPhase = ShaftPhases.at(frame, marks),
                    modifier = Modifier
                        .heightIn(max = maxVideoHeight)
                        .aspectRatio(pose.smoothed.width.toFloat() / pose.smoothed.height),
                )
            }

            PhaseButtons(
                marks = marks,
                currentFrame = frame,
                onGoTo = { goTo(it.frame) },
                onSetHere = { vm.setManualFrame(Phase.entries[it.position - 1], frame) },
                onResetToAuto = {
                    vm.resetToAuto(Phase.entries[it.position - 1])
                    goTo(it.autoFrame)
                },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )

            PhaseSeekBar(
                frameCount = frameCount,
                currentFrame = frame,
                markFrames = marks.map { it.frame },
                markKinds = marks.map(PhaseButtonState::kindOf),
                selectedMark = PhaseButtonState.phaseAtFrame(marks, frame)?.minus(1),
                onSeek = ::goTo,
                onMarkMoved = { index, f -> vm.setManualFrame(Phase.entries[index], f) },
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = { goTo(SeekMath.step(frame, -1, frameCount)) }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.result_prev_frame), maxLines = 1)
                }
                Button(
                    onClick = { if (player.isPlaying) player.pause() else player.play() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(if (player.isPlaying) R.string.result_pause else R.string.result_play), maxLines = 1)
                }
                OutlinedButton(onClick = { goTo(SeekMath.step(frame, 1, frameCount)) }, Modifier.weight(1f)) {
                    Text(stringResource(R.string.result_next_frame), maxLines = 1)
                }
            }
            Text(
                stringResource(R.string.result_frame_position, frame + 1, frameCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
            )
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }

        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val metrics = remember(frame, marks, pose) { FrameMetricsResolver.resolve(frame, marks, pose.smoothed) }
            MetricsSection(frame, metrics, swing.heightCm, settings.lengthUnit)
            HorizontalDivider()
            HeightField(
                unit = settings.lengthUnit,
                initialCm = swing.heightCm,
                onChange = vm::updateHeightCm,
            )
            Text(
                stringResource(R.string.result_height_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MemoField(initial = swing.memo, onSave = vm::updateMemo)
        }
    }
}

/** メモ欄。入力が止まって少したったら保存し、画面を離れるときにも保存する。 */
@Composable
private fun MemoField(initial: String, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var saved by remember { mutableStateOf(initial) }
    val latestText by rememberUpdatedState(text)
    val latestSaved by rememberUpdatedState(saved)
    val latestOnSave by rememberUpdatedState(onSave)

    LaunchedEffect(text) {
        if (text == saved) return@LaunchedEffect
        delay(MEMO_SAVE_DELAY_MS)
        saved = text
        latestOnSave(text)
    }
    DisposableEffect(Unit) {
        onDispose { if (latestText != latestSaved) latestOnSave(latestText) }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(stringResource(R.string.memo_label)) },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MEMO_SAVE_DELAY_MS = 600L
