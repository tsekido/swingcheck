package jp.co.updates.swingcheck.ui.result

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.data.PositionMarkEntity
import jp.co.updates.swingcheck.display.PhaseButtonKind
import jp.co.updates.swingcheck.display.PhaseButtonState

/**
 * P1〜P8 のボタン。押すとそのPのコマへ、長押しでメニュー（このコマをPnにする／自動に戻す）。
 * 見た目：手で直したPは tertiary 色、推定できなかったPは error 色で「P3?」、普通の自動は surfaceVariant。
 * 表示中のコマに当たるPは色を濃くして太字にする。
 */
@Composable
fun PhaseButtons(
    marks: List<PositionMarkEntity>,
    currentFrame: Int,
    onGoTo: (PositionMarkEntity) -> Unit,
    onSetHere: (PositionMarkEntity) -> Unit,
    onResetToAuto: (PositionMarkEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        marks.forEach { mark ->
            PhaseButton(
                mark = mark,
                selected = mark.frame == currentFrame,
                onClick = { onGoTo(mark) },
                onSetHere = { onSetHere(mark) },
                onResetToAuto = { onResetToAuto(mark) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhaseButton(
    mark: PositionMarkEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onSetHere: () -> Unit,
    onResetToAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val kind = PhaseButtonState.kindOf(mark)
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (kind) {
        PhaseButtonKind.AUTO ->
            if (selected) scheme.primary to scheme.onPrimary else scheme.surfaceVariant to scheme.onSurfaceVariant
        PhaseButtonKind.MANUAL ->
            if (selected) scheme.tertiary to scheme.onTertiary else scheme.tertiaryContainer to scheme.onTertiaryContainer
        PhaseButtonKind.UNCERTAIN ->
            if (selected) scheme.error to scheme.onError else scheme.errorContainer to scheme.onErrorContainer
    }
    val stateText = when (kind) {
        PhaseButtonKind.MANUAL -> stringResource(R.string.result_phase_state_manual)
        PhaseButtonKind.UNCERTAIN -> stringResource(R.string.result_phase_state_uncertain)
        PhaseButtonKind.AUTO -> null
    }
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(container, shape)
                .then(if (selected) Modifier.border(2.dp, scheme.onSurface, shape) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }, role = Role.Button)
                .semantics { if (stateText != null) stateDescription = stateText },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (kind == PhaseButtonKind.UNCERTAIN) stringResource(R.string.result_phase_button_uncertain, mark.position)
                else stringResource(R.string.result_phase_button, mark.position),
                color = content,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.result_phase_menu_set, mark.position)) },
                onClick = {
                    menuOpen = false
                    onSetHere()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.result_phase_menu_reset)) },
                enabled = mark.manualFrame != null,
                onClick = {
                    menuOpen = false
                    onResetToAuto()
                },
            )
        }
    }
}
